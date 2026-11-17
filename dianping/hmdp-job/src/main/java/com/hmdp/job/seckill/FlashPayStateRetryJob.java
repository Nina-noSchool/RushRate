package com.hmdp.job.seckill;

import com.hmdp.shared.domain.compensation.model.FlashPayStateCode;
import com.hmdp.shared.stateengine.model.StateTask;
import com.hmdp.shared.stateengine.model.StateTaskStatus;
import com.hmdp.shared.stateengine.service.StateEngine;
import com.hmdp.shared.stateengine.spi.StateTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 闪赔 FAILED 任务重试调度 Job（2026-11-18，P4 retryable 分层的调度腿）。
 *
 * <p>机制：扫描 FLASH_PAY 状态机中 runtimeStatus=FAILED 的任务（可重试失败、
 * 重试预算未耗尽才会落在这个状态），按指数退避节奏驱动
 * {@code StateEngine.resume}——resume 内部的 claimForResume CAS
 * （FAILED→RUNNING）保证多实例/多触发源下节点绝不会被双重执行；
 * 重试耗尽后引擎落 MANUAL_REVIEW，从此退出本 Job 的扫描范围（转人工）。</p>
 *
 * <p>退避：第 n 次重试需距上次更新 {@code delay * 2^n} 秒（封顶 64 倍，防重试风暴）。
 * 开关 {@code dewu.compensation.retry.flash-pay-retry-enabled} 默认关——
 * 开启前置是支付幂等能力验证通过；关闭时本 Job 直接返回，行为与旧版一致。</p>
 */
@Slf4j
@Component
public class FlashPayStateRetryJob {

    @Resource
    private StateTaskRepository stateTaskRepository;

    @Resource
    private StateEngine stateEngine;

    /** 闪赔可重试失败自动 resume 开关（默认关） */
    @Value("${dewu.compensation.retry.flash-pay-retry-enabled:false}")
    private boolean retryEnabled;

    /** FAILED 任务的基础退避间隔（秒） */
    @Value("${dewu.compensation.retry.flash-pay-retry-delay-seconds:30}")
    private int retryDelaySeconds;

    @Scheduled(fixedDelay = 30000)
    public void retryFailedFlashPayTasks() {
        if (!retryEnabled || stateEngine == null) {
            return;
        }
        try {
            List<StateTask> failedTasks = stateTaskRepository.findByMachineCodeAndStatus(
                    FlashPayStateCode.MACHINE_CODE, StateTaskStatus.FAILED);
            LocalDateTime now = LocalDateTime.now();
            for (StateTask task : failedTasks) {
                // 指数退避：第 n 次重试需距上次更新 delay * 2^n 秒（封顶 64 倍）
                long backoffSeconds = retryDelaySeconds * (1L << Math.min(task.getRetryCount(), 6));
                if (task.getUpdatedAt().isAfter(now.minusSeconds(backoffSeconds))) {
                    continue;
                }
                log.info("flashpay retry job resuming FAILED task, taskId={}, retryCount={}, currentState={}",
                        task.getTaskId(), task.getRetryCount(), task.getCurrentState());
                stateEngine.resume(task);
            }
        } catch (Exception e) {
            // Job 整体异常：打 ERROR 等下一轮，绝不让重试调度拖垮应用
            log.error("flashpay state retry job failed", e);
        }
    }
}
