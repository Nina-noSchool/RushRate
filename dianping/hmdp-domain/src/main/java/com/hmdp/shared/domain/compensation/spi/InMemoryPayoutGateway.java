package com.hmdp.shared.domain.compensation.spi;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存支付网关 — 测试/默认装配用的 PayoutGateway 实现。
 *
 * <p>按 claimNo 幂等：pay 用 computeIfAbsent，重复调用返回同一 txId。</p>
 */
public class InMemoryPayoutGateway implements PayoutGateway {

    private final ConcurrentMap<String, PaymentRecord> store = new ConcurrentHashMap<>();
    /** FAIL_THEN_SUCCESS 模式的调用计数：同一 claimNo 第 1 次 pay 失败（可重试超时），之后成功 */
    private final ConcurrentMap<String, Integer> attemptCounts = new ConcurrentHashMap<>();
    private final AtomicLong txSeq = new AtomicLong(0);
    private final MockMode mode;

    public InMemoryPayoutGateway(MockMode mode) {
        this.mode = mode == null ? MockMode.SUCCESS : mode;
    }

    @Override
    public PaymentResult pay(String claimNo, long amount) {
        PaymentRecord existing = store.get(claimNo);
        if (existing != null) {
            return existing.isSuccess()
                    ? PaymentResult.success(existing.getTxId())
                    : PaymentResult.failure("PREVIOUS_FAILED", "上一次支付已失败");
        }
        String txId = "TX-" + txSeq.incrementAndGet();
        switch (mode) {
            case FAIL:
                store.put(claimNo, new PaymentRecord(claimNo, amount, txId, false, "GATEWAY_TIMEOUT"));
                return PaymentResult.failure("GATEWAY_TIMEOUT", "支付网关超时");
            case REJECT:
                store.put(claimNo, new PaymentRecord(claimNo, amount, txId, false, "RISK_REJECTED"));
                return PaymentResult.failure("RISK_REJECTED", "风控拒绝");
            case FAIL_THEN_SUCCESS: {
                int attempt = attemptCounts.merge(claimNo, 1, Integer::sum);
                if (attempt == 1) {
                    // 首次：可重试超时——不写 store，让下一次调用真正重新支付（幂等键尚未落定）
                    return PaymentResult.failure("GATEWAY_TIMEOUT", "支付网关超时(首次)");
                }
                store.put(claimNo, new PaymentRecord(claimNo, amount, txId, true, "OK"));
                return PaymentResult.success(txId);
            }
            default:
                store.put(claimNo, new PaymentRecord(claimNo, amount, txId, true, "OK"));
                return PaymentResult.success(txId);
        }
    }

    @Override
    public PaymentRecord query(String claimNo) {
        return store.get(claimNo);
    }
}