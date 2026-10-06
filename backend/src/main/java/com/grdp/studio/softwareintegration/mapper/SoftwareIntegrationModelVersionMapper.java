package com.grdp.studio.softwareintegration.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.grdp.studio.softwareintegration.entity.SoftwareIntegrationModelVersionEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SoftwareIntegrationModelVersionMapper extends BaseMapper<SoftwareIntegrationModelVersionEntity> {
    // Current read: an enclosing REPEATABLE READ transaction may already have a stale snapshot.
    @Select("SELECT version_no FROM software_integration_model_version WHERE model_id = #{modelId} ORDER BY version_no DESC LIMIT 1 FOR UPDATE")
    Integer selectLatestVersionNoForUpdate(@Param("modelId") long modelId);
}
