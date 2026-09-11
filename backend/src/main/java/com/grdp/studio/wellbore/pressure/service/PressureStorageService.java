package com.grdp.studio.wellbore.pressure.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.grdp.studio.common.BusinessException;
import com.grdp.studio.pvtstorage.entity.WellHeadLookupEntity;
import com.grdp.studio.pvtstorage.entity.WellPvtEntity;
import com.grdp.studio.pvtstorage.mapper.WellHeadLookupMapper;
import com.grdp.studio.pvtstorage.mapper.WellPvtMapper;
import com.grdp.studio.wellbore.pressure.dto.PressureCalculateRequest;
import com.grdp.studio.wellbore.pressure.dto.PressureRecordDetail;
import com.grdp.studio.wellbore.pressure.dto.PressureRecordSummary;
import com.grdp.studio.wellbore.pressure.dto.PressureSaveRequest;
import com.grdp.studio.wellbore.pressure.entity.PressureMethodResultEntity;
import com.grdp.studio.wellbore.pressure.entity.PressureProfileEntity;
import com.grdp.studio.wellbore.pressure.entity.WellPressureConversionEntity;
import com.grdp.studio.wellbore.pressure.mapper.PressureMethodResultMapper;
import com.grdp.studio.wellbore.pressure.mapper.PressureProfileMapper;
import com.grdp.studio.wellbore.pressure.mapper.WellPressureConversionMapper;
import com.grdp.studio.wellbore.pressure.method.PressureCalculator;
import com.grdp.studio.wellbore.temperature.service.TemperatureStorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.time.LocalDateTime;

@Service
public class PressureStorageService {
    private final WellHeadLookupMapper wells;
    private final WellPvtMapper pvts;
    private final WellPressureConversionMapper records;
    private final PressureMethodResultMapper methods;
    private final PressureProfileMapper profiles;
    private final TemperatureStorageService temperatures;
    private final PressureConversionService calculator;
    private final ObjectMapper json;

    public PressureStorageService(
            WellHeadLookupMapper wells,
            WellPvtMapper pvts,
            WellPressureConversionMapper records,
            PressureMethodResultMapper methods,
            PressureProfileMapper profiles,
            TemperatureStorageService temperatures,
            PressureConversionService calculator,
            ObjectMapper json
    ) {
        this.wells = wells;
        this.pvts = pvts;
        this.records = records;
        this.methods = methods;
        this.profiles = profiles;
        this.temperatures = temperatures;
        this.calculator = calculator;
        this.json = json;
    }

    public List<PressureRecordSummary> list(
            long projectId,
            long reservoirId,
            String wellName
    ) {
        long wellId = wellId(projectId, reservoirId, wellName);
        return records.selectList(
                        new LambdaQueryWrapper<WellPressureConversionEntity>()
                                .eq(WellPressureConversionEntity::getWellId, wellId)
                                .orderByDesc(WellPressureConversionEntity::getPressureNo)
                ).stream()
                .map(PressureRecordSummary::from)
                .toList();
    }

    public PressureRecordDetail detail(
            long id,
            long projectId,
            long reservoirId,
            String wellName
    ) {
        long wellId = wellId(projectId, reservoirId, wellName);
        WellPressureConversionEntity record = records.selectOne(
                new LambdaQueryWrapper<WellPressureConversionEntity>()
                        .eq(WellPressureConversionEntity::getId, id)
                        .eq(WellPressureConversionEntity::getWellId, wellId)
        );
        if (record == null) {
            throw new BusinessException(404, "未找到当前井压力折算方案");
        }
        List<PressureMethodResultEntity> methodResults = methods.selectList(
                new LambdaQueryWrapper<PressureMethodResultEntity>()
                        .eq(PressureMethodResultEntity::getPressureId, id)
        );
        var profileMap = new LinkedHashMap<Long, List<PressureProfileEntity>>();
        for (PressureMethodResultEntity method : methodResults) {
            profileMap.put(method.getId(), profiles.selectList(
                    new LambdaQueryWrapper<PressureProfileEntity>()
                            .eq(PressureProfileEntity::getMethodResultId, method.getId())
                            .orderByAsc(PressureProfileEntity::getPointNo)
            ));
        }
        return new PressureRecordDetail(record, methodResults, profileMap);
    }

