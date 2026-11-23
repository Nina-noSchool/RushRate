# 秒杀回滚 SREM=0 静默失败修复方案

> 目标：让"回滚到底有没有真的发生"这一个事实，在 **Lua 返回值 / Java 调用方 / DB 对账日志** 三处用同一套语义表达。
> 状态：**已落地**（2026-11-23）。全量单测 120 个全绿（新增 RollbackResultCodeTest 4 个）。核对源码日期 2026-11-23。

---

## 一、问题本质与现状证据

### 1.1 缺陷本质

同一个事实（库存是否真的回补了）被三处**不一致**记录：

| 层 | 现状 | 后果 |
|---|---|---|
| Lua 返回值 | 幂等命中 `return 0`（rollback.lua:46），`removed=0` 穿透到末尾 `return 0`（:82），两者与真正回补的路径返回值相同 | 协议层信息丢失，调用方无法区分 |
| Java 调用方 | `executeGovernanceRollback()` 是 `void`，丢弃 `execute()` 返回值（VoucherOrderServiceImpl:716-727） | 没人接返回值 |
| DB 对账日志 | `rollbackSeckillVoucherPlus()` 无条件 `saveRestoreReconcileLog`（:576）；超时关单 `restoreRedisReservation()` 无条件 insert RESTORE（SeckillOrderTimeoutService:115） | **制造假证据**：没回补也写 RESTORE |

关键点：**Lua 侧的 RESTORE 本来就是条件化的**（hset 包在 `if removed == 1` 内，rollback.lua:55-79），假证据只出在 Java 侧的 DB 对账日志。所以返回值三态的意义是"让 Java 知道该不该写 DB 的 RESTORE"，不是"让 Lua 停止写假日志"。

### 1.2 根因（治本层）

回滚脚本用 `SREM` 返回值推断"扣减有没有发生过"（`removed==1 ⇒ INCRBY`）。正向 Lua 里 `incrby`（seckill_plus.lua:68）与 `sadd`（:69）原子同批，所以默认"用户不在集合 ⇒ 没扣过"。但 Set 成员和库存计数是**两个独立状态**，会被独立修改（TTL 过期、其他流程移出、超时关单），代理关系一断就出错。

真正的一手事实是 trace 账本：`seckill:trace:log:{voucherId}` Hash，field=traceId，记录 DEDUCT / RESTORE。

### 1.3 账本的两个结构约束（设计前必读）

1. **单 field 覆盖式**：正向扣减写 DEDUCT（seckill_plus.lua:83），回滚写 RESTORE（rollback.lua:75），**同一个 traceId 只能同时是其中一种**，不会并存两条。所以"trace 是 DEDUCT"可以作为"有笔扣减未冲正"的判据。
2. **有 TTL**：正向与回滚都 `expire traceLogKey ttlSeconds`，`SECKILL_TRACE_TTL_SECONDS = 2 * 24 * 60 * 60L`（2 天，VoucherOrderServiceImpl:144）；超时关单路径也是 2 天（SeckillOrderTimeoutService:112）。账本过期后 `HGET` 查不到——**不能把"查不到账本"直接判成异常**，否则超时关单/延迟回滚会制造一批假告警。

### 1.4 受影响的全部调用点（4 处回滚 + 3 处 RESTORE 写入）

回滚调用点：
1. `VoucherOrderServiceImpl:409`（建单事务 catch 分支）→ `rollbackSeckillVoucherPlus`
2. `SeckillPlusOrderListener:208`（OUT_OF_STOCK 分支）
3. `SeckillPlusOrderListener:242`（USER_VOUCHER_CONFLICT 分支）
4. `OrderCreateRecoveryService:189` / `SeckillReconcileCompensationService:149`（恢复/补偿链路）

RESTORE 对账日志写入点：
1. `VoucherOrderServiceImpl.saveRestoreReconcileLog`（:757-772），被 `rollbackSeckillVoucherPlus` 无条件调用
2. `SeckillOrderTimeoutService.restoreRedisReservation`（:115-124），**独立路径，不经过 executeGovernanceRollback**
3. Lua 内 `hset`（条件化，无需改）

