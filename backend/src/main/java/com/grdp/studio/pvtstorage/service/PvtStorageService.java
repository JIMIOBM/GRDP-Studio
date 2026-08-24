package com.grdp.studio.pvtstorage.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.grdp.studio.common.BusinessException;
import com.grdp.studio.pvtstorage.dto.PvtRecordDetail;
import com.grdp.studio.pvtstorage.dto.PvtRecordSummary;
import com.grdp.studio.pvtstorage.dto.PvtSaveRequest;
import com.grdp.studio.pvtstorage.dto.PvtSaveResponse;
import com.grdp.studio.pvtstorage.entity.PvtGasInputEntity;
import com.grdp.studio.pvtstorage.entity.PvtGasResultEntity;
import com.grdp.studio.pvtstorage.entity.PvtRockInputEntity;
import com.grdp.studio.pvtstorage.entity.PvtRockResultEntity;
import com.grdp.studio.pvtstorage.entity.PvtSettingsEntity;
import com.grdp.studio.pvtstorage.entity.PvtWaterInputEntity;
import com.grdp.studio.pvtstorage.entity.PvtWaterResultEntity;
import com.grdp.studio.pvtstorage.entity.WellHeadLookupEntity;
import com.grdp.studio.pvtstorage.entity.WellPvtEntity;
import com.grdp.studio.pvtstorage.mapper.PvtGasInputMapper;
import com.grdp.studio.pvtstorage.mapper.PvtGasResultMapper;
import com.grdp.studio.pvtstorage.mapper.PvtRockInputMapper;
import com.grdp.studio.pvtstorage.mapper.PvtRockResultMapper;
import com.grdp.studio.pvtstorage.mapper.PvtSettingsMapper;
import com.grdp.studio.pvtstorage.mapper.PvtWaterInputMapper;
import com.grdp.studio.pvtstorage.mapper.PvtWaterResultMapper;
import com.grdp.studio.pvtstorage.mapper.WellHeadLookupMapper;
import com.grdp.studio.pvtstorage.mapper.WellPvtMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * PVT 八表统一保存服务。
 *
 * <p>关联链路：project_well_heads.id -> project_well_pvt.well_id
 * -> 七张 PVT 子表的 pvt_id。</p>
 */
@Service
public class PvtStorageService {

    private static final List<String> PROPERTY_KINDS = List.of("gas", "water", "rock");
    private static final List<String> SECTIONS = List.of("input", "result");

    private final WellHeadLookupMapper wellHeadLookupMapper;
    private final WellPvtMapper wellPvtMapper;
    private final PvtGasInputMapper gasInputMapper;
    private final PvtWaterInputMapper waterInputMapper;
    private final PvtRockInputMapper rockInputMapper;
    private final PvtRockResultMapper rockResultMapper;
    private final PvtSettingsMapper settingsMapper;
    private final PvtGasResultMapper gasResultMapper;
    private final PvtWaterResultMapper waterResultMapper;
    private final ObjectMapper objectMapper;

