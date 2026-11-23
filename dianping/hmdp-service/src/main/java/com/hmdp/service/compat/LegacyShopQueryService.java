package com.hmdp.service.compat;

import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;

import java.util.List;

public interface LegacyShopQueryService {

    Shop getShopById(Long shopId);

    /**
     * 走缓存查询店铺基础信息（商详链路专用）。
     * <p>复用现有 cache:shop: 多级缓存（L1 → L2 逻辑过期 → DB），
     * 与 /shop/{id} 接口、店铺更新、评分刷新共享同一 key 与失效广播，
     * 避免同一店铺出现两份缓存。只返回纯 Shop 基础对象，不含评价/博客。</p>
     */
    Shop getShopByIdCached(Long shopId);

    List<Shop> queryNearbyShops(Long typeId, Double x, Double y, int current);

    List<Voucher> getVouchersByShopId(Long shopId);
}
