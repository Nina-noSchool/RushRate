package com.hmdp.metrics;

import com.hmdp.shared.domain.taskchain.service.TaskChainListener;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 任务链引擎的 Micrometer 监听器（2026-11-18 可观测性，P1）。
 *
 * <p>引擎在节点/阶段结束时通过 {@link TaskChainListener} SPI 回调本类，
 * 把 Q34 追问 3 自认的"节点耗时、失败率、阶段超时没有指标"缺口补上。
 * 指标口径（点分名 → Prometheus 下划线）：</p>
 * <ul>
 *   <li>{@code hmdp.taskchain.node.duration}（Timer，tag: phase/task_type/node）——
 *       节点耗时分布，慢节点看 max；nodeId 取值有限（商详链 6 节点级别），基数可控</li>
 *   <li>{@code hmdp.taskchain.node.failure}（Counter，tag: phase/node）—— 节点失败数，
 *       含被 skippable 吞掉的失败，能回答"哪个节点在坏"</li>
 *   <li>{@code hmdp.taskchain.phase.duration} / {@code hmdp.taskchain.phase.failure}
 *       （tag: phase）—— 阶段粒度同构，阶段超时会在 failure 里可见</li>
 * </ul>
 *
 * <p>注册即存在（首次 record 前计数为 0），Grafana 面板不会 no data；
 * 引擎侧已对监听器异常做吞噬兜底，这里再自保一层，绝不影响业务执行。</p>
 */
@Component
public class TaskChainMetricsListener implements TaskChainListener {

    private final MeterRegistry registry;

    public TaskChainMetricsListener(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onNodeEnd(String phase, String nodeId, String taskType, long costMs,
                          boolean success, String error) {
        try {
            registry.timer("hmdp.taskchain.node.duration",
                            "phase", phase, "task_type", taskType, "node", nodeId)
                    .record(Math.max(costMs, 0), TimeUnit.MILLISECONDS);
            if (!success) {
                registry.counter("hmdp.taskchain.node.failure",
                                "phase", phase, "node", nodeId)
                        .increment();
            }
        } catch (Exception ignore) {
            // 可观测性组件故障绝不影响业务执行（引擎侧已有兜底，这里再自保一层）
        }
    }

    @Override
    public void onPhaseEnd(String phase, long costMs, boolean success, String error) {
        try {
            registry.timer("hmdp.taskchain.phase.duration", "phase", phase)
                    .record(Math.max(costMs, 0), TimeUnit.MILLISECONDS);
            if (!success) {
                registry.counter("hmdp.taskchain.phase.failure", "phase", phase).increment();
            }
        } catch (Exception ignore) {
        }
    }
}
