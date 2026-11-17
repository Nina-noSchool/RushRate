package com.hmdp.shopdetail.application.model.request;

import lombok.Data;

/**
 * 保障申诉工单回执请求体（2026-11-18，P8）：管理端对工单/申诉给出人工结论。
 */
@Data
public class ClaimTicketResolveRequest {

    /** 人工结论：APPROVED（按申诉赔付）/ REJECTED（驳回申诉） */
    private String outcome;

    /** 回执说明 */
    private String resolution;
}
