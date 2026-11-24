package com.hmdp.shared.domain.compensation.spi;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 闪赔赔付所需的券信息 — 领域层值对象，屏蔽 DB 表结构细节。
 *
 * <p>只保留赔付决策真正需要的三个维度，不让领域层感知 Voucher/VoucherOrder 实体：
 * <ul>
 *   <li>{@code payValueFen}：券的实际支付金额（分），赔付基数；来自 tb_voucher.pay_value；</li>
 *   <li>{@code verified}：是否已核销——核销即消费完成，不走自动全退；</li>
 *   <li>{@code payable}：订单是否处于可赔状态（已支付且未取消/未退款）。</li>
 * </ul>
 */
@Getter
@AllArgsConstructor
public class VoucherInfo {

    /** 券实际支付金额（分），例如 6800 = 68 元 */
    private final long payValueFen;

    /** 是否已核销（status=VERIFIED 或 use_time 非空） */
    private final boolean verified;

    /** 订单是否可赔（PAID 且非 CANCELLED/REFUNDED/REFUNDING） */
    private final boolean payable;

    /** 券是否在 DB 中存在——与"金额是否有效"解耦，避免把"金额0的券"误判成"券不存在" */
    private final boolean exists;

    /** 全参构造的便捷重载：默认券存在 */
    public VoucherInfo(long payValueFen, boolean verified, boolean payable) {
        this(payValueFen, verified, payable, true);
    }
}
