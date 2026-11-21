package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 入口守卫测试（2026-11-21 批次）：售罄短路 + 令牌前置。
 */
class VoucherOrderEntryGuardTest {

    private final StringRedisTemplate template = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final ISeckillVoucherService seckillVoucherService = mock(ISeckillVoucherService.class);
    private final RedisIdWorker idWorker = mock(RedisIdWorker.class);

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    private VoucherOrderServiceImpl newService() {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", template);
        ReflectionTestUtils.setField(service, "seckillVoucherService", seckillVoucherService);
        ReflectionTestUtils.setField(service, "redisIdWorker", idWorker);
        when(template.opsForValue()).thenReturn(valueOps);
        return service;
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<Long, Long> soldOutMapOf(VoucherOrderServiceImpl service) {
        return (ConcurrentHashMap<Long, Long>) ReflectionTestUtils.getField(service, "soldOutAt");
    }

    @Test
    void soldOutMark_shouldShortCircuitWhenMarked() {
        VoucherOrderServiceImpl service = newService();
        UserDTO user = new UserDTO();
        user.setId(9L);
        UserHolder.saveUser(user);
        soldOutMapOf(service).put(7L, System.currentTimeMillis());

        Result result = service.seckillVoucherPlus(7L, null);

        // "库存不足，已售罄"只有本地短路口会产生——文案即断言，无需 mock Redis
        assertEquals("库存不足，已售罄", result.getErrorMsg());
        assertFalse(result.getSuccess());
    }

    @Test
    void soldOutMark_shouldExpireAfterTtl() {
        VoucherOrderServiceImpl service = newService();
        UserDTO user = new UserDTO();
        user.setId(9L);
        UserHolder.saveUser(user);
        // 时间戳置于 TTL 之外 → 标记过期自动失效，恢复走 Lua
        soldOutMapOf(service).put(7L, System.currentTimeMillis() - 600000);
        when(idWorker.nextId("order")).thenReturn(101L);

        Result result = service.seckillVoucherPlus(7L, null);

        // 过期标记被清除后走 Lua；Lua 未打桩返回 null → r=-1 → "秒杀库存未初始化"（证明未命中短路口）
        assertEquals("秒杀库存未初始化", result.getErrorMsg());
        assertFalse(soldOutMapOf(service).containsKey(7L), "过期标记应被惰性清除");
    }

    @Test
    void accessTokenGate_shouldRejectWithoutToken() {
        VoucherOrderServiceImpl service = newService();
        ReflectionTestUtils.setField(service, "accessTokenEnabled", true);
        UserDTO user = new UserDTO();
        user.setId(9L);
        UserHolder.saveUser(user);
        when(idWorker.nextId("order")).thenReturn(101L);

        Result result = service.seckillVoucherPlus(7L, null);

        // 开关开启且无令牌 → gate 直接拒绝（"请先获取抢购资格"为 gate 口唯一来源），库存 Lua 不执行
        assertEquals("请先获取抢购资格", result.getErrorMsg());
        assertFalse(result.getSuccess());
    }

    @Test
    void issueAccessToken_shouldStoreAndReturn() {
        VoucherOrderServiceImpl service = newService();
        UserDTO user = new UserDTO();
        user.setId(1013L);
        UserHolder.saveUser(user);
        SeckillVoucher voucher = new SeckillVoucher().setVoucherId(19L).setStock(100);
        when(seckillVoucherService.getById(19L)).thenReturn(voucher);

        String token = service.issueAccessToken(19L);

        assertNotNull(token);
        assertFalse(token.isEmpty());
        verify(valueOps).set(eq("seckill:access:token:19:1013"), eq(token), eq(600L), eq(TimeUnit.SECONDS));
    }
}
