package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.shopdetail.application.model.request.GuaranteeClaimCreateRequest;
import com.hmdp.shopdetail.domain.model.GuaranteeClaim;
import com.hmdp.shopdetail.facade.ShopDetailFacade;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 保障申诉链路越权防护测试（2026-11-17，对应手册 Q72 修复）。
 * 两类防线都在控制器层收口：认证门槛（未登录拒绝）与横向越权（非属主拒绝）。
 * UserHolder 是 ThreadLocal，测试直接 saveUser 模拟登录态，@AfterEach 必须 remove，
 * 否则线程复用会把登录态泄漏给同线程的下一个用例——正是 Q39 追问 2 讲的那个坑。
 */
class ShopDetailControllerAuthorizationTest {

    private static final Long OWNER_ID = 1001L;
    private static final Long ATTACKER_ID = 2002L;
    private static final String CLAIM_NO = "claim-abc123";

    private ShopDetailController controller;
    private ShopDetailFacade facade;

    @BeforeEach
    void setUp() {
        facade = mock(ShopDetailFacade.class);
        controller = new ShopDetailController();
        controller.setShopDetailFacade(facade);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    private GuaranteeClaim ownedClaim() {
        GuaranteeClaim claim = new GuaranteeClaim();
        claim.setClaimNo(CLAIM_NO);
        claim.setUserId(OWNER_ID);
        claim.setShopId(1L);
        claim.setPlanCode("PLAN-A");
        claim.setReason("屏幕碎裂");
        claim.setStatus("SUBMITTED");
        return claim;
    }

    private void loginAs(Long userId) {
        UserDTO user = new UserDTO();
        user.setId(userId);
        UserHolder.saveUser(user);
    }

    // ---- 认证门槛：提交申诉必须登录（替换早期匿名 userId=0 的兜底） ----

    @Test
    void submitClaim_withoutLogin_rejected() {
        Result result = controller.submitClaim(new GuaranteeClaimCreateRequest());

        assertFalse(result.getSuccess());
        assertEquals("请先登录", result.getErrorMsg());
        verify(facade, never()).submitGuaranteeClaim(any());
    }

    @Test
    void submitClaim_withLogin_bindsCurrentUserAndProceeds() {
        loginAs(OWNER_ID);
        GuaranteeClaimCreateRequest request = new GuaranteeClaimCreateRequest();
        request.setShopId(1L);
        request.setPlanCode("PLAN-A");

        Result result = controller.submitClaim(request);

        assertTrue(result.getSuccess());
        ArgumentCaptor<GuaranteeClaim> captor = ArgumentCaptor.forClass(GuaranteeClaim.class);
        verify(facade).submitGuaranteeClaim(captor.capture());
        assertEquals(OWNER_ID, captor.getValue().getUserId());
        assertNotNull(captor.getValue().getClaimNo());
    }

    // ---- 横向越权：重试会真实驱动赔付状态机，必须仅属主可调 ----

    @Test
    void retryClaim_byNonOwner_rejected() {
        loginAs(ATTACKER_ID);
        when(facade.getGuaranteeClaim(CLAIM_NO)).thenReturn(ownedClaim());

        Result result = controller.retryGuaranteeClaim(CLAIM_NO);

        assertFalse(result.getSuccess());
        assertEquals("无权操作该申诉单", result.getErrorMsg());
        verify(facade, never()).retryGuaranteeClaim(any());
    }

    @Test
    void retryClaim_byOwner_proceeds() {
        loginAs(OWNER_ID);
        when(facade.getGuaranteeClaim(CLAIM_NO)).thenReturn(ownedClaim());
        when(facade.retryGuaranteeClaim(CLAIM_NO)).thenReturn(ownedClaim());

        Result result = controller.retryGuaranteeClaim(CLAIM_NO);

        assertTrue(result.getSuccess());
        verify(facade).retryGuaranteeClaim(CLAIM_NO);
    }

    @Test
    void retryClaim_notFound_rejectedWithNotFound() {
        loginAs(OWNER_ID);
        when(facade.getGuaranteeClaim(CLAIM_NO)).thenReturn(null);

        Result result = controller.retryGuaranteeClaim(CLAIM_NO);

        assertEquals("申诉单不存在", result.getErrorMsg());
        verify(facade, never()).retryGuaranteeClaim(any());
    }

    // ---- 横向越权：他人申诉单的内容与进度不可探测 ----

    @Test
    void getClaim_byNonOwner_rejected() {
        loginAs(ATTACKER_ID);
        when(facade.getGuaranteeClaim(CLAIM_NO)).thenReturn(ownedClaim());

        Result result = controller.getGuaranteeClaim(CLAIM_NO);

        assertFalse(result.getSuccess());
        assertEquals("无权查看该申诉单", result.getErrorMsg());
    }

    @Test
    void getClaim_byOwner_ok() {
        loginAs(OWNER_ID);
        GuaranteeClaim claim = ownedClaim();
        when(facade.getGuaranteeClaim(CLAIM_NO)).thenReturn(claim);

        Result result = controller.getGuaranteeClaim(CLAIM_NO);

        assertTrue(result.getSuccess());
        assertSame(claim, result.getData());
    }

    // ---- 场景校验轻版（P5）：入参快速失败，不创建注定转人工的申诉单 ----

    @Test
    void submitClaim_blankPlanCode_rejected() {
        loginAs(OWNER_ID);
        GuaranteeClaimCreateRequest request = new GuaranteeClaimCreateRequest();
        request.setShopId(1L);
        request.setPlanCode(" ");

        Result result = controller.submitClaim(request);

        assertFalse(result.getSuccess());
        assertEquals("planCode不能为空", result.getErrorMsg());
        verify(facade, never()).submitGuaranteeClaim(any());
    }

    @Test
    void submitClaim_unknownShop_mappedToBusinessFail() {
        loginAs(OWNER_ID);
        GuaranteeClaimCreateRequest request = new GuaranteeClaimCreateRequest();
        request.setShopId(99L);
        request.setPlanCode("PLAN-A");
        when(facade.submitGuaranteeClaim(any()))
                .thenThrow(new IllegalArgumentException("shop not found: 99"));

        Result result = controller.submitClaim(request);

        // 应用层 shopId 存在性校验抛出的 IAE 被映射为业务失败，而不是 500
        assertFalse(result.getSuccess());
        assertEquals("shop not found: 99", result.getErrorMsg());
    }
}
