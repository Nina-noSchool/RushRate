package com.hmdp.shared.domain.compensation.node;

import com.hmdp.shared.domain.compensation.amount.PayoutAmountCalculator;
import com.hmdp.shared.domain.compensation.model.FlashPayPayload;
import com.hmdp.shared.domain.compensation.model.FlashPayStateCode;
import com.hmdp.shared.domain.compensation.spi.PaymentResult;
import com.hmdp.shared.domain.compensation.spi.PayoutGateway;
import com.hmdp.shared.stateengine.model.NodeResult;
import com.hmdp.shared.stateengine.model.StateTask;
import com.hmdp.shared.stateengine.service.StateContext;
import com.hmdp.shared.stateengine.service.StateNode;

/**
 * 闪赔审批通过节点 — 计算赔付金额并调用支付网关。
 *
 * <p>流程：反序列化 payload 取 planCode → 算金额 → 调 gateway.pay(taskId, amount)。
 * 无规则、结果为零返回 failure(retryable=false)——赔付范围语义问题，重试无意义。</p>
 *
 * <p><b>失败分层（2026-11-18，P6）</b>：支付侧失败按瞬时性分层——</p>
 * <ul>
 *   <li>网关返回 GATEWAY_TIMEOUT（瞬时故障）→ failure(retryable=true)：
 *       配合 FlashPayFailurePolicy 的 retryEnabled 开关进入 FAILED，
 *       由重试调度 Job 按退避 resume（网关按 claimNo 幂等，重试不会重复扣款）；</li>
 *   <li>网关调用抛异常（网络抖动）→ failure(retryable=true)：同上；</li>
 *   <li>网关返回其他业务失败（如 RISK_REJECTED 风控拒绝）→ failure(retryable=false)：
 *       业务语义需要人工判断赔付渠道。</li>
 * </ul>
 *
 * <p>所有依赖均通过构造器注入，保证节点本身无静态可变状态。</p>
 */
public class FlashPayApprovedNode implements StateNode {

    /** 网关侧约定的瞬时故障错误码：timeout 类，值得重试 */
    private static final String TRANSIENT_ERROR_CODE = "GATEWAY_TIMEOUT";

    private final PayoutAmountCalculator calculator;
    private final PayoutGateway gateway;

    public FlashPayApprovedNode(PayoutAmountCalculator calculator, PayoutGateway gateway) {
        this.calculator = calculator;
        this.gateway = gateway;
    }

    @Override
    public String getStateCode() {
        return FlashPayStateCode.APPROVED.name();
    }

    /**
     * APPROVED 节点核心业务：算金额 → 调支付 → 决定是否流转到 COMPLETED。
     *
     * <p>失败分层见类注释：瞬时故障 retryable=true（FAILED → 重试调度 resume），
     * 业务拒绝 retryable=false（转人工）。幂等键使用 task.getTaskId()（即 claimNo），
     * 由支付网关内部保证同一索赔单不会重复扣款。</p>
     */
    @Override
    public NodeResult execute(StateTask task, StateContext context) {
        // payload 仍是引擎不感知的纯字符串，节点自己反序列化取 planCode
        FlashPayPayload payload = FlashPayPayload.deserialize(task.getPayload());

        // 金额计算器内部会按 planCode 查规则、再受 maxAmount 上限约束
        long amount = calculator.calculate(payload.getPlanCode());
        if (amount <= 0) {
            // 无规则或结果为零意味着这个 planCode 不在赔付范围内，不是可重试故障
            return NodeResult.failure("NO_PAYOUT_RULE", "未配置赔付金额规则", false);
        }

        PaymentResult payResult;
        try {
            // 幂等：同一 claimNo 多次调用只会产生一条真实支付记录
            payResult = gateway.pay(task.getTaskId(), amount);
        } catch (Exception e) {
            // 网关抛异常（超时/网络抖动）——瞬时故障，值得重试（P6 分层修正）
            return NodeResult.failure("PAYMENT_ERROR", "支付调用异常: " + e.getMessage(), true);
        }

        if (!payResult.isSuccess()) {
            if (TRANSIENT_ERROR_CODE.equals(payResult.getErrorCode())) {
                // 网关超时：瞬时故障，retryable=true——FAILED 后由重试调度按退避 resume，
                // 网关幂等键保证重试不会重复扣款
                return NodeResult.failure("PAYMENT_TIMEOUT",
                        "支付失败(" + payResult.getErrorCode() + "): " + payResult.getMessage(), true);
            }
            // 其他业务失败（风控拒绝、余额不足等）——需人工判断是否改走其他赔付渠道
            return NodeResult.failure("PAYMENT_FAILED",
                    "支付失败(" + payResult.getErrorCode() + "): " + payResult.getMessage(), false);
        }

        // 支付成功，携带 txId 跳转到 COMPLETED 节点，由后续节点写审计和发通知
        return NodeResult.next(FlashPayStateCode.COMPLETED.name(),
                "已支付 amount=" + amount + "分 txId=" + payResult.getTxId());
    }
}