package com.hmdp.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskChainMetricsListenerTest {

    @Test
    void onNodeEnd_success_recordsDurationTimerOnly() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TaskChainMetricsListener listener = new TaskChainMetricsListener(registry);

        listener.onNodeEnd("DATA_PROCESSING", "QueryShopBase", "IO_BOUND", 120, true, null);

        var timer = registry.get("hmdp.taskchain.node.duration")
                .tag("phase", "DATA_PROCESSING")
                .tag("node", "QueryShopBase")
                .tag("task_type", "IO_BOUND")
                .timer();
        assertEquals(1, timer.count());
        assertTrue(timer.totalTime(TimeUnit.MILLISECONDS) >= 120.0);
        // 成功不注册失败计数（find 找不到 meter 返回空，不会抛）
        assertEquals(0, registry.find("hmdp.taskchain.node.failure").counters().size());
    }

    @Test
    void onNodeEnd_failure_incrementsFailureCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TaskChainMetricsListener listener = new TaskChainMetricsListener(registry);

        listener.onNodeEnd("DATA_PROCESSING", "QueryShopBlogs", "IO_BOUND", 50, false, "db down");

        var counter = registry.get("hmdp.taskchain.node.failure")
                .tag("phase", "DATA_PROCESSING")
                .tag("node", "QueryShopBlogs")
                .counter();
        assertEquals(1.0, counter.count());
    }

    @Test
    void onPhaseEnd_recordsDurationAndFailure() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TaskChainMetricsListener listener = new TaskChainMetricsListener(registry);

        listener.onPhaseEnd("DATA_PROCESSING", 300, true, null);
        listener.onPhaseEnd("POST_PROCESSING", 999, false, "phase timeout");

        assertEquals(1, registry.get("hmdp.taskchain.phase.duration")
                .tag("phase", "DATA_PROCESSING").timer().count());
        assertEquals(1.0, registry.get("hmdp.taskchain.phase.failure")
                .tag("phase", "POST_PROCESSING").counter().count());
    }

    @Test
    void onNodeEnd_negativeCost_isClampedToZeroNotThrow() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TaskChainMetricsListener listener = new TaskChainMetricsListener(registry);

        listener.onNodeEnd("DATA_PROCESSING", "QueryShopVouchers", "IO_BOUND", -5, true, null);

        assertEquals(1, registry.get("hmdp.taskchain.node.duration")
                .tag("node", "QueryShopVouchers").timer().count());
    }
}
