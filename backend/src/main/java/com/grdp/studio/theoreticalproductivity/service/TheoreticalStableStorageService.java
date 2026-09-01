package com.grdp.studio.theoreticalproductivity.service;

import com.grdp.studio.theoreticalproductivity.dto.TheoreticalStableDtos.Detail;
import com.grdp.studio.theoreticalproductivity.dto.TheoreticalStableDtos.DefaultParameterDetail;
import com.grdp.studio.theoreticalproductivity.dto.TheoreticalStableDtos.DefaultParameterRequest;
import com.grdp.studio.theoreticalproductivity.dto.TheoreticalStableDtos.Input;
import com.grdp.studio.theoreticalproductivity.dto.TheoreticalStableDtos.IprPoint;
import com.grdp.studio.theoreticalproductivity.dto.TheoreticalStableDtos.Operation;
import com.grdp.studio.theoreticalproductivity.dto.TheoreticalStableDtos.RenameRequest;
import com.grdp.studio.theoreticalproductivity.dto.TheoreticalStableDtos.Output;
import com.grdp.studio.theoreticalproductivity.dto.TheoreticalStableDtos.SaveRequest;
import com.grdp.studio.theoreticalproductivity.dto.TheoreticalStableDtos.SavedOperation;
import com.grdp.studio.theoreticalproductivity.dto.TheoreticalStableDtos.Summary;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 理论计算稳定流六表存储服务。
 *
 * <p>保存采用一个事务：定位井、创建或更新稳定流、替换当前注采方向的输入快照、
 * 三种压力结果和 IPR 数据。任何一步失败都会整体回滚。</p>
 */
@Service
public class TheoreticalStableStorageService {
    private static final Set<String> WELL_TYPES = Set.of("vertical", "horizontal");
    private static final Set<String> SOURCES = Set.of("default", "pvt");
    private static final Set<String> OPERATION_TYPES = Set.of("production", "injection");
    private static final Set<String> PRESSURE_METHODS =
            Set.of("pseudo_pressure", "pressure_squared", "pressure");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public TheoreticalStableStorageService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 仅查询稳定流主记录摘要，不加载体积较大的 IPR JSON。 */
    public List<Summary> list(long projectId, long reservoirId, String wellName) {
        long wellId = findWellId(projectId, reservoirId, wellName);
        return jdbc.query("""
                SELECT s.id,s.stable_no,s.stable_name,s.pvt_id,s.pvt_name_snapshot,s.parameter_source
                FROM project_well_theoretical_stable_calculation s
                JOIN project_well_theoretical_productivity d ON d.id=s.theoretical_productivity_id
                WHERE d.well_id=? ORDER BY s.stable_no
                """, (rs, rowNum) -> summary(rs), wellId);
    }

