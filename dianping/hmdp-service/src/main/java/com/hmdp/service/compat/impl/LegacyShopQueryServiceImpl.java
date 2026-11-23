package com.hmdp.service.compat.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.service.compat.LegacyShopQueryService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.service.impl.VoucherServiceImpl;
import com.hmdp.utils.SystemConstants;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Collections;
import java.util.List;

@Service
public class LegacyShopQueryServiceImpl implements LegacyShopQueryService {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private VoucherServiceImpl voucherService;

    @Override
    public Shop getShopById(Long shopId) {
        return shopService.getById(shopId);
    }

    @Override
    public Shop getShopByIdCached(Long shopId) {
        // 复用多级缓存链路（L1 + L2 逻辑过期 + DB 回源），但跳过布隆守卫：
        // 布隆只在启动/每日 rebuild 时全量同步，新增店铺在下次 rebuild 前会被误判不存在，
        // 商详不能因此返回空页；穿透防护由 loadOnMiss 的空值标记 + 互斥锁兜底。
        // 直接与 cache:shop: 共享同一 key，与 /shop/{id}、店铺更新、评分刷新同一失效链路。
        // 返回的纯 Shop 基础对象与 getShopById 的 DO 结构一致，tags/vouchers 仍现算。
        return shopService.queryShopNoBloomGuard(shopId);
    }

    @Override
    public List<Shop> queryNearbyShops(Long typeId, Double x, Double y, int current) {
        if (typeId == null) {
            return Collections.emptyList();
        }
        if (x != null && y != null) {
            Object data = shopService.queryShopByType(typeId.intValue(), current, x, y).getData();
            return data instanceof List ? (List<Shop>) data : Collections.<Shop>emptyList();
        }
        Page<Shop> page = shopService.query()
                .eq("type_id", typeId)
                .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
        return page.getRecords();
    }

    @Override
    public List<Voucher> getVouchersByShopId(Long shopId) {
        Object data = voucherService.queryVoucherOfShop(shopId).getData();
        return data instanceof List ? (List<Voucher>) data : Collections.<Voucher>emptyList();
    }
}
