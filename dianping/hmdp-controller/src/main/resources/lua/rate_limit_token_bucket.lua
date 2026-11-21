-- 令牌桶限流（懒补充）—— 与滑动窗口限流（sliding_window.lua）互补：
--   滑动窗口 = "窗口内严格 ≤ 上限"（无突发容忍），适合用户级防刷；
--   令牌桶   = "恒速补充 + 桶容量允许突发"，适合接口全局水位 / IP 维度。
-- 细节：
--   ① Redis TIME 取服务器时钟——多实例应用时钟漂移不影响补充速率；
--   ② 补充量 clamp 到 60s——时钟跳变不会一次性灌满桶；
--   ③ hash 存 tokens + last_ms 两字段，O(1) 状态（对比 ZSET 滑窗的成员数状态）。
-- KEYS[1] = rate:token:limit:{key}
-- ARGV[1] = capacity   桶容量（突发上限）
-- ARGV[2] = ratePerSec 每秒恒速补充令牌数
-- ARGV[3] = expireMs   桶 key 空闲过期时间（防冷 key 常驻）
-- 返回：1 放行（扣掉一个令牌），0 拒绝（令牌不足）
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local ratePerSec = tonumber(ARGV[2])
local expireMs = tonumber(ARGV[3])

local now = redis.call('TIME')
local nowMillis = now[1] * 1000 + math.floor(now[2] / 1000)

local state = redis.call('HMGET', key, 'tokens', 'last_ms')
local tokens = tonumber(state[1])
local lastMs = tonumber(state[2])

if not tokens or not lastMs then
    tokens = capacity
    lastMs = nowMillis
end

local delta = nowMillis - lastMs
if delta < 0 then delta = 0 end
if delta > 60000 then delta = 60000 end
tokens = math.min(capacity, tokens + delta * ratePerSec / 1000)

local allowed
if tokens < 1 then
    allowed = 0
else
    tokens = tokens - 1
    allowed = 1
end

redis.call('HSET', key, 'tokens', tokens, 'last_ms', nowMillis)
redis.call('PEXPIRE', key, expireMs)
return allowed
