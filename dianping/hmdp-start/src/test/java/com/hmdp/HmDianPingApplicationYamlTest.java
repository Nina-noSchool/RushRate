package com.hmdp;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HmDianPingApplicationYamlTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldDisableRabbitAutoConfiguration() {
        InputStream inputStream = HmDianPingApplicationYamlTest.class
                .getClassLoader()
                .getResourceAsStream("application.yaml");
        Map<String, Object> root = new Yaml().load(inputStream);
        Map<String, Object> spring = (Map<String, Object>) root.get("spring");
        Map<String, Object> autoconfigure = (Map<String, Object>) spring.get("autoconfigure");
        List<String> exclude = (List<String>) autoconfigure.get("exclude");

        assertTrue(exclude.contains("org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldKeepRocketMqTimeoutAndFlashPayConfig() {
        InputStream inputStream = HmDianPingApplicationYamlTest.class
                .getClassLoader()
                .getResourceAsStream("application.yaml");
        Map<String, Object> root = new Yaml().load(inputStream);

        Map<String, Object> rocketmq = (Map<String, Object>) root.get("rocketmq");
        Map<String, Object> producer = (Map<String, Object>) rocketmq.get("producer");
        assertEquals(10000, ((Number) producer.get("send-message-timeout")).intValue());

        Map<String, Object> flashPay = (Map<String, Object>) root.get("flash-pay");
        // 2026-11-24：规则表已移除，改为按券实付金额赔付；max-payout 只做异常大额兜底
        assertNull(flashPay.get("payout-rules"));
        assertEquals(100000, ((Number) flashPay.get("max-payout")).intValue());
    }
}
