package com.hmdp.ratelimit;

import com.hmdp.dto.Result;
import com.hmdp.metrics.MetricsRecorder;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

/**
 * 令牌桶限流切面（2026-11-21 批次）—— {@link RateLimit} 注解的执行器。
 *
 * <p>职责只做三件事：解析 SpEL key → 调 {@link RateLimitGate} 取令牌 →
 * 拒绝时返回统一失败结果（不再透传进业务方法）。总开关
 * {@code seckill.rate-limit.token-bucket.enabled} 可全局关闭供压测对照。</p>
 */
@Slf4j
@Aspect
@Component
public class RateLimitAspect {

    private final RateLimitGate gate;
    private final RateLimitKeyResolver keyResolver = new RateLimitKeyResolver();
    private final boolean enabled;

    public RateLimitAspect(StringRedisTemplate stringRedisTemplate,
                           MetricsRecorder metricsRecorder,
                           @Value("${seckill.rate-limit.token-bucket.enabled:true}") boolean enabled) {
        this.gate = new RateLimitGate(stringRedisTemplate, metricsRecorder);
        this.enabled = enabled;
    }

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        if (!enabled) {
            return pjp.proceed();
        }
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        String key = keyResolver.resolve(rateLimit.key(), method, pjp.getArgs(), currentClientIp());
        if (!StringUtils.hasText(key)) {
            // key 解析失败 fail-open：限流配置错误不阻断业务
            return pjp.proceed();
        }
        if (!gate.tryAcquire(key, rateLimit)) {
            return Result.fail(rateLimit.message());
        }
        return pjp.proceed();
    }

    private String currentClientIp() {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) {
                return "unknown";
            }
            HttpServletRequest request = attributes.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            if (StringUtils.hasText(forwarded)) {
                return forwarded.split(",")[0].trim();
            }
            return request.getRemoteAddr();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
