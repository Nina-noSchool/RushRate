package com.hmdp.shared.domain.compensation.policy;

import com.hmdp.shared.stateengine.model.NodeResult;
import com.hmdp.shared.stateengine.model.RetryDecision;
import com.hmdp.shared.stateengine.model.StateTask;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlashPayFailurePolicyTest {

    private final StateTask task = StateTask.create("task-1", "biz-1", "FLASH_PAY", "PAYMENT", "payload");

    @Test
    void retryDisabled_retryableFailure_stillGoesManualReview() {
        FlashPayFailurePolicy policy = new FlashPayFailurePolicy(false);

        RetryDecision decision = policy.onFailure(task,
                NodeResult.failure("PAYMENT_TIMEOUT", "gateway timeout", true));

        assertTrue(decision.isManualReview());
    }

    @Test
    void retryEnabled_retryableFailure_goesRetry() {
        FlashPayFailurePolicy policy = new FlashPayFailurePolicy(true);

        RetryDecision decision = policy.onFailure(task,
                NodeResult.failure("PAYMENT_TIMEOUT", "gateway timeout", true));

        assertFalse(decision.isManualReview());
    }

    @Test
    void retryEnabled_semanticFailure_stillGoesManualReview() {
        FlashPayFailurePolicy policy = new FlashPayFailurePolicy(true);

        // 业务/数据类失败（retryable=false）重试无意义，开关开着也不重试
        RetryDecision decision = policy.onFailure(task,
                NodeResult.failure("INVALID_REASON", "reason blank", false));

        assertTrue(decision.isManualReview());
    }

    @Test
    void defaultConstructor_keepsLegacyAlwaysManualReview() {
        FlashPayFailurePolicy policy = new FlashPayFailurePolicy();

        RetryDecision retryable = policy.onFailure(task,
                NodeResult.failure("PAYMENT_TIMEOUT", "gateway timeout", true));
        RetryDecision semantic = policy.onFailure(task,
                NodeResult.failure("INVALID_REASON", "reason blank", false));

        assertTrue(retryable.isManualReview());
        assertTrue(semantic.isManualReview());
    }
}
