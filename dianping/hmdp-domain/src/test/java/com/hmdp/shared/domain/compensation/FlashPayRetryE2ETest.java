package com.hmdp.shared.domain.compensation;

import com.hmdp.shared.domain.compensation.model.FlashPayPayload;
import com.hmdp.shared.domain.compensation.service.FlashPayStateMachineFacade;
import com.hmdp.shared.domain.compensation.spi.InMemoryPayoutAuditRepository;
import com.hmdp.shared.domain.compensation.spi.InMemoryPayoutGateway;
import com.hmdp.shared.domain.compensation.spi.LoggingPayoutNotificationService;
import com.hmdp.shared.domain.compensation.amount.PayoutAmountCalculator;
import com.hmdp.shared.domain.compensation.machine.FlashPayStateMachineDefinition;
import com.hmdp.shared.stateengine.model.StateTask;
import com.hmdp.shared.stateengine.model.StateTaskStatus;
import com.hmdp.shared.stateengine.service.StateEngine;
import com.hmdp.shared.stateengine.service.StateMachineRegistry;
import com.hmdp.shared.stateengine.spi.InMemoryStateTaskRepository;
import com.hmdp.shared.stateengine.spi.InMemoryStateTransitionLogRepository;
import com.hmdp.shared.stateengine.policy.RetryPolicy;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * retryable 分层 + 重试调度的端到端验证（2026-11-18，P6）。
 *
 * <p>场景：网关 FAIL_THEN_SUCCESS（首次 GATEWAY_TIMEOUT，重试成功）+ 重试开关开启。
 * 全链路：start → 支付超时(retryable=true) → FAILED → resume → 网关二次支付成功 →
 * COMPLETED（审计+通知由 COMPLETED 节点完成）。这条测试证明的是 P4 分层在支付失败
 * 上真正生效——修复前 FlashPayApprovedNode 把所有失败写死 retryable=false，分层是休眠的。</p>
 */
class FlashPayRetryE2ETest {

    @Test
    void retryable_payment_timeout_then_resume_completes() {
        InMemoryPayoutGateway gateway = new InMemoryPayoutGateway(
                com.hmdp.shared.domain.compensation.spi.MockMode.FAIL_THEN_SUCCESS);
        InMemoryPayoutAuditRepository auditRepository = new InMemoryPayoutAuditRepository();
        LoggingPayoutNotificationService notificationService = new LoggingPayoutNotificationService();

        StateMachineRegistry registry = new StateMachineRegistry();
        registry.register(new FlashPayStateMachineDefinition(
                new PayoutAmountCalculator(100000L,
                        new com.hmdp.shared.domain.compensation.spi.InMemoryVoucherInfoProvider()
                                .putPayable(13L, 6800L)),
                gateway, auditRepository, notificationService,
                true));
        StateEngine engine = new StateEngine(
                registry,
                new InMemoryStateTaskRepository(),
                new InMemoryStateTransitionLogRepository(),
                new RetryPolicy(3)
        );
        FlashPayStateMachineFacade facade = new FlashPayStateMachineFacade(engine,
                new InMemoryStateTaskRepository());

        // 第一次驱动：支付超时（retryable=true）→ FAILED（等待重试调度 resume）
        StateTask first = facade.start("claim-e2e", "shop-1",
                new FlashPayPayload("FLASH_PAY", "商家提前打烊，权益未兑现", 13L));
        assertEquals(StateTaskStatus.FAILED, first.getRuntimeStatus());
        assertEquals(1, first.getRetryCount());
        assertNotNull(first.getLastMessage());
        // 网关尚未落定支付记录（首次失败不写幂等键，重试才能重新支付）
        assertEquals(null, gateway.query("claim-e2e"));

        // 第二次驱动（模拟重试调度 Job 的 resume）：网关二次支付成功 → COMPLETED
        StateTask second = facade.startOrResume("claim-e2e", "shop-1",
                new FlashPayPayload("FLASH_PAY", "商家提前打烊，权益未兑现", 13L));
        assertEquals(StateTaskStatus.COMPLETED, second.getRuntimeStatus());
        // 幂等证据：支付记录恰好一笔，终局消息是 COMPLETED 节点的自动赔付摘要
        assertNotNull(gateway.query("claim-e2e"));
        assertTrue(second.getLastMessage().contains("自动赔付"));
    }

    @Test
    void retry_switch_off_payment_timeout_still_goes_manual_review() {
        InMemoryPayoutGateway gateway = new InMemoryPayoutGateway(
                com.hmdp.shared.domain.compensation.spi.MockMode.FAIL);
        StateMachineRegistry registry = new StateMachineRegistry();
        registry.register(new FlashPayStateMachineDefinition(
                new PayoutAmountCalculator(100000L,
                        new com.hmdp.shared.domain.compensation.spi.InMemoryVoucherInfoProvider()
                                .putPayable(13L, 6800L)),
                gateway, new InMemoryPayoutAuditRepository(), new LoggingPayoutNotificationService(),
                false));
        StateEngine engine = new StateEngine(
                registry,
                new InMemoryStateTaskRepository(),
                new InMemoryStateTransitionLogRepository(),
                new RetryPolicy(3)
        );
        FlashPayStateMachineFacade facade = new FlashPayStateMachineFacade(engine,
                new InMemoryStateTaskRepository());

        StateTask result = facade.start("claim-off", "shop-1",
                new FlashPayPayload("FLASH_PAY", "商家提前打烊，权益未兑现", 13L));

        // 开关关闭 = 旧语义：即使 GATEWAY_TIMEOUT 也转人工
        assertEquals(StateTaskStatus.MANUAL_REVIEW, result.getRuntimeStatus());
    }
}