    public PvtStorageService(
            WellHeadLookupMapper wellHeadLookupMapper,
            WellPvtMapper wellPvtMapper,
            PvtGasInputMapper gasInputMapper,
            PvtWaterInputMapper waterInputMapper,
            PvtRockInputMapper rockInputMapper,
            PvtRockResultMapper rockResultMapper,
            PvtSettingsMapper settingsMapper,
            PvtGasResultMapper gasResultMapper,
            PvtWaterResultMapper waterResultMapper,
            ObjectMapper objectMapper
    ) {
        this.wellHeadLookupMapper = wellHeadLookupMapper;
        this.wellPvtMapper = wellPvtMapper;
        this.gasInputMapper = gasInputMapper;
        this.waterInputMapper = waterInputMapper;
        this.rockInputMapper = rockInputMapper;
        this.rockResultMapper = rockResultMapper;
        this.settingsMapper = settingsMapper;
        this.gasResultMapper = gasResultMapper;
        this.waterResultMapper = waterResultMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 按项目、气藏和井名查询该井已经保存的 PVT 主记录。
     * 没有匹配井或没有 PVT 时返回空集合，前端因此不会挂载任何静态节点。
     */
    public List<PvtRecordSummary> list(long projectId, long gasReservoirId, String wellName) {
        if (wellName == null || wellName.isBlank()) {
            return List.of();
        }
        List<Long> wellIds = findWellIds(projectId, gasReservoirId, wellName.trim());
        if (wellIds.isEmpty()) {
            return List.of();
        }
        if (wellIds.size() > 1) {
            throw new BusinessException(409, "当前项目、气藏和井名对应多口井，请先清理重复井数据");
        }

        return wellPvtMapper.selectList(
                        new LambdaQueryWrapper<WellPvtEntity>()
                                .eq(WellPvtEntity::getWellId, wellIds.getFirst())
                                .orderByAsc(WellPvtEntity::getPvtNo)
                ).stream()
                .map(PvtRecordSummary::from)
                .toList();
    }

    /**
     * 加载左侧某个已有 PVT 的完整数据。pvtId 还必须属于三字段定位出的当前井，
     * 防止同名井或错误节点加载到其他项目、气藏的数据。
     */
    public PvtRecordDetail getDetail(
            long pvtId,
            long projectId,
            long gasReservoirId,
            String wellName
    ) {
        long wellId = requireWellId(projectId, gasReservoirId, wellName);
        WellPvtEntity pvt = wellPvtMapper.selectOne(
                new LambdaQueryWrapper<WellPvtEntity>()
                        .eq(WellPvtEntity::getId, pvtId)
                        .eq(WellPvtEntity::getWellId, wellId)
        );
        if (pvt == null) {
            throw new BusinessException(404, "没有找到当前井对应的PVT性质");
        }

        PvtGasInputEntity gasInput = gasInputMapper.selectOne(
                new LambdaQueryWrapper<PvtGasInputEntity>()
                        .eq(PvtGasInputEntity::getPvtId, pvtId));
        PvtWaterInputEntity waterInput = waterInputMapper.selectOne(
                new LambdaQueryWrapper<PvtWaterInputEntity>()
                        .eq(PvtWaterInputEntity::getPvtId, pvtId));
        PvtRockInputEntity rockInput = rockInputMapper.selectOne(
                new LambdaQueryWrapper<PvtRockInputEntity>()
                        .eq(PvtRockInputEntity::getPvtId, pvtId));

        Map<String, String> settings = new LinkedHashMap<>();
        settingsMapper.selectList(new LambdaQueryWrapper<PvtSettingsEntity>()
                        .eq(PvtSettingsEntity::getPvtId, pvtId))
                .forEach(item -> settings.put(item.getPropertyKind(), item.getSettingsJson()));

        List<PvtRecordDetail.GasResultPoint> gasResults = gasResultMapper.selectList(
                        new LambdaQueryWrapper<PvtGasResultEntity>()
                                .eq(PvtGasResultEntity::getPvtId, pvtId)
                                .orderByAsc(PvtGasResultEntity::getPointNo))
                .stream().map(PvtRecordDetail.GasResultPoint::from).toList();
        List<PvtRecordDetail.WaterResultPoint> waterResults = waterResultMapper.selectList(
                        new LambdaQueryWrapper<PvtWaterResultEntity>()
                                .eq(PvtWaterResultEntity::getPvtId, pvtId)
                                .orderByAsc(PvtWaterResultEntity::getPointNo))
                .stream().map(PvtRecordDetail.WaterResultPoint::from).toList();
        List<PvtRecordDetail.RockResultPoint> rockResults = rockResultMapper.selectList(
                        new LambdaQueryWrapper<PvtRockResultEntity>()
                                .eq(PvtRockResultEntity::getPvtId, pvtId)
                                .orderByAsc(PvtRockResultEntity::getCurveType)
                                .orderByAsc(PvtRockResultEntity::getPointNo))
                .stream().map(PvtRecordDetail.RockResultPoint::from).toList();

        return new PvtRecordDetail(
                PvtRecordSummary.from(pvt),
                PvtRecordDetail.GasInput.from(gasInput),
                PvtRecordDetail.WaterInput.from(waterInput),
                PvtRecordDetail.RockInput.from(rockInput),
                settings,
                gasResults,
                waterResults,
                rockResults
        );
    }

    /**
     * 在一个事务内完成井定位、PVT 主记录创建/复用、子表保存和主表状态更新。
     * 任意一步失败都会回滚，避免出现只有主表、没有明细的半成品数据。
     */
    @Transactional
    public PvtSaveResponse save(PvtSaveRequest request) {
        String kind = normalize(request.propertyKind());
        String section = normalize(request.section());
        validateSaveTarget(kind, section);

        // 先把页面上的项目、气藏、井名转换成数据库真正使用的 well_id。
        long wellId = requireWellId(request);
        WellPvtEntity pvt = findOrCreatePvt(wellId, request);
        int savedRows = switch (kind) {
            case "gas" -> saveGas(pvt.getId(), section, request);
            case "water" -> saveWater(pvt.getId(), section, request);
            case "rock" -> saveRock(pvt.getId(), section, request);
            default -> throw new BusinessException(400, "不支持的PVT性质类型");
        };

        // 计算方法和界面参数按性质类型保存为 JSON，便于以后恢复计算现场。
        if (request.settings() != null && !request.settings().isEmpty()) {
            saveSettings(pvt.getId(), kind, request.settings());
        }

        String status = section.equals("result") ? "calculated" : "data-ready";
        pvt.setPvtName(request.pvtName().trim());
        pvt.setStatus(status);
        pvt.setSourceType(normalizeSourceType(request.sourceType(), section));
        if (section.equals("result")) {
            pvt.setLastCalculatedKind(kind);
        }
        wellPvtMapper.updateById(pvt);

        return new PvtSaveResponse(pvt.getId(), kind, section, savedRows, status);
    }

    private void validateSaveTarget(String kind, String section) {
        if (!PROPERTY_KINDS.contains(kind)) {
            throw new BusinessException(400, "不支持的PVT性质类型");
        }
        if (!SECTIONS.contains(section)) {
            throw new BusinessException(400, "不支持的保存区域");
        }
    }

    private long requireWellId(PvtSaveRequest request) {
        return requireWellId(request.projectId(), request.gasReservoirId(), request.wellName());
    }

    private long requireWellId(long projectId, long gasReservoirId, String wellName) {
        if (wellName == null || wellName.isBlank()) {
            throw new BusinessException(400, "井名不能为空");
        }
        List<Long> ids = findWellIds(projectId, gasReservoirId, wellName.trim());
        if (ids.isEmpty()) {
            throw new BusinessException(404, "没有找到当前项目、气藏和井名对应的井");
        }
        if (ids.size() > 1) {
            throw new BusinessException(409, "当前项目、气藏和井名对应多口井，请先清理重复井数据");
        }
        return ids.getFirst();
    }

    private List<Long> findWellIds(long projectId, long gasReservoirId, String wellName) {
        // 使用 MyBatis-Plus LambdaQueryWrapper，避免维护注解原生 SQL，字段重构也更安全。
        return wellHeadLookupMapper.selectList(
                        new LambdaQueryWrapper<WellHeadLookupEntity>()
                                .select(WellHeadLookupEntity::getId)
                                .eq(WellHeadLookupEntity::getProjectId, projectId)
                                .eq(WellHeadLookupEntity::getProjectGasReservoirId, gasReservoirId)
                                .eq(WellHeadLookupEntity::getWellName, wellName)
                                .orderByAsc(WellHeadLookupEntity::getId)
                ).stream()
                .map(WellHeadLookupEntity::getId)
                .toList();
    }

    private WellPvtEntity findOrCreatePvt(long wellId, PvtSaveRequest request) {
        // 同一口井的同一 pvt_no 始终复用一条主记录，避免重复保存产生重复 PVT。
        WellPvtEntity entity = wellPvtMapper.selectOne(
                new LambdaQueryWrapper<WellPvtEntity>()
                        .eq(WellPvtEntity::getWellId, wellId)
                        .eq(WellPvtEntity::getPvtNo, request.pvtNo())
        );
        if (entity != null) {
            return entity;
        }

        entity = new WellPvtEntity();
        entity.setWellId(wellId);
        entity.setPvtNo(request.pvtNo());
        entity.setPvtName(request.pvtName().trim());
        entity.setStatus("draft");
        entity.setSourceType(normalizeSourceType(request.sourceType(), request.section()));
        wellPvtMapper.insert(entity);
        if (entity.getId() == null) {
            throw new BusinessException(500, "创建PVT主记录失败");
        }
        return entity;
    }

    private int saveGas(long pvtId, String section, PvtSaveRequest request) {
        if (section.equals("input")) {
            PvtSaveRequest.GasInput input = request.gasInput();
            if (input == null) {
                throw new BusinessException(400, "天然气数据列表中没有可保存的数据");
            }
            validateGasInput(input);

            // 输入表与主表是一对一关系：存在则更新，不存在才新增。
            PvtGasInputEntity entity = gasInputMapper.selectOne(
                    new LambdaQueryWrapper<PvtGasInputEntity>()
                            .eq(PvtGasInputEntity::getPvtId, pvtId)
            );
            boolean isNew = entity == null;
            if (isNew) {
                entity = new PvtGasInputEntity();
                entity.setPvtId(pvtId);
            }
            entity.setGasType(input.gasType().trim());
            entity.setSpecificGravity(input.specificGravity());
            entity.setHydrogenSulfide(input.hydrogenSulfide());
            entity.setCarbonDioxide(input.carbonDioxide());
            entity.setNitrogen(input.nitrogen());
            entity.setCondensateOilDensity(input.condensateOilDensity());
            if (isNew) {
                gasInputMapper.insert(entity);
            } else {
                gasInputMapper.updateById(entity);
            }
            return 1;
        }

        List<PvtSaveRequest.GasResultPoint> rows = request.gasResults();
        if (rows == null || rows.isEmpty()) {
            throw new BusinessException(400, "天然气结果分析图中没有可保存的数据");
        }
        rows.forEach(this::validateGasResult);
        // 结果曲线按“整组快照”保存，先清除旧点再写入新点；事务保证操作原子性。
        gasResultMapper.delete(new LambdaQueryWrapper<PvtGasResultEntity>()
                .eq(PvtGasResultEntity::getPvtId, pvtId));
        for (int index = 0; index < rows.size(); index++) {
            gasResultMapper.insert(toGasResultEntity(pvtId, index + 1, rows.get(index)));
        }
        return rows.size();
    }

    private int saveWater(long pvtId, String section, PvtSaveRequest request) {
        if (section.equals("input")) {
            PvtSaveRequest.WaterInput input = request.waterInput();
            if (input == null) {
                throw new BusinessException(400, "地层水数据列表中没有可保存的数据");
            }
            requireFinite(input.formationPressure(), "地层压力");
            requireFinite(input.formationTemperature(), "地层温度");
            requireFinite(input.salinity(), "矿化度");

            // 输入表与主表是一对一关系：存在则更新，不存在才新增。
            PvtWaterInputEntity entity = waterInputMapper.selectOne(
                    new LambdaQueryWrapper<PvtWaterInputEntity>()
                            .eq(PvtWaterInputEntity::getPvtId, pvtId)
            );
            boolean isNew = entity == null;
            if (isNew) {
                entity = new PvtWaterInputEntity();
                entity.setPvtId(pvtId);
            }
            entity.setFormationPressure(input.formationPressure());
            entity.setFormationTemperature(input.formationTemperature());
            entity.setSalinity(input.salinity());
            if (isNew) {
                waterInputMapper.insert(entity);
            } else {
                waterInputMapper.updateById(entity);
            }
            return 1;
        }

        List<PvtSaveRequest.WaterResultPoint> rows = request.waterResults();
        if (rows == null || rows.isEmpty()) {
            throw new BusinessException(400, "地层水结果分析图中没有可保存的数据");
        }
        rows.forEach(this::validateWaterResult);
        // 与天然气结果一致，地层水结果也以当前计算产生的完整曲线覆盖旧曲线。
        waterResultMapper.delete(new LambdaQueryWrapper<PvtWaterResultEntity>()
                .eq(PvtWaterResultEntity::getPvtId, pvtId));
        for (int index = 0; index < rows.size(); index++) {
            waterResultMapper.insert(toWaterResultEntity(pvtId, index + 1, rows.get(index)));
        }
        return rows.size();
    }

    private int saveRock(long pvtId, String section, PvtSaveRequest request) {
        PvtSaveRequest.RockInput input = request.rockInput();
        if (input == null) {
            throw new BusinessException(400, "岩石性质中没有可保存的数据");
        }
        requireFinite(input.porosity(), "岩石孔隙度");

        PvtRockInputEntity entity = rockInputMapper.selectOne(
                new LambdaQueryWrapper<PvtRockInputEntity>()
                        .eq(PvtRockInputEntity::getPvtId, pvtId)
        );
        boolean isNew = entity == null;
        if (isNew) {
            entity = new PvtRockInputEntity();
            entity.setPvtId(pvtId);
        }
        entity.setPorosity(input.porosity());
        entity.setRockType(trimToNull(input.rockType()));
        entity.setCalculationMethod(trimToNull(input.calculationMethod()));
        if (isNew) {
            rockInputMapper.insert(entity);
        } else {
            rockInputMapper.updateById(entity);
        }
        if (section.equals("input")) {
            return 1;
        }

        List<PvtSaveRequest.RockResultPoint> rows = request.rockResults();
        if (rows == null || rows.isEmpty()) {
            throw new BusinessException(400, "岩石结果分析图中没有可保存的数据");
        }
        rows.forEach(this::validateRockResult);
        // 岩石两条曲线也作为完整快照保存，避免新旧计算点混合。
        rockResultMapper.delete(new LambdaQueryWrapper<PvtRockResultEntity>()
                .eq(PvtRockResultEntity::getPvtId, pvtId));
        rows.forEach(row -> rockResultMapper.insert(toRockResultEntity(pvtId, row)));
        return rows.size();
    }

    private void saveSettings(long pvtId, String kind, Map<String, Object> settings) {
        String json;
        try {
            json = objectMapper.writeValueAsString(settings);
        } catch (JacksonException exception) {
            throw new BusinessException(500, "PVT计算设置序列化失败");
        }

        // 每个 PVT、每种性质最多一条设置，唯一键为 (pvt_id, property_kind)。
        PvtSettingsEntity entity = settingsMapper.selectOne(
                new LambdaQueryWrapper<PvtSettingsEntity>()
                        .eq(PvtSettingsEntity::getPvtId, pvtId)
                        .eq(PvtSettingsEntity::getPropertyKind, kind)
        );
        boolean isNew = entity == null;
        if (isNew) {
            entity = new PvtSettingsEntity();
            entity.setPvtId(pvtId);
            entity.setPropertyKind(kind);
        }
        entity.setSettingsJson(json);
        if (isNew) {
            settingsMapper.insert(entity);
        } else {
            settingsMapper.updateById(entity);
        }
    }

    private void validateGasInput(PvtSaveRequest.GasInput input) {
        requireFinite(input.specificGravity(), "天然气比重");
        requireFinite(input.hydrogenSulfide(), "H2S");
        requireFinite(input.carbonDioxide(), "CO2");
        requireFinite(input.nitrogen(), "N2");
        requireOptionalFinite(input.condensateOilDensity(), "凝析油密度");
    }

    private PvtGasResultEntity toGasResultEntity(long pvtId, int pointNo,
                                                  PvtSaveRequest.GasResultPoint row) {
        PvtGasResultEntity entity = new PvtGasResultEntity();
        entity.setPvtId(pvtId);
        entity.setPointNo(pointNo);
        entity.setPressure(row.pressure());
        entity.setTemperature(row.temperature());
        entity.setDeviationFactor(row.deviationFactor());
        entity.setPseudoPressure(row.pseudoPressure());
        entity.setVolumeFactor(row.volumeFactor());
        entity.setDensity(row.density());
        entity.setCompressibility(row.compressibility());
        entity.setViscosity(row.viscosity());
        return entity;
    }

    private PvtWaterResultEntity toWaterResultEntity(long pvtId, int pointNo,
                                                      PvtSaveRequest.WaterResultPoint row) {
        PvtWaterResultEntity entity = new PvtWaterResultEntity();
        entity.setPvtId(pvtId);
        entity.setPointNo(pointNo);
        entity.setPressure(row.pressure());
        entity.setTemperature(row.temperature());
        entity.setSalinity(row.salinity());
        entity.setGasSolubility(row.gasSolubility());
        entity.setVolumeFactor(row.volumeFactor());
        entity.setDensity(row.density());
        entity.setIsothermalCompressibility(row.isothermalCompressibility());
        entity.setViscosity(row.viscosity());
        return entity;
    }

    private PvtRockResultEntity toRockResultEntity(long pvtId, PvtSaveRequest.RockResultPoint row) {
        PvtRockResultEntity entity = new PvtRockResultEntity();
        entity.setPvtId(pvtId);
        entity.setCurveType(normalize(row.curveType()));
        entity.setPointNo(row.pointNo());
        entity.setPorosity(row.porosity());
        entity.setCompressibilityFactor(row.compressibilityFactor());
        return entity;
    }

    private void validateGasResult(PvtSaveRequest.GasResultPoint row) {
        requireFinite(row.pressure(), "压力");
        requireFinite(row.temperature(), "温度");
        requireOptionalFinite(row.deviationFactor(), "天然气偏差系数");
        requireOptionalFinite(row.pseudoPressure(), "气体拟压力");
        requireOptionalFinite(row.volumeFactor(), "天然气体积系数");
        requireOptionalFinite(row.density(), "天然气密度");
        requireOptionalFinite(row.compressibility(), "天然气压缩系数");
        requireOptionalFinite(row.viscosity(), "天然气粘度");
        if (row.deviationFactor() == null && row.pseudoPressure() == null
                && row.volumeFactor() == null && row.density() == null
                && row.compressibility() == null && row.viscosity() == null) {
            throw new BusinessException(400, "天然气结果行没有性质数据");
        }
    }

    private void validateWaterResult(PvtSaveRequest.WaterResultPoint row) {
        requireFinite(row.pressure(), "压力");
        requireFinite(row.temperature(), "温度");
        requireFinite(row.salinity(), "矿化度");
        requireOptionalFinite(row.gasSolubility(), "天然气在水中的溶解度");
        requireOptionalFinite(row.volumeFactor(), "地层水体积系数");
        requireOptionalFinite(row.density(), "地层水密度");
        requireOptionalFinite(row.isothermalCompressibility(), "地层水等温压缩系数");
        requireOptionalFinite(row.viscosity(), "地层水粘度");
        if (row.gasSolubility() == null && row.volumeFactor() == null
                && row.density() == null && row.isothermalCompressibility() == null
                && row.viscosity() == null) {
            throw new BusinessException(400, "地层水结果行没有性质数据");
        }
    }

    private void validateRockResult(PvtSaveRequest.RockResultPoint row) {
        String curveType = normalize(row.curveType());
        if (!List.of("cemented", "carbonate").contains(curveType)) {
            throw new BusinessException(400, "岩石曲线类型只能是cemented或carbonate");
        }
        if (row.pointNo() == null || row.pointNo() < 1) {
            throw new BusinessException(400, "岩石结果点序号必须大于0");
        }
        requireFinite(row.porosity(), "岩石孔隙度");
        requireFinite(row.compressibilityFactor(), "岩石压缩系数");
    }

    private void requireFinite(Double value, String field) {
        if (value == null || !Double.isFinite(value)) {
            throw new BusinessException(400, field + "必须是有效数字");
        }
    }

    private void requireOptionalFinite(Double value, String field) {
        if (value != null && !Double.isFinite(value)) {
            throw new BusinessException(400, field + "必须是有效数字");
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeSourceType(String value, String section) {
        String normalized = normalize(value);
        if (List.of("manual", "import", "calculation").contains(normalized)) {
            return normalized;
        }
        return "result".equals(normalize(section)) ? "calculation" : "manual";
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
