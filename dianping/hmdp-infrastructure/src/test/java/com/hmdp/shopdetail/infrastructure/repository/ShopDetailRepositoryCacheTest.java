package com.hmdp.shopdetail.infrastructure.repository;

import com.hmdp.entity.Shop;
import com.hmdp.service.compat.LegacyBlogQueryService;
import com.hmdp.service.compat.LegacyShopQueryService;
import com.hmdp.shopdetail.domain.model.ShopDetailAggregate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商详 base 走缓存的接线测试。
 *
 * <p>验证 {@code findBaseDetail} 用的是走缓存的 {@code getShopByIdCached}，
 * 而不是裸查库的 {@code getShopById}；同时确认 shop 查不到时空聚合兜底不炸。</p>
 */
class ShopDetailRepositoryCacheTest {

    private ShopDetailRepository repository;
    private LegacyShopQueryService legacyShopQueryService;

    @BeforeEach
    void setUp() {
        repository = new ShopDetailRepository();
        legacyShopQueryService = mock(LegacyShopQueryService.class);
        ReflectionTestUtils.setField(repository, "legacyShopQueryService", legacyShopQueryService);
        ReflectionTestUtils.setField(repository, "legacyBlogQueryService", mock(LegacyBlogQueryService.class));

        when(legacyShopQueryService.getVouchersByShopId(13L)).thenReturn(Collections.emptyList());
    }

    @Test
    void shouldUseCachedShopQueryInsteadOfRawGetById() {
        Shop shop = new Shop().setId(13L).setName("测试奶茶店").setScore(45).setComments(10).setSold(100);
        when(legacyShopQueryService.getShopByIdCached(13L)).thenReturn(shop);

        ShopDetailAggregate aggregate = repository.findBaseDetail(13L);

        assertNotNull(aggregate);
        assertEquals(13L, aggregate.getShopId());
        assertEquals("测试奶茶店", aggregate.getShopName());
        // 关键断言：走缓存方法，不再裸查库
        verify(legacyShopQueryService).getShopByIdCached(13L);
        verify(legacyShopQueryService, never()).getShopById(13L);
    }

    @Test
    void shouldReturnEmptyAggregateWhenShopNotFound() {
        when(legacyShopQueryService.getShopByIdCached(999L)).thenReturn(null);

        ShopDetailAggregate aggregate = repository.findBaseDetail(999L);

        // 查不到时空聚合，tags 空列表，不抛 NPE
        assertNotNull(aggregate);
        assertNotNull(aggregate.getTags());
        assertEquals(0, aggregate.getTags().size());
        verify(legacyShopQueryService).getShopByIdCached(999L);
    }
}
