-- 1.参数列表
--1.1.优惠券id
local voucherId=ARGV[1]
--1.2.用户id
local userId=ARGV[2]
--1.3.订单id
local orderId=ARGV[3]
--1.4.是否限购一张（2026-11-19）：'1'=限购（秒杀券，稀缺性防黄牛）
--                     '0'=不限购（普通代金券是常态销售，允许同用户重复购买）
-- 不传时默认 '1'，向后兼容历史调用
local limitOne=ARGV[4] or '1'

-- 2.数据key
--2.1.库存key
local stockKey='seckill:stock:' .. voucherId
--2.2.订单key
local orderKey='seckill:order:' .. voucherId

-- 3.脚本业务
--3.1.判断库存是否充足
local stock = tonumber(redis.call('get', stockKey))
if stock == nil then
    --print("库存获取失败: " .. stockKey)
    return -1
end

if (stock<= 0) then
    --3.2.库存不足，返回1
    return 1
end
--3.3.判断用户是否下单（仅限购券校验；普通代金券允许同用户重复购买）
if limitOne == '1' and (redis.call('sismember',orderKey,userId)==1) then
    --3.4.存在，说明重复下单，返回2
    return 2
end
-- 3.5.扣库存 incrby stockKey -1
redis.call('incrby',stockKey,-1)
-- 3.6.下单(保存)用户 sadd orderKey userId
redis.call('sadd',orderKey,userId)
-- 3.7. 发消息到消息队列中 XADD  stream.orders * k1 v1 k2 v2
redis.call('xadd','stream.orders','*', 'userId',userId,'voucherId',voucherId,'id',orderId)