package com.hmdp.shopdetail.application.service;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.shopdetail.domain.model.GuaranteeClaim;
import com.hmdp.shopdetail.domain.service.ShopDetailDomainService;
import com.hmdp.shoprating.service.ShopRatingRefreshService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 保障申诉场景校验轻版（P5）测试：shopId 存在性在提交时快速失败，
 * 不再创建注定转人工的申诉单；planCode 的规则语义仍归状态机。
 */
class ShopDetailApplicationClaimValidationTest {

    private ShopDetailDomainService domainService;
    private ShopRatingRefreshService refreshService;
    private IShopService shopService;
    private ShopDetailApplicationService service;

    @BeforeEach
    void setUp() {
        domainService = mock(ShopDetailDomainService.class);
        refreshService = mock(ShopRatingRefreshService.class);
        shopService = mock(IShopService.class);
        service = new ShopDetailApplicationService(domainService, refreshService);
        setField(service, "shopService", shopService);
    }

    private GuaranteeClaim claim(Long shopId) {
        GuaranteeClaim claim = new GuaranteeClaim();
        claim.setClaimNo("claim-1");
        claim.setShopId(shopId);
        claim.setUserId(9527L);
        claim.setPlanCode("PLAN-A");
        claim.setReason("屏幕碎裂");
        claim.setStatus("SUBMITTED");
        return claim;
    }

    private void loginAs(Long userId) {
        UserDTO user = new UserDTO();
        user.setId(userId);
        com.hmdp.utils.UserHolder.saveUser(user);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        com.hmdp.utils.UserHolder.removeUser();
    }

    @Test
    void submitClaim_unknownShop_throwsFastBeforePersist() {
        when(shopService.getById(99L)).thenReturn(null);
        loginAs(9527L);

        assertThrows(IllegalArgumentException.class, () -> service.submitGuaranteeClaim(claim(99L)));

        // 校验失败时不落库、不驱动状态机
        verify(domainService, never()).createClaim(any(GuaranteeClaim.class));
    }

    @Test
    void submitClaim_shopExists_proceedsToPersist() {
        when(shopService.getById(1L)).thenReturn(new Shop());
        when(domainService.createClaim(any(GuaranteeClaim.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        loginAs(9527L);

        GuaranteeClaim saved = service.submitGuaranteeClaim(claim(1L));

        assertNotNull(saved);
        assertEquals(9527L, saved.getUserId());
        verify(domainService).createClaim(any(GuaranteeClaim.class));
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("字段注入失败: " + name, e);
        }
    }
}
