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

-- ============ 3. 演示数据：把一笔种子秒杀单标记为"已核销" ============
-- 种子库 tb_voucher_order 里 voucher_id=13 的订单都是 status=1(UNPAID)、use_time=NULL。
-- 分表 patch 会把 type=1 的搬到 tb_seckill_order，这里取一笔改成已支付+已核销，
-- 用来演示"用户申诉一张已核销的券 → 不走自动赔付 → 转人工"。
-- 只改一笔，其余保持"已支付未核销"用于演示全额退。
UPDATE tb_seckill_order
SET status = 3, use_time = NOW(), pay_time = NOW()
WHERE voucher_id = 13
  AND id = (SELECT MIN(id) FROM (SELECT id FROM tb_seckill_order WHERE voucher_id = 13) t)
  AND status = 1;

-- 核对：应看到 1 笔 VERIFIED(3)、其余 PAID(2)
SELECT status, COUNT(*) AS cnt FROM tb_seckill_order WHERE voucher_id = 13 GROUP BY status;
