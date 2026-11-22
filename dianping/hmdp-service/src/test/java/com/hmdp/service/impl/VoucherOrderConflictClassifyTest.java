package com.hmdp.service.impl;

import com.hmdp.dto.SeckillVoucherOrderMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.VoucherOrderCreateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * createVoucherOrderPlus 冲突定性测试（2026-11-22 缺口修复）：
 * DuplicateKeyException 不携带约束名，主键冲突与 uk_user_voucher 冲突必须回查 DB 区分——
 * 主键冲突 = 消息重投（DUPLICATE 忽略）；user+voucher 冲突 = 多扣的那笔（回滚 Redis）。
 */
class VoucherOrderConflictClassifyTest {

    private VoucherOrderMapper baseMapper = mock(VoucherOrderMapper.class);
    private VoucherOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", baseMapper);
    }

    private SeckillVoucherOrderMessage message() {
        SeckillVoucherOrderMessage message = new SeckillVoucherOrderMessage();
        message.setOrderId(2002L);
        message.setUserId(9L);
        message.setVoucherId(7L);
        message.setTraceId("trace-2002");
        return message;
    }

    private void stubInsertConflict() {
        doThrow(new DuplicateKeyException("Duplicate entry for key"))
                .when(baseMapper)
                .insert(org.mockito.ArgumentMatchers.<VoucherOrder>any());
    }

    @Test
    void pkConflict_shouldClassifyAsDuplicateRedelivery() {
        stubInsertConflict();
        // 第一级回查：同 orderId 的订单已存在 → 消息重投
        when(baseMapper.selectById(2002L)).thenReturn(new VoucherOrder().setId(2002L));

        VoucherOrderCreateResult result = service.createVoucherOrderPlus(message());

        assertEquals(VoucherOrderCreateResult.DUPLICATE, result);
    }

    @Test
    void ukConflict_shouldClassifyAsUserVoucherConflict() {
        stubInsertConflict();
        // orderId 未命中（新订单号），user+voucher 命中（Redis 标记失效后多扣的那笔）
        when(baseMapper.selectById(2002L)).thenReturn(null);
        when(baseMapper.selectOne(any())).thenReturn(new VoucherOrder().setId(1001L).setUserId(9L).setVoucherId(7L));

        VoucherOrderCreateResult result = service.createVoucherOrderPlus(message());

        assertEquals(VoucherOrderCreateResult.USER_VOUCHER_CONFLICT, result);
    }

    @Test
    void conflictRowVanished_shouldConservativelyTreatAsRedelivery() {
        stubInsertConflict();
        // 两级回查都查不到（测试数据撞 id 等极端场景）→ 保守按消息重投忽略，不误伤回滚
        when(baseMapper.selectById(2002L)).thenReturn(null);
        when(baseMapper.selectOne(any())).thenReturn(null);

        VoucherOrderCreateResult result = service.createVoucherOrderPlus(message());

        assertEquals(VoucherOrderCreateResult.DUPLICATE, result);
    }
}
