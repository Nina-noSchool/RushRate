package com.hmdp.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hmdp.entity.Shop;
import com.hmdp.metrics.MetricsRecorder;
import com.hmdp.utils.MultiLevelCacheService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * 店铺更新的事务后驱逐接线测试（Mockito，不起 Spring 容器）。
 *
 * <p>覆盖四类语义：
 * ① 提交成功才驱逐——提交前 L1/Redis/广播都不能动，提交后各一次；
 * ② 回滚不驱逐——数据没变，缓存也不该动；
 * ③ 提交后驱逐失败被完全吞掉——已提交的事务不能对外表现为失败，只告警 + 打点；
 * ④ 无事务上下文立即驱逐——不能因为拿不到事务就跳过删除。</p>
 */
class ShopServiceImplUpdateTransactionTest {

    private static final String SHOP_KEY_PREFIX = RedisConstants.CACHE_SHOP_KEY;
    private static final String INVALIDATION_DESTINATION =
            MultiLevelCacheService.CACHE_INVALIDATION_TOPIC + ":" + MultiLevelCacheService.CACHE_INVALIDATION_TAG;

    private ShopServiceImpl shopService;
    private MultiLevelCacheService multiLevelCacheService;
    private MetricsRecorder metricsRecorder;
    private Logger shopServiceLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        multiLevelCacheService = mock(MultiLevelCacheService.class);
        metricsRecorder = mock(MetricsRecorder.class);

        // updateById 走 MyBatis-Plus ServiceImpl，直接 new 出来的实例没有 baseMapper；
        // spy + doReturn 只验证事务钩子接线，不触发真实 SQL
        shopService = spy(new ShopServiceImpl());
        doReturn(true).when(shopService).updateById(any(Shop.class));
        ReflectionTestUtils.setField(shopService, "multiLevelCacheService", multiLevelCacheService);
        ReflectionTestUtils.setField(shopService, "metricsRecorder", metricsRecorder);

        shopServiceLogger = (Logger) LoggerFactory.getLogger(ShopServiceImpl.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        shopServiceLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        shopServiceLogger.detachAppender(logAppender);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.clear();
    }

    @Test
    void shouldEvictAndBroadcastOnlyAfterCommit() {
        // 用真实 MultiLevelCacheService + mock 底层，验证 "evict 与 broadcast 各一次"
        Cache<String, Object> localCache = Caffeine.newBuilder().maximumSize(10).build();
        localCache.put(SHOP_KEY_PREFIX + 13L, new RedisData());
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);

        MultiLevelCacheService realCache = new MultiLevelCacheService();
        ReflectionTestUtils.setField(realCache, "shopLocalCache", localCache);
        ReflectionTestUtils.setField(realCache, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(realCache, "rocketMQTemplate", rocketMQTemplate);

        ShopServiceImpl service = spy(new ShopServiceImpl());
        doReturn(true).when(service).updateById(any(Shop.class));
        ReflectionTestUtils.setField(service, "multiLevelCacheService", realCache);
        ReflectionTestUtils.setField(service, "metricsRecorder", metricsRecorder);

        TransactionSynchronizationManager.initSynchronization();
        service.update(new Shop().setId(13L));

        // 提交前：L1、Redis、广播一个都不能动
        assertNotNull(localCache.getIfPresent(SHOP_KEY_PREFIX + 13L));
        verify(redisTemplate, never()).delete(anyString());
        verify(rocketMQTemplate, never()).syncSend(anyString(), (Object) any());

        TransactionSynchronizationUtils.triggerAfterCommit();

        // 提交后：L1 失效、Redis 删除、广播发送各一次
        assertNull(localCache.getIfPresent(SHOP_KEY_PREFIX + 13L));
        verify(redisTemplate, times(1)).delete(SHOP_KEY_PREFIX + 13L);
        verify(rocketMQTemplate, times(1)).syncSend(INVALIDATION_DESTINATION, "13");
    }

    @Test
    void shouldNotEvictWhenTransactionRollsBack() {
        TransactionSynchronizationManager.initSynchronization();

        shopService.update(new Shop().setId(13L));
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(multiLevelCacheService, never()).evictAndBroadcast(anyString(), any());
    }

    @Test
    void shouldSwallowEvictFailureAfterCommit() {
        doThrow(new IllegalStateException("redis down"))
                .when(multiLevelCacheService).evictAndBroadcast(anyString(), any());
        TransactionSynchronizationManager.initSynchronization();

        shopService.update(new Shop().setId(13L));

        // 事务已提交，驱逐异常既不能外抛，也不能反向标记事务失败
        assertDoesNotThrow(TransactionSynchronizationUtils::triggerAfterCommit);
        verify(metricsRecorder, times(1)).incCacheEvictFailure();
        assertTrue(logAppender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN),
                "驱逐失败必须留下 warn 日志");
    }

    @Test
    void shouldEvictImmediatelyWithoutTransaction() {
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());

        shopService.update(new Shop().setId(13L));

        // 没有事务可等，就必须当场删——跳过删除会让缓存一直脏到逻辑过期
        verify(multiLevelCacheService, times(1)).evictAndBroadcast(SHOP_KEY_PREFIX, 13L);
    }

    @Test
    void shouldBroadcastOncePerShopWithinSameTransaction() {
        TransactionSynchronizationManager.initSynchronization();

        shopService.update(new Shop().setId(13L));
        shopService.update(new Shop().setId(13L));
        shopService.update(new Shop().setId(14L));

        verify(multiLevelCacheService, never()).evictAndBroadcast(anyString(), any());

        TransactionSynchronizationUtils.triggerAfterCommit();

        // 同一店铺只广播一次；不同店铺各一次
        verify(multiLevelCacheService, times(1)).evictAndBroadcast(SHOP_KEY_PREFIX, 13L);
        verify(multiLevelCacheService, times(1)).evictAndBroadcast(SHOP_KEY_PREFIX, 14L);
        verifyNoMoreInteractions(multiLevelCacheService);
    }
}
