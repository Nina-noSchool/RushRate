# 无忧闪赔按券值赔付改造方案（B 方案定稿）

> 状态：**已实现，全项目测试通过（245 tests，BUILD SUCCESS）**
> 日期：2026-11-24
> 目标：把「planCode 查表给 50 分」改成「按券实际支付金额全额退」，经得住面试官「券值 68 块赔 5 毛合适吗」的追问。

## 一、源码事实（方案依据，全部核实）

| 事实 | 证据 |
|---|---|
| 券值是 `tb_voucher.pay_value`，单位分 | `hmdp.sql:800` 注释「200代表2元」；券 13 `pay_value=6800`（68元）、`actual_value=15800`（面值158元） |
| 现在固定赔 50 分、上限 500 分 | `application.yaml:136-138` `FLASH_PAY: 50` / `max-payout: 500`；节点日志 `FlashPayApprovedNode:91` 明确单位是分 |
| 计算器是 domain 纯函数，无 DB 能力 | `PayoutAmountCalculator` 只持有 `Map<String,Long>` + `maxAmount` |
| payload 现在两段 `planCode|reason` | `FlashPayPayload:13,26-35`；构造点 `ShopDetailApplicationService:184` |
| claim 全链路没有 voucherId | `GuaranteeClaimCreateRequest` / domain 模型 / dataobject / `tb_shop_guarantee_claim` 均无此列 |
| 核销状态在 `tb_seckill_order` 有 `status(VERIFIED=3)` + `use_time` | `VoucherOrderStatus:30`、`VoucherOrder:64` |
| 普通券单 `tb_normal_order` 无核销字段 | `patch_split_order_tables.sql:37-45` 只有 5 列 |
| 项目里没有任何代码写 `use_time` | 全项目 grep 只在实体/枚举声明处出现 |
| 分表按 `tb_voucher.type` 分流（1秒杀/0普通） | `patch_split_order_tables.sql:47-58` |
| 老种子数据是测试行、重试 Job 开关默认关 | `hmdp.sql:498,656` 共 10+3+7 行，`application.yaml:17` `flash-pay-retry-enabled: false` |
| infrastructure 可直接用 service 层 mapper | `hmdp-infrastructure/pom.xml` 依赖 `hmdp-service` |

## 二、已定决策

| 决策点 | 选择 | 理由 |
|---|---|---|
| 业务口径 | 未核销全额退，已核销转人工；**不引入赔付比例** | 本地生活券没核销=没消费，全退是主流（美团/抖音） |
| 赔付金额来源 | `tb_voucher.pay_value`（实付 68 元），不是面值 158 元 | 「全额退」退的是用户实付，不是券面值 |
| 普通券核销字段 | 给 `tb_normal_order` 加 `status`/`use_time` | 用户选择，让普通券也能判断核销 |
| 核销写入口 | **不做核销端点**，patch SQL 里 UPDATE 一条种子订单为 VERIFIED 作演示 | 本次目标是赔付逻辑，核销端点超出范围；面试说明是演示数据 |
| maxPayout | 改成异常值兜底：调到 `100000`（1000元），超限转人工而非截断 | 截断会让「全额退」名不副实；超限转人工才是风控 |
| 核销判断位置 | `FlashPayApprovedNode` 内前置判断 | 不动状态机拓扑、不加状态枚举，改动最小 |
| 老数据处理 | 清 hmdp.sql 里的闪赔种子，代码不做 voucherId 缺失兼容分支 | 种子是测试数据、重试 Job 默认关，兼容逻辑是为不存在的场景加负担 |

## 三、改动清单

### 1. DB 层
- 新建 `db/patch_flash_pay_voucher_value.sql`：
  - `tb_shop_guarantee_claim` 加 `voucher_id BIGINT UNSIGNED NULL`
  - `tb_normal_order` 加 `status TINYINT UNSIGNED DEFAULT 2`、`use_time TIMESTAMP NULL`
  - UPDATE 一条秒杀单种子为 `status=3, use_time=NOW()` 作「已核销转人工」演示数据
- 改 `hmdp.sql`：删掉 `tb_shop_guarantee_claim` 10 行、`tb_state_task` 3 行、`tb_state_transition_log` 7 行里的闪赔种子（保留非闪赔的 CHAIN_FLOW 行）

### 2. domain 层
- `FlashPayPayload`：扩为三段 `planCode|reason|voucherId`，`deserialize` 用 `split("\\|", 3)`
- 新增 SPI `VoucherInfoProvider`：
  ```java
  VoucherInfo findByVoucherId(Long voucherId);  // null=券不存在
  ```
  `VoucherInfo`：`payValueFen`(long)、`verified`(boolean)、`payable`(boolean，PAID 且未取消/未退款)
- `PayoutAmountCalculator`：改为 `calculate(planCode, VoucherInfo, maxAmount)` 语义——
  - `voucherInfo == null` 或 `!payable` → 0（转人工）
  - `verified` → 特殊码，节点据此转人工
  - `payValueFen <= 0` → 0
  - `payValueFen > maxAmount` → 0（超限转人工，不截断）
  - 正常 → `payValueFen`
- `FlashPayApprovedNode`：算金额前查 `VoucherInfo`，按「已核销 / 券不存在 / 不可赔 / 超限 / 金额无效」分别 `NodeResult.failure(code, msg, false)` → 转人工；正常才调 `gateway.pay`
- `FlashPayStateMachineDefinition`：构造器传导 `VoucherInfoProvider`；`FlashPayCompletedNode` 的审计金额同步

### 3. infrastructure 层
- 新增 `MybatisVoucherInfoProvider implements VoucherInfoProvider`：注入 `VoucherMapper` + `VoucherOrderMapper` + `NormalOrderMapper`，按 `voucher.type` 分流查订单核销态
- `SharedEngineAutoConfiguration`：新增 provider Bean，传给 calculator 和 stateMachineRegistry

### 4. application/controller 层
- `GuaranteeClaimCreateRequest`、`GuaranteeClaim`(domain)、`GuaranteeClaim`(dataobject) 加 `voucherId`
- `ShopDetailController.submitClaim`：`claim.setVoucherId(request.getVoucherId())`
- `ShopDetailApplicationService:184`：payload 构造带 `claim.getVoucherId()`

### 5. config
- `application.yaml`：`max-payout: 100000`（1000元兜底）；`payout-rules` **彻底删除**（规则表赔 50 分是问题根源，留 fallback 等于给自己留后门，与"清老数据、不做兼容"自相矛盾）

### 6. 测试
- 重写 `PayoutAmountCalculatorTest`：覆盖券值正常/已核销/券不存在/未支付/超限/金额为 0 六种
- 新增 `MybatisVoucherInfoProviderTest`（或 domain 层内存版）覆盖分表路由
- 更新 `FlashPayStateMachineFacadeTest` 等所有 `new FlashPayPayload(...)` 调用点为三段
- 更新 `ShopDetailControllerAuthorizationTest` 等 claim 构造点

## 四、验证标准
- mvn compile 全模块通过
- 相关模块全量测试 FAIL=0 ERR=0
- 面试话术：「赔付金额来自券的实际支付金额（tb_voucher.pay_value），未核销就直接全额退；已核销的不走自动赔付、转人工判断。计算器里保留单笔上限做风控兜底，超限转人工不截断。」
