package com.hmdp.shared.stateengine;

import com.hmdp.shared.stateengine.model.NodeResult;
import com.hmdp.shared.stateengine.model.RetryDecision;
import com.hmdp.shared.stateengine.model.StateTask;
import com.hmdp.shared.stateengine.model.StateTaskStatus;
import com.hmdp.shared.stateengine.policy.RetryPolicy;
import com.hmdp.shared.stateengine.service.StateContext;
import com.hmdp.shared.stateengine.service.StateEngine;
import com.hmdp.shared.stateengine.service.StateEngineListener;
import com.hmdp.shared.stateengine.service.StateMachineDefinition;
import com.hmdp.shared.stateengine.service.StateMachineRegistry;
import com.hmdp.shared.stateengine.service.StateNode;
import com.hmdp.shared.stateengine.spi.InMemoryStateTaskRepository;
import com.hmdp.shared.stateengine.spi.InMemoryStateTransitionLogRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 终局监听器 SPI 测试（2026-11-18 可观测性）：任务到达终局时恰好回调一次，
 * runtimeStatus 正确；监听器抛异常被引擎吞掉，业务照常完成——可观测性组件故障绝不影响业务。
 */
class StateEngineListenerTest {

    /** 记录式监听器：把回调参数全部存下来供断言 */
    private static class RecordingListener implements StateEngineListener {
        final List<String> machineCodes = new ArrayList<>();
        final List<String> taskIds = new ArrayList<>();
        final List<String> runtimeStatuses = new ArrayList<>();
        final List<Long> costs = new ArrayList<>();
        final List<Boolean> successes = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        @Override
        public void onTaskFinished(String machineCode, String taskId, String runtimeStatus,
                                   long costMs, boolean success, String error) {
            machineCodes.add(machineCode);
            taskIds.add(taskId);
            runtimeStatuses.add(runtimeStatus);
            costs.add(costMs);
            successes.add(success);
            errors.add(error);
        }
    }

    /** 抛异常的监听器：验证引擎的吞噬兜底 */
    private static class ThrowingListener implements StateEngineListener {
        @Override
        public void onTaskFinished(String machineCode, String taskId, String runtimeStatus,
                                   long costMs, boolean success, String error) {
            throw new RuntimeException("listener boom");
        }
    }

    /** 固定结果的节点（自备版，StateEngineTest 里的同名类是 private） */
    private static class FixedResultNode implements StateNode {
        private final String code;
        private final NodeResult result;

        private FixedResultNode(String code, NodeResult result) {
            this.code = code;
            this.result = result;
        }

        @Override
        public String getStateCode() {
            return code;
        }

        @Override
        public NodeResult execute(StateTask task, StateContext context) {
            return result;
        }
    }

    private StateMachineRegistry completedMachineRegistry() {
        StateMachineRegistry registry = new StateMachineRegistry();
        registry.register(StateMachineDefinition.builder("demo_machine", "SUBMITTED")
                .addNode(new FixedResultNode("SUBMITTED", NodeResult.next("COMPLETED", "ok")))
                .addNode(new FixedResultNode("COMPLETED", NodeResult.terminalSuccess("completed")))
                .terminalStates(Collections.singleton("COMPLETED"))
                .failurePolicy((task, result) -> RetryDecision.manualReview("terminal failure"))
                .build());
        return registry;
    }

    @Test
    void onTaskFinished_firesOnceWithCompletedOnSuccess() {
        RecordingListener listener = new RecordingListener();
        StateEngine engine = new StateEngine(
                completedMachineRegistry(),
                new InMemoryStateTaskRepository(),
                new InMemoryStateTransitionLogRepository(),
                new RetryPolicy(3),
                List.of(listener)
        );

        engine.start(StateTask.create("task-1", "biz-1", "demo_machine", "SUBMITTED", "payload"));

        assertEquals(1, listener.taskIds.size());
        assertEquals("demo_machine", listener.machineCodes.get(0));
        assertEquals("task-1", listener.taskIds.get(0));
        assertEquals(StateTaskStatus.COMPLETED.name(), listener.runtimeStatuses.get(0));
        assertTrue(listener.successes.get(0));
        assertTrue(listener.costs.get(0) >= 0);
    }

    @Test
    void onTaskFinished_firesWithManualReviewOnPolicyReject() {
        RecordingListener listener = new RecordingListener();
        StateMachineRegistry registry = new StateMachineRegistry();
        registry.register(StateMachineDefinition.builder("demo_machine", "SUBMITTED")
                .addNode(new FixedResultNode("SUBMITTED",
                        NodeResult.failure("INVALID_REASON", "reason blank", false)))
                .terminalStates(Collections.singleton("MANUAL_REVIEW"))
                .failurePolicy((task, result) -> RetryDecision.manualReview(result.getErrorMessage()))
                .build());
        StateEngine engine = new StateEngine(
                registry,
                new InMemoryStateTaskRepository(),
                new InMemoryStateTransitionLogRepository(),
                new RetryPolicy(3),
                List.of(listener)
        );

        engine.start(StateTask.create("task-2", "biz-2", "demo_machine", "SUBMITTED", "payload"));

        assertEquals(1, listener.taskIds.size());
        assertEquals(StateTaskStatus.MANUAL_REVIEW.name(), listener.runtimeStatuses.get(0));
        assertFalse(listener.successes.get(0));
        assertTrue(listener.errors.get(0) != null && !listener.errors.get(0).isEmpty());
    }

    @Test
    void throwingListener_isSwallowed_businessStillCompletes() {
        StateEngine engine = new StateEngine(
                completedMachineRegistry(),
                new InMemoryStateTaskRepository(),
                new InMemoryStateTransitionLogRepository(),
                new RetryPolicy(3),
                List.of(new ThrowingListener())
        );

        StateTask result = engine.start(
                StateTask.create("task-3", "biz-3", "demo_machine", "SUBMITTED", "payload"));

        assertEquals(StateTaskStatus.COMPLETED, result.getRuntimeStatus());
    }
}
