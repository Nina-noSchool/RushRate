-- ============================================================================
-- 秒杀Plus回滚Lua脚本：回退Redis中的库存扣减和用户标记
--
-- 触发场景：
--   1. MQ消息发送失败 → 需要回退Lua脚本已扣减的Redis库存
--   2. DB库存不足    → 建单失败，需要恢复Redis库存供其他用户抢购
--   3. 超时未支付    → 取消订单，释放库存
--
-- 参数说明：
--   ARGV[1] voucherId   - 优惠券ID
--   ARGV[2] userId      - 用户ID
--   ARGV[3] orderId     - 订单ID
--   ARGV[4] traceId     - 对账追踪ID
--   ARGV[5] ttlSeconds  - 追踪日志过期时间（秒）
--   ARGV[6] nowMillis   - 当前时间戳（毫秒）
--
-- Redis Key 说明：
--   seckill:stock:{voucherId}        - 秒杀库存
--   seckill:order:{voucherId}        - 已下单用户集合
--   seckill:req:{voucherId}:{userId}  - 请求去重标记
--   seckill:trace:log:{voucherId}    - 对账追踪日志（Hash，field=traceId）
--
-- 返回值（四态，Java 侧 VoucherOrderServiceImpl 必须用同一份语义）：
--    1  - 真回补了：账本 DEDUCT，INCRBY 已执行，账本已覆盖为 RESTORE
--    2  - 幂等命中：账本已是 RESTORE，历史已回滚，无需操作
--    0  - 账本查无此笔：没有 DEDUCT 记录，压根没扣过，无需回补
--   -1  - 异常不一致：账本是 DEDUCT 但用户已不在集合中（removed=0）。
--         库存仍已回补、账本仍已覆盖为 RESTORE；但集合成员与账本对不上，
--         Java 侧必须走 rollback_failure_log + 告警，不能静默吞掉。
--
-- 幂等与回补依据：
--   以 trace 账本（HGET traceLogKey traceId）的 logType 为唯一依据，
--   不再用 SREM 返回值推断"扣减是否发生过"。Set 成员与库存计数是两个独立状态，
--   会被独立修改（主从切换丢标记/运维操作），代理关系不可靠。
--   账本 field=traceId 单值覆盖：同一 traceId 只可能是 DEDUCT 或 RESTORE 之一。
-- ============================================================================

local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]
local traceId = ARGV[4]
local ttlSeconds = tonumber(ARGV[5])
local nowMillis = ARGV[6]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
local requestKey = 'seckill:req:' .. voucherId .. ':' .. userId
local traceLogKey = 'seckill:trace:log:' .. voucherId

-- 1) 读账本，判断这笔是 DEDUCT / RESTORE / 不存在
local previousTrace = redis.call('hget', traceLogKey, traceId)
local previous = nil
if previousTrace then
    previous = cjson.decode(previousTrace)
end

-- 2) 幂等命中：账本已是 RESTORE → 历史回滚过，正常跳过
if previous and previous['logType'] == 'RESTORE' then
    redis.call('del', requestKey)
    return 2
end

-- 3) 账本查无此笔：没有扣减可回补（异常调用，但无库存影响）
if not previous then
    redis.call('del', requestKey)
    return 0
end

-- 4) 账本是 DEDUCT → 真回补。依据账本而非 SREM 返回值决定是否 INCRBY。
local removed = redis.call('srem', orderKey, userId)
local currentStock = tonumber(redis.call('get', stockKey))
if currentStock == nil then
    currentStock = 0
end
local beforeQty = currentStock
local afterQty = beforeQty + 1
redis.call('incrby', stockKey, 1)

-- 账本覆盖为 RESTORE，标记本次回滚（同一 field 覆盖写）
local logEntry = cjson.encode({
    traceId = traceId,
    orderId = orderId,
    userId = userId,
    voucherId = voucherId,
    beforeQty = beforeQty,
    changeQty = 1,
    afterQty = afterQty,
    logType = 'RESTORE',
    ts = nowMillis
})
redis.call('hset', traceLogKey, traceId, logEntry)
if ttlSeconds ~= nil and ttlSeconds > 0 then
    redis.call('expire', traceLogKey, ttlSeconds)
end

-- 清理请求去重标记，允许用户后续重试
redis.call('del', requestKey)

-- 5) removed=0 说明集合成员与账本对不上（Redis 防线失效的信号）。
--    库存已回补、账本已覆盖，但必须让 Java 侧告警，不能静默。
if removed == 0 then
    return -1
end
return 1