---

## 二、返回值协议（四处必须使用同一份定义）

建议**四态**（比原方案三态多一态，把"本来就不用回补"和"账本查无此笔"分开）：

| 码 | 含义 | Java 侧动作 |
|---|---|---|
| `1` | 真回补了（账本 DEDUCT，removed=1，INCRBY 已执行，已写 RESTORE 覆盖账本） | 写 DB RESTORE 日志 |
| `2` | 幂等命中（账本已是 RESTORE，历史已回滚） | 不写 RESTORE，正常返回 |
| `0` | 账本查无此笔（无 DEDUCT，压根没扣过） | 不写 RESTORE，不告警（异常调用但无库存影响，可 INFO 留痕） |
| `-1` | 异常不一致（账本 DEDUCT 但用户已不在集合，`removed=0`） | 不写 RESTORE，走 `RollbackFailureService.record` 告警 |

> 若坚持三态，可把 `0`/`2` 合并为"无需回补"，但必须在注释里写清"无需回补≠异常"。四态更利于排查，推荐四态。
> **务必在 Lua 头部注释和 Java 常量/枚举里各写一份，并标注"两处必须一致"。**

---

## 三、止血层：三处联动（改动必成组，缺一处白搭）

### 3.1 Lua 脚本：`seckill_plus_rollback.lua`

改写主体逻辑（现 40-82 行）：

```lua
-- 1) 先读账本，判断这笔到底是 DEDUCT / RESTORE / 不存在
local previousTrace = redis.call('hget', traceLogKey, traceId)
local previous = nil
if previousTrace then previous = cjson.decode(previousTrace) end

-- 2) 幂等命中：账本已是 RESTORE → 正常跳过，清标记，return 2
if previous and previous['logType'] == 'RESTORE' then
    redis.call('del', requestKey)
    return 2
end

-- 3) 账本查无此笔 → 没有扣减可回补，清标记，return 0
if not previous then
    redis.call('del', requestKey)
    return 0
end

-- 4) 账本是 DEDUCT → 真回补（不依赖 SREM 返回值作为"是否发生过"的依据）
local removed = redis.call('srem', orderKey, userId)
local currentStock = tonumber(redis.call('get', stockKey)) or 0
local beforeQty = currentStock
redis.call('incrby', stockKey, 1)     -- 账本说扣过，就回补，不看 removed
local logEntry = cjson.encode({ ... logType = 'RESTORE', ... })
redis.call('hset', traceLogKey, traceId, logEntry)
if ttlSeconds ~= nil and ttlSeconds > 0 then
    redis.call('expire', traceLogKey, ttlSeconds)
end
redis.call('del', requestKey)

-- 5) 用户在不在集合不再决定回补与否，但 removed=0 是"账本与集合不一致"的信号
if removed == 0 then
    return -1     -- 已回补库存，但集合成员对不上 → 暴露防线失效，交告警
end
return 1
```

要点：
- 把"是否回补"的依据从 `removed` 换成**账本 logType**，这是治本层，建议止血时就一起做（分两次改会让中间态更乱）。
- `removed=0` 不再吞掉回补，而是**回补照做 + 返回 -1 告警**；避免"账本说扣过、集合说没扣"时既不回补又不告警的三不管。
- 所有 return 分支前都要 `del requestKey`，不要漏。
- TTL 处理：步骤 4 覆盖写 RESTORE 后重新 `expire`，保持 2 天窗口。

### 3.2 主路径：`VoucherOrderServiceImpl`

1. **新增返回值常量或枚举**（类内 `private static final long RB_*`，或复用 domain 里新建 enum），与 Lua 的四态一一对应。
2. **`executeGovernanceRollback`（:716）`void` → `long`**，把 `stringRedisTemplate.execute(...)` 的返回值 return 出去。注意 RedisTemplate 返回 `Long`，可能为 `null`，要 `== null ? 0 : val` 兜底。
3. **`rollbackSeckillVoucherPlus`（:574-577）按码分支**：

