package com.hmdp.shared.domain.compensation.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FlashPayPayload 序列化往返测试。
 *
 * <p>覆盖三段格式 planCode|reason|voucherId 的边界，重点是 reason（用户填写、内容不可控）
 * 里出现分隔符 | 时 voucherId 不能丢——否则全额退会被误判成 NO_VOUCHER 转人工。</p>
 */
class FlashPayPayloadTest {

    @Test
    void should_round_trip_normal_three_parts() {
        FlashPayPayload payload = new FlashPayPayload("FLASH_PAY", "商家提前打烊，权益未兑现", 13L);
        FlashPayPayload restored = FlashPayPayload.deserialize(payload.serialize());

        assertEquals("FLASH_PAY", restored.getPlanCode());
        assertEquals("商家提前打烊，权益未兑现", restored.getReason());
        assertEquals(13L, restored.getVoucherId());
    }

    @Test
    void should_keep_voucher_id_when_reason_contains_pipe() {
        // reason 是用户填写的申诉原因，可能含竖线；分隔不能把 voucherId 吃掉
        FlashPayPayload payload = new FlashPayPayload("FLASH_PAY", "商家提前打烊|券不能用", 13L);
        FlashPayPayload restored = FlashPayPayload.deserialize(payload.serialize());

        assertEquals("FLASH_PAY", restored.getPlanCode());
        assertEquals("商家提前打烊|券不能用", restored.getReason());
        assertEquals(13L, restored.getVoucherId(), "reason 含 | 时 voucherId 不能丢");
    }

    @Test
    void should_keep_all_pipes_in_reason() {
        FlashPayPayload payload = new FlashPayPayload("FLASH_PAY", "a|b|c|d", 99L);
        FlashPayPayload restored = FlashPayPayload.deserialize(payload.serialize());

        assertEquals("a|b|c|d", restored.getReason());
        assertEquals(99L, restored.getVoucherId());
    }

    @Test
    void should_handle_null_voucher_id() {
        FlashPayPayload payload = new FlashPayPayload("FLASH_PAY", "原因", null);
        FlashPayPayload restored = FlashPayPayload.deserialize(payload.serialize());

        assertEquals("FLASH_PAY", restored.getPlanCode());
        assertEquals("原因", restored.getReason());
        assertNull(restored.getVoucherId());
    }

    @Test
    void should_treat_malformed_voucher_id_as_missing() {
        FlashPayPayload restored = FlashPayPayload.deserialize("FLASH_PAY|原因|not-a-number");
        assertNull(restored.getVoucherId());
    }

    @Test
    void should_fallback_to_legacy_two_part_format() {
        // 老格式 planCode|reason（末段是文本、不是数字）→ voucherId 缺失，reason 完整保留
        FlashPayPayload restored = FlashPayPayload.deserialize("FLASH_PAY|商家提前打烊，权益未兑现");
        assertEquals("FLASH_PAY", restored.getPlanCode());
        assertEquals("商家提前打烊，权益未兑现", restored.getReason());
        assertNull(restored.getVoucherId());
    }

    @Test
    void should_not_mistake_numeric_legacy_reason_as_voucher_id() {
        // 老格式 planCode|reason，若 reason 恰好是纯数字（如用户填"123"），
        // 不能把它误判成 voucherId——否则会拿 123 当券 ID 去查库，甚至误赔。
        FlashPayPayload restored = FlashPayPayload.deserialize("FLASH_PAY|123");
        assertEquals("FLASH_PAY", restored.getPlanCode());
        assertEquals("123", restored.getReason());
        assertNull(restored.getVoucherId());
    }

    @Test
    void should_handle_null_payload_without_npe() {
        // DB 里 payload 字段若为 NULL（脏数据/历史行），resume 时不能 NPE 卡死任务
        FlashPayPayload restored = FlashPayPayload.deserialize(null);
        assertNotNull(restored);
        assertNull(restored.getVoucherId());
    }
}
