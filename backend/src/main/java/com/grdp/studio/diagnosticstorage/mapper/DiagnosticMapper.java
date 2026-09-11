package com.grdp.studio.diagnosticstorage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.grdp.studio.diagnosticstorage.entity.DiagnosticEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DiagnosticMapper extends BaseMapper<DiagnosticEntity> {
}
