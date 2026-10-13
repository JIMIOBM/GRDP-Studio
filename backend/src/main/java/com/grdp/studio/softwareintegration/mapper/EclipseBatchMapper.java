package com.grdp.studio.softwareintegration.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.grdp.studio.softwareintegration.entity.EclipseBatchEntity;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
public interface EclipseBatchMapper extends BaseMapper<EclipseBatchEntity> {
    @Select("SELECT id FROM software_integration_project WHERE id=(SELECT project_id FROM software_integration_model WHERE id=(SELECT model_id FROM software_integration_model_version WHERE id=#{versionId})) AND deleted_at IS NULL FOR UPDATE")
    Long lockActiveProjectForVersion(long versionId);
    @Select("SELECT CONCAT(status,':',COALESCE(result_contract,'')) FROM software_integration_run WHERE id=#{runId} AND model_version_id=#{versionId}")
    String runState(@Param("runId") long runId,@Param("versionId") long versionId);
}
