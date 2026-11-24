package com.hmdp.shared.domain.compensation.amount;

import com.hmdp.shared.domain.compensation.spi.VoucherInfo;
import com.hmdp.shared.domain.compensation.spi.VoucherInfoProvider;

/**
 * 赔付金额计算器 — 按券的实际支付金额报价，受单笔上限做风控兜底。
 *
 * <p><b>2026-11-24 改造</b>：从「planCode 查规则表给固定值」改为「按 voucherId 查券实付金额全额退」。
 * 规则表（payout-rules）已移除——它赔 50 分、券值 6800 分，违背业务常识。</p>
 *
 * <p>赔付决策（按顺序短路）：
 * <ol>
 *   <li>券不存在 / voucherId 缺失 → NO_VOUCHER，转人工；</li>
 *   <li>已核销 → VOUCHER_USED，转人工（消费已完成，不属于闪赔范围）；</li>
 *   <li>订单不可赔（未支付/已取消/已退款）→ ORDER_NOT_PAYABLE，转人工；</li>
 *   <li>券值 &le; 0 → INVALID_VOUCHER_VALUE，转人工；</li>
 *   <li>券值 &gt; maxAmount → AMOUNT_EXCEEDS_LIMIT，转人工（上限是风控兜底，不是把 68 元截成 5 元）；</li>
 *   <li>正常 → 全额赔 payValueFen。</li>
 * </ol>
 *
 * <p>依赖通过构造器注入 VoucherInfoProvider，领域层不感知 DB 表结构；
 * 测试可用内存实现替换，不必起数据库。</p>
 */
public class PayoutAmountCalculator {

    /** 单笔赔付上限（分）——异常大额兜底，超限转人工而非截断 */
    private final long maxAmount;
    private final VoucherInfoProvider voucherInfoProvider;

    public PayoutAmountCalculator(long maxAmount, VoucherInfoProvider voucherInfoProvider) {
        this.maxAmount = maxAmount;
        this.voucherInfoProvider = voucherInfoProvider;
    }

    /**
     * 按券 ID 计算赔付报价。
     *
     * @param voucherId 券 ID
     * @return 可赔报价（金额=券实付）或带原因码的拒绝报价
     */
    public PayoutQuote calculate(Long voucherId) {
        if (voucherId == null) {
            return PayoutQuote.reject("NO_VOUCHER", "申诉未关联券，无法计算赔付金额");
        }
        VoucherInfo info = voucherInfoProvider.findByVoucherId(voucherId);
        if (info == null || !info.isExists()) {
            return PayoutQuote.reject("NO_VOUCHER", "券不存在或查不到有效订单: " + voucherId);
        }
        if (info.isVerified()) {
            return PayoutQuote.reject("VOUCHER_USED", "券已核销，消费已完成，不走自动赔付");
        }
        if (!info.isPayable()) {
            return PayoutQuote.reject("ORDER_NOT_PAYABLE", "订单未支付/已取消/已退款，不在赔付范围");
        }
        long payValue = info.getPayValueFen();
        if (payValue <= 0) {
            return PayoutQuote.reject("INVALID_VOUCHER_VALUE", "券支付金额无效: " + payValue);
        }
        if (payValue > maxAmount) {
            // 上限是风控兜底：超限转人工，绝不把 68 元截成 5 元后还宣称"全额退"
            return PayoutQuote.reject("AMOUNT_EXCEEDS_LIMIT",
                    "券值 " + payValue + "分超过单笔上限 " + maxAmount + "分，转人工风控复核");
        }
        return PayoutQuote.pay(payValue);
    }
}
