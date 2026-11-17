package com.hmdp.shopdetail.application.model.request;

import lombok.Data;

/**
 * 保障申诉开工单请求体（2026-11-18，P8）：属主为 MANUAL_REVIEW 申诉补充材料/催办。
 */
@Data
public class ClaimTicketRequest {

    /** 工单内容（补充材料/催办说明） */
    private String content;
}
