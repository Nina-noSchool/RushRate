package com.hmdp.shared.domain.taskchain;

import com.hmdp.shared.domain.taskchain.annotation.TaskNode;
import com.hmdp.shared.domain.taskchain.enums.ExecutionMode;
import com.hmdp.shared.domain.taskchain.enums.ProcessPhase;
import com.hmdp.shared.domain.taskchain.enums.TaskTypeEnum;
import com.hmdp.shared.domain.taskchain.model.PhaseConfig;
import com.hmdp.shared.domain.taskchain.model.TaskContext;
import com.hmdp.shared.domain.taskchain.model.TaskNodeHandler;
import com.hmdp.shared.domain.taskchain.model.TaskNodeHolder;
import com.hmdp.shared.domain.taskchain.model.TaskNodeRegistry;
import com.hmdp.shared.domain.taskchain.service.PhaseExecutionEngine;
import com.hmdp.shared.domain.taskchain.service.TaskChainEngine;
import com.hmdp.shared.domain.taskchain.service.TaskDispatcher;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并行阶段部分成功模式测试（2026-11-18，P3）：
 * 阶段超时不再整体丢弃结果——已完成节点的 output 在合并循环中被抢救回主 context，
 * 错误信息带 merged/cancelled 统计；failOnError=false 的链路带着部分数据继续，
 * failOnError=true 仍在阶段边界熔断（原语义不变，见 TaskChainEngineTest 超时用例）。
 */
class PhaseExecutionEnginePartialTest {

    @Test
    void should_salvage_output_of_completed_nodes_on_timeout_and_continue() {
        // failOnError=false：超时阶段失败 → 链路继续；已完成节点的 output 已被抢救回 context
        AtomicInteger postPhaseRuns = new AtomicInteger();
        TaskNodeHandler<Long, String> fastOutput = context -> {
            context.setAttribute("a", "shop");
            context.setOutput("partial");
        };
        TaskNodeHandler<Long, String> slow = context -> {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // 行为良好的节点：被中断后立即收尾，不再写共享 attributes
                return;
            }
            context.setAttribute("c", "slow-never-merged");
        };
        TaskNodeHandler<Long, String> assemble = context -> postPhaseRuns.incrementAndGet();

        List<TaskNodeHolder> nodes = Arrays.asList(
                parallel("fastOutput", ProcessPhase.DATA_PROCESSING, fastOutput),
                parallel("slow", ProcessPhase.DATA_PROCESSING, slow),
                serial("assemble", ProcessPhase.POST_PROCESSING, assemble)
        );

        TaskChainEngine engine = createEngine(nodes);
        TaskContext<Long, String> context = new TaskContext<>();
        context.setInput(1L);

        engine.executePhaseChain("shop_detail", context, Arrays.asList(
                new PhaseConfig(ProcessPhase.DATA_PROCESSING, ExecutionMode.PARALLEL, false, 100L),
                new PhaseConfig(ProcessPhase.POST_PROCESSING, ExecutionMode.SERIAL)
        ));