```java
@Override
public void rollbackSeckillVoucherPlus(VoucherOrder voucherOrder) {
    long code = executeGovernanceRollback(voucherOrder);
    if (code == RB_RESTORED) {                 // 1
        saveRestoreReconcileLog(voucherOrder, ReconcileBusinessType.FAIL, "rollback triggered");
    } else if (code == RB_INCONSISTENT) {      // -1
        rollbackFailureService.record(RollbackFailureService.SOURCE_MAIN_CHAIN,
                voucherOrder, "TRACE_DEDUCT_BUT_SREM_ZERO", 1, null);
    }
    // 2 = 幂等命中，0 = 账本无此笔：均不写 RESTORE（消灭假证据）
}
```

4. 注入 `RollbackFailureService`（该类在 hmdp-service 模块，同模块可直接 `@Resource`）。

### 3.3 平行路径：`SeckillOrderTimeoutService.restoreRedisReservation`（:97-125）

它自己 new 了 `DefaultRedisScript`，**不经过 executeGovernanceRollback**，必须单独改：

- 接住 `execute` 返回值（同样 `null` 兜底）；
- 只有 `1` 才执行 115-124 行的 RESTORE insert；
- `-1` 走 `rollbackFailureService.record(SOURCE_TIMEOUT, ...)`，需新增 source 常量（可选，但建议）；
- `2`/`0` 不写 RESTORE。

> 漏掉这条，超时关单的假证据仍在，第三向扫描继续被污染。

### 3.4 第三处"联动"的真实范围

止血不是只改三处，而是**四个动作成组**：Lua 返回值语义化 + 主路径接值写日志 + 超时路径接值写日志 + 告警出口接线。DB 侧 RESTORE 写入点只有前两条路径（见 1.4），改完这两条即封堵所有假证据。

---

## 四、告警层（复用现成件，不重复造）

- `RollbackFailureService.record(source, order, resultCode, retryAttempts, cause)` 现成可用（:55 起）：内部已 `metricsRecorder.incRollbackFailure()`（:58）+ 落 `tb_rollback_failure_log` + `alert_status=PENDING` + error 日志。
- `-1` 分支直接调它，`resultCode` 用 `TRACE_DEDUCT_BUT_SREM_ZERO`。
- 可选新增 `SOURCE_TIMEOUT = "TIMEOUT"` 常量区分超时关单来源。
- 告警规则侧确认 `rollback_failure_total` 指标已被采集（Prometheus 已有该指标名），否则"记录准确了但没人处理"。

---

## 五、对账层配套（确认 + 少量改动）

第三向扫描 `SeckillReconcileScanService.scanTraceLogs` 现状（:243-270）：

- `logType != DEDUCT`（含回滚覆盖写成的 RESTORE）→ 跳过；
- 按 traceId 查 DB 有 RESTORE 日志 → 跳过；
- DB 有任何日志（DEDUCT/RESTORE 任一）→ 跳过；
- Redis 有 DEDUCT 且 DB 完全无痕 → 生成幽灵预占工单。

**改动后的影响**：

1. 修复后"真回补"会写 RESTORE（Redis 覆盖 + DB 新增），扫描第一/二道过滤就能跳过，**行为不变**。
2. `-1` 场景：Lua 已回补库存并写 RESTORE 覆盖账本，但 DB 不写 RESTORE、只写 `tb_rollback_failure_log`。扫描看到 Redis 是 RESTORE → 直接跳过，**不会误报幽灵预占**，无需改扫描逻辑。
3. `0`/`2` 场景：账本查无此笔或已 RESTORE，扫描同样跳过。
4. **不引入 `ROLLBACK_SKIPPED` 日志类型**——理由：一旦写进 `voucher_reconcile_log`，`scanDeductLogs`（以 DEDUCT 为主，:130-155）和 `scanTraceLogs` 都要新增对它的过滤分支，收益仅是"留痕"，而 failure_log 已经留痕了。保持 `ReconcileLogType` 只有 `DEDUCT`/`RESTORE` 两个值。
5. 建议在 `scanTraceLogs` 的跳过注释里补一行："RESTORE 现在只由 Lua 真回补时写，DB 侧 RESTORE 只由 Java 按 Lua 返回值写，不再无条件生成。"

