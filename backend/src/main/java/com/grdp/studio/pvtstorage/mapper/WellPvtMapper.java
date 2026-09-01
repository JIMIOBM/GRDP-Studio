package com.grdp.studio.pvtstorage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.grdp.studio.pvtstorage.entity.WellPvtEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface WellPvtMapper extends BaseMapper<WellPvtEntity> {

    /**
     * 删除 PVT 前检查它是否已经被产能试井引用。
     * 被引用的 PVT 不能直接删除，否则已保存试井会失去计算所使用的 PVT 快照来源。
     */
    @Select("""
            SELECT COUNT(*)
            FROM project_well_productivity_test
            WHERE pvt_id = #{pvtId}
            """)
    long countProductivityTestReferences(@Param("pvtId") long pvtId);
}
