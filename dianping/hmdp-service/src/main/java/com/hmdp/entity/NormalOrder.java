package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hmdp.shared.reconciliation.model.VoucherOrderStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 普通券订单实体（2026-11-21 业务分表）。
 *
 * <p>普通代金券（type=0）常态销售、允许同用户重复购买，走旧端点同步直插，
 * 不进入 MQ/对账/关单域 —— 因此只保留最小字段集，
 * 且不带 uk_user_voucher 唯一约束（一人一单拦截按券类型分层已跳过 type=0）。</p>
 *
 * <p>秒杀域订单见 {@link VoucherOrder}（tb_seckill_order）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_normal_order")
public class NormalOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 — RedisIdWorker 预生成（IdType.INPUT），与秒杀单共用同一发号器，全局不冲突 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 下单的用户ID */
    private Long userId;

    /** 购买的券ID */
    private Long voucherId;

    /** 下单时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /**
     * 订单状态 — 2026-11-24 闪赔按券值赔付改造补加。
     * 原瘦表无此列导致普通券无法判断核销态，只能一律转人工。
     */
    private VoucherOrderStatus status;

    /** 核销时间 — 与 status=VERIFIED 等价，双写便于排查 */
    private LocalDateTime useTime;
}
