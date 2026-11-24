-- 2026-11-24 无忧闪赔按券值赔付改造
--
-- 背景：原闪赔按 planCode 查规则表固定赔 50 分，券实际支付 6800 分（68元）时明显不合理。
--       改为按券实际支付金额（tb_voucher.pay_value）全额退，已核销转人工。
--
-- 本 patch 做三件事：
--   1. 申诉单加 voucher_id（闪赔申请要带上"赔哪张券"）
--   2. 普通券订单补核销字段（原来 tb_normal_order 是瘦表，无法判断核销）
--   3. 造一条"已核销"演示数据，供面试演示"已核销转人工"分支
--
-- 执行前提：patch_split_order_tables.sql 已执行（tb_seckill_order / tb_normal_order 存在）。

-- ============ 1. 申诉单加 voucher_id ============
ALTER TABLE tb_shop_guarantee_claim
    ADD COLUMN voucher_id BIGINT UNSIGNED DEFAULT NULL COMMENT '关联券ID，闪赔按此券实付金额赔付' AFTER user_id;

-- ============ 2. 普通券订单补核销字段 ============
-- 与 tb_seckill_order（LIKE tb_voucher_order）保持一致：
--   status: 1=未支付 2=已支付 3=已核销 4=已取消 5=退款中 6=已退款（见 VoucherOrderStatus）
--   use_time: 核销时间，核销时写入
ALTER TABLE tb_normal_order
    ADD COLUMN status TINYINT UNSIGNED NOT NULL DEFAULT 2 COMMENT '订单状态：2=已支付 3=已核销',
    ADD COLUMN use_time TIMESTAMP NULL DEFAULT NULL COMMENT '核销时间';

-- ============ 3. 演示数据：券13造两笔可演示订单 ============
-- 种子库 voucher_id=13 的订单原本是 status=1(UNPAID) 或 4(CANCELLED)，没有 PAID。
-- 而闪赔只对 PAID(已支付未核销) 自动全退，且 MybatisVoucherInfoProvider 按
-- create_time 取「最近一笔」判断核销态，所以演示数据必须改对「最近一笔」：
--   · 最近一笔  → PAID(2)     默认演示「未核销 → 按券值全额退」
--   · 次近一笔  → VERIFIED(3) 演示「已核销 → 转人工」（把最近一笔也改成 3 即可切换分支）
-- 注意：种子数据这批订单 create_time 全部相同，仅靠 ORDER BY create_time 取值不稳定，
-- 所以这里同时把 create_time 显式错开（PAID 最新、VERIFIED 次新），保证 provider 稳定取到目标笔。
UPDATE tb_seckill_order
SET status = 2, pay_time = NOW(), use_time = NULL, create_time = NOW()
WHERE id = (SELECT id FROM (
    SELECT id FROM tb_seckill_order WHERE voucher_id = 13 ORDER BY create_time DESC LIMIT 1
) t);

UPDATE tb_seckill_order
SET status = 3, pay_time = NOW(), use_time = NOW(),
    create_time = DATE_SUB(NOW(), INTERVAL 1 SECOND)
WHERE id = (SELECT id FROM (
    SELECT id FROM tb_seckill_order WHERE voucher_id = 13 ORDER BY create_time DESC LIMIT 1, 1
) t);

-- 核对：应看到 1 笔 PAID(2)、1 笔 VERIFIED(3)，其余保持原状
SELECT status, COUNT(*) AS cnt FROM tb_seckill_order WHERE voucher_id = 13 GROUP BY status;
