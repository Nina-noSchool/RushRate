package com.hmdp.listener;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.SeckillVoucherOrderMessage;
import com.hmdp.metrics.MetricsRecorder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.VoucherOrderCreateResult;
import com.hmdp.service.impl.OrderCreateRecoveryService;
import com.hmdp.service.impl.RollbackFailureService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * USER_VOUCHER_CONFLICT 分支测试（2026-11-22 缺口修复）：
 * Redis 一人一单标记失效后多扣的那笔撞唯一索引——消费端必须回滚 Redis + 指标计数 + 不抛异常。
 * DUPLICATE（主键冲突=消息重投）的幂等忽略由既有 SeckillPlusOrderListenerTest 覆盖。
 */
class SeckillPlusOrderListenerConflictTest {

    @Test
    void userVoucherConflict_shouldRollbackAndCount() {
        IVoucherOrderService voucherOrderService = mock(IVoucherOrderService.class);
        OrderCreateRecoveryService recoveryService = mock(OrderCreateRecoveryService.class);
        RollbackFailureService rollbackFailureService = mock(RollbackFailureService.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsRecorder metrics = new MetricsRecorder(registry);
        SeckillPlusOrderListener listener = new SeckillPlusOrderListener(
                voucherOrderService, recoveryService, rollbackFailureService, metrics);
        SeckillVoucherOrderMessage message = message();
        when(voucherOrderService.createVoucherOrderPlus(message))
                .thenReturn(VoucherOrderCreateResult.USER_VOUCHER_CONFLICT);

        assertDoesNotThrow(() -> listener.onMessage(JSONUtil.toJsonStr(message)));

        // 回滚多扣的那笔（Redis 库存 + 一人一单标记 + RESTORE 日志）
        verify(voucherOrderService).rollbackSeckillVoucherPlus(any());
        assertEquals(1.0, registry.get("hmdp.user.voucher.conflict").counter().count());
    }

    @Test
    void userVoucherConflict_rollbackFailure_shouldFallToReconciliation() {
        IVoucherOrderService voucherOrderService = mock(IVoucherOrderService.class);
        OrderCreateRecoveryService recoveryService = mock(OrderCreateRecoveryService.class);
        RollbackFailureService rollbackFailureService = mock(RollbackFailureService.class);
        MetricsRecorder metrics = new MetricsRecorder(new SimpleMeterRegistry());
        SeckillPlusOrderListener listener = new SeckillPlusOrderListener(
                voucherOrderService, recoveryService, rollbackFailureService, metrics);
        SeckillVoucherOrderMessage message = message();
        when(voucherOrderService.createVoucherOrderPlus(message))
                .thenReturn(VoucherOrderCreateResult.USER_VOUCHER_CONFLICT);
        doThrow(new RuntimeException("redis down")).when(voucherOrderService)
                .rollbackSeckillVoucherPlus(any());

        // 回滚失败不抛异常：落 tb_rollback_failure_log + 告警，交对账引擎兜底
        assertDoesNotThrow(() -> listener.onMessage(JSONUtil.toJsonStr(message)));
        verify(rollbackFailureService).record(any(), any(), any(), anyInt(), any());
    }

    private SeckillVoucherOrderMessage message() {
        SeckillVoucherOrderMessage message = new SeckillVoucherOrderMessage();
        message.setOrderId(101L);
        message.setUserId(9L);
        message.setVoucherId(7L);
        message.setTraceId("trace-101");
        return message;
    }
}
