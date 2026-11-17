package com.hmdp.compensation;

import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONUtil;
import com.hmdp.service.impl.RocketMqReliablePublisher;
import com.hmdp.shared.domain.compensation.spi.PayoutNotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 闪赔通知的 MQ 真实通道（2026-11-18，P7）。
 *
 * <p>此前通知是日志通道（ClaimPayoutNotificationService 落结构化日志）——
 * 本适配器把赔付结果通知升级为 RocketMQ 可靠发布：发布失败由
 * RocketMqReliablePublisher 内部落本地消息表（30 秒重试、耗尽转 FAILED），
 * 通知从"日志里可查"升级为"消息流可追溯、失败可补投"。</p>
 *
 * <p>消费侧：通知事件落在既有 topic 的新 tag 下（seckill-plus-order-topic:claim-payout-notify），
 * 后续接入站内信/短信消费组即可消费，无需改动本适配器。</p>
 *
 * <p>可靠性纪律：通知是旁路——本方法任何异常都不外泄（状态机主流程不受影响）；
 * 发布失败的双重保障是"本地消息表留底 + 日志告警"。</p>
 */
@Component
@Primary
@Slf4j
public class MqPayoutNotificationAdapter implements PayoutNotificationService {

    /** 通知事件类型（本地消息表的事件类型字段） */
    public static final String EVENT_CLAIM_PAYOUT_NOTIFY = "CLAIM_PAYOUT_NOTIFY";
    /** 通知目的地：既有 topic 的新 tag（无需新建 topic，复用现有消费组基础设施） */
    public static final String DESTINATION_CLAIM_PAYOUT_NOTIFY = "seckill-plus-order-topic:claim-payout-notify";

    private final RocketMqReliablePublisher publisher;

    public MqPayoutNotificationAdapter(RocketMqReliablePublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void notifyUser(String claimNo, Long userId, String message) {
        try {
            // payload 自包含：userId 允许缺失（消费者按 claimNo 反查申诉单补齐），
            // JSON 由 hutool 序列化，message 含引号等特殊字符也安全
            Map<String, Object> payload = new HashMap<>();
            payload.put("claimNo", claimNo);
            payload.put("userId", userId);
            payload.put("message", message);
            // ignoreNullValue=false：userId 为 null 时显式序列化成 "userId":null，
            // 让 payload 契约自解释（消费端按 claimNo 反查申诉单补齐）
            String json = JSONUtil.toJsonStr(payload, JSONConfig.create().setIgnoreNullValue(false));
            boolean accepted = publisher.publish(
                    EVENT_CLAIM_PAYOUT_NOTIFY,
                    DESTINATION_CLAIM_PAYOUT_NOTIFY,
                    json);
            if (!accepted) {
                log.warn("[PayoutNotify][MQ] publish rejected, claimNo={}", claimNo);
            }
        } catch (Exception e) {
            // 发布器内部已把失败落本地消息表（可靠兜底）；这里只告警
            log.warn("[PayoutNotify][MQ] publish failed, claimNo={}", claimNo, e);
        }
    }
}
