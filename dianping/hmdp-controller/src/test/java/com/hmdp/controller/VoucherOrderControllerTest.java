package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VoucherOrderControllerTest {

    private MockMvc mockMvc;

    private IVoucherOrderService voucherOrderService;

    @BeforeEach
    void setUp() {
        voucherOrderService = mock(IVoucherOrderService.class);
        VoucherOrderController controller = new VoucherOrderController();
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "voucherOrderService", voucherOrderService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void seckillVoucherPlus_shouldDelegateVoucherIdToService() throws Exception {
        when(voucherOrderService.seckillVoucherPlus(7L)).thenReturn(Result.ok(101L));

        mockMvc.perform(post("/voucher-order/seckill-plus/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                // 2026-11-21：雪花订单号超出 JS 安全整数（2^53），控制器统一转 String 下发防丢精度
                .andExpect(jsonPath("$.data").value("101"));

        verify(voucherOrderService).seckillVoucherPlus(7L);
    }

    @Test
    void seckillVoucher_shouldDelegateAndStringifyOrderId() throws Exception {
        when(voucherOrderService.seckillVoucher(9L)).thenReturn(Result.ok(662273077891039235L));

        mockMvc.perform(post("/voucher-order/seckill/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("662273077891039235"));

        verify(voucherOrderService).seckillVoucher(9L);
    }

    @Test
    void queryOrderById_shouldDelegateToOwnedQuery() throws Exception {
        com.hmdp.entity.VoucherOrder order = new com.hmdp.entity.VoucherOrder();
        order.setId(101L);
        order.setUserId(9L);
        order.setVoucherId(7L);
        when(voucherOrderService.queryVoucherOrderOwned(101L)).thenReturn(Result.ok(order));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/voucher-order/101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.voucherId").value(7));

        verify(voucherOrderService).queryVoucherOrderOwned(101L);
    }
}
