package com.hmdp.service.impl;

import com.hmdp.entity.Shop;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.MultiLevelCacheService;
import com.hmdp.utils.RedisBloomFilter;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商详链路专用查询 {@link ShopServiceImpl#queryShopNoBloomGuard} 的接线测试。
 *
 * <p>核心保证：即使布隆判定"确定不存在"，该方法仍走多级缓存链路——
 * 新增店铺在布隆下次 rebuild 前不会被误判成空商详。穿透防护由
 * {@code loadOnMiss} 的空值标记 + 互斥锁兜底，不依赖布隆前置守卫。</p>
 */
class ShopNoBloomGuardTest {

    private ShopServiceImpl shopService;
    private RedisBloomFilter bloomFilter;
    private MultiLevelCacheService multiLevelCacheService;
    private Shop shop;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        shopService = new ShopServiceImpl();
        bloomFilter = mock(RedisBloomFilter.class);
        multiLevelCacheService = mock(MultiLevelCacheService.class);
        shop = new Shop().setId(13L).setName("测试奶茶店");

        ReflectionTestUtils.setField(shopService, "shopBloomFilter", bloomFilter);
        ReflectionTestUtils.setField(shopService, "multiLevelCacheService", multiLevelCacheService);
        ReflectionTestUtils.setField(shopService, "localCacheEnabled", true);
        ReflectionTestUtils.setField(shopService, "clientClient", mock(CacheClient.class));

        when(multiLevelCacheService.queryWithLocalCache(
                anyString(), anyLong(), eq(Shop.class), any(Function.class),
                any(), any(TimeUnit.class))).thenReturn(shop);
    }

    @Test
    void shouldBypassBloomGuardEvenWhenBloomSaysAbsent() {
        // 布隆确定说"不存在"——新增店铺尚未进布隆的典型场景
        when(bloomFilter.mightContain(13L)).thenReturn(false);

        Shop result = shopService.queryShopNoBloomGuard(13L);

        // 仍然拿到店铺：不被布隆误杀
        assertSame(shop, result);
        // 关键断言：压根没问过布隆，守卫被完全跳过
        verify(bloomFilter, never()).mightContain(anyLong());
        // 走的是 /shop/{id} 同一条多级缓存链路、同一个 key
        verify(multiLevelCacheService).queryWithLocalCache(
                eq(RedisConstants.CACHE_SHOP_KEY), eq(13L), eq(Shop.class),
                any(Function.class), any(), any(TimeUnit.class));
    }

    @Test
    void shouldReturnNullWhenCacheChainMisses() {
        when(multiLevelCacheService.queryWithLocalCache(
                anyString(), anyLong(), eq(Shop.class), any(Function.class),
                any(), any(TimeUnit.class))).thenReturn(null);

        Shop result = shopService.queryShopNoBloomGuard(999L);

        // DB 确实没有时返回 null，与 getById 语义一致，由上层空聚合兜底
        assertNull(result);
    }

    @Test
    void shouldFallbackToSingleLevelWhenLocalCacheDisabled() {
        ReflectionTestUtils.setField(shopService, "localCacheEnabled", false);
        CacheClient cacheClient = (CacheClient) ReflectionTestUtils.getField(shopService, "clientClient");
        when(cacheClient.queryWithLogicalExpire(
                anyString(), anyLong(), eq(Shop.class), any(Function.class),
                any(), any(TimeUnit.class))).thenReturn(shop);

        Shop result = shopService.queryShopNoBloomGuard(13L);

        assertSame(shop, result);
        verify(multiLevelCacheService, never())
                .queryWithLocalCache(anyString(), anyLong(), eq(Shop.class), any(Function.class),
                        any(), any(TimeUnit.class));
    }
}
