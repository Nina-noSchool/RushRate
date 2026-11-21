package com.hmdp.ratelimit;

import com.hmdp.metrics.MetricsRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitGateTest {

    private final StringRedisTemplate template = mock(StringRedisTemplate.class);
    private final MetricsRecorder metrics = mock(MetricsRecorder.class);
    private final RateLimitGate gate = new RateLimitGate(template, metrics);

    private final RateLimit rateLimit = sample();

    private RateLimit sample() {
        return new RateLimit() {
            @Override public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return RateLimit.class;
            }
            @Override public String key() { return "'voucher:' + #voucherId"; }
            @Override public int capacity() { return 50; }
            @Override public int ratePerSec() { return 20; }
            @Override public int expireMs() { return 120000; }
            @Override public String message() { return "抢购人数过多"; }
        };
    }

    @Test
    void shouldProceedWhenTokenAcquired() {
        when(template.execute(any(RedisScript.class), any(List.class), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        assertTrue(gate.tryAcquire("voucher:19", rateLimit));
        verify(metrics, never()).incRatelimitRejected();
    }

    @Test
    void shouldRejectAndCountWhenTokensExhausted() {
        when(template.execute(any(RedisScript.class), any(List.class), anyString(), anyString(), anyString()))
                .thenReturn(0L);

        assertFalse(gate.tryAcquire("voucher:19", rateLimit));
        verify(metrics).incRatelimitRejected();
    }

    @Test
    void shouldFailOpenWhenRedisThrows() {
        when(template.execute(any(RedisScript.class), any(List.class), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("redis down"));

        // 限流是保护语义：自身故障不放大为业务故障 → fail-open 放行
        assertTrue(gate.tryAcquire("voucher:19", rateLimit));
        verify(metrics, never()).incRatelimitRejected();
    }

    @Test
    void shouldBuildKeyWithPrefix() {
        java.util.concurrent.atomic.AtomicReference<List<String>> captured = new java.util.concurrent.atomic.AtomicReference<>();
        when(template.execute(any(RedisScript.class), any(List.class), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    captured.set(inv.getArgument(1));
                    return 1L;
                });

        gate.tryAcquire("voucher:19", rateLimit);

        assertTrue(captured.get().contains("rate:token:limit:voucher:19"),
                "key 应统一加 rate:token:limit: 前缀，实际=" + captured.get());
        verify(metrics, never()).incRatelimitRejected();
    }
}
