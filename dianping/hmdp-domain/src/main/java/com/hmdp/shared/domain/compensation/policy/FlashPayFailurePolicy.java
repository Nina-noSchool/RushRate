package com.hmdp.shared.domain.compensation.policy;

import com.hmdp.shared.stateengine.model.NodeResult;
import com.hmdp.shared.stateengine.model.RetryDecision;
import com.hmdp.shared.stateengine.model.StateTask;
import com.hmdp.shared.stateengine.policy.FailurePolicy;

/**
 * 无忧闪赔失败策略 — 可重试分层（2026-11-18，P4 兑现此前的演进注释）。
 *
 * <p>定位：无忧闪赔状态机层。失败按语义分层：</p>
 * <ul>
 *   <li><b>retryable=true</b>（基础设施类失败，如支付网关超时）且重试开关开启 →
 *       返回 {@link RetryDecision#retry}——结合 RetryPolicy 的次数预算，
 *       任务进入 FAILED 等待重试调度 Job 以退避节奏 resume（引擎的
 *       claimForResume CAS 保证并发安全）。</li>
 *   <li><b>其余失败</b>（申诉原因为空、无金额规则等数据/业务问题）→
 *       manualReview，重试无意义。</li>
 * </ul>
 *
 * <p>开关默认关：开启前置是支付幂等能力验证通过（InMemoryPayoutGateway 已按
 * claimNo 幂等，可先用场景网关验证后开启）。开关关闭时行为与旧版完全一致。</p>
 */
public class FlashPayFailurePolicy implements FailurePolicy {

    /** 可重试失败是否走自动重试（false = 失败一律转人工，旧语义） */
    private final boolean retryEnabled;

    public FlashPayFailurePolicy() {
        this(false);
    }

    public FlashPayFailurePolicy(boolean retryEnabled) {
        this.retryEnabled = retryEnabled;
    }

    @Override
    public RetryDecision onFailure(StateTask task, NodeResult result) {
        if (retryEnabled && result.isRetryable()) {
            return RetryDecision.retry("retryable failure: " + result.getErrorCode());
        }
        return RetryDecision.manualReview(result.getErrorMessage());
    }
}