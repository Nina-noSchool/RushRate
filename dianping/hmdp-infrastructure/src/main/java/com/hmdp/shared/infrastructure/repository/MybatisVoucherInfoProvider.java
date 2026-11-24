package com.hmdp.shared.infrastructure.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hmdp.entity.NormalOrder;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.NormalOrderMapper;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.shared.domain.compensation.spi.VoucherInfo;
import com.hmdp.shared.domain.compensation.spi.VoucherInfoProvider;
import com.hmdp.shared.reconciliation.model.VoucherOrderStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 券信息查询的 MyBatis 实现 — 闪赔按券值赔付的数据源。
 *
 * <p>两跳查询：
 * <ol>
 *   <li>查 tb_voucher 拿 pay_value（实付）和 type（决定订单在哪张表）；</li>
 *   <li>按 type 分流：type=1 秒杀券查 tb_seckill_order，type=0 普通券查 tb_normal_order，
 *       取该券最近一笔订单的 status/use_time 判断核销态。</li>
 * </ol>
 *
 * <p>为什么按最近一笔：普通券允许复购，同一 voucherId 可能有多笔订单；
 * 申诉针对的是「这张券最近一次消费」，取 create_time 最新的一笔最贴近语义。</p>
 *
 * <p>分表路由依据 tb_voucher.type 而非 voucherId 取模——这是业务分表（垂直拆分），
 * 不是 sharding 取模；路由规则与 patch_split_order_tables.sql 的迁移条件保持一致。</p>
 */
@Slf4j
@Repository
public class MybatisVoucherInfoProvider implements VoucherInfoProvider {

    @Resource
    private VoucherMapper voucherMapper;
    @Resource
    private VoucherOrderMapper voucherOrderMapper;
    @Resource
    private NormalOrderMapper normalOrderMapper;

    @Override
    public VoucherInfo findByVoucherId(Long voucherId) {
        if (voucherId == null) {
            return null;
        }
        Voucher voucher = voucherMapper.selectById(voucherId);
        if (voucher == null) {
            return null;
        }
        long payValueFen = voucher.getPayValue() == null ? 0L : voucher.getPayValue();
        Integer type = voucher.getType();

        if (type != null && type == 1) {
            // 秒杀券：tb_seckill_order（VoucherOrder 实体）
            List<VoucherOrder> orders = voucherOrderMapper.selectList(
                    Wrappers.<VoucherOrder>lambdaQuery()
                            .eq(VoucherOrder::getVoucherId, voucherId)
                            .orderByDesc(VoucherOrder::getCreateTime));
            if (orders.isEmpty()) {
                return null;
            }
            VoucherOrder latest = orders.get(0);
            boolean verified = isVerified(latest.getStatus(), latest.getUseTime());
            boolean payable = isPayable(latest.getStatus());
            return new VoucherInfo(payValueFen, verified, payable, true);
        }

        // 普通券：tb_normal_order（NormalOrder 实体，2026-11-24 补了 status/use_time）
        List<NormalOrder> orders = normalOrderMapper.selectList(
                Wrappers.<NormalOrder>lambdaQuery()
                        .eq(NormalOrder::getVoucherId, voucherId)
                        .orderByDesc(NormalOrder::getCreateTime));
        if (orders.isEmpty()) {
            return null;
        }
        NormalOrder latest = orders.get(0);
        VoucherOrderStatus status = latest.getStatus();
        boolean verified = status == VoucherOrderStatus.VERIFIED || latest.getUseTime() != null;
        boolean payable = status == VoucherOrderStatus.PAID;
        return new VoucherInfo(payValueFen, verified, payable, true);
    }

    /** 秒杀单核销判定：status=VERIFIED 或 use_time 非空 */
    private boolean isVerified(VoucherOrderStatus status, LocalDateTime useTime) {
        return status == VoucherOrderStatus.VERIFIED || useTime != null;
    }

    /** 可赔判定：仅 PAID（已支付未核销），UNPAID/CANCELLED/REFUNDING/REFUNDED 均不自动赔 */
    private boolean isPayable(VoucherOrderStatus status) {
        return status == VoucherOrderStatus.PAID;
    }
}
