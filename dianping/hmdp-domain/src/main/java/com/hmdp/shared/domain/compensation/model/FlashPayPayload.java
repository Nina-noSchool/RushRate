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
        // 不能从头 split("\\|", 3)：reason 是用户填写的、内容不可控，一旦含 | 就会把
        // voucherId 挤到中间段、导致第三段丢失 → 全额退被误判成 NO_VOUCHER 转人工。
        // 正确做法：voucherId 固定在末尾且是纯数字，从末尾用 lastIndexOf 切最后一段；
        // 前面整段再按第一个 | 切成 planCode / reason，reason 里任意 | 都原样保留。
        String planCode;
        String reason;
        Long voucherId = null;

        int lastPipe = payload.lastIndexOf('|');
        if (lastPipe < 0) {
            // 兼容老格式/异常数据：没有任何分隔符，整串当 planCode
            return new FlashPayPayload(payload, "", null);
        }

        String head = payload.substring(0, lastPipe);          // planCode|reason（reason 可含 |）
        String voucherPart = payload.substring(lastPipe + 1);  // 末段：voucherId
        if (!voucherPart.isEmpty()) {
            try {
                voucherId = Long.valueOf(voucherPart);
            } catch (NumberFormatException ignored) {
                // 末段不是数字：说明这是老的两段格式 planCode|reason（末段是原因文本），
                // 退回按第一段 | 切分，voucherId 缺失 → 走 NO_VOUCHER 转人工
                int firstPipe = payload.indexOf('|');
                planCode = payload.substring(0, firstPipe);
                reason = payload.substring(firstPipe + 1);
                return new FlashPayPayload(planCode, reason, null);
            }
        }

        int firstPipe = head.indexOf('|');
        if (firstPipe < 0) {
            // 只有一段分隔符：planCode|voucherId 缺 reason（异常数据）
            planCode = head;
            reason = "";
        } else {
            planCode = head.substring(0, firstPipe);
            reason = head.substring(firstPipe + 1);
        }
        return new FlashPayPayload(planCode, reason, voucherId);
    }
}