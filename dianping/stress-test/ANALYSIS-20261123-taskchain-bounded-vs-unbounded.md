# 任务链线程池 有界 vs 无界 对比压测（2026-11-23）

## 结论先行

**改动（无界→有界队列 + CallerRuns）在延迟上没有收益，反而劣化；只在 JVM 堆占用上有明显下降。**
按项目"实测无收益 + 知道为什么就是完整结论"的纪律，**不建议把这次改动作为默认配置合入**，除非目标明确是"防 OOM"而非"降延迟"。

## 场景

- 接口：`GET /shop/detail/1`，商详链路（DATA_PROCESSING 4 个 IO 节点并行 + POST_PROCESSING 串行）。
- 该接口**没有令牌桶注解、没有滑动窗口拦截器**（滑动窗口只拦 seckill-plus），登录拦截器放行，因此能直接高并发打；无外部限流干扰。
- JMeter：200 并发线程 × 20 循环 = 4000 请求，ramp-up 3s，keepalive。
- 两轮（REPEATS=2），每轮独立启动应用、预热 50 次、独立结果目录。
- 环境：litmus-vm（4 核 / 11G），JDK21 平台线程，`concurrency.virtual-threads.enabled=false`。
- 变量控制：两个 jar 由同一份源码打出，唯一差异是 `TaskChainExecutorConfig` 里线程池构造（git show HEAD 取改前版本打基线 jar，打完立即恢复改动版）。
  - UNBOUNDED：`Executors.newFixedThreadPool(4/8)`，无界 LinkedBlockingQueue
  - BOUNDED：显式 `ThreadPoolExecutor(4/4, queue 200)` / `(8/8, queue 256)` + `CallerRunsPolicy` + 命名线程工厂

## 数据

| 轮次 | 版本 | 成功 | AVG | P50 | P95 | P99 | MAX | 堆占用(末尾) |
|---|---|---|---|---|---|---|---|---|
| r1 | UNBOUNDED | 4000/4000 | 735.5ms | 801 | 1108 | **1315** | 1793 | 382MB |
| r1 | BOUNDED | 4000/4000 | 856.9ms | 809 | 1761 | **2044** | 2545 | 270MB |
| r2 | UNBOUNDED | 4000/4000 | 835.6ms | 887 | 1210 | **1378** | 1675 | 373MB |
| r2 | BOUNDED | 4000/4000 | 849.4ms | 839 | 1560 | **1745** | 2359 | 96MB |

对照：低并发 smoke（20 线程 ×10，200 请求）P50=28ms、P99=56ms。

## 解读

1. **延迟没收敛，反而升高约 30~55%（P99）**：CallerRunsPolicy 在队列满时让**提交线程自己跑节点任务**。这里的提交线程是 Tomcat 请求线程，它本来该接新连接，现在被拉去执行 IO 节点（查 DB/Redis），导致 accept 队列积压、请求排队——**等待从"池内队列"搬到了"Tomcat 的 accept 队列"**，总 RT 没有减少，只是换了个地方排队。这与 `docs/VIRTUAL-THREADS.md` Phase D 的实测发现同源："队列从 accept 队列搬进了处理管线内部"，瓶颈在下游而不在线程池排队位置。

2. **堆占用显著下降（382MB→270MB，r2 更明显到 96MB）**：这是有界队列唯一确定的收益——无界 LinkedBlockingQueue 在下游变慢时会无限堆积任务对象，有界队列把堆积量封顶。这是"防 OOM"价值，不是"降延迟"价值。

3. **吞吐/正确性无差异**：两版都是 4000/4000、零错误，说明在当前并发下无界队列还没堆到 OOM，CallerRuns 也没导致任务丢失。

4. **P50 基本持平（801 vs 809 / 887 vs 839）**：说明大部分请求根本没触及队列满的边界，差异集中在长尾——正是被 CallerRuns 接管的那部分请求。

## 建议

- **如果目标是降延迟**：这次改动无效，应回退。真正的瓶颈在下游（Hikari 连接池、DB 行锁、syncSend），不在任务链线程池。可考虑的方向见 VIRTUAL-THREADS.md 的 R-2/R-6 分析。
- **如果目标是防 OOM、给延迟设定上限**：可以保留，但要接受 P99 劣化的代价，或者换 `AbortPolicy` + 快速失败（让请求直接失败而不是拖长尾），这取决于业务对"慢成功"和"快失败"的取舍。
- **若保留改动**：当前验证只覆盖了"200 并发正常下游"，**还没有真正注入下游延迟**（arthas 手动 attach 或 DB 慢查询），队列满的极端场景未测；且 CallerRuns 触发次数没有埋点，无法证明阈值设得是否合理。这两项是下次实验的前提。

## 产物

- jar：`hmdp-start-taskchain-UNBOUNDED-20261123.jar`（sha256 862402005019fe5069c95c856e5a82cb8df63d8006310c4ed7f143aa61ce1bb9）
  `hmdp-start-taskchain-bounded-20261123.jar`（sha256 dc6afee58a68ce71f060780c90b0de57e929e51c26a61e9cb78500d81909316d）
- jmx：`/tmp/shop_detail_bench.jmx`；驱动脚本：`~/hmdp-mq/bench_taskchain.sh`
- 结果目录：`~/hmdp-mq/bench-taskchain/results/{formal-UNBOUNDED,formal-BOUNDED,formal-UNBOUNDED-r2,formal-BOUNDED-r2}/`