    @Transactional
    public PressureRecordDetail save(
            PressureSaveRequest save,
            String token,
            String cookie,
            String environment
    ) {
        if (save == null || save.calculation == null) {
            throw new BusinessException(400, "缺少压力折算参数");
        }

        var request = save.calculation;
        long wellId = wellId(
                require(request.projectId, "项目"),
                require(request.gasReservoirId, "气藏"),
                request.wellName
        );
        Long temperatureRecordId = resolveTemperatureRecordId(request);
        Long pvtId = resolvePvtId(request, wellId);
        var temperature = temperatures.detail(
                temperatureRecordId,
                request.projectId,
                request.gasReservoirId,
                request.wellName
        );
        if (!wellEquals(temperature.record().getWellId(), wellId)) {
            throw new BusinessException(400, "温度方案不属于当前井");
        }

        var calculation = calculator.calculateDetailed(request, token, cookie, environment);
        PressureCalculator.Result output = calculation.result();
        WellPressureConversionEntity entity = new WellPressureConversionEntity();
        entity.setWellId(wellId);
        entity.setTemperatureId(temperatureRecordId);
        entity.setPvtId(pvtId);
        entity.setPressureNo(nextNo(wellId));
        entity.setPressureName(name(save.pressureName, "压力折算方案"));
        entity.setOperationMode("production");
        entity.setBoundaryPosition(request.boundaryPosition);
        entity.setBoundaryPressureMpa(request.boundaryPressure);
        entity.setStatus("calculated");
        entity.setIdTubingMm(request.idTubing);
        entity.setRoughnessMm(request.roughness);
        entity.setAngleDeg(request.angle);
        entity.setQGas1e4M3d(request.qGas);
        entity.setQLiqM3d(request.qLiq);
        entity.setGasSpecificGravity(calculation.gasSpecificGravity());
        entity.setInputJson(write(request));
        entity.setRemark(blank(save.remark));
        records.insert(entity);

        for (PressureCalculator.MethodResult method : output.methods().values()) {
            saveMethod(entity.getId(), request.boundaryPressure, method);
        }
        return detailByWell(entity.getId(), wellId);
    }

    private void saveMethod(
            long pressureId,
            double boundaryPressure,
            PressureCalculator.MethodResult result
    ) {
        PressureMethodResultEntity method = new PressureMethodResultEntity();
        method.setPressureId(pressureId);
        method.setMethodCode(result.methodCode());
        method.setMethodName("HB".equals(result.methodCode())
                ? "Hagedorn & Brown"
                : "Mukherjee & Brill");
        method.setAlgorithmVersion("supplied-js-1");
        method.setIterationLimit(10);
        method.setConvergenceToleranceMpa(0.0001);
        method.setRelaxationFactor(0.5);
        method.setConverged(result.allSegmentsConverged());
        method.setCalculatedAt(LocalDateTime.now());
        method.setMaxSegmentIterationCount(result.maxIterationCount());
        method.setNonconvergedSegmentCount(result.nonconvergedSegmentCount());
        method.setSegmentCount(result.profile().size() - 1);
        method.setBottomPressureMpa(result.profile().getLast().pressure());
        method.setPressureDifferenceMpa(
                result.profile().getLast().pressure() - boundaryPressure
        );
        method.setResultSummaryJson(write(result));
        methods.insert(method);

        for (int index = 0; index < result.profile().size(); index++) {
            PressureCalculator.Point point = result.profile().get(index);
            PressureProfileEntity profile = new PressureProfileEntity();
            profile.setMethodResultId(method.getId());
            profile.setPointNo(index);
            profile.setDepthM(point.depth());
            profile.setTemperatureC(point.temperature());
            profile.setPressureMpa(point.pressure());
            profile.setSegmentAvgPressureMpa(point.segmentAveragePressure());
            profile.setSegmentAvgTemperatureC(point.segmentAverageTemperature());
            profile.setGasVolumeFactor(point.gasVolumeFactor());
            profile.setGasDensityKgM3(point.gasDensity());
            profile.setGasViscosityMpas(point.gasViscosity());
            profile.setLiquidDensityKgM3(point.liquidDensity());
            profile.setLiquidViscosityMpas(point.liquidViscosity());
            profile.setPressureGradientMpaPerM(point.gradient());
            profile.setSegmentIterationCount(point.iterationCount());
            profile.setSegmentConverged(point.segmentConverged());
            profiles.insert(profile);
        }
    }

