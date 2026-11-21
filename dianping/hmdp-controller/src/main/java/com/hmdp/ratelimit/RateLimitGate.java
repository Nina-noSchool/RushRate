package com.hmdp.ratelimit;

import com.hmdp.metrics.MetricsRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;

/**
 * 令牌桶限流闸门（2026-11-21 批次）—— Lua 原子取令牌 + 拒绝指标。
 *
 * <p>与布隆过滤器同哲学：fail-open——Redis 执行异常时不阻断业务主链路，
 * 放行并记日志（限流是保护语义，自身故障不应放大为业务故障）。</p>
 */
@Slf4j
public class RateLimitGate {

    private static final DefaultRedisScript<Long> TOKEN_BUCKET_SCRIPT;

    static {
        TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>();
        TOKEN_BUCKET_SCRIPT.setLocation(new ClassPathResource("lua/rate_limit_token_bucket.lua"));
        TOKEN_BUCKET_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;
    private final MetricsRecorder metricsRecorder;

    public RateLimitGate(StringRedisTemplate stringRedisTemplate, MetricsRecorder metricsRecorder) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.metricsRecorder = metricsRecorder;
    }

    /**
     * @return true = 放行（拿到令牌或 fail-open），false = 拒绝（令牌不足）
     */
    public boolean tryAcquire(String key, RateLimit rateLimit) {
        try {
            Long allowed = stringRedisTemplate.execute(
                    TOKEN_BUCKET_SCRIPT,
                    Collections.singletonList("rate:token:limit:" + key),
                    String.valueOf(rateLimit.capacity()),
                    String.valueOf(rateLimit.ratePerSec()),
                    String.valueOf(rateLimit.expireMs()));
            if (allowed != null && allowed == 1L) {
                return true;
            }
            metricsRecorder.incRatelimitRejected();
            log.info("token bucket rate limit rejected, key={}, capacity={}, ratePerSec={}",
                    key, rateLimit.capacity(), rateLimit.ratePerSec());
            return false;
        } catch (Exception e) {
            log.warn("rate limit gate failed-open, key={}", key, e);
            return true;
        }
    }
}
