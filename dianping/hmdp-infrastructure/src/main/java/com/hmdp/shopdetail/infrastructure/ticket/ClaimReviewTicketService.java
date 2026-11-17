package com.hmdp.shopdetail.infrastructure.ticket;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.shared.domain.compensation.service.FlashPayStateMachineFacade;
import com.hmdp.shared.infrastructure.persistence.dataobject.ClaimReviewTicket;
import com.hmdp.shared.infrastructure.persistence.mapper.ClaimReviewTicketMapper;
import com.hmdp.shared.stateengine.model.StateTask;
import com.hmdp.shared.stateengine.spi.StateTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 闪赔人工审核工单服务（2026-11-18，P8 最小版）。
 *
 * <p>职责：申诉单进入 MANUAL_REVIEW 后的沟通与回执闭环——</p>
 * <ul>
 *   <li><b>open</b>：属主为申诉单开工单（补充材料/催办说明）；</li>
 *   <li><b>findByClaimNo</b>：按申诉单号查工单流水；</li>
 *   <li><b>resolve</b>：管理端回执（APPROVED/REJECTED）——更新 OPEN 工单为已回执，
 *       并驱动状态机 resume 收口（claimForResume CAS 保证并发安全；
 *       回执的动作语义是"人工处理完毕、从状态机视角关闭"，见 Q32 追问 3）。</li>
 * </ul>
 *
 * <p>状态机联动守卫：仅当该 claimNo 在状态引擎里有任务时才 resume；
 * 没有任务（如引擎装配前的历史申诉）只记工单回执，不凭空创建状态任务。</p>
 */
@Slf4j
@Service
public class ClaimReviewTicketService {

    /** 管理端回执的合法结论 */
    public static final String OUTCOME_APPROVED = "APPROVED";
    public static final String OUTCOME_REJECTED = "REJECTED";

    private final ClaimReviewTicketMapper ticketMapper;
    private final StateTaskRepository stateTaskRepository;

    /** 状态机门面：回执时驱动 MANUAL_REVIEW 任务收口（共享引擎未装配时为 null，跳过联动） */
    @Autowired(required = false)
    private FlashPayStateMachineFacade flashPayStateMachineFacade;

    public ClaimReviewTicketService(ClaimReviewTicketMapper ticketMapper,
                                    StateTaskRepository stateTaskRepository) {
        this.ticketMapper = ticketMapper;
        this.stateTaskRepository = stateTaskRepository;
    }

    /**
     * 属主开工单。
     *
     * @throws IllegalArgumentException content 为空
     */
    public ClaimReviewTicket open(String claimNo, Long userId, String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("工单内容不能为空");
        }
        ClaimReviewTicket ticket = new ClaimReviewTicket();
        ticket.setClaimNo(claimNo);
        ticket.setUserId(userId);
        ticket.setContent(content);
        ticket.setTicketStatus("OPEN");
        ticket.setCreatedAt(LocalDateTime.now());
        ticket.setUpdatedAt(LocalDateTime.now());
        ticketMapper.insert(ticket);
        return ticket;
    }

    /** 按申诉单号查工单流水（创建时间升序）。 */
    public List<ClaimReviewTicket> findByClaimNo(String claimNo) {
        QueryWrapper<ClaimReviewTicket> wrapper = new QueryWrapper<>();
        wrapper.eq("claim_no", claimNo).orderByAsc("created_at");
        return ticketMapper.selectList(wrapper);
    }

    /**
     * 管理端回执：更新该申诉单下所有 OPEN 工单为 RESOLVED，并驱动状态机 resume 收口。
     *
     * @param outcome    人工结论：APPROVED（按申诉赔付）/ REJECTED（驳回申诉）
     * @param resolution 回执说明
     * @throws IllegalArgumentException outcome 非法
     */
    public void resolve(String claimNo, String outcome, String resolution) {
        if (!OUTCOME_APPROVED.equals(outcome) && !OUTCOME_REJECTED.equals(outcome)) {
            throw new IllegalArgumentException("outcome 必须为 APPROVED 或 REJECTED: " + outcome);
        }
        LocalDateTime now = LocalDateTime.now();
        for (ClaimReviewTicket ticket : findByClaimNo(claimNo)) {
            if ("OPEN".equals(ticket.getTicketStatus())) {
                ticket.setTicketStatus("RESOLVED");
                ticket.setResolution(outcome + ": " + (resolution == null ? "" : resolution));
                ticket.setUpdatedAt(now);
                ticketMapper.updateById(ticket);
            }
        }
        // 状态机联动：仅当该申诉单在状态引擎里有任务时才 resume
        // （resume 内部 claimForResume CAS：MANUAL_REVIEW→RUNNING→ManualReviewNode 收口；
        //   非 MANUAL_REVIEW 状态的任务抢占会失败，原样返回，无副作用）
        StateTask task = stateTaskRepository.findByTaskId(claimNo);
        if (task != null && flashPayStateMachineFacade != null) {
            log.info("claim review ticket resolved, driving state machine, claimNo={}, outcome={}",
                    claimNo, outcome);
            flashPayStateMachineFacade.startOrResume(claimNo, task.getBizKey(), null);
        }
    }
}
