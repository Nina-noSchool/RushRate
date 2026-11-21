package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R2 定案（2026-11-19）：发送失败不回滚 Redis——扣减成功的事实单向兑现。
 *
 * <p>语义反转的两条断言：
 * <ul>
 *   <li>发送失败（publish 返回 false，发布器内部已落本地消息表）→ 不回滚 +
 *       返回订单号（"处理中"语义，用户轮询），由消息表重投 / 恢复任务 / 对账三层保证最终补单；</li>
 *   <li>Lua 一人一单命中 → 新码 5 → 幂等返回"已参与，订单处理中"（success 语义，非报错）。</li>
 * </ul></p>
 */
class VoucherOrderProcessingSemanticsTest {

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    private VoucherOrderServiceImpl serviceWith(Long orderId, Long luaResult) {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        RedisIdWorker redisIdWorker = mock(RedisIdWorker.class);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(service, "redisIdWorker", redisIdWorker);
        UserDTO user = new UserDTO();
        user.setId(9L);
        UserHolder.saveUser(user);
        when(redisIdWorker.nextId("order")).thenReturn(orderId);
        when(stringRedisTemplate.execute(any(), any(), any(Object[].class))).thenReturn(luaResult);
        return service;
    }

    @Test
    void messageSendFailed_shouldNotRollback_andReturnOrderIdAsProcessing() {
        // publish 为 mock 默认值（false = 发送失败，发布器内部已落本地消息表），无需显式 stub
        VoucherOrderServiceImpl service = serviceWith(101L, 0L);

        Result result = service.seckillVoucherPlus(7L, null);

        // 处理中语义：success + 订单号返回，绝不回滚已扣减的事实
        assertTrue(result.getSuccess());
        assertEquals(101L, result.getData());
    }

    @Test
    void idempotentEntry_shouldReturnProcessingSemanticsInsteadOfError() {
        // Lua 一人一单命中 → 新码 5：系统已记住这次参与，幂等返回"处理中"而非报错
        VoucherOrderServiceImpl service = serviceWith(102L, 5L);

        Result result = service.seckillVoucherPlus(7L, null);

        assertTrue(result.getSuccess());
        assertEquals("您已参与，订单处理中", result.getData());
    }
}
