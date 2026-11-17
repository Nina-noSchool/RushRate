package com.hmdp.shared.stateengine;

import com.hmdp.shared.stateengine.model.StateTask;
import com.hmdp.shared.stateengine.model.StateTaskStatus;
import com.hmdp.shared.stateengine.spi.InMemoryStateTaskRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryStateTaskRepositoryTest {

    @Test
    void findByTaskId_shouldReturnSavedTask() {
        InMemoryStateTaskRepository repository = new InMemoryStateTaskRepository();
        StateTask task = StateTask.create("task-1", "biz-1", "MACHINE", "CHECKING", "payload");

        repository.save(task);

        StateTask restored = repository.findByTaskId("task-1");

        assertNotNull(restored);
        assertEquals("task-1", restored.getTaskId());
        assertEquals("CHECKING", restored.getCurrentState());
    }

    @Test
    void claimForResume_shouldTransitionFailedAndManualReviewToRunning() {
        InMemoryStateTaskRepository repository = new InMemoryStateTaskRepository();

        StateTask failed = StateTask.create("task-f", "biz-f", "MACHINE", "CHECKING", "payload");
        failed.markFailed("CHECKING", "waiting retry");
        repository.save(failed);
        StateTask manual = StateTask.create("task-m", "biz-m", "MACHINE", "MANUAL_REVIEW", "payload");
        manual.markManualReview("need human");
        repository.save(manual);

        assertTrue(repository.claimForResume(failed));
        assertEquals(StateTaskStatus.RUNNING, repository.findByTaskId("task-f").getRuntimeStatus());

        assertTrue(repository.claimForResume(manual));
        assertEquals(StateTaskStatus.RUNNING, repository.findByTaskId("task-m").getRuntimeStatus());
    }

    @Test
    void claimForResume_shouldRejectNonResumableStatuses() {
        InMemoryStateTaskRepository repository = new InMemoryStateTaskRepository();

        StateTask pending = StateTask.create("task-p", "biz-p", "MACHINE", "CHECKING", "payload");
        repository.save(pending);
        StateTask running = StateTask.create("task-r", "biz-r", "MACHINE", "CHECKING", "payload");
        running.markRunning();
        repository.save(running);
        StateTask completed = StateTask.create("task-c", "biz-c", "MACHINE", "CHECKING", "payload");
        completed.markCompleted("CHECKING", "done");
        repository.save(completed);
        StateTask unknown = StateTask.create("task-x", "biz-x", "MACHINE", "CHECKING", "payload");

        assertFalse(repository.claimForResume(pending));
        assertFalse(repository.claimForResume(running));
        assertFalse(repository.claimForResume(completed));
        assertFalse(repository.claimForResume(unknown));

        // 抢占失败不改状态
        assertEquals(StateTaskStatus.PENDING, repository.findByTaskId("task-p").getRuntimeStatus());
        assertEquals(StateTaskStatus.RUNNING, repository.findByTaskId("task-r").getRuntimeStatus());
        assertEquals(StateTaskStatus.COMPLETED, repository.findByTaskId("task-c").getRuntimeStatus());
    }

    @Test
    void claimForResume_shouldBeAtomic_doubleClaimOnlyOnceWins() {
        InMemoryStateTaskRepository repository = new InMemoryStateTaskRepository();
        StateTask failed = StateTask.create("task-d", "biz-d", "MACHINE", "CHECKING", "payload");
        failed.markFailed("CHECKING", "waiting retry");
        repository.save(failed);

        assertTrue(repository.claimForResume(failed));
        // 第二个触发源再抢：状态已 RUNNING，必然失败——双执行被挡下
        assertFalse(repository.claimForResume(failed));
    }

    @Test
    void findByMachineCodeAndStatus_shouldFilterByMachineAndRuntimeStatus() {
        InMemoryStateTaskRepository repository = new InMemoryStateTaskRepository();

        StateTask failed = StateTask.create("task-f", "biz-f", "FLASH_PAY", "PAYMENT", "payload");
        failed.markFailed("PAYMENT", "gateway timeout");
        repository.save(failed);
        StateTask manual = StateTask.create("task-m", "biz-m", "FLASH_PAY", "PAYMENT", "payload");
        manual.markManualReview("need human");
        repository.save(manual);
        StateTask otherMachine = StateTask.create("task-o", "biz-o", "OTHER_MACHINE", "CHECKING", "payload");
        otherMachine.markFailed("CHECKING", "other machine failure");
        repository.save(otherMachine);

        List<StateTask> failedFlashPay = repository.findByMachineCodeAndStatus("FLASH_PAY", StateTaskStatus.FAILED);

        // 只命中 FLASH_PAY + FAILED：MANUAL_REVIEW 与其他机器码的任务都不进重试扫描范围
        assertEquals(1, failedFlashPay.size());
        assertEquals("task-f", failedFlashPay.get(0).getTaskId());
        assertEquals(0, repository.findByMachineCodeAndStatus("FLASH_PAY", StateTaskStatus.COMPLETED).size());
        assertEquals(1, repository.findByMachineCodeAndStatus("OTHER_MACHINE", StateTaskStatus.FAILED).size());
    }
}
