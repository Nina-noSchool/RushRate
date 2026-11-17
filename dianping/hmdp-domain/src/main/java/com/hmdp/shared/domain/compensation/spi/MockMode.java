package com.hmdp.shared.domain.compensation.spi;

/**
 * 支付模拟模式 — 供 InMemoryPayoutGateway 测试/联调时切换行为。
 */
public enum MockMode {
    /** 模拟支付成功 */
    SUCCESS,
    /** 模拟网关故障（返回失败，errorCode=GATEWAY_TIMEOUT，节点映射为 retryable=true） */
    FAIL,
    /** 模拟业务拒绝（如风控拦截，retryable=false） */
    REJECT,
    /**
     * 模拟"首次超时、重试成功"（2026-11-18，P6）：同一 claimNo 的第一次 pay
     * 返回 GATEWAY_TIMEOUT 失败（retryable=true，触发 FAILED + 重试调度 resume），
     * 之后的所有调用成功——正是 retryable 分层 + 重试调度的端到端验证场景。
     */
    FAIL_THEN_SUCCESS
}