    /** 读取井级默认参数；gas_type 为空表示顶部首次计算尚未完成默认参数初始化。 */
    public DefaultParameterDetail defaultParameters(long projectId, long reservoirId, String wellName) {
        long wellId = findWellId(projectId, reservoirId, wellName);
        try {
            return jdbc.queryForObject("""
                    SELECT well_type,gas_type,specific_gravity,hydrogen_sulfide,carbon_dioxide,nitrogen,
                           modification_method,deviation_factor_method,viscosity_method,permeability,
                           formation_thickness,skin_factor,drainage_radius,wellbore_radius,
                           horizontal_section_length,original_formation_pressure,formation_temperature
                    FROM project_well_theoretical_productivity
                    WHERE well_id=? AND gas_type IS NOT NULL
                    """, (rs, rowNum) -> new DefaultParameterDetail(rs.getString(1),
                    new Input(rs.getString(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5),
                            rs.getDouble(6), rs.getString(7), rs.getString(8), rs.getString(9),
                            rs.getDouble(10), rs.getDouble(11), rs.getDouble(12), rs.getDouble(13),
                            rs.getDouble(14), nullableDouble(rs, 15), rs.getDouble(16), rs.getDouble(17))),
                    wellId);
        } catch (EmptyResultDataAccessException error) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前井还没有理论计算默认参数");
        }
    }

    /** 更新井级唯一默认参数，不创建稳定流计算，也不修改 next_stable_no。 */
    @Transactional
    public void saveDefaultParameters(DefaultParameterRequest request) {
        if (!WELL_TYPES.contains(request.wellType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "井型只能是 vertical 或 horizontal");
        }
        long wellId = findWellId(request.projectId(), request.gasReservoirId(), request.wellName());
        long theoreticalId = ensureTheoreticalRecord(wellId, request.wellType());
        Input input = request.input();
        jdbc.update("""
                UPDATE project_well_theoretical_productivity
                SET gas_type=?,specific_gravity=?,hydrogen_sulfide=?,carbon_dioxide=?,nitrogen=?,
                    modification_method=?,deviation_factor_method=?,viscosity_method=?,permeability=?,
                    formation_thickness=?,skin_factor=?,drainage_radius=?,wellbore_radius=?,
                    horizontal_section_length=?,original_formation_pressure=?,formation_temperature=?
                WHERE id=?
                """, input.gasType(), input.specificGravity(), input.hydrogenSulfide(),
                input.carbonDioxide(), input.nitrogen(), input.modificationMethod(),
                input.deviationFactorMethod(), input.viscosityMethod(), input.permeability(),
                input.formationThickness(), input.skinFactor(), input.drainageRadius(), input.wellboreRadius(),
                input.horizontalSectionLength(), input.originalFormationPressure(), input.formationTemperature(),
                theoreticalId);
    }

    /**
     * 加载一整次稳定流快照。operations 以 production/injection 为键，便于前端切换
     * 采气和注气时各自恢复参数与结果，避免两边数据串用。
     */
    public Detail detail(long stableId, long projectId, long reservoirId, String wellName) {
        long wellId = findWellId(projectId, reservoirId, wellName);
        Summary record = requireSummary(stableId, wellId);
        String wellType = jdbc.queryForObject("""
                SELECT d.well_type FROM project_well_theoretical_productivity d
                JOIN project_well_theoretical_stable_calculation s ON s.theoretical_productivity_id=d.id
                WHERE s.id=? AND d.well_id=?
                """, String.class, stableId, wellId);
        Map<String, SavedOperation> operations = new LinkedHashMap<>();
        List<Long> operationIds = jdbc.query("""
                SELECT id FROM project_well_theoretical_stable_operation
                WHERE stable_calculation_id=? ORDER BY operation_type
                """, (rs, rowNum) -> rs.getLong(1), stableId);
        for (Long operationId : operationIds) {
            SavedOperation operation = loadOperation(operationId);
            operations.put(operation.operationType(), operation);
        }
        return new Detail(record, wellType, operations);
    }

    @Transactional
    public Summary save(SaveRequest request) {
        // 所有校验必须发生在写库前，避免创建出缺少三种压力结果的半成品记录。
        validateRequest(request);
        long wellId = findWellId(request.projectId(), request.gasReservoirId(), request.wellName());
        validatePvtOwnership(request.pvtId(), wellId);
        long theoreticalId = ensureTheoreticalRecord(wellId, request.wellType());
        // stableId 为空表示首次保存；有值表示修改左侧已经存在的稳定流。
        long stableId = request.stableId() == null
                ? createStable(theoreticalId, request)
                : updateStable(theoreticalId, request);
        replaceOperation(stableId, request.operation());
        return requireSummary(stableId, wellId);
    }

    /** 重命名只更新主记录显示名称，保留该次计算的所有输入、输出和 IPR 数据。 */
    @Transactional
    public Summary rename(long stableId, RenameRequest request) {
        long wellId = findWellId(request.projectId(), request.gasReservoirId(), request.wellName());
        requireSummary(stableId, wellId);
        String stableName = request.stableName().trim();
        try {
            if (jdbc.update("UPDATE project_well_theoretical_stable_calculation SET stable_name=? WHERE id=?",
                    stableName, stableId) != 1) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "稳定流重命名失败");
            }
        } catch (DataIntegrityViolationException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前井下已经存在同名稳定流记录", error);
        }
        return requireSummary(stableId, wellId);
    }

    @Transactional
    public void delete(long stableId, long projectId, long reservoirId, String wellName) {
        long wellId = findWellId(projectId, reservoirId, wellName);
        requireSummary(stableId, wellId);
        // 子表由六表中已经验证的 ON DELETE CASCADE 自动整组删除。
        if (jdbc.update("DELETE FROM project_well_theoretical_stable_calculation WHERE id=?", stableId) != 1) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "稳定流记录删除失败");
        }
    }

    /** 读取或创建一口井唯一的理论计算主记录，并保证后续井型不可被另一种井型覆盖。 */
    private long ensureTheoreticalRecord(long wellId, String wellType) {
        // 一口井只能有一条理论计算主记录。FOR UPDATE 防止并发首次保存产生重复主记录。
        List<Long> ids = jdbc.query("""
                SELECT id FROM project_well_theoretical_productivity WHERE well_id=? FOR UPDATE
                """, (rs, rowNum) -> rs.getLong(1), wellId);
        if (!ids.isEmpty()) {
            String storedType = jdbc.queryForObject(
                    "SELECT well_type FROM project_well_theoretical_productivity WHERE id=?",
                    String.class, ids.getFirst());
            if (!wellType.equals(storedType)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "当前井已经固定为另一种井型");
            }
            return ids.getFirst();
        }
        return insertAndReturnKey("""
                INSERT INTO project_well_theoretical_productivity(well_id,well_type,next_stable_no)
                VALUES(?,?,1)
                """, wellId, wellType);
    }

    /** 在主记录行锁内分配永不复用的编号，并创建新的“稳定流N”。 */
    private long createStable(long theoreticalId, SaveRequest request) {
        // 编号在主记录上统一分配且只增不回退；删除“稳定流2”后也不会再次复用编号2。
        Integer stableNo = jdbc.queryForObject("""
                SELECT next_stable_no FROM project_well_theoretical_productivity WHERE id=? FOR UPDATE
                """, Integer.class, theoreticalId);
        if (stableNo == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "理论计算主记录编号读取失败");
        }
        String stableName = request.stableName() == null || request.stableName().isBlank()
                ? "稳定流" + stableNo : request.stableName().trim();
        long stableId = insertAndReturnKey("""
                INSERT INTO project_well_theoretical_stable_calculation
                  (theoretical_productivity_id,stable_no,stable_name,pvt_id,pvt_name_snapshot,
                   parameter_source,algorithm_code,algorithm_name,remark)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, theoreticalId, stableNo, stableName, request.pvtId(), trimToNull(request.pvtName()),
                request.parameterSource(), request.algorithmCode(), request.algorithmName(),
                trimToNull(request.remark()));
        jdbc.update("UPDATE project_well_theoretical_productivity SET next_stable_no=? WHERE id=?",
                stableNo + 1, theoreticalId);
        return stableId;
    }

    /** 更新已有稳定流的PVT和算法快照；具体方向数据由replaceOperation原子替换。 */
    private long updateStable(long theoreticalId, SaveRequest request) {
        // 同时校验 stableId 与当前井的主记录关系，防止跨井修改其他井的数据。
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM project_well_theoretical_stable_calculation
                WHERE id=? AND theoretical_productivity_id=?
                """, Long.class, request.stableId(), theoreticalId);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前井下不存在该稳定流记录");
        }
        jdbc.update("""
                UPDATE project_well_theoretical_stable_calculation
                SET stable_name=COALESCE(NULLIF(?,''),stable_name),pvt_id=?,pvt_name_snapshot=?,
                    parameter_source=?,algorithm_code=?,algorithm_name=?,remark=?
                WHERE id=?
                """, request.stableName() == null ? null : request.stableName().trim(), request.pvtId(),
                trimToNull(request.pvtName()), request.parameterSource(), request.algorithmCode(),
                request.algorithmName(), trimToNull(request.remark()), request.stableId());
        return request.stableId();
    }

    /**
     * 原子覆盖当前注采方向：旧输入、三种输出及IPR全部替换；
     * 同一稳定流下未被本次保存选中的另一方向保持不变。
     */
    private void replaceOperation(long stableId, Operation operation) {
        // 覆盖范围只限当前方向：重算采气不会删除同一稳定流下已经保存的注气。
        List<Long> ids = jdbc.query("""
                SELECT id FROM project_well_theoretical_stable_operation
                WHERE stable_calculation_id=? AND operation_type=? FOR UPDATE
                """, (rs, rowNum) -> rs.getLong(1), stableId, operation.operationType());
        long operationId;
        Timestamp now = new Timestamp(System.currentTimeMillis());
        if (ids.isEmpty()) {
            operationId = insertAndReturnKey("""
                    INSERT INTO project_well_theoretical_stable_operation
                      (stable_calculation_id,operation_type,calculated_at) VALUES(?,?,?)
                    """, stableId, operation.operationType(), now);
        } else {
            operationId = ids.getFirst();
            jdbc.update("UPDATE project_well_theoretical_stable_operation SET calculated_at=? WHERE id=?",
                    now, operationId);
            // 删除输出会继续级联删除它们各自的 IPR JSON；另一个注采方向不受影响。
            jdbc.update("DELETE FROM project_well_theoretical_stable_output WHERE operation_id=?", operationId);
            jdbc.update("DELETE FROM project_well_theoretical_stable_input WHERE operation_id=?", operationId);
        }
        insertInput(operationId, operation.input());
        for (Output output : operation.outputs()) {
            long outputId = insertOutput(operationId, output, now);
            insertIpr(outputId, output.iprPoints());
        }
    }

    /** 保存算法本次真正使用的输入快照，避免后续PVT修改影响历史结果。 */
    private void insertInput(long operationId, Input input) {
        jdbc.update("""
                INSERT INTO project_well_theoretical_stable_input
                  (operation_id,gas_type,specific_gravity,hydrogen_sulfide,carbon_dioxide,nitrogen,
                   modification_method,deviation_factor_method,viscosity_method,permeability,
                   formation_thickness,skin_factor,drainage_radius,wellbore_radius,
                   horizontal_section_length,original_formation_pressure,formation_temperature)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, operationId, input.gasType(), input.specificGravity(), input.hydrogenSulfide(),
                input.carbonDioxide(), input.nitrogen(), input.modificationMethod(),
                input.deviationFactorMethod(), input.viscosityMethod(), input.permeability(),
                input.formationThickness(), input.skinFactor(), input.drainageRadius(), input.wellboreRadius(),
                input.horizontalSectionLength(), input.originalFormationPressure(), input.formationTemperature());
    }

    /** 写入一种压力处理结果，并返回主键供对应IPR JSON建立一对一关系。 */
    private long insertOutput(long operationId, Output output, Timestamp calculatedAt) {
        return insertAndReturnKey("""
                INSERT INTO project_well_theoretical_stable_output
                  (operation_id,pressure_method,darcy_seepage_coefficient,
                   non_darcy_seepage_coefficient,open_flow_capacity,gradient,intercept,r_squared,
                   reliability_level,reliability_description,calculated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, operationId, output.pressureMethod(), output.darcySeepageCoefficient(),
                output.nonDarcySeepageCoefficient(), output.openFlowCapacity(), output.gradient(),
                output.intercept(), output.rSquared(), trimToNull(output.reliabilityLevel()),
                trimToNull(output.reliabilityDescription()), calculatedAt);
    }

    /** 将一种压力处理下的全部曲线点序列化为JSON快照。 */
    private void insertIpr(long outputId, List<IprPoint> points) {
        try {
            // 一种压力处理对应一个 JSON；curveNumber 用来还原同一结果下的10条曲线。
            jdbc.update("INSERT INTO project_well_theoretical_stable_ipr(output_id,ipr_json) VALUES(?,?)",
                    outputId, objectMapper.writeValueAsString(points == null ? List.of() : points));
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "IPR曲线序列化失败", error);
        }
    }

    /** 按输入、三种输出和IPR层级还原一个注采方向。 */
    private SavedOperation loadOperation(long operationId) {
        // 按“方向 -> 唯一输入 -> 三种输出 -> 各自IPR”的层级组装前端需要的快照。
        String operationType = jdbc.queryForObject(
                "SELECT operation_type FROM project_well_theoretical_stable_operation WHERE id=?",
                String.class, operationId);
        Input input = jdbc.queryForObject("""
                SELECT gas_type,specific_gravity,hydrogen_sulfide,carbon_dioxide,nitrogen,
                       modification_method,deviation_factor_method,viscosity_method,permeability,
                       formation_thickness,skin_factor,drainage_radius,wellbore_radius,
                       horizontal_section_length,original_formation_pressure,formation_temperature
                FROM project_well_theoretical_stable_input WHERE operation_id=?
                """, (rs, rowNum) -> new Input(rs.getString(1), rs.getDouble(2), rs.getDouble(3),
                rs.getDouble(4), rs.getDouble(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getDouble(9), rs.getDouble(10), rs.getDouble(11), rs.getDouble(12), rs.getDouble(13),
                nullableDouble(rs, 14), rs.getDouble(15), rs.getDouble(16)), operationId);
        List<Output> outputs = jdbc.query("""
                SELECT id,pressure_method,darcy_seepage_coefficient,non_darcy_seepage_coefficient,
                       open_flow_capacity,gradient,intercept,r_squared,reliability_level,reliability_description
                FROM project_well_theoretical_stable_output WHERE operation_id=?
                ORDER BY FIELD(pressure_method,'pseudo_pressure','pressure_squared','pressure')
                """, (rs, rowNum) -> new Output(rs.getString(2), rs.getDouble(3), rs.getDouble(4),
                rs.getDouble(5), nullableDouble(rs, 6), nullableDouble(rs, 7), nullableDouble(rs, 8),
                rs.getString(9), rs.getString(10), loadIpr(rs.getLong(1))), operationId);
        return new SavedOperation(operationType, input, outputs);
    }

    /** 读取并反序列化一条输出对应的IPR JSON；兼容早期缺失曲线行的空结果。 */
    private List<IprPoint> loadIpr(long outputId) {
        try {
            String json = jdbc.queryForObject(
                    "SELECT ipr_json FROM project_well_theoretical_stable_ipr WHERE output_id=?",
                    String.class, outputId);
            return objectMapper.readValue(json, new TypeReference<List<IprPoint>>() {});
        } catch (EmptyResultDataAccessException error) {
            return List.of();
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "IPR曲线解析失败", error);
        }
    }

    /** 使用项目、气藏和井名三项共同定位唯一井ID，防止同名井跨项目串数据。 */
    private long findWellId(long projectId, long reservoirId, String wellName) {
        if (wellName == null || wellName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "井名不能为空");
        }
        // 井名本身并非全局唯一，必须同时使用项目和气藏限定所属井。
        List<Long> ids = jdbc.query("""
                SELECT id FROM project_well_heads
                WHERE project_id=? AND project_gas_reservoir_id=? AND well_name=?
                """, (rs, rowNum) -> rs.getLong(1), projectId, reservoirId, wellName.trim());
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前项目和气藏下不存在井：" + wellName);
        }
        if (ids.size() > 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "项目、气藏和井名对应多条井记录");
        }
        return ids.getFirst();
    }

    private Summary requireSummary(long stableId, long wellId) {
        try {
            return jdbc.queryForObject("""
                    SELECT s.id,s.stable_no,s.stable_name,s.pvt_id,s.pvt_name_snapshot,s.parameter_source
                    FROM project_well_theoretical_stable_calculation s
                    JOIN project_well_theoretical_productivity d ON d.id=s.theoretical_productivity_id
                    WHERE s.id=? AND d.well_id=?
                    """, (rs, rowNum) -> summary(rs), stableId, wellId);
        } catch (EmptyResultDataAccessException error) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前井下不存在该稳定流记录");
        }
    }

    private Summary summary(ResultSet rs) throws SQLException {
        long pvtValue = rs.getLong(4);
        boolean pvtIsNull = rs.wasNull();
        return new Summary(rs.getLong(1), rs.getInt(2), rs.getString(3),
                pvtIsNull ? null : pvtValue, rs.getString(5), rs.getString(6));
    }

    /** 在写库前验证枚举值、PVT来源以及三种压力结果是否完整且不重复。 */
    private void validateRequest(SaveRequest request) {
        if (!WELL_TYPES.contains(request.wellType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "井型只能是 vertical 或 horizontal");
        }
        if (!SOURCES.contains(request.parameterSource())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "参数来源只能是 default 或 pvt");
        }
        if (!OPERATION_TYPES.contains(request.operation().operationType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "注采方向不正确");
        }
        // 业务约束：一次方向保存必须恰好包含拟压力、压力平方、压力三种结果。
        Set<String> methods = new java.util.HashSet<>();
        for (Output output : request.operation().outputs()) {
            if (!PRESSURE_METHODS.contains(output.pressureMethod())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "压力处理方式不正确");
            }
            if (!methods.add(output.pressureMethod())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "同一压力处理结果不能重复");
            }
        }
        if (methods.size() != 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "一次保存必须包含三种压力处理结果");
        }
        if ("pvt".equals(request.parameterSource()) && request.pvtId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "选择PVT参数来源时必须提供pvtId");
        }
    }

    /** 校验选中的PVT确实属于当前井；默认PVT使用null，不需要实体PVT记录。 */
    private void validatePvtOwnership(Long pvtId, long wellId) {
        if (pvtId == null) return;
        // PVT 必须属于当前井；默认PVT没有实体记录，因此允许 pvtId 为 null。
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM project_well_pvt WHERE id=? AND well_id=?",
                Long.class, pvtId, wellId);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "所选PVT不属于当前井或已经被删除");
        }
    }

    private long insertAndReturnKey(String sql, Object... args) {
        org.springframework.jdbc.support.GeneratedKeyHolder holder =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < args.length; index++) {
                statement.setObject(index + 1, args[index]);
            }
            return statement;
        }, holder);
        Number key = holder.getKey();
        if (key == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "数据库未返回新记录ID");
        }
        return key.longValue();
    }

    private static Double nullableDouble(ResultSet rs, int column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

