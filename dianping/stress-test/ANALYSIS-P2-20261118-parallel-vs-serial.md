# P2 商详聚合：并行 vs 串行 RT 对照（2026-11-18）

## 设计

- 开关：`shopdetail.parallel-enabled`（默认 true）——false 强制走 `domainService` 串行查库路径，
  与"引擎未装配"降级是**同一条代码路径**，构成单变量对照。
- 轮次：VM（4 vCPU，应用/MySQL/Redis 同机），`benchmark` profile，顺序请求 `/shop/detail/1`，
  预热 20 次 + 实测 200 次/轮，curl `time_total` 逐条采集。
- 采样归档：`results/p2_rt_parallel_20261118.txt`、`results/p2_rt_serial_20261118.txt`。

## 结果（200 采样 × 2 轮）

| 模式 | avg | p50 | p95 | max |
|---|---:|---:|---:|---:|
| 并行（任务链，DATA_PROCESSING=PARALLEL） | 29.4ms | 28.1ms | 37.5ms | 101.5ms |
| 串行（domainService 直查） | 29.9ms | 28.8ms | 38.3ms | 69.9ms |

**结论：同规模下无显著差异（avg 差 0.5ms ≈ 1.7%，噪声内）。**

## 为什么并行的"数学收益"没有显形

P1 可观测性指标（`hmdp.taskchain.node.duration`）给出了直接证据：

- 四个 DATA_PROCESSING 节点单耗时都在 **2–6ms** 量级
  （query_shop_reviews avg≈5.3ms、query_recent_blogs max≈136ms 为冷启动尖峰、
  shop_base/vouchers avg≈3ms）——串行求和 ≈ 15ms，并行理论上限 ≈ 6ms（最慢节点），
  理论差 ≈ 10ms；
- 但 29ms 的总 RT 里大头是 HTTP 往返 + 序列化 + 评分刷新触发等固定开销，且
  **商详节点内部的查询走多级缓存命中**（L1 Caffeine / L2 Redis 逻辑过期），
  缓存把节点耗时进一步压平；
- 顺序请求 + 缓存命中场景下，并行的收益窗口被固定开销与缓存吸收。

**并行的收益窗口**：节点单耗显著大于编排固定开销时（慢查询、外部接口聚合）。
本项目的真实演示场景（本地 Redis/MySQL + 缓存命中）不在窗口内——这个负向/中性结果
与虚拟线程实验（负结果归档）同一性质：**先有可信测量，再谈优化**。

## 结论的用法

- Q31 口播（已改写）："对照 RT 轮我还没跑，收益没写进简历" → 现在补上了对照：
  同规模无显著差异，收益窗口在慢源聚合场景。
- 保留开关 `shopdetail.parallel-enabled`：并行编排保留（结构上仍是正确的默认），
  对照能力随时可复跑。

## 附：P1 指标首个真实样本（并行轮，actuator 采集）

```
hmdp_taskchain_node_duration_seconds_count{node="query_shop_reviews",...} 220.0
hmdp_taskchain_node_duration_seconds_sum{node="query_shop_reviews",...} 1.164   # avg≈5.3ms
hmdp_taskchain_node_duration_seconds_max{node="query_recent_blogs",...} 0.136   # 冷启动尖峰
hmdp_taskchain_node_duration_seconds_count{node="enrich_shop_detail",...} 220.0
hmdp_taskchain_node_duration_seconds_sum{node="enrich_shop_detail",...} 0.649   # avg≈2.9ms
```
