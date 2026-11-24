package com.hmdp.shared.domain.compensation.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FlashPayPayload 序列化/反序列化「解析矩阵」测试。
 *
 * <p>背景：2026-11-24 连续两轮审查各抓出 1 个解析 bug——
 * <ol>
 *   <li>reason（用户填写、内容不可控）含 | 时，从头 split 会把 voucherId 挤掉；</li>
 *   <li>改为末段切分后，老两段格式里纯数字 reason 又被误当成 voucherId。</li>
 * </ol>
 * 这类问题靠"测几个典型场景"抓不全，必须用矩阵把 planCode/reason/voucherId 的组合一次性锁死。
 *
 * <p><b>格式约束</b>：planCode 是第一段、按第一个 | 切分，天然不能含 |；且它是匹配状态机的
 * 内部编码（如 FLASH_PAY），非法值状态机不会启动，无需为它做转义。真正用户可控、必须防御的是
 * reason——往返矩阵对它做了含 |、纯数字、首尾 | 等全覆盖。
 *
 * <p>两组测试：
 * <ul>
 *   <li>{@link #roundTripMatrix()}：serialize→deserialize 往返，保证自己写的数据自己能读回；</li>
 *   <li>{@link #parseMatrix()}：直接喂 payload 字符串（含历史/脏数据），保证解析器行为精确。</li>
 * </ul>
 */
class FlashPayPayloadTest {

    /**
     * 往返矩阵：任一合法字段组合，序列化后再反序列化必须还原成同样的三个字段。
     * 注意 reason 为 null 时 serialize 写成空串，故期望值也用空串。
     */
    static Stream<Arguments> roundTripMatrix() {
        return Stream.of(
                // planCode,         reason(写入值),         voucherId, 期望reason,       期望voucherId
                Arguments.of("FLASH_PAY", "商家提前打烊", 13L, "商家提前打烊", 13L),
                Arguments.of("FLASH_PAY", "", 13L, "", 13L),                       // reason 为空
                Arguments.of("FLASH_PAY", null, 13L, "", 13L),                     // reason null→空串
                Arguments.of("FLASH_PAY", "原因", null, "原因", null),             // voucherId 缺失
                Arguments.of("FLASH_PAY", "a|b", 13L, "a|b", 13L),                 // reason 单个 |
                Arguments.of("FLASH_PAY", "a|b|c|d", 99L, "a|b|c|d", 99L),         // reason 多个 |
                Arguments.of("FLASH_PAY", "|开头和|结尾|", 7L, "|开头和|结尾|", 7L),// reason 首尾都是 |
                Arguments.of("FLASH_PAY", "123", 13L, "123", 13L),                 // reason 纯数字但有三段
                Arguments.of("FLASH_PAY", "数字在末段123", 0L, "数字在末段123", 0L) // voucherId=0 边界
        );
    }

    @ParameterizedTest(name = "往返[{index}] planCode={0} reason={1} voucherId={2}")
    @MethodSource("roundTripMatrix")
    @DisplayName("serialize→deserialize 往返：字段无损还原")
    void should_round_trip_all_field_combinations(String planCode, String reasonIn, Long voucherId,
                                                   String reasonExpected, Long voucherExpected) {
        FlashPayPayload restored = FlashPayPayload.deserialize(
                new FlashPayPayload(planCode, reasonIn, voucherId).serialize());

        assertEquals(planCode, restored.getPlanCode());
        assertEquals(reasonExpected, restored.getReason());
        assertEquals(voucherExpected, restored.getVoucherId());
    }

    /**
     * 解析矩阵：直接喂 payload 字符串，断言精确解析结果。
     * 覆盖 null/空/无分隔符/两段/三段/历史格式/脏数据。
     */
    static Stream<Arguments> parseMatrix() {
        return Stream.of(
                // payload,                          期望planCode, 期望reason,       期望voucherId, 说明
                Arguments.of(null,                  "",           "",              null,        "null payload 不 NPE"),
                Arguments.of("",                    "",           "",              null,        "空串"),
                Arguments.of("FLASH_PAY",           "FLASH_PAY",  "",              null,        "无分隔符=只有planCode"),
                // --- 老两段格式（planCode|reason）---
                Arguments.of("FLASH_PAY|商家打烊",   "FLASH_PAY",  "商家打烊",       null,        "老格式文本reason"),
                Arguments.of("FLASH_PAY|123",        "FLASH_PAY",  "123",           null,        "老格式纯数字reason不误判为券ID"),
                Arguments.of("FLASH_PAY|not-a-num",  "FLASH_PAY",  "not-a-num",     null,        "老格式非数字reason"),
                // --- 新三段格式（planCode|reason|voucherId）---
                Arguments.of("FLASH_PAY|原因|13",    "FLASH_PAY",  "原因",          13L,         "标准三段"),
                Arguments.of("FLASH_PAY||13",        "FLASH_PAY",  "",              13L,         "reason为空仍正确取券ID"),
                Arguments.of("FLASH_PAY|原因|",      "FLASH_PAY",  "原因",          null,        "末段空=voucherId缺失"),
                Arguments.of("FLASH_PAY|a|b|13",     "FLASH_PAY",  "a|b",           13L,         "reason含|只吃最后一段"),
                Arguments.of("FLASH_PAY|123|13",     "FLASH_PAY",  "123",           13L,         "reason纯数字+三段"),
                // --- 脏数据 ---
                Arguments.of("FLASH_PAY|原因|abc",   "FLASH_PAY",  "原因|abc",      null,        "末段非数字→退回两段,券ID缺失"),
                Arguments.of("FLASH_PAY|原因|-5",    "FLASH_PAY",  "原因",          -5L,         "负数也是合法long,查库无果后转人工"),
                Arguments.of("P|LAN|原因|5",         "P",          "LAN|原因",      5L,          "planCode含|按首段切(非法planCode,状态机不会启动)")
        );
    }

    @ParameterizedTest(name = "解析[{index}] payload={0}")
    @MethodSource("parseMatrix")
    @DisplayName("deserialize 解析矩阵：历史/脏数据精确解析")
    void should_parse_all_payload_shapes(String payload, String planExpected, String reasonExpected,
                                         Long voucherExpected) {
        FlashPayPayload restored = FlashPayPayload.deserialize(payload);

        assertNotNull(restored);
        assertEquals(planExpected, restored.getPlanCode());
        assertEquals(reasonExpected, restored.getReason());
        assertEquals(voucherExpected, restored.getVoucherId());
    }
}
