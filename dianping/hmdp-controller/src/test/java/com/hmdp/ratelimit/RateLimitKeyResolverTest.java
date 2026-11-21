package com.hmdp.ratelimit;

import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RateLimitKeyResolverTest {

    private final RateLimitKeyResolver resolver = new RateLimitKeyResolver();

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    private Method methodOf(String name) throws NoSuchMethodException {
        return Sample.class.getDeclaredMethod(name, Long.class);
    }

    static class Sample {
        @SuppressWarnings("unused")
        void sample(Long voucherId) {
        }
    }

    @Test
    void shouldResolveBuiltinIpVariable() throws Exception {
        String key = resolver.resolve("'ip:' + #ip", methodOf("sample"), new Object[]{7L}, "10.0.0.9");
        assertEquals("ip:10.0.0.9", key);
    }

    @Test
    void shouldResolveMethodParameterByName() throws Exception {
        String key = resolver.resolve("'voucher:' + #voucherId", methodOf("sample"), new Object[]{19L}, "10.0.0.9");
        assertEquals("voucher:19", key);
    }

    @Test
    void shouldResolveUserIdWhenLoggedIn() throws Exception {
        com.hmdp.dto.UserDTO user = new com.hmdp.dto.UserDTO();
        user.setId(1013L);
        UserHolder.saveUser(user);
        String key = resolver.resolve("'user:' + #userId", methodOf("sample"), new Object[]{19L}, "10.0.0.9");
        assertEquals("user:1013", key);
    }

    @Test
    void shouldReturnEmptyOnBrokenExpression() throws Exception {
        String key = resolver.resolve("'x:' + #notExists((", methodOf("sample"), new Object[]{7L}, "10.0.0.9");
        assertTrue(key.isEmpty(), "解析失败应返回空串走 fail-open");
    }

    @Test
    void shouldResolveWhenRequestContextAbsent() throws Exception {
        // 无 HTTP 上下文（定时任务/测试环境）也不应炸：#ip 解析为 null 变量
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        org.junit.jupiter.api.Assertions.assertNotNull(template);
        String key = resolver.resolve("'ip:' + #ip", methodOf("sample"), new Object[]{7L}, "unknown");
        assertEquals("ip:unknown", key);
    }
}
