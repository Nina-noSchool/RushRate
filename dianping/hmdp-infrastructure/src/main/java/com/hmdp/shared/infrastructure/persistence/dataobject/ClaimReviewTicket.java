package com.hmdp.shared.infrastructure.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 闪赔人工审核工单数据对象（2026-11-18，P8）。
 *
 * <p>申诉单进入 MANUAL_REVIEW 后，属主可开工单补充材料/催办；
 * 管理端回执后工单闭环，回执动作同时驱动状态机 resume 收口。</p>
 */
@Data
@TableName("tb_claim_review_ticket")
public class ClaimReviewTicket {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 申诉单号 */
    private String claimNo;

    /** 开单属主（申诉单 userId） */
    private Long userId;

    /** 工单内容（补充材料/催办说明） */
    private String content;

    /** 工单状态：OPEN / RESOLVED */
    private String ticketStatus;

    /** 人工回执说明（outcome: 说明） */
    private String resolution;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