**`VoucherOrderCreateResult.ROLLBACK_FAILED` 预留枚举**（:15-18 注释明确"为 rollbackSeckillVoucherPlus 改为返回值驱动做准备"）：本次改动正好用上这个预留语义。Listener 的 ROLLBACK_FAILED 分支（:222-225）当前是死代码，**不要**和 Lua 的四态混用——它是"建单结果"层，Lua 四态是"回滚执行"层，两者不同层。可选增强：Listener 的两个回滚调用点（:208、:242）现在靠 try-catch 接异常，改后 `rollbackSeckillVoucherPlus` 内部已处理 `-1`，异常路径保持不变即可。

---

## 六、TTL 与账本过期的处理（防假告警）

账本 TTL = 2 天。回滚可能发生在扣减后很久（超时关单 15 分钟 + 兜底 Job，或延迟补偿），但都在 2 天内。仍需防御：

- 账本过期 → `HGET` 返回 nil → Lua 走 `return 0`（账本无此笔，不回补不告警）。
- **风险**：极端情况下账本过期但库存确实被扣过，此时不回补会留下幽灵预占。
- **兜底**：第三向扫描本就是"Redis 有 DEDUCT、DB 无痕"的检测，但账本过期后 Redis 侧也没痕了，扫描也看不到。
- **对策（本次不做，记录为后续项）**：要么让账本 TTL 显著大于最长回滚窗口（当前 2 天 vs 15 分钟关单，已有充足余量）；要么对超时关单走"DB 订单存在即回补"的第二判据（SeckillOrderTimeoutService 已有 DB 订单，天然可判）。**本次方案在 2 天 TTL 前提下安全，不改。**

---

## 七、测试清单（必须覆盖）

| 场景 | 期望 |
|---|---|
| 正常回滚（账本 DEDUCT + 用户在集合） | Lua `1`；Redis 库存 +1；账本覆盖为 RESTORE；DB 写 RESTORE；无 failure_log |
| 幂等重复回滚（账本已 RESTORE） | Lua `2`；库存不变；DB 不写第二条 RESTORE |
| 账本查无此笔 | Lua `0`；库存不变；不写 RESTORE、不告警 |
| **静默失败场景**（账本 DEDUCT + 用户不在集合） | Lua `-1`；**库存仍 +1**；DB **不写** RESTORE；**写** failure_log + 指标 +1 |
| 超时关单路径正常 | restoreRedisReservation 接 `1` → 写 RESTORE（businessType=TIMEOUT） |
| 超时关单路径异常 | 接 `-1` → 不写 RESTORE，写 failure_log |
| 第三向扫描 | 修复后 RESTORE 仍被正确跳过；`-1` 场景不误报幽灵预占 |

单测落点建议：新增 `RollbackSremZeroTest`（Lua 返回值四态）与 `RollbackFailureService` 调用验证；改动 `SeckillPlusOrderListenerConflictTest` / `...RecoveryTest` 的 mock 断言（若方法签名变）。

---

## 八、落地顺序与验证

1. **先止血成组改**：Lua 四态 + 主路径接值 + 超时路径接值 + 告警接线（3.1~3.4 + 四）。治本层（按账本判断）已在 3.1 的 Lua 改写里一并完成，不拆两次。
2. **跑单测**（上表七类），重点验证"静默失败场景库存仍回补 + 假 RESTORE 消失"。
3. **跑三向扫描回归**：构造 `-1` 工单，确认不误报幽灵预占。
4. **灰度观察**：`rollback_failure_total` 指标上线后先看 24h 基线——如果 `-1` 大量出现，说明 Set 成员与账本不一致是常态，要回查"谁在独立修改 Set"（超时关单、TTL、主从切换），而不是简单告警。

## 九、一句话方案

让"是否回补"在 Lua 返回值、Java 调用方、DB 日志三处用同一套语义：Lua 按**账本 logType**（而非 SREM 返回值）决定是否回补并返回四态，Java 只有收到"真回补"才写 DB RESTORE、异常态走现成的 `RollbackFailureService` 告警；同时补齐超时关单的平行路径，保持第三向扫描对 RESTORE 的既有过滤不变。
