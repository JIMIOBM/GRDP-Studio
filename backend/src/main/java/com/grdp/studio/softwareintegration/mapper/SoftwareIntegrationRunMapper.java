package com.grdp.studio.softwareintegration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationRunEntity;
import org.apache.ibatis.annotations.Select;

public interface SoftwareIntegrationRunMapper extends BaseMapper<SoftwareIntegrationRunEntity> {
    @Select("SELECT * FROM software_integration_run WHERE id = #{id} FOR UPDATE")
    SoftwareIntegrationRunEntity selectForUpdate(long id);

    @Select("SELECT * FROM software_integration_run WHERE status = 'QUEUED' ORDER BY id ASC LIMIT 1")
    SoftwareIntegrationRunEntity selectOldestQueued();
}
