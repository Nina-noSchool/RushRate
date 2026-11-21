package com.hmdp.service.impl;

import com.hmdp.dto.UserDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillVoucherOrderMessage;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.NormalOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mapper.NormalOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class VoucherOrderServiceImplTest {

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    // 2026-11-19 R2 定案：原 seckillVoucherPlus_shouldRollbackRedisStateWhenMessageSendFails
    // 已被语义反转删除——发送失败不再回滚，改"处理中"语义。反转后的断言见
    // VoucherOrderProcessingSemanticsTest（不回滚 + 订单号返回 + 幂等入口）。

    @Test
    void seckillVoucherPlus_shouldRejectFrequentRequests() {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        RedisIdWorker redisIdWorker = mock(RedisIdWorker.class);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(service, "redisIdWorker", redisIdWorker);

        UserDTO user = new UserDTO();
        user.setId(9L);
        UserHolder.saveUser(user);
        when(redisIdWorker.nextId("order")).thenReturn(101L);
        when(stringRedisTemplate.execute(any(), eq(Collections.emptyList()), eq("7"), eq("9"), eq("101"),
                eq("trace-101"), any(), any()))
                .thenReturn(3L);

        Result result = service.seckillVoucherPlus(7L, null);

        assertTrue(!result.getSuccess());
        assertEquals("请求过于频繁，请稍后重试", result.getErrorMsg());
    }

    @Test
    void rollbackSeckillVoucherPlus_shouldBeIdempotent() {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);

        com.hmdp.entity.VoucherOrder order = new com.hmdp.entity.VoucherOrder();
        order.setId(101L);
        order.setVoucherId(7L);
        order.setUserId(9L);
        order.setTraceId("trace-101");

        service.rollbackSeckillVoucherPlus(order);

        verify(stringRedisTemplate).execute(any(), eq(Collections.emptyList()), eq("7"), eq("9"),
                eq("101"), eq("trace-101"), any(), any());
    }

    @Test
    void seckillVoucherPlus_shouldAcceptOrderWhenMessageSendSucceedsBeforeConsumerPersists() {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        RedisIdWorker redisIdWorker = mock(RedisIdWorker.class);
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        RocketMqReliablePublisher reliablePublisher = mock(RocketMqReliablePublisher.class);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(service, "redisIdWorker", redisIdWorker);
        ReflectionTestUtils.setField(service, "rocketMQTemplate", rocketMQTemplate);
        ReflectionTestUtils.setField(service, "rocketMqReliablePublisher", reliablePublisher);

        UserDTO user = new UserDTO();
        user.setId(9L);
        UserHolder.saveUser(user);
        when(redisIdWorker.nextId("order")).thenReturn(101L);
        when(stringRedisTemplate.execute(any(), eq(Collections.emptyList()), eq("7"), eq("9"), eq("101"),
                eq("trace-101"), any(), any()))
                .thenReturn(0L);
        when(reliablePublisher.publish(eq("SECKILL_ORDER_CREATE"), eq("seckill-plus-order-topic:order-create"), any()))
                .thenReturn(true);
        when(reliablePublisher.publishDelay(eq("SECKILL_ORDER_TIMEOUT"), eq("seckill-plus-order-topic:order-timeout"), any(), eq(15)))
                .thenReturn(true);

        Result result = service.seckillVoucherPlus(7L, null);

        assertTrue(result.getSuccess());
        assertEquals(101L, result.getData());
        verify(reliablePublisher).publish(eq("SECKILL_ORDER_CREATE"), eq("seckill-plus-order-topic:order-create"),
                argThat(payload -> {
                    SeckillVoucherOrderMessage message = cn.hutool.json.JSONUtil.toBean((String) payload, SeckillVoucherOrderMessage.class);
                    return "trace-101".equals(message.getTraceId())
                            && Long.valueOf(101L).equals(message.getOrderId())
                            && Long.valueOf(7L).equals(message.getVoucherId())
                            && Long.valueOf(9L).equals(message.getUserId());
                }));
        verify(reliablePublisher).publishDelay(eq("SECKILL_ORDER_TIMEOUT"), eq("seckill-plus-order-topic:order-timeout"), any(), eq(15));
        verify(stringRedisTemplate).execute(any(), eq(Collections.emptyList()), eq("7"), eq("9"),
                eq("101"), eq("trace-101"), any(), any());
    }

    @Test
    void seckillVoucherPlus_shouldAcceptOrderWhenMessageSendSucceeds() {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        RedisIdWorker redisIdWorker = mock(RedisIdWorker.class);
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        RocketMqReliablePublisher reliablePublisher = mock(RocketMqReliablePublisher.class);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(service, "redisIdWorker", redisIdWorker);
        ReflectionTestUtils.setField(service, "rocketMQTemplate", rocketMQTemplate);
        ReflectionTestUtils.setField(service, "rocketMqReliablePublisher", reliablePublisher);

        UserDTO user = new UserDTO();
        user.setId(9L);
        UserHolder.saveUser(user);
        when(redisIdWorker.nextId("order")).thenReturn(101L);
        when(stringRedisTemplate.execute(any(), eq(Collections.emptyList()), eq("7"), eq("9"), eq("101"),
                eq("trace-101"), any(), any()))
                .thenReturn(0L);
        when(reliablePublisher.publish(eq("SECKILL_ORDER_CREATE"), eq("seckill-plus-order-topic:order-create"), any()))
                .thenReturn(true);
        when(reliablePublisher.publishDelay(eq("SECKILL_ORDER_TIMEOUT"), eq("seckill-plus-order-topic:order-timeout"), any(), eq(15)))
                .thenReturn(true);

        Result result = service.seckillVoucherPlus(7L, null);

        assertTrue(result.getSuccess());
        assertEquals(101L, result.getData());
    }

    @Test
    void seckillVoucherPlus_shouldNotRollbackWhenPostPublishStepFails() {
        VoucherOrderServiceImpl service = spy(new VoucherOrderServiceImpl());
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        RedisIdWorker redisIdWorker = mock(RedisIdWorker.class);
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        RocketMqReliablePublisher reliablePublisher = mock(RocketMqReliablePublisher.class);
        OrderCreateVerificationService verificationService = mock(OrderCreateVerificationService.class);
        VoucherOrderMapper voucherOrderMapper = mock(VoucherOrderMapper.class);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(service, "redisIdWorker", redisIdWorker);
        ReflectionTestUtils.setField(service, "rocketMQTemplate", rocketMQTemplate);
        ReflectionTestUtils.setField(service, "rocketMqReliablePublisher", reliablePublisher);
        ReflectionTestUtils.setField(service, "orderCreateVerificationService", verificationService);
        ReflectionTestUtils.setField(service, "baseMapper", voucherOrderMapper);

        UserDTO user = new UserDTO();
        user.setId(9L);
        UserHolder.saveUser(user);
        when(redisIdWorker.nextId("order")).thenReturn(101L);
        when(stringRedisTemplate.execute(any(), eq(Collections.emptyList()), eq("7"), eq("9"), eq("101"),
                eq("trace-101"), any(), any()))
                .thenReturn(0L);
        when(reliablePublisher.publish(eq("SECKILL_ORDER_CREATE"), eq("seckill-plus-order-topic:order-create"), any()))
                .thenReturn(true);
        when(reliablePublisher.publishDelay(eq("SECKILL_ORDER_TIMEOUT"), eq("seckill-plus-order-topic:order-timeout"), any(), eq(15)))
                .thenReturn(true);
        when(voucherOrderMapper.selectById(101L)).thenReturn(null);
        doThrow(new RuntimeException("db timeout"))
                .when(verificationService).createPendingTask(anyLong(), anyLong(), anyLong(), anyString());

        Result result = service.seckillVoucherPlus(7L, null);

        // 订单消息已发出 → 后续步骤失败也不回滚 Redis，用户照常拿到订单号
        assertTrue(result.getSuccess());
        assertEquals(101L, result.getData());
        verify(service, never()).rollbackSeckillVoucherPlus(any());
    }

    @Test
    void loadDbStock_shouldUseDirectLookupCompatibleWithJdk21() {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        ISeckillVoucherService seckillVoucherService = mock(ISeckillVoucherService.class);
        ReflectionTestUtils.setField(service, "seckillVoucherService", seckillVoucherService);

        SeckillVoucher voucher = new SeckillVoucher().setVoucherId(13L).setStock(100);
        when(seckillVoucherService.getById(13L)).thenReturn(voucher);
        Integer stock = (Integer) ReflectionTestUtils.invokeMethod(service, "loadDbStock", 13L);

        assertEquals(100, stock);
        verify(seckillVoucherService).getById(13L);
    }

    // ---- 限购语义分层（2026-11-19）：普通代金券可重复购买，秒杀券一人一单 ----
    // Lua 第 4 参 limitOne 的传参语义由 VM 实弹验证背书（普通券连买成功 + 秒杀券一人一单拦截），
    // 这里验证 Java 侧的类型查询与结果映射链路不炸、消息映射正确。

    @Test
    void seckillVoucher_normalVoucher_surfacesLuaResult() {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        RedisIdWorker redisIdWorker = mock(RedisIdWorker.class);
        com.hmdp.service.IVoucherService voucherService = mock(com.hmdp.service.IVoucherService.class);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(service, "redisIdWorker", redisIdWorker);
        ReflectionTestUtils.setField(service, "voucherService", voucherService);
        UserDTO user = new UserDTO();
        user.setId(9L);
        UserHolder.saveUser(user);
        when(redisIdWorker.nextId("order")).thenReturn(101L);
        com.hmdp.entity.Voucher voucher = new com.hmdp.entity.Voucher();
        voucher.setId(18L);
        voucher.setType(0);
        when(voucherService.getById(18L)).thenReturn(voucher);
        when(stringRedisTemplate.execute(any(), any(), any(Object[].class))).thenReturn(1L);

        Result result = service.seckillVoucher(18L);

        // Lua 返回 1（库存不足）→ 映射为业务失败；链路含类型查询与参数下发
        assertFalse(result.getSuccess());
        assertEquals("库存不足", result.getErrorMsg());
        verify(voucherService).getById(18L);
    }

    @Test
    void seckillVoucher_seckillVoucher_surfacesLuaResult() {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        RedisIdWorker redisIdWorker = mock(RedisIdWorker.class);
        com.hmdp.service.IVoucherService voucherService = mock(com.hmdp.service.IVoucherService.class);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(service, "redisIdWorker", redisIdWorker);
        ReflectionTestUtils.setField(service, "voucherService", voucherService);
        UserDTO user = new UserDTO();
        user.setId(9L);
        UserHolder.saveUser(user);
        when(redisIdWorker.nextId("order")).thenReturn(101L);
        com.hmdp.entity.Voucher voucher = new com.hmdp.entity.Voucher();
        voucher.setId(17L);
        voucher.setType(1);
        when(voucherService.getById(17L)).thenReturn(voucher);
        when(stringRedisTemplate.execute(any(), any(), any(Object[].class))).thenReturn(2L);

        Result result = service.seckillVoucher(17L);

        // Lua 返回 2（重复下单拦截）→ 映射为"不能重复下单"
        assertFalse(result.getSuccess());
        assertEquals("不能重复下单", result.getErrorMsg());
        verify(voucherService).getById(17L);
    }

    // ---- 订单属主查询（2026-11-21）：前端轮询建单结果的只读端点 ----
    // 秒杀Plus"处理中"语义要求订单状态可查；不存在/越权均拒绝，不暴露他人订单。

    @Test
    void queryVoucherOrderOwned_shouldReturnOrderForOwner() {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        VoucherOrderMapper voucherOrderMapper = mock(VoucherOrderMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", voucherOrderMapper);
        UserDTO user = new UserDTO();
        user.setId(9L);
        UserHolder.saveUser(user);
        com.hmdp.entity.VoucherOrder order = new com.hmdp.entity.VoucherOrder();
        order.setId(101L);
        order.setUserId(9L);
        when(voucherOrderMapper.selectById(101L)).thenReturn(order);

        Result result = service.queryVoucherOrderOwned(101L);

        assertTrue(result.getSuccess());
        assertEquals(order, result.getData());
    }

    @Test
    void queryVoucherOrderOwned_shouldRejectForeignOrder() {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        VoucherOrderMapper voucherOrderMapper = mock(VoucherOrderMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", voucherOrderMapper);
        UserDTO user = new UserDTO();
        user.setId(8L);
        UserHolder.saveUser(user);
        com.hmdp.entity.VoucherOrder order = new com.hmdp.entity.VoucherOrder();
        order.setId(101L);
        order.setUserId(9L);
        when(voucherOrderMapper.selectById(101L)).thenReturn(order);

        Result result = service.queryVoucherOrderOwned(101L);

        assertFalse(result.getSuccess());
        assertEquals("无权查看该订单", result.getErrorMsg());
    }

    @Test
    void queryVoucherOrderOwned_shouldFailWhenOrderMissing() {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        VoucherOrderMapper voucherOrderMapper = mock(VoucherOrderMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", voucherOrderMapper);
        UserDTO user = new UserDTO();
        user.setId(9L);
        UserHolder.saveUser(user);
        when(voucherOrderMapper.selectById(101L)).thenReturn(null);

        Result result = service.queryVoucherOrderOwned(101L);

        assertFalse(result.getSuccess());
        assertEquals("订单不存在", result.getErrorMsg());
    }

    // ---- 业务分表（2026-11-21）：秒杀单落 tb_seckill_order，普通券单落 tb_normal_order ----

    @Test
    void createVoucherOrder_normalVoucher_shouldInsertIntoNormalOrderTable() {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        ISeckillVoucherService seckillVoucherService = mock(ISeckillVoucherService.class);
        com.hmdp.service.IVoucherService voucherService = mock(com.hmdp.service.IVoucherService.class);
        NormalOrderMapper normalOrderMapper = mock(NormalOrderMapper.class);
        VoucherOrderMapper baseMapper = mock(VoucherOrderMapper.class);
        ReflectionTestUtils.setField(service, "seckillVoucherService", seckillVoucherService);
        ReflectionTestUtils.setField(service, "voucherService", voucherService);
        ReflectionTestUtils.setField(service, "normalOrderMapper", normalOrderMapper);
        ReflectionTestUtils.setField(service, "baseMapper", baseMapper);

        com.hmdp.entity.Voucher voucher = new com.hmdp.entity.Voucher().setId(18L).setType(0);
        when(voucherService.getById(18L)).thenReturn(voucher);
        com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper chain =
                mock(com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper.class);
        doReturn(chain).when(chain).setSql(anyString());
        doReturn(chain).when(chain).eq(anyString(), any());
        doReturn(chain).when(chain).gt(anyString(), any());
        doReturn(true).when(chain).update();
        doReturn(chain).when(seckillVoucherService).update();

        com.hmdp.entity.VoucherOrder order = new com.hmdp.entity.VoucherOrder()
                .setId(201L).setUserId(9L).setVoucherId(18L);

        Result result = service.createVoucherOrder(order);

        // 普通券单 → tb_normal_order 瘦表（允许复购），不落秒杀表
        assertTrue(result.getSuccess());
        verify(normalOrderMapper).insert(argThat((NormalOrder n) ->
                Long.valueOf(201L).equals(n.getId())
                        && Long.valueOf(9L).equals(n.getUserId())
                        && Long.valueOf(18L).equals(n.getVoucherId())));
        verify(baseMapper, never()).insert(org.mockito.ArgumentMatchers.<com.hmdp.entity.VoucherOrder>any());
    }

    @Test
    void queryVoucherOrderOwned_shouldFallbackToNormalOrderTable() {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        VoucherOrderMapper voucherOrderMapper = mock(VoucherOrderMapper.class);
        NormalOrderMapper normalOrderMapper = mock(NormalOrderMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", voucherOrderMapper);
        ReflectionTestUtils.setField(service, "normalOrderMapper", normalOrderMapper);
        UserDTO user = new UserDTO();
        user.setId(9L);
        UserHolder.saveUser(user);
        when(voucherOrderMapper.selectById(301L)).thenReturn(null);
        NormalOrder normalOrder = new NormalOrder().setId(301L).setUserId(9L).setVoucherId(18L);
        when(normalOrderMapper.selectById(301L)).thenReturn(normalOrder);

        Result result = service.queryVoucherOrderOwned(301L);

        // 秒杀表查不到 → 回退查普通券单表
        assertTrue(result.getSuccess());
        assertEquals(normalOrder, result.getData());
    }

    @Test
    void queryVoucherOrderOwned_shouldNotNpeWhenNormalMapperMissing() {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        VoucherOrderMapper voucherOrderMapper = mock(VoucherOrderMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", voucherOrderMapper);
        UserDTO user = new UserDTO();
        user.setId(9L);
        UserHolder.saveUser(user);
        when(voucherOrderMapper.selectById(301L)).thenReturn(null);

        // normalOrderMapper 未注入（对账模块未启用等场景）→ 降级为"订单不存在"，不 NPE
        Result result = service.queryVoucherOrderOwned(301L);

        assertFalse(result.getSuccess());
        assertEquals("订单不存在", result.getErrorMsg());
    }
}
