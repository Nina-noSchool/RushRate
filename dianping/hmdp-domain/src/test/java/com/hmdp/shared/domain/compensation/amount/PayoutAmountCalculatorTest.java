package com.hmdp.shared.domain.compensation.amount;

import com.hmdp.shared.domain.compensation.spi.InMemoryVoucherInfoProvider;
import com.hmdp.shared.domain.compensation.spi.VoucherInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PayoutAmountCalculatorTest {

    private static final long MAX = 100000L;

    @Test
    void should_pay_full_voucher_value_when_unused() {
        // 券 13 实付 6800 分（68元），未核销已支付 → 全额赔 6800，不再是固定 50 分
        InMemoryVoucherInfoProvider provider = new InMemoryVoucherInfoProvider()
                .putPayable(13L, 6800L);
        PayoutAmountCalculator calc = new PayoutAmountCalculator(MAX, provider);

        PayoutQuote quote = calc.calculate(13L);

        assertTrue(quote.isPayable());
        assertEquals(6800L, quote.getAmountFen());
        assertNull(quote.getRejectCode());
    }

    @Test
    void should_reject_when_voucher_already_used() {
        // 已核销：消费已完成，不走自动赔付
        InMemoryVoucherInfoProvider provider = new InMemoryVoucherInfoProvider()
                .put(13L, new VoucherInfo(6800L, true, true));
        PayoutAmountCalculator calc = new PayoutAmountCalculator(MAX, provider);

        PayoutQuote quote = calc.calculate(13L);

        assertFalse(quote.isPayable());
        assertEquals("VOUCHER_USED", quote.getRejectCode());
        assertEquals(0L, quote.getAmountFen());
    }

    @Test
    void should_reject_when_voucher_not_found() {
        PayoutAmountCalculator calc = new PayoutAmountCalculator(MAX, new InMemoryVoucherInfoProvider());

        PayoutQuote quote = calc.calculate(999L);

        assertFalse(quote.isPayable());
        assertEquals("NO_VOUCHER", quote.getRejectCode());
    }

    @Test
    void should_reject_when_voucher_id_null() {
        PayoutAmountCalculator calc = new PayoutAmountCalculator(MAX, new InMemoryVoucherInfoProvider());

        PayoutQuote quote = calc.calculate(null);

        assertFalse(quote.isPayable());
        assertEquals("NO_VOUCHER", quote.getRejectCode());
    }

    @Test
    void should_reject_when_order_not_payable() {
        // 未支付/已取消的订单不在赔付范围
        InMemoryVoucherInfoProvider provider = new InMemoryVoucherInfoProvider()
                .put(13L, new VoucherInfo(6800L, false, false));
        PayoutAmountCalculator calc = new PayoutAmountCalculator(MAX, provider);

        PayoutQuote quote = calc.calculate(13L);

        assertFalse(quote.isPayable());
        assertEquals("ORDER_NOT_PAYABLE", quote.getRejectCode());
    }

    @Test
    void should_reject_invalid_voucher_value() {
        InMemoryVoucherInfoProvider provider = new InMemoryVoucherInfoProvider()
                .put(13L, new VoucherInfo(0L, false, true));
        PayoutAmountCalculator calc = new PayoutAmountCalculator(MAX, provider);

        PayoutQuote quote = calc.calculate(13L);

        assertFalse(quote.isPayable());
        assertEquals("INVALID_VOUCHER_VALUE", quote.getRejectCode());
    }

    @Test
    void should_reject_and_not_truncate_when_exceeds_max() {
        // 关键：超限转人工，绝不把 200000 分截断成 100000 分还宣称"全额退"
        InMemoryVoucherInfoProvider provider = new InMemoryVoucherInfoProvider()
                .putPayable(13L, 200000L);
        PayoutAmountCalculator calc = new PayoutAmountCalculator(MAX, provider);

        PayoutQuote quote = calc.calculate(13L);

        assertFalse(quote.isPayable());
        assertEquals("AMOUNT_EXCEEDS_LIMIT", quote.getRejectCode());
        assertEquals(0L, quote.getAmountFen());
    }

    @Test
    void should_pay_when_value_exactly_at_max() {
        // 边界：恰好等于上限可赔
        InMemoryVoucherInfoProvider provider = new InMemoryVoucherInfoProvider()
                .putPayable(13L, MAX);
        PayoutAmountCalculator calc = new PayoutAmountCalculator(MAX, provider);

        PayoutQuote quote = calc.calculate(13L);

        assertTrue(quote.isPayable());
        assertEquals(MAX, quote.getAmountFen());
    }
}
