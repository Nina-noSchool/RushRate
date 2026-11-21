package com.hmdp.mapper;

import com.hmdp.entity.NormalOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 普通券订单 Mapper（2026-11-21 业务分表）—— 旧端点同步直插 + 属主查询双表回退用。
 */
public interface NormalOrderMapper extends BaseMapper<NormalOrder> {

}
