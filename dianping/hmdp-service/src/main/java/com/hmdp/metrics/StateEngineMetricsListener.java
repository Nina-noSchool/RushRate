package com.hmdp.metrics;

import com.hmdp.shared.stateengine.service.StateEngineListener;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 状态机引擎的 Micrometer 监听器（2026-11-18 可观测性，P1）。
 *
 * <p>任务到达终局（COMPLETED / FAILED / MANUAL_REVIEW）时由引擎回调，把
 * Q34 追问 3 自认的"FAILED、MANUAL_REVIEW、流转耗时没有指标"缺口补上。
 * 指标口径（点分名 → Prometheus 下划线）：</p>
 * <ul>
 *   <li>{@code hmdp.stateengine.task.count}（Counter，tag: machine/state）——
 *       终局三分计数：成功（COMPLETED）、可重试挂起（FAILED）、转人工（MANUAL_REVIEW），
 *       失败率与转人工占比直接从这组计数出</li>
 *   <li>{@code hmdp.stateengine.task.duration}（Timer，tag: machine）——
 *       单次 start/resume 驱动的总耗时分布</li>
 * </ul>
 *
 * <p>引擎侧已对监听器异常做吞噬兜底，这里再自保一层，绝不影响业务执行。</p>
 */
@Component
public class StateEngineMetricsListener implements StateEngineListener {

    private final MeterRegistry registry;

    public StateEngineMetricsListener(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onTaskFinished(String machineCode, String taskId, String runtimeStatus,
                               long costMs, boolean success, String error) {
        try {
            registry.counter("hmdp.stateengine.task.count",
                            "machine", machineCode, "state", runtimeStatus)
                    .increment();
            registry.timer("hmdp.stateengine.task.duration", "machine", machineCode)
                    .record(Math.max(costMs, 0), TimeUnit.MILLISECONDS);
        } catch (Exception ignore) {
        }
    }
}
