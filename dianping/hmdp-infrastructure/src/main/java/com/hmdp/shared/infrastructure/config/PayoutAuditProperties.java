package com.hmdp.shared.infrastructure.config;

import com.hmdp.shared.domain.compensation.spi.MockMode;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 无忧闪赔赔付配置项
 *
 * <p>对应前缀 flash-pay。</p>
 */
@Data
@ConfigurationProperties(prefix = "flash-pay")
public class PayoutAuditProperties {

    /**
     * 单笔赔付上限，单位分 — 异常大额兜底，超限转人工复核而非截断。
     * 2026-11-24：从 500（5元）调到 100000（1000元），足以覆盖在售最高券值（158元）。
     */
    private long maxPayout = 100000;

    /** 支付模拟模式 */
    private PaymentMock payment = new PaymentMock();

    @Data
    public static class PaymentMock {
        /** 模拟模式：SUCCESS / FAIL / REJECT */
        private MockMode mockMode = MockMode.SUCCESS;
    }
}