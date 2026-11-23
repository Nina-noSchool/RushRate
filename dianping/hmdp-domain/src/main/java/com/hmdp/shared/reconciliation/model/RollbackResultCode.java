package com.hmdp.shared.reconciliation.model;

/**
 * 秒杀回滚 Lua 四态返回码 —— 必须与 seckill_plus_rollback.lua 头部注释保持同一份语义。
 *
 * <p>放在 domain 层供两个调用路径共用：
 * <ul>
 *   <li>主回滚路径：{@code VoucherOrderServiceImpl.rollbackSeckillVoucherPlus}（hmdp-service）</li>
 *   <li>超时关单平行路径：{@code SeckillOrderTimeoutService.restoreRedisReservation}（hmdp-application）</li>
 * </ul>
 * 任何一处的分支判断都必须引用本枚举，不得在各自类里另写魔数，
 * 否则"是否回补"这一事实又会在多处不一致地表达——正是本次修复要消除的问题。
 * </p>
 */
public enum RollbackResultCode {

    /** 真回补：账本 DEDUCT，INCRBY 已执行，账本已覆盖为 RESTORE → 写 DB RESTORE 日志 */
    RESTORED(1),
    /** 幂等命中：账本已是 RESTORE，历史已回滚 → 不写 RESTORE（避免重复日志/假证据） */
    ALREADY_RESTORED(2),
    /** 账本查无此笔：压根没扣过 → 不写 RESTORE、不告警 */
    NO_DEDUCT(0),
    /** 异常不一致：账本 DEDUCT 但用户已不在集合（SREM=0）→ 库存已回补，但不写 RESTORE，走告警 */
    INCONSISTENT(-1);

    private final long code;

    RollbackResultCode(long code) {
        this.code = code;
    }

    public long getCode() {
        return code;
    }

    /** 把 Lua 返回码转成枚举；未知码按"无此笔"处理（保守：不写 RESTORE、不误告警） */
    public static RollbackResultCode fromCode(long code) {
        for (RollbackResultCode value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return NO_DEDUCT;
    }
}
