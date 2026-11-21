package com.hmdp.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 令牌桶限流注解（2026-11-21 批次）—— 声明式限流配置，业务方法零侵入。
 *
 * <p>与既有 {@code SlidingWindowInterceptor}（滑动窗口，按 userId 防刷）互补：
 * 本注解面向"任意接口可挂"的通用限流，key 由 SpEL 声明，支持 {@code #ip}、
 * {@code #userId}（切面注入的内置变量）与方法参数名（如 {@code #voucherId}）。</p>
 *
 * <p>算法语义：恒速补充令牌 + 桶容量允许突发（对比滑动窗口的"窗口内严格 ≤ 上限"）。
 * Lua 原子执行（rate_limit_token_bucket.lua），多实例共享同一全局桶。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RateLimit {

    /** 限流 key 的 SpEL 表达式，如 {@code "'ip:' + #ip"}、{@code "'voucher:' + #voucherId"} */
    String key();

    /** 桶容量 —— 允许的最大突发请求数 */
    int capacity();

    /** 每秒恒速补充令牌数（长期平均速率上限） */
    int ratePerSec();

    /** 桶 key 空闲过期时间（毫秒），防冷 key 常驻 */
    int expireMs() default 120000;

    /** 拒绝时的提示文案 */
    String message() default "请求过于频繁，请稍后重试";
}
