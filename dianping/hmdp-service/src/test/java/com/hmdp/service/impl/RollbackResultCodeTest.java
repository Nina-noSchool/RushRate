package com.hmdp.service.impl;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.entity.VoucherReconcileLog;
import com.hmdp.mapper.VoucherReconcileLogMapper;
import com.hmdp.shared.reconciliation.model.RollbackResultCode;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回滚四态返回码驱动测试 —— 验证"是否回补"这一事实在 Lua 返回值 / Java 分支 / DB 日志三处一致。
 * 核心断言：只有 RESTORED(1) 写 DB RESTORE；INCONSISTENT(-1) 走告警不写 RESTORE；
 * ALREADY_RESTORED(2) / NO_DEDUCT(0) 均不写 RESTORE（消灭假证据）。
 */
class RollbackResultCodeTest {

    private VoucherOrderServiceImpl serviceWith(StringRedisTemplate redis,
                                                 VoucherReconcileLogMapper logMapper,
                                                 RollbackFailureService failureService) {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "voucherReconcileLogMapper", logMapper);
        ReflectionTestUtils.setField(service, "rollbackFailureService", failureService);
        return service;
    }

    private VoucherOrder order() {
        VoucherOrder order = new VoucherOrder();
        order.setId(101L);
        order.setVoucherId(7L);
        order.setUserId(9L);
        order.setTraceId("trace-101");
        return order;
    }

    private void stubLua(StringRedisTemplate redis, long code) {
        when(redis.execute(any(), eq(Collections.emptyList()), eq("7"), eq("9"), eq("101"),
                eq("trace-101"), any(), any())).thenReturn(code);
    }

    @Test
    void enum_fromCode_mapsAllFourStates() {
        assertEquals(RollbackResultCode.RESTORED, RollbackResultCode.fromCode(1));
        assertEquals(RollbackResultCode.ALREADY_RESTORED, RollbackResultCode.fromCode(2));
        assertEquals(RollbackResultCode.NO_DEDUCT, RollbackResultCode.fromCode(0));
        assertEquals(RollbackResultCode.INCONSISTENT, RollbackResultCode.fromCode(-1));
        // 未知码保守兜底：不写 RESTORE、不误告警
        assertEquals(RollbackResultCode.NO_DEDUCT, RollbackResultCode.fromCode(99));
    }

    @Test
    void restored_shouldWriteDbRestoreLog() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        VoucherReconcileLogMapper mapper = mock(VoucherReconcileLogMapper.class);
        RollbackFailureService failure = mock(RollbackFailureService.class);
        stubLua(redis, 1L);

        serviceWith(redis, mapper, failure).rollbackSeckillVoucherPlus(order());

        verify(mapper).insert(any(VoucherReconcileLog.class));
        verify(failure, never()).record(anyString(), any(), anyString(), eq(1), any());
    }

    @Test
    void inconsistent_shouldAlertButNeverWriteRestoreLog() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        VoucherReconcileLogMapper mapper = mock(VoucherReconcileLogMapper.class);
        RollbackFailureService failure = mock(RollbackFailureService.class);
        stubLua(redis, -1L);

        serviceWith(redis, mapper, failure).rollbackSeckillVoucherPlus(order());

        // 假证据消失：SREM=0 场景不再写 RESTORE
        verify(mapper, never()).insert(any(VoucherReconcileLog.class));
        // 但必须显式告警
        verify(failure).record(eq(RollbackFailureService.SOURCE_MAIN_CHAIN), any(),
                eq("TRACE_DEDUCT_BUT_SREM_ZERO"), eq(1), any());
    }

    @Test
    void alreadyRestoredAndNoDeduct_shouldNotWriteRestoreLog() {
        for (long code : new long[]{2L, 0L}) {
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            VoucherReconcileLogMapper mapper = mock(VoucherReconcileLogMapper.class);
            RollbackFailureService failure = mock(RollbackFailureService.class);
            stubLua(redis, code);

            serviceWith(redis, mapper, failure).rollbackSeckillVoucherPlus(order());

            verify(mapper, never()).insert(any(VoucherReconcileLog.class));
            verify(failure, never()).record(anyString(), any(), anyString(), eq(1), any());
        }
    }
}
