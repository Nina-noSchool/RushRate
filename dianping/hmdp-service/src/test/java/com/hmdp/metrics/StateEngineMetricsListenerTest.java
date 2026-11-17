package com.hmdp.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateEngineMetricsListenerTest {

    @Test
    void onTaskFinished_countsByTerminalState() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StateEngineMetricsListener listener = new StateEngineMetricsListener(registry);

        listener.onTaskFinished("flash_pay", "task-1", "COMPLETED", 120, true, null);
        listener.onTaskFinished("flash_pay", "task-2", "MANUAL_REVIEW", 80, false, "invalid reason");
        listener.onTaskFinished("flash_pay", "task-3", "FAILED", 60, false, "gateway timeout");

        assertEquals(1.0, registry.get("hmdp.stateengine.task.count")
                .tag("machine", "flash_pay").tag("state", "COMPLETED").counter().count());
        assertEquals(1.0, registry.get("hmdp.stateengine.task.count")
                .tag("machine", "flash_pay").tag("state", "MANUAL_REVIEW").counter().count());
        assertEquals(1.0, registry.get("hmdp.stateengine.task.count")
                .tag("machine", "flash_pay").tag("state", "FAILED").counter().count());
    }

    @Test
    void onTaskFinished_recordsDurationTimer() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StateEngineMetricsListener listener = new StateEngineMetricsListener(registry);

        listener.onTaskFinished("flash_pay", "task-1", "COMPLETED", 250, true, null);

        var timer = registry.get("hmdp.stateengine.task.duration")
                .tag("machine", "flash_pay").timer();
        assertEquals(1, timer.count());
        assertTrue(timer.totalTime(TimeUnit.MILLISECONDS) >= 250.0);
    }

    @Test
    void onTaskFinished_negativeCost_isClampedToZeroNotThrow() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StateEngineMetricsListener listener = new StateEngineMetricsListener(registry);

        listener.onTaskFinished("flash_pay", "task-1", "FAILED", -3, false, "boom");

        assertEquals(1, registry.get("hmdp.stateengine.task.duration")
                .tag("machine", "flash_pay").timer().count());
    }
}
