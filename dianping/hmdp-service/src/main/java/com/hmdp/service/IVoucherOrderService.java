package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillVoucherOrderMessage;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 优惠券订单服务接口
 * <p>
 * 继承 MyBatis-Plus 的 {@link IService}，提供优惠券订单相关的业务操作。
 * 包含两套秒杀方案：
 * <ul>
 *   <li><b>原始秒杀</b>：Redis Lua 扣库存 + 同步建单 + Redisson 分布式锁，适合中等并发场景</li>
 *   <li><b>秒杀Plus</b>：Redis Lua 原子校验扣减 + RocketMQ 异步建单 + 对账追踪，适合高并发场景</li>
 * </ul>
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 原始秒杀下单（同步建单版本）
     * <p>
     * 执行 Redis Lua 脚本原子校验一人一单 + 扣减库存，校验通过后同步调用
     * {@link #createVoucherOrder} 完成数据库订单插入和库存扣减。
     * </p>
     *
     * @param voucherId 秒杀优惠券 ID
     * @return 秒杀结果，成功时返回 orderId
     */
    Result seckillVoucher(Long voucherId);

    /**
     * 秒杀Plus下单（异步建单版本，推荐）
     * <p>
     * 完整链路：RedisIdWorker 生成 orderId → Redis Lua 原子校验（请求去重 + 库存 + 一人一单）
     * → 可靠 MQ 发送建单消息 → 发送延时关单消息 → 返回 orderId。
     * 2026-11-19 R2 定案：发送失败不回滚 Redis，返回"处理中"语义，三层兜底补单。
     * </p>
     * <p>2026-11-21 批次：入口增加本地售罄短路（首次库存不足后 5 分钟内不打 Redis）
     * 与令牌前置校验（开关 {@code seckill.access-token.enabled} 开启时，须持有效抢购资格令牌）。</p>
     *
     * @param voucherId  秒杀优惠券 ID
     * @param accessToken 抢购资格令牌（开关关闭时传 null）
     * @return 秒杀结果，成功时返回 orderId
     */
    Result seckillVoucherPlus(Long voucherId, String accessToken);

    /**
     * 发放抢购资格令牌（2026-11-21 批次：令牌前置授权——开抢前领资格，抢购时一次性消费）。
     * <p>同一用户对同一券重复领取时覆盖旧令牌；令牌有效期 10 分钟。</p>
     *
     * @param voucherId 秒杀优惠券 ID
     * @return 令牌字符串（写入请求头 X-Seckill-Token）
     */
    String issueAccessToken(Long voucherId);

    /**
     * 查询订单（仅限属主）
     * <p>
     * 在 {@code @Transactional} 事务中完成：一人一单校验 → DB 乐观锁扣减库存 → 订单入库。
     * 由 Redisson 分布式锁（见 {@link #handleVoucherOrder}）保证同一用户不会并发建单。
     * </p>
     * <p>2026-11-21 幽灵单治理：拦截不再静默吞单——一人一单命中 / DB 库存不足
     * 均返回失败结果，由调用方透传给用户（不再出现"假成功+幽灵订单号"）。</p>
     *
     * @param voucherOrder 待创建的优惠券订单
     * @return 建单结果：成功返回 ok；一人一单命中返回"不能重复下单"；DB 库存不足返回"库存不足"
     */
    Result createVoucherOrder(VoucherOrder voucherOrder);

    /**
     * 秒杀Plus的异步建单方法（由 MQ 消费者调用）
     * <p>
     * 在 {@code @Transactional} 事务中完成三项操作：
     * <ol>
     *   <li>插入订单（主键冲突 → {@link VoucherOrderCreateResult#DUPLICATE}，幂等处理）</li>
     *   <li>DB 乐观锁扣减库存（库存不足 → 事务回滚，返回 {@link VoucherOrderCreateResult#OUT_OF_STOCK}）</li>
     *   <li>写入 DEDUCT 类型对账日志</li>
     * </ol>
     * </p>
     *
     * @param message MQ 消息体，包含 orderId、userId、voucherId、traceId
     * @return 创建结果：CREATED（成功）、DUPLICATE（幂等忽略）、OUT_OF_STOCK（DB 库存不足）
     */
    VoucherOrderCreateResult createVoucherOrderPlus(SeckillVoucherOrderMessage message);

    /**
     * 回滚秒杀Plus订单，恢复 Redis 中已扣减的库存
     * <p>
     * 触发场景：MQ 发送失败、DB 库存不足、或其他需要回退 Redis 预扣库存的异常。
     * 通过 seckill_plus_rollback.lua 原子执行恢复，脚本内置幂等保护。
     * </p>
     *
     * @param voucherOrder 需要回滚的订单
     */
    void rollbackSeckillVoucherPlus(VoucherOrder voucherOrder);

    /**
     * 判断指定订单是否已存在于数据库
     * <p>
     * 用于超时关单前的校验、建单校验任务的轮询检查等场景。
     * </p>
     *
     * @param orderId 订单 ID
     * @return {@code true} 存在，{@code false} 不存在
     */
    boolean existsVoucherOrder(Long orderId);

    /**
     * 查询订单（仅限属主）
     * <p>
     * 为前端轮询建单结果提供只读端点：订单不存在 / 非本人订单均返回失败，
     * 不暴露他人订单信息（与申诉工作台的属主校验同一防护风格）。
     * </p>
     *
     * @param orderId 订单 ID
     * @return 属主订单实体；订单不存在返回"订单不存在"，越权返回"无权查看该订单"
     */
    Result queryVoucherOrderOwned(Long orderId);

    /**
     * Redisson 分布式锁保护下的订单创建（原始秒杀使用）
     * <p>
     * 以 {@code lock:order:{userId}} 为锁键，保证同一用户不会并发创建订单。
     * 锁内通过 Spring AOP 代理调用 {@link #createVoucherOrder} 以确保事务生效。
     * </p>
     *
     * @param voucherOrder 待创建的优惠券订单
     */
    void handleVoucherOrder(VoucherOrder voucherOrder);
}