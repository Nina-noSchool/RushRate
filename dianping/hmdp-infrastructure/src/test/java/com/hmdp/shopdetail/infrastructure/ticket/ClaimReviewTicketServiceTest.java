package com.hmdp.shopdetail.infrastructure.ticket;

import com.hmdp.shared.domain.compensation.service.FlashPayStateMachineFacade;
import com.hmdp.shared.infrastructure.persistence.dataobject.ClaimReviewTicket;
import com.hmdp.shared.infrastructure.persistence.mapper.ClaimReviewTicketMapper;
import com.hmdp.shared.stateengine.model.StateTask;
import com.hmdp.shared.stateengine.spi.StateTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 闪赔人工审核工单服务测试（P8）：开单持久化、回执更新 OPEN 工单、
 * 状态机联动守卫（任务存在才 resume）。
 */
class ClaimReviewTicketServiceTest {

    private ClaimReviewTicketMapper ticketMapper;
    private StateTaskRepository stateTaskRepository;
    private FlashPayStateMachineFacade facade;
    private ClaimReviewTicketService service;

    @BeforeEach
    void setUp() {
        ticketMapper = mock(ClaimReviewTicketMapper.class);
        stateTaskRepository = mock(StateTaskRepository.class);
        facade = mock(FlashPayStateMachineFacade.class);
        service = new ClaimReviewTicketService(ticketMapper, stateTaskRepository);
        setField(service, "flashPayStateMachineFacade", facade);
    }

    private StateTask manualReviewTask(String claimNo) {
        StateTask task = StateTask.create(claimNo, "shop-1", "FLASH_PAY", "MANUAL_REVIEW", "FLASH_PAY|reason");
        task.markRunning();
        task.markManualReview("need human");
        return task;
    }

    @Test
    void open_persistsTicketWithOpenStatus() {
        ClaimReviewTicket ticket = service.open("claim-1", 7L, "补充材料：维修单据照片");

        ArgumentCaptor<ClaimReviewTicket> captor = ArgumentCaptor.forClass(ClaimReviewTicket.class);
        verify(ticketMapper).insert(captor.capture());
        assertEquals("claim-1", captor.getValue().getClaimNo());
        assertEquals(7L, captor.getValue().getUserId());
        assertEquals("OPEN", captor.getValue().getTicketStatus());
        assertEquals("OPEN", ticket.getTicketStatus());
    }

    @Test
    void open_blankContent_throws() {
        assertThrows(IllegalArgumentException.class, () -> service.open("claim-1", 7L, " "));
        verify(ticketMapper, never()).insert(any(ClaimReviewTicket.class));
    }

    @Test
    void resolve_invalidOutcome_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.resolve("claim-1", "MAYBE", "note"));
        verify(ticketMapper, never()).updateById(any(ClaimReviewTicket.class));
    }

    @Test
    void resolve_updatesOpenTickets_andDrivesStateMachineWhenTaskExists() {
        ClaimReviewTicket open = new ClaimReviewTicket();
        open.setClaimNo("claim-1");
        open.setUserId(7L);
        open.setTicketStatus("OPEN");
        when(ticketMapper.selectList(any())).thenReturn(List.of(open));
        when(stateTaskRepository.findByTaskId("claim-1")).thenReturn(manualReviewTask("claim-1"));

        service.resolve("claim-1", "APPROVED", "材料齐全，按申诉赔付");

        ArgumentCaptor<ClaimReviewTicket> captor = ArgumentCaptor.forClass(ClaimReviewTicket.class);
        verify(ticketMapper).updateById(captor.capture());
        assertEquals("RESOLVED", captor.getValue().getTicketStatus());
        assertTrue(captor.getValue().getResolution().startsWith("APPROVED: "));
        // MANUAL_REVIEW 任务存在 → 状态机联动 resume 收口
        verify(facade).startOrResume(eq("claim-1"), eq("shop-1"), any());
    }

    @Test
    void resolve_noStateMachineTask_recordsResolutionOnly() {
        ClaimReviewTicket open = new ClaimReviewTicket();
        open.setClaimNo("claim-1");
        open.setTicketStatus("OPEN");
        when(ticketMapper.selectList(any())).thenReturn(List.of(open));
        when(stateTaskRepository.findByTaskId("claim-1")).thenReturn(null);

        service.resolve("claim-1", "REJECTED", "证据不足");

        // 无状态任务（如引擎装配前的历史申诉）→ 只记工单回执，不凭空创建状态任务
        verify(ticketMapper).updateById(any(ClaimReviewTicket.class));
        verify(facade, never()).startOrResume(any(), any(), any());
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
