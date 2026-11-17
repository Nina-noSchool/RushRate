package com.hmdp.compensation;

import com.hmdp.service.impl.RocketMqReliablePublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MqPayoutNotificationAdapterTest {

    private final RocketMqReliablePublisher publisher = mock(RocketMqReliablePublisher.class);
    private final MqPayoutNotificationAdapter adapter = new MqPayoutNotificationAdapter(publisher);

    @Test
    void notifyUser_success_forwardsSelfContainedPayload() {
        when(publisher.publish(anyString(), anyString(), anyString())).thenReturn(true);

        adapter.notifyUser("claim-1", 7L, "赔付已完成");

        verify(publisher).publish(
                eq(MqPayoutNotificationAdapter.EVENT_CLAIM_PAYOUT_NOTIFY),
                eq(MqPayoutNotificationAdapter.DESTINATION_CLAIM_PAYOUT_NOTIFY),
                anyString());
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(publisher).publish(anyString(), anyString(), payloadCaptor.capture());
        String payload = payloadCaptor.getValue();
        assertTrue(payload.contains("\"claimNo\":\"claim-1\""));
        assertTrue(payload.contains("\"userId\":7"));
        assertTrue(payload.contains("赔付已完成"));
    }

    @Test
    void notifyUser_nullUserId_payloadCarriesNullForConsumerToResolve() {
        when(publisher.publish(anyString(), anyString(), anyString())).thenReturn(true);

        adapter.notifyUser("claim-2", null, "消息");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(publisher).publish(anyString(), anyString(), payloadCaptor.capture());
        assertTrue(payloadCaptor.getValue().contains("\"userId\":null"));
        assertTrue(payloadCaptor.getValue().contains("\"claimNo\":\"claim-2\""));
    }

    @Test
    void notifyUser_publishRejected_doesNotThrow() {
        when(publisher.publish(anyString(), anyString(), anyString())).thenReturn(false);

        assertDoesNotThrow(() -> adapter.notifyUser("claim-3", 8L, "msg"));
        verify(publisher).publish(anyString(), anyString(), anyString());
    }

    @Test
    void notifyUser_publishThrows_doesNotThrow_reliabilityComesFromLocalMessageTable() {
        when(publisher.publish(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("mq down"));

        // 发布器内部已把失败落本地消息表（30s 重试、耗尽转 FAILED）——适配器绝不让通知异常外泄
        assertDoesNotThrow(() -> adapter.notifyUser("claim-4", 9L, "msg"));
        verify(publisher).publish(anyString(), anyString(), anyString());
    }
}
