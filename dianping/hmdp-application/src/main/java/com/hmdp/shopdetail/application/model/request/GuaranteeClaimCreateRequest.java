package com.hmdp.shopdetail.application.model.request;

import lombok.Data;

@Data
public class GuaranteeClaimCreateRequest {

    private Long shopId;
    private String planCode;
    private String reason;
    /** 关联券 ID — 闪赔按该券实付金额赔付 */
    private Long voucherId;
}
