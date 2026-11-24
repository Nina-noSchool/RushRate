package com.hmdp.shared.domain.compensation.amount;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 赔付报价 — 计算器的返回值，把「能赔多少」和「为什么不能赔」合并成一个对象。
 *
 * <p>为什么不用裸 long：0 既可能是「券值 0」也可能是「已核销不赔」，
 * 节点需要区分不同原因给出不同的错误码和人工提示。用 long 会把语义压扁。</p>
 */
@Getter
@AllArgsConstructor
public class PayoutQuote {

    /** 是否可自动赔付 */
    private final boolean payable;

    /** 赔付金额（分）；不可赔时为 0 */
    private final long amountFen;

    /** 不可赔原因码（APPROVED 节点据此转人工），可赔时为 null */
    private final String rejectCode;

    /** 不可赔的人话说明，可赔时为 null */
    private final String rejectMessage;

    /** 可赔报价 */
    public static PayoutQuote pay(long amountFen) {
        return new PayoutQuote(true, amountFen, null, null);
    }

    /** 不可赔：带错误码和说明 */
    public static PayoutQuote reject(String code, String message) {
        return new PayoutQuote(false, 0L, code, message);
    }
}
