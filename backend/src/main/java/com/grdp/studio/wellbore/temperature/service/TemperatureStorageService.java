package com.grdp.studio.wellbore.temperature.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.grdp.studio.common.BusinessException;
import com.grdp.studio.pvtstorage.entity.WellHeadLookupEntity;
import com.grdp.studio.pvtstorage.entity.WellPvtEntity;
import com.grdp.studio.pvtstorage.mapper.WellHeadLookupMapper;
import com.grdp.studio.pvtstorage.mapper.WellPvtMapper;
import com.grdp.studio.wellbore.temperature.dto.TemperatureRecordDetail;
import com.grdp.studio.wellbore.temperature.dto.TemperatureRecordSummary;
import com.grdp.studio.wellbore.temperature.dto.TemperatureSaveRequest;
import com.grdp.studio.wellbore.temperature.entity.TemperatureProfileEntity;
import com.grdp.studio.wellbore.temperature.entity.WellTemperatureEntity;
import com.grdp.studio.wellbore.temperature.mapper.TemperatureProfileMapper;
import com.grdp.studio.wellbore.temperature.mapper.WellTemperatureMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class TemperatureStorageService {
    private final WellHeadLookupMapper wells;
    private final WellPvtMapper pvts;
    private final WellTemperatureMapper records;
    private final TemperatureProfileMapper profiles;
    private final ObjectMapper json;
    private final TemperatureService calculator;

    public TemperatureStorageService(
            WellHeadLookupMapper wells,
            WellPvtMapper pvts,
            WellTemperatureMapper records,
            TemperatureProfileMapper profiles,
            ObjectMapper json,
            TemperatureService calculator
    ) {
        this.wells = wells;
        this.pvts = pvts;
        this.records = records;
        this.profiles = profiles;
        this.json = json;
        this.calculator = calculator;
    }

    public List<TemperatureRecordSummary> list(
            long projectId,
            long gasReservoirId,
            String wellName
    ) {
        long wellId = wellId(projectId, gasReservoirId, wellName);
        return records.selectList(
                        new LambdaQueryWrapper<WellTemperatureEntity>()
                                .eq(WellTemperatureEntity::getWellId, wellId)
                                .orderByDesc(WellTemperatureEntity::getTemperatureNo)
                ).stream()
                .map(TemperatureRecordSummary::from)
                .toList();
    }

    public TemperatureRecordDetail detail(
            long id,
            long projectId,
            long gasReservoirId,
            String wellName
    ) {
        long wellId = wellId(projectId, gasReservoirId, wellName);
        WellTemperatureEntity record = records.selectOne(
                new LambdaQueryWrapper<WellTemperatureEntity>()
                        .eq(WellTemperatureEntity::getId, id)
                        .eq(WellTemperatureEntity::getWellId, wellId)
        );
        if (record == null) {
            throw new BusinessException(404, "未找到当前井温度方案");
        }
        return detailByWell(id, wellId);
    }

    @Transactional
    public TemperatureRecordDetail save(TemperatureSaveRequest save) {
        if (save == null || save.calculation == null) {
            throw new BusinessException(400, "缺少温度计算参数");
        }

        var request = save.calculation;
        long wellId = wellId(
                required(request.projectId, "项目"),
                required(request.gasReservoirId, "气藏"),
                request.wellName
        );
        validatePvt(request.propertySource, request.pvtId, wellId);

        var result = calculator.calculate(request);
        WellTemperatureEntity entity = new WellTemperatureEntity();
        entity.setWellId(wellId);
        entity.setPvtId(request.pvtId);
        entity.setTemperatureNo(nextNo(wellId));
        entity.setTemperatureName(name(save.temperatureName, "温度方案"));
        entity.setModelCode(request.tempModel.toUpperCase(Locale.ROOT));
        entity.setPropertySource(
                "PVT".equalsIgnoreCase(request.propertySource) ? "PVT" : "MANUAL"
        );
        // 井口/井底选项只改变页面名称，算法与现有数据库边界约束均保持井口基准。
        entity.setBoundaryPosition("wellhead");
        entity.setStatus("calculated");
        entity.setDepthM(request.depth);
        entity.setStepM(request.step);
        entity.setIdTubingMm(request.idTubing);
        entity.setTempGradientCPer100m(request.tGrad);
        entity.setAngleDeg(request.angle);
        entity.setGasSpecificGravity(request.gammaG);
        entity.setLiquidDensityKgM3(request.rhoL);
        entity.setSurfaceTemperatureC(request.tSurf);
        entity.setBoundaryTemperatureC(request.tWh);
        entity.setReferencePressureMpa(request.referencePressure);
        entity.setQGas1e4M3d(request.qGas);
        entity.setQLiqM3d(request.qLiq);
        entity.setUToWM2k(request.uTo);
        entity.setWallMm(request.wallMm);
        entity.setMuJtKMpa(request.muJt);
        entity.setCpGasJKgk(request.cpGas);
        entity.setFormationKWmk(request.formationK);
        entity.setFormationRhocpMjM3k(request.formationRhoCp);
        if (result.thermal() != null) {
            entity.setRelaxationDistanceM(result.thermal().relaxationDistance());
            entity.setDimensionlessTime(result.thermal().dimensionlessTime());
            entity.setTimeFunction(result.thermal().timeFunction());
            entity.setMixtureHeatCapacityJKgk(result.thermal().mixtureHeatCapacity());
        }
        entity.setBottomFluidTemperatureC(result.inferredBottomTemperature());
        entity.setPredictedWellheadTemperatureC(result.predictedWellheadTemperature());
        entity.setJtGradient(0d);
        entity.setGravityGradient(result.gravityGradient());
        entity.setInputJson(write(request));
        entity.setResultSummaryJson(write(result));
        entity.setRemark(blank(save.remark));
        records.insert(entity);

        for (int index = 0; index < result.depth().size(); index++) {
            TemperatureProfileEntity point = new TemperatureProfileEntity();
            point.setTemperatureId(entity.getId());
            point.setPointNo(index);
            point.setDepthM(result.depth().get(index));
            point.setFluidTemperatureC(result.temp().get(index));
            point.setFormationTemperatureC(result.tempFormation().get(index));
            profiles.insert(point);
        }
        return detailByWell(entity.getId(), wellId);
    }

    @Transactional
    public void delete(long id, long projectId, long gasReservoirId, String wellName) {
        long wellId = wellId(projectId, gasReservoirId, wellName);
        long count = records.selectCount(
                new LambdaQueryWrapper<WellTemperatureEntity>()
                        .eq(WellTemperatureEntity::getId, id)
                        .eq(WellTemperatureEntity::getWellId, wellId)
        );
        if (count == 0) {
            throw new BusinessException(404, "未找到当前井温度方案");
        }
        try {
            records.deleteById(id);
        } catch (Exception exception) {
            throw new BusinessException(409, "该温度方案已被压力折算记录引用，无法删除");
        }
    }

    public TemperatureRecordDetail detailByWell(long id, long wellId) {
        WellTemperatureEntity record = records.selectOne(
                new LambdaQueryWrapper<WellTemperatureEntity>()
                        .eq(WellTemperatureEntity::getId, id)
                        .eq(WellTemperatureEntity::getWellId, wellId)
        );
        List<TemperatureProfileEntity> profile = profiles.selectList(
                new LambdaQueryWrapper<TemperatureProfileEntity>()
                        .eq(TemperatureProfileEntity::getTemperatureId, id)
                        .orderByAsc(TemperatureProfileEntity::getPointNo)
        );
        return new TemperatureRecordDetail(record, profile);
    }

    private void validatePvt(String propertySource, Long pvtId, long wellId) {
        if (!"PVT".equalsIgnoreCase(propertySource)) return;
        if (pvtId == null) {
            throw new BusinessException(400, "PVT物性来源必须选择PVT方案");
        }
        WellPvtEntity pvt = pvts.selectOne(
                new LambdaQueryWrapper<WellPvtEntity>()
                        .eq(WellPvtEntity::getId, pvtId)
                        .eq(WellPvtEntity::getWellId, wellId)
        );
        if (pvt == null) {
            throw new BusinessException(400, "所选PVT不属于当前井");
        }
    }

    private long wellId(long projectId, long gasReservoirId, String wellName) {
        if (wellName == null || wellName.isBlank()) {
            throw new BusinessException(400, "井名不能为空");
        }
        List<WellHeadLookupEntity> matches = wells.selectList(
                new LambdaQueryWrapper<WellHeadLookupEntity>()
                        .eq(WellHeadLookupEntity::getProjectId, projectId)
                        .eq(WellHeadLookupEntity::getProjectGasReservoirId, gasReservoirId)
                        .eq(WellHeadLookupEntity::getWellName, wellName.trim())
        );
        if (matches.size() != 1) {
            throw new BusinessException(
                    matches.isEmpty() ? 404 : 409,
                    "当前项目、气藏和井名无法唯一定位井记录"
            );
        }
        return matches.getFirst().getId();
    }

    private int nextNo(long wellId) {
        return records.selectList(
                        new LambdaQueryWrapper<WellTemperatureEntity>()
                                .eq(WellTemperatureEntity::getWellId, wellId)
                ).stream()
                .map(WellTemperatureEntity::getTemperatureNo)
                .filter(Objects::nonNull)
                .max(Integer::compare)
                .orElse(0) + 1;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new BusinessException(500, "温度方案快照序列化失败");
        }
    }

    private static long required(Long id, String name) {
        if (id == null || id <= 0) {
            throw new BusinessException(400, name + "ID无效");
        }
        return id;
    }

    private static String name(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
