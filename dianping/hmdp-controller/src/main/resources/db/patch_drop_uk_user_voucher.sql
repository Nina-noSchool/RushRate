-- 2026-11-21 普通券可重复购买语义与唯一索引冲突治理
--
-- 背景：tb_voucher_order 上的 uk_user_voucher(user_id, voucher_id) 唯一索引
--       把"同用户同券"硬限成一单——普通代金券（type=0，常态销售可重复购买）的
--       第二单在 save 时抛 DuplicateKeyException，对外表现为"服务器异常"。
--
-- 治理：唯一索引降级为普通索引。一人一单的防线重新归位：
--   秒杀券（type=1）：
--     1) seckill.lua / seckill_plus.lua 原子一人一单（Redis 侧，并发安全的第一道闸）
--     2) 旧路径 Redisson 锁 lock:order:{userId} 串行同用户请求（应用侧）
--     3) createVoucherOrder 的 count 校验（应用侧，2026-11-21 治理后结果透传不再吞单）
--     DB 唯一索引从来不是可靠防线——热表唯一键冲突是写路径瓶颈，真实秒杀不依赖它。
--   普通券（type=0）：应用层不做一人一单拦截（2026-11-19 限购语义分层），DB 层解除硬限。
--
-- 附带收益：user_id 维度查询（属主校验 / 一人一单 count）走普通索引，性能不回退。

ALTER TABLE tb_voucher_order
    DROP INDEX uk_user_voucher,
    ADD INDEX idx_user_voucher (user_id, voucher_id);
