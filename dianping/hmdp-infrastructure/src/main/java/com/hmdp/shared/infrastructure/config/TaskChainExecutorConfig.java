package com.hmdp.shared.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 任务链线程池配置
 * <p>
 * 为任务链引擎提供两个独立的线程池，避免 CPU 密集型和 IO 密集型任务互相抢占资源：
 * <ul>
 *   <li>taskChainCpuExecutor：固定 4 线程，用于纯内存计算节点（CPU_BOUND）——
 *       虚拟线程对 CPU 密集任务无收益，此池始终为平台线程；</li>
 *   <li>taskChainIoExecutor：数据库/缓存/RPC 查询节点（IO_BOUND）——
 *       {@code concurrency.virtual-threads.enabled=true} 时切换为虚拟线程
 *       per-task executor（无队列无池化），否则维持固定 8 线程历史行为。</li>
 * </ul>
 * 开关语义见 docs/VIRTUAL-THREADS.md；关闭时行为与历史版本分毫不变。
 * <p>
 * <b>为什么这里保留 Executors.newFixedThreadPool（无界 LinkedBlockingQueue），不改成有界队列 + CallerRuns</b>：
 * 2026-11-23 做过对照实验（详见 dianping/stress-test/ANALYSIS-20261123-taskchain-bounded-vs-unbounded.md）：
 * 同一份源码只换线程池构造，200 并发 × 4000 请求打商详，各跑两轮。
 * 无界基线 P50 801/887ms、P99 1315/1378ms、堆 382/373MB；
 * 有界 200/256 + CallerRuns 版 P50 809/839ms、P99 2044/1745ms、堆 270/96MB。
 * 结论：有界队列只换来堆下降（防 OOM），P99 反而劣化约 30~55%，吞吐和正确性无差异（均 4000/4000）。
 * 原因是 CallerRuns 在队列满时让提交线程（Tomcat 请求线程）自己跑节点任务，
 * 等待只是从"池内队列"搬到"Tomcat accept 队列"，总 RT 没降；真正瓶颈在下游 Hikari/DB，
 * 与 docs/VIRTUAL-THREADS.md 里 Phase D 的发现同源——换排队位置解决不了下游慢。
 * <p>
 * 保留无界的两个前提（改动前须重新评估）：
 * <ol>
 *   <li>提交方受限，堆涨不上去：商详链路由 Tomcat 线程驱动，每线程最多在途 4 个节点，
 *       上限约 Tomcat 线程数 × 4；评分刷新链路有 lock 串行。4000 并发实测无界版堆仅 382MB，
 *       未触发 OOM；</li>
 *   <li>背压靠下游容量而非队列长度：IO 池固定 8 已对齐 Hikari 默认 max 10，
 *       并发上限被下游连接池间接锁住；要降延迟应优化下游（DB/连接池），不是给队列加界。</li>
 * </ol>
 * 代价需明确：下游异常变慢时，无界队列会让延迟层层放大且堆无上限，这是当前接受的取舍。
 * 若将来提交方不再受限（例如新增无节流的异步批量入口），必须重做本对照实验再决定。
 * </p>
 */
@Configuration
public class TaskChainExecutorConfig {

    @Bean("taskChainCpuExecutor")
    public Executor taskChainCpuExecutor() {
        return Executors.newFixedThreadPool(4);
    }

    @Bean("taskChainIoExecutor")
    public Executor taskChainIoExecutor(
            @Value("${concurrency.virtual-threads.enabled:false}") boolean virtualThreadsEnabled) {
        return virtualThreadsEnabled
                ? Executors.newVirtualThreadPerTaskExecutor()
                : Executors.newFixedThreadPool(8);
    }
}
