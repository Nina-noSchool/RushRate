package com.hmdp.shared.domain.compensation.spi;

/**
 * 券信息查询 SPI — 闪赔计算器按 voucherId 取赔付依据的扩展点。
 *
 * <p>领域层只认这个接口，不感知券表/订单表结构。基础设施层实现负责：
 * <ol>
 *   <li>查 tb_voucher.pay_value 拿实付金额；</li>
 *   <li>按 tb_voucher.type 路由到 tb_seckill_order 或 tb_normal_order 查核销态。</li>
 * </ol>
 *
 * <p>与 PayoutGateway 同构的设计：领域定义端口、基础设施给适配器，
 * 测试可用内存实现替换，不必起 DB。</p>
 */
public interface VoucherInfoProvider {

    /**
     * 按券 ID 查询赔付所需信息。
     *
     * @param voucherId 券 ID
     * @return 券信息；券不存在或查不到有效订单时返回 {@code null}
     */
    VoucherInfo findByVoucherId(Long voucherId);
}
