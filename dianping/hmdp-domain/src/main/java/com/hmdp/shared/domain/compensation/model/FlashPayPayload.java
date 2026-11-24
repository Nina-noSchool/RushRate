package com.hmdp.shared.domain.compensation.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 无忧闪赔 Payload — 闪赔状态机任务的业务参数载体。
 *
 * <p>定位：无忧闪赔状态机层。封装闪赔申请所需的业务上下文，
 * 序列化为字符串存储到 StateTask.payload 中，执行时反序列化还原。</p>
 *
 * <p>序列化格式（用 | 分隔）：
 * <pre>planCode|reason|voucherId</pre></p>
 *
 * <p>voucherId 是 2026-11-24「按券值赔付」改造新增的第三段：计算器据此查券实付金额与核销态。
 * 老的两段格式数据已在初始化脚本里清理，反序列化不再做兼容分支。</p>
 */
@Getter
@AllArgsConstructor
public class FlashPayPayload {

    /** 闪赔计划编码 */
    private final String planCode;
    /** 申诉原因（用户填写，SUBMITTED节点校验非空） */
    private final String reason;
    /** 关联券 ID（赔付基数来源：tb_voucher.pay_value） */
    private final Long voucherId;

    /** 序列化为字符串，存入 StateTask.payload */
    public String serialize() {
        return planCode + "|" + (reason == null ? "" : reason) + "|" + (voucherId == null ? "" : voucherId);
    }

    /** 从 StateTask.payload 反序列化还原 */
    public static FlashPayPayload deserialize(String payload) {
        // limit=3：保证 reason 字段里即便包含 | 也不会被多切（只切成 planCode/reason/voucherId 三段）
        String[] parts = payload.split("\\|", 3);
        String planCode = parts[0];
        String reason = parts.length > 1 ? parts[1] : "";
        String voucherPart = parts.length > 2 ? parts[2] : "";
        Long voucherId = null;
        if (!voucherPart.isEmpty()) {
            try {
                voucherId = Long.valueOf(voucherPart);
            } catch (NumberFormatException ignored) {
                // 非法 voucherId 视作缺失，APPROVED 节点会以 NO_VOUCHER 转人工
            }
        }
        return new FlashPayPayload(planCode, reason, voucherId);
    }
}