-- 抢购资格令牌（一次性消费）：GET 比对后 DEL——防重复使用、防伪造
-- 与令牌发放（SET seckill:access:token:{voucherId}:{userId} = token，TTL 10min）配套。
-- KEYS[1] = seckill:access:token:{voucherId}:{userId}
-- ARGV[1] = 请求携带的令牌
-- 返回：1 消费成功（放行），0 无令牌/令牌不符
local saved = redis.call('GET', KEYS[1])
if not saved or saved ~= ARGV[1] then
    return 0
end
redis.call('DEL', KEYS[1])
return 1
