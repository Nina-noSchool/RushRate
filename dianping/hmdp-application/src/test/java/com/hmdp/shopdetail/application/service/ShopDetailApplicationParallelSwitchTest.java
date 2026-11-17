package com.hmdp.shopdetail.application.service;

import com.hmdp.shopdetail.application.task.ShopDetailTaskContext;
import com.hmdp.shopdetail.domain.service.ShopDetailDomainService;
import com.hmdp.shoprating.service.ShopRatingRefreshService;
import com.hmdp.shared.domain.taskchain.enums.ExecutionMode;
import com.hmdp.shared.domain.taskchain.enums.ProcessPhase;
import com.hmdp.shared.domain.taskchain.model.PhaseConfig;
import com.hmdp.shared.domain.taskchain.service.TaskChainEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 商详聚合并行开关（P2 对照实验）单测：parallelEnabled 决定走任务链并行还是串行降级路径。
 * 开关语义与"引擎未装配"降级共用同一条串行代码路径——保证对照实验是单变量。
 */
class ShopDetailApplicationParallelSwitchTest {

    private ShopDetailDomainService domainService;
    private ShopRatingRefreshService refreshService;
    private TaskChainEngine engine;
    private ShopDetailApplicationService service;

    @BeforeEach
    void setUp() {
        domainService = mock(ShopDetailDomainService.class);
        refreshService = mock(ShopRatingRefreshService.class);
        engine = mock(TaskChainEngine.class);
        service = new ShopDetailApplicationService(domainService, refreshService);
        setField(service, "taskChainEngine", engine);
    }

    @Test
    void parallelEnabled_true_routesThroughEngineInParallelMode() {
        setField(service, "parallelEnabled", true);

        service.getShopDetail(1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PhaseConfig>> captor = ArgumentCaptor.forClass(List.class);
        verify(engine).executePhaseChain(eq("shop_detail"), any(ShopDetailTaskContext.class), captor.capture());
        List<PhaseConfig> configs = captor.getValue();
        assertEquals(2, configs.size());
        assertEquals(ProcessPhase.DATA_PROCESSING, configs.get(0).getPhase());
        assertEquals(ExecutionMode.PARALLEL, configs.get(0).getExecutionMode());
        assertEquals(ProcessPhase.POST_PROCESSING, configs.get(1).getPhase());
        assertEquals(ExecutionMode.SERIAL, configs.get(1).getExecutionMode());
        verify(domainService, never()).getShopDetail(1L);
    }

    @Test
    void parallelEnabled_false_forcesSerialFallbackPath() {
        setField(service, "parallelEnabled", false);

        service.getShopDetail(1L);

        // 开关关闭 → 引擎一次都不碰，走 domainService 串行路径
        verify(engine, never()).executePhaseChain(anyString(), any(ShopDetailTaskContext.class), any());
        verify(domainService).getShopDetail(1L);
    }

    @Test
    void engineMissing_serialFallbackStillWorks() {
        // 引擎未装配（默认构造）→ 串行降级路径可用，接口不因引擎缺失而不可用
        service = new ShopDetailApplicationService(domainService, refreshService);
        setField(service, "parallelEnabled", true);

        service.getShopDetail(1L);

        verify(domainService).getShopDetail(1L);
        assertTrue(true);
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("字段注入失败: " + name, e);
        }
    }
}
