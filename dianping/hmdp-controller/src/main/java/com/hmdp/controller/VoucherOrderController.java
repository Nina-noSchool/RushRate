package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

/**
 * 优惠券订单控制器
 * <p>
 * 处理优惠券下单相关的 HTTP 请求，包括普通秒杀和基于 Redis + Lua 的高性能秒杀两种方式。
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    /** 优惠券订单服务 */
    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * 普通秒杀优惠券接口
     * <p>
     * 基于 Redisson 分布式锁实现的秒杀方案，适用于并发量中等的场景。
     * </p>
     *
     * @param voucherId 优惠券 ID
     * @return 秒杀结果
     */
    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return stringifyOrderId(voucherOrderService.seckillVoucher(voucherId));
    }

    /**
     * 高性能秒杀优惠券接口
     * <p>
     * 基于 Redis + Lua 脚本保证原子性校验，结合消息队列异步下单，支持更高的并发量。
     * </p>
     *
     * @param voucherId 优惠券 ID
     * @return 秒杀结果
     */
    @PostMapping("seckill-plus/{id}")
    public Result seckillVoucherPlus(@PathVariable("id") Long voucherId) {
        return stringifyOrderId(voucherOrderService.seckillVoucherPlus(voucherId));
    }

    /**
     * 订单号 Long → String 下发。
     * <p>2026-11-21：雪花订单号超出 JS Number 安全整数范围（2^53 ≈ 9.0e15），
     * 直接序列化为 number 会被浏览器 JSON.parse 丢精度（弹窗/轮询拿到错号）；
     * 统一转字符串下发，前端展示与回传均为精确值。</p>
     */
    private Result stringifyOrderId(Result result) {
        if (result.getSuccess() && result.getData() instanceof Long) {
            return Result.ok(String.valueOf(result.getData()));
        }
        return result;
    }

    /**
     * 订单查询接口（仅限属主）
     * <p>
     * 配合秒杀Plus的"处理中"语义：前端拿到订单号后轮询本接口确认订单建成。
     * 订单不存在 / 非本人订单均返回失败，不暴露他人订单。
     * </p>
     *
     * @param id 订单 ID
     * @return 属主订单实体（含状态）；否则失败结果
     */
    @GetMapping("{id}")
    public Result queryOrderById(@PathVariable("id") Long id) {
        return voucherOrderService.queryVoucherOrderOwned(id);
    }
}