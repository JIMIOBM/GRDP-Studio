package com.grdp.studio.pvtstorage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.grdp.studio.pvtstorage.entity.WellHeadLookupEntity;

/**
 * 根据页面上下文定位唯一井记录。
 * 不能只按井名查询，因为不同项目或气藏中可能存在同名井。
 */
public interface WellHeadLookupMapper extends BaseMapper<WellHeadLookupEntity> {
}