    @Transactional
    public void delete(long id, long projectId, long reservoirId, String wellName) {
        long wellId = wellId(projectId, reservoirId, wellName);
        long count = records.selectCount(
                new LambdaQueryWrapper<WellPressureConversionEntity>()
                        .eq(WellPressureConversionEntity::getId, id)
                        .eq(WellPressureConversionEntity::getWellId, wellId)
        );
        if (count == 0) {
            throw new BusinessException(404, "未找到当前井压力折算方案");
        }
        records.deleteById(id);
    }

    private PressureRecordDetail detailByWell(long id, long wellId) {
        WellPressureConversionEntity record = records.selectOne(
                new LambdaQueryWrapper<WellPressureConversionEntity>()
                        .eq(WellPressureConversionEntity::getId, id)
                        .eq(WellPressureConversionEntity::getWellId, wellId)
        );
        List<PressureMethodResultEntity> methodResults = methods.selectList(
                new LambdaQueryWrapper<PressureMethodResultEntity>()
                        .eq(PressureMethodResultEntity::getPressureId, id)
        );
        var profileMap = new LinkedHashMap<Long, List<PressureProfileEntity>>();
        for (PressureMethodResultEntity method : methodResults) {
            profileMap.put(method.getId(), profiles.selectList(
                    new LambdaQueryWrapper<PressureProfileEntity>()
                            .eq(PressureProfileEntity::getMethodResultId, method.getId())
                            .orderByAsc(PressureProfileEntity::getPointNo)
            ));
        }
        return new PressureRecordDetail(record, methodResults, profileMap);
    }

    private long wellId(long projectId, long reservoirId, String wellName) {
        if (wellName == null || wellName.isBlank()) {
            throw new BusinessException(400, "井名不能为空");
        }
        List<WellHeadLookupEntity> matches = wells.selectList(
                new LambdaQueryWrapper<WellHeadLookupEntity>()
                        .eq(WellHeadLookupEntity::getProjectId, projectId)
                        .eq(WellHeadLookupEntity::getProjectGasReservoirId, reservoirId)
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
                        new LambdaQueryWrapper<WellPressureConversionEntity>()
                                .eq(WellPressureConversionEntity::getWellId, wellId)
                ).stream()
                .map(WellPressureConversionEntity::getPressureNo)
                .filter(Objects::nonNull)
                .max(Integer::compare)
                .orElse(0) + 1;
    }

    private Long resolveTemperatureRecordId(PressureCalculateRequest request) {
        if (request.temperatureRecordId != null && request.temperatureRecordId > 0) {
            return request.temperatureRecordId;
        }
        List<com.grdp.studio.wellbore.temperature.dto.TemperatureRecordSummary> history =
                temperatures.list(
                        request.projectId,
                        request.gasReservoirId,
                        request.wellName
                );
        if (history.isEmpty()) {
            throw new BusinessException(
                    400,
                    "当前井没有温度计算历史，请先在温度模型页面完成一次计算"
            );
        }
        request.temperatureRecordId = history.getFirst().id();
        return request.temperatureRecordId;
    }

    private Long resolvePvtId(PressureCalculateRequest request, long wellId) {
        WellPvtEntity pvt;
        if (request.pvtId != null && request.pvtId > 0) {
            pvt = pvts.selectOne(
                    new LambdaQueryWrapper<WellPvtEntity>()
                            .eq(WellPvtEntity::getId, request.pvtId)
                            .eq(WellPvtEntity::getWellId, wellId)
            );
        } else {
            pvt = pvts.selectOne(
                    new LambdaQueryWrapper<WellPvtEntity>()
                            .eq(WellPvtEntity::getWellId, wellId)
                            .orderByDesc(WellPvtEntity::getPvtNo)
                            .last("LIMIT 1")
            );
        }
        if (pvt == null) {
            throw new BusinessException(400, "当前井没有可用的PVT方案");
        }
        request.pvtId = pvt.getId();
        return request.pvtId;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new BusinessException(500, "压力方案快照序列化失败");
        }
    }

    private static boolean wellEquals(Long actual, long expected) {
        return actual != null && actual == expected;
    }

    private static long require(Long id, String name) {
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
