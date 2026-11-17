package com.hmdp.shared.stateengine.service;

/**
 * 状态机监听器 SPI —— 引擎任务到达终局时的可观测性扩展点（默认空实现，按需覆写）。
 *
 * <p>定位与任务链侧的 {@code TaskChainListener} 一致：核心引擎保持零观测依赖，
 * 监听器实现（Micrometer 计数/计时、慢任务告警）注册为 Spring Bean 后由
 * SharedEngineAutoConfiguration 通过 ObjectProvider 收集注入。</p>
 *
 * <p>监听器抛出的异常会被引擎吞掉并降级为 WARN 日志——可观测性组件故障
 * 绝不能影响业务执行。</p>
 */
public interface StateEngineListener {

    /**
     * 任务到达终局时回调——一次 start/resume 驱动恰好触发一次：
     * runtimeStatus 为 COMPLETED（成功到达终态）、FAILED（可重试失败挂起，等待外部 resume）
     * 或 MANUAL_REVIEW（转人工审核）。
     *
     * @param machineCode   状态机编码（如 flash_pay）
     * @param taskId        任务标识
     * @param runtimeStatus 终局状态（StateTaskStatus.name()）
     * @param costMs        本次驱动的总耗时（毫秒，从进入 run 主循环到终局）
     * @param success       是否成功到达终态（仅 COMPLETED 为 true）
     * @param error         终局前的最后一条错误描述（成功时可能为 null）
     */
    default void onTaskFinished(String machineCode, String taskId, String runtimeStatus,
                                long costMs, boolean success, String error) {
    }
}