        assertEquals(1, postPhaseRuns.get());
        // 部分成功：已完成节点的 output 被抢救（修复前：超时抛异常，output 为 null）
        assertEquals("partial", context.getOutput());
        assertEquals("shop", context.<String>getAttribute("a"));
        assertFalse(context.getAttribute("c") != null);
        assertFalse(context.isSuccess());
        assertTrue(context.getErrorMessage().contains("Phase timed out"));
        assertTrue(context.getErrorMessage().contains("partial merge: merged=1, cancelled=1"));
    }

    @Test
    void should_terminate_on_timeout_with_failOnError_true_but_output_salvaged() {
        AtomicInteger postPhaseRuns = new AtomicInteger();
        TaskNodeHandler<Long, String> fastOutput = context -> {
            context.setOutput("partial");
        };
        TaskNodeHandler<Long, String> slow = context -> {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        List<TaskNodeHolder> nodes = Arrays.asList(
                parallel("fastOutput", ProcessPhase.DATA_PROCESSING, fastOutput),
                parallel("slow", ProcessPhase.DATA_PROCESSING, slow),
                serial("assemble", ProcessPhase.POST_PROCESSING, assemble(postPhaseRuns))
        );

        TaskChainEngine engine = createEngine(nodes);
        TaskContext<Long, String> context = new TaskContext<>();
        context.setInput(1L);

        engine.executePhaseChain("shop_detail", context, Arrays.asList(
                new PhaseConfig(ProcessPhase.DATA_PROCESSING, ExecutionMode.PARALLEL, true, 100L),
                new PhaseConfig(ProcessPhase.POST_PROCESSING, ExecutionMode.SERIAL)
        ));

        // failOnError=true：熔断语义不变（POST 不执行），但 output 已抢救进 context 供取证
        assertEquals(0, postPhaseRuns.get());
        assertTrue(context.isTerminateChain());
        assertFalse(context.isSuccess());
        assertEquals("partial", context.getOutput());
        assertTrue(context.getErrorMessage().contains("partial merge: merged=1, cancelled=1"));
    }

    @Test
    void partial_merge_counts_reflect_completed_and_cancelled() {
        TaskNodeHandler<Long, String> fast = context -> context.setAttribute("a", "shop");
        TaskNodeHandler<Long, String> slow1 = context -> sleep500();
        TaskNodeHandler<Long, String> slow2 = context -> sleep500();

        List<TaskNodeHolder> nodes = Arrays.asList(
                parallel("fast", ProcessPhase.DATA_PROCESSING, fast),
                parallel("slow1", ProcessPhase.DATA_PROCESSING, slow1),
                parallel("slow2", ProcessPhase.DATA_PROCESSING, slow2)
        );

        TaskChainEngine engine = createEngine(nodes);
        TaskContext<Long, String> context = new TaskContext<>();
        context.setInput(1L);

        engine.executePhaseChain("shop_detail", context, Arrays.asList(
                new PhaseConfig(ProcessPhase.DATA_PROCESSING, ExecutionMode.PARALLEL, true, 100L)
        ));

        assertTrue(context.getErrorMessage().contains("partial merge: merged=1, cancelled=2"));
    }

    private TaskNodeHandler<Long, String> assemble(AtomicInteger postPhaseRuns) {
        return context -> {
            postPhaseRuns.incrementAndGet();
            context.setOutput("should-not-run");
        };
    }

    private static void sleep500() {
        try {
            Thread.sleep(500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private TaskChainEngine createEngine(List<TaskNodeHolder> nodes) {
        TaskNodeRegistry registry = new TaskNodeRegistry(nodes);
        TaskDispatcher dispatcher = new TaskDispatcher(
                Executors.newFixedThreadPool(2),
                Executors.newFixedThreadPool(4)
        );
        PhaseExecutionEngine phaseExecutionEngine = new PhaseExecutionEngine(dispatcher);
        return new TaskChainEngine(registry, phaseExecutionEngine);
    }

    private TaskNodeHolder parallel(String nodeId, ProcessPhase phase, TaskNodeHandler<Long, String> handler) {
        return new TaskNodeHolder(handler, annotation(nodeId, phase, TaskTypeEnum.IO_BOUND));
    }

    private TaskNodeHolder serial(String nodeId, ProcessPhase phase, TaskNodeHandler<Long, String> handler) {
        return new TaskNodeHolder(handler, annotation(nodeId, phase, TaskTypeEnum.CPU_BOUND));
    }

    private TaskNode annotation(String nodeId, ProcessPhase phase, TaskTypeEnum taskTypeEnum) {
        return new TaskNode() {
            @Override
            public String nodeId() {
                return nodeId;
            }

            @Override
            public String nodeName() {
                return nodeId;
            }

            @Override
            public TaskTypeEnum taskType() {
                return taskTypeEnum;
            }

            @Override
            public String group() {
                return "shop_detail";
            }

            @Override
            public int order() {
                return 1;
            }

            @Override
            public boolean skippable() {
                return false;
            }

            @Override
            public String description() {
                return "";
            }

            @Override
            public ProcessPhase phase() {
                return phase;
            }

            @Override
            public long timeout() {
                return 0;
            }

            @Override
            public Class<? extends Annotation> annotationType() {
                return TaskNode.class;
            }
        };
    }
}
