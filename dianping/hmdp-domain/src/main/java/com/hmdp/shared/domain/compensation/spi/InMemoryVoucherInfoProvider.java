package com.hmdp.shared.domain.compensation.spi;

import java.util.HashMap;
import java.util.Map;

/**
 * 内存版券信息查询 — 测试与 createDefault 简单场景用，免 DB。
 *
 * <p>可预设每个 voucherId 的券信息；未预设的券返回 null（视作不存在，走 NO_VOUCHER 转人工）。</p>
 */
public class InMemoryVoucherInfoProvider implements VoucherInfoProvider {

    private final Map<Long, VoucherInfo> store = new HashMap<>();

    /** 预设一张券的信息 */
    public InMemoryVoucherInfoProvider put(Long voucherId, VoucherInfo info) {
        store.put(voucherId, info);
        return this;
    }

    /** 快捷预设：可赔券（实付金额分） */
    public InMemoryVoucherInfoProvider putPayable(Long voucherId, long payValueFen) {
        return put(voucherId, new VoucherInfo(payValueFen, false, true));
    }

    @Override
    public VoucherInfo findByVoucherId(Long voucherId) {
        return store.get(voucherId);
    }
}
