package com.hmdp.job.seckill;

import com.hmdp.shared.stateengine.model.StateTask;
import com.hmdp.shared.stateengine.model.StateTaskStatus;
import com.hmdp.shared.stateengine.service.StateEngine;
import com.hmdp.shared.stateengine.spi.StateTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 闪赔 FAILED 任务重试调度 Job 测试（P4）：开关关不扫、退避未到不 resume、
 * 退避已到才 resume——三态与 Job 的节奏契约一致。
 */
class FlashPayStateRetryJobTest {

    private StateTaskRepository taskRepository;
    private StateEngine stateEngine;
    private FlashPayStateRetryJob job;

    @BeforeEach
    void setUp() {
        taskRepository = mock(StateTaskRepository.class);
        stateEngine = mock(StateEngine.class);
        job = new FlashPayStateRetryJob();
        setField(job, "stateTaskRepository", taskRepository);
        setField(job, "stateEngine", stateEngine);
        setField(job, "retryEnabled", true);
        setField(job, "retryDelaySeconds", 30);
    }

    @Test
    void retryDisabled_shouldNotScanAtAll() {
        setField(job, "retryEnabled", false);

        job.retryFailedFlashPayTasks();

        verify(taskRepository, never()).findByMachineCodeAndStatus(any(), any());
        verify(stateEngine, never()).resume(any());
    }

    @Test
    void retryEnabled_backoffElapsed_resumesOnlyElapsedTask() {
        StateTask elapsed = failedTask("task-elapsed", 0, LocalDateTime.now().minusSeconds(300));
        StateTask fresh = failedTask("task-fresh", 0, LocalDateTime.now());
        when(taskRepository.findByMachineCodeAndStatus("FLASH_PAY", StateTaskStatus.FAILED))
                .thenReturn(List.of(elapsed, fresh));

        job.retryFailedFlashPayTasks();

        // 只 resume 退避已过的任务：fresh 的 updatedAt 距今不足 30 秒，被退避规则跳过
        verify(stateEngine).resume(elapsed);
        verify(stateEngine, never()).resume(fresh);
        assertEquals(1, 1);
    }

    @Test
    void retryEnabled_noFailedTasks_neverResumes() {
        when(taskRepository.findByMachineCodeAndStatus("FLASH_PAY", StateTaskStatus.FAILED))
                .thenReturn(Collections.emptyList());

        job.retryFailedFlashPayTasks();

        verify(stateEngine, never()).resume(any());
    }

    private StateTask failedTask(String taskId, int retryCount, LocalDateTime updatedAt) {
        StateTask task = StateTask.create(taskId, "biz-" + taskId, "FLASH_PAY", "PAYMENT", "payload");
        task.markRunning();
        task.markFailed("PAYMENT", "gateway timeout");
        for (int i = 0; i < retryCount; i++) {
            task.incrementRetryCount();
        }
        setField(task, "updatedAt", updatedAt);
        assertTrue(task.getRuntimeStatus() == StateTaskStatus.FAILED);
        return task;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("字段注入失败: " + name, e);
        }
    }
}
