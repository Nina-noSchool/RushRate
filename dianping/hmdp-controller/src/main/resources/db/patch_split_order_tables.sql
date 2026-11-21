-- 2026-11-21 业务分表：秒杀单与普通券订单垂直拆分
--
-- 背景：tb_voucher_order 一张表同时承载秒杀单（洪水，走 MQ/对账/关单域）与
--       普通券单（涓流，同步直插，允许复购）。uk_user_voucher 唯一索引与
--       "普通券可复购"语义冲突（patch_drop_uk_user_voucher.sql 已临时降级），
--       且两类单的流量形态、生命周期、约束要求完全不同。
--
-- 本 patch：按业务语义垂直拆分——
--   tb_seckill_order：秒杀单全字段，恢复 uk_user_voucher 唯一索引做 DB 级最终防线
--                     （应用层 Lua 原子 + 分布式锁 + count 三层之外再加一道静态保险）；
--   tb_normal_order ：普通券单瘦表，无唯一约束，复购自由。
--   对账/关单/恢复/申诉域只覆盖秒杀域 → 这些代码零改动（MyBatis-Plus 实体指向切换）。
--
-- 执行前提：tb_mq_event_record 无 PENDING_RETRY 在途消息（迁移窗口）。

-- ============ 1. 迁移前 dedupe 检查（秒杀段存在重复则唯一索引建不起来） ============
-- 预期返回 0 行；若有重复，先人工归档处理重复行再继续。
SELECT user_id, voucher_id, COUNT(*) AS dup
FROM tb_voucher_order o
JOIN tb_voucher v ON v.id = o.voucher_id AND v.type = 1
GROUP BY user_id, voucher_id
HAVING dup > 1;

-- ============ 2. 建秒杀单表（LIKE 继承列结构与既有索引） ============
CREATE TABLE IF NOT EXISTS tb_seckill_order LIKE tb_voucher_order;

-- LIKE 继承的是"降级后的"普通索引，恢复唯一索引（本次分表的核心目的）
SET @has_uk = (SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_seckill_order'
                 AND INDEX_NAME = 'uk_user_voucher' AND NON_UNIQUE = 0);
SET @ddl = IF(@has_uk = 0,
    'ALTER TABLE tb_seckill_order DROP INDEX idx_user_voucher, ADD UNIQUE KEY uk_user_voucher (user_id, voucher_id)',
    'SELECT ''uk already unique''');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============ 3. 建普通券单瘦表 ============
CREATE TABLE IF NOT EXISTS tb_normal_order (
    id          BIGINT UNSIGNED NOT NULL COMMENT '订单ID（RedisIdWorker 预生成）',
    user_id     BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    voucher_id  BIGINT UNSIGNED NOT NULL COMMENT '券ID',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_voucher (user_id, voucher_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='普通券订单（允许复购）';

-- ============ 4. 数据迁移：按 tb_voucher.type 分流 ============
INSERT INTO tb_seckill_order (id, user_id, voucher_id, pay_type, status, create_time, pay_time, use_time, refund_time, update_time)
SELECT o.id, o.user_id, o.voucher_id, o.pay_type, o.status, o.create_time, o.pay_time, o.use_time, o.refund_time, o.update_time
FROM tb_voucher_order o
JOIN tb_voucher v ON v.id = o.voucher_id
WHERE v.type = 1;

INSERT INTO tb_normal_order (id, user_id, voucher_id, create_time, update_time)
SELECT o.id, o.user_id, o.voucher_id, o.create_time, o.update_time
FROM tb_voucher_order o
JOIN tb_voucher v ON v.id = o.voucher_id
WHERE v.type = 0;

-- ============ 5. 迁移核对 ============
SELECT 'seckill' AS tbl, COUNT(*) AS rows_migrated FROM tb_seckill_order
UNION ALL
SELECT 'normal' AS tbl, COUNT(*) AS rows_migrated FROM tb_normal_order;

-- 老表保留观察期，确认双表运行稳定后手动归档：
-- RENAME TABLE tb_voucher_order TO tb_voucher_order_legacy;
