package com.hmdp.shared.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 补偿流重试配置项
 * <p>
 * 对应配置前缀 dewu.compensation.retry，目前只有一个参数：
 * maxRetryCount（最大重试次数，默认 3）。
 * </p>
 */
@Data
@ConfigurationProperties(prefix = "dewu.compensation.retry")
public class CompensationRetryProperties {

    private int maxRetryCount = 3;

    /**
     * 闪赔可重试失败是否走自动重试（2026-11-18，P4；默认关）。
     * <p>开启前置：支付幂等能力验证通过（网关按 claimNo 幂等）。关闭时
     * FlashPayFailurePolicy 全部转人工，与旧语义一致。</p>
     */
    private boolean flashPayRetryEnabled = false;

    /** 闪赔 FAILED 任务重试的基础退避间隔（秒），按重试次数指数递增（封顶 64 倍） */
    private int flashPayRetryDelaySeconds = 30;
}
