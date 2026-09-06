package com.grdp.studio.dynamicproductivity.service;

import com.grdp.studio.dynamicproductivity.dto.DynamicUnstableDtos.CalculatedOperation;
import com.grdp.studio.dynamicproductivity.dto.DynamicUnstableDtos.Derived;
import com.grdp.studio.dynamicproductivity.dto.DynamicUnstableDtos.Detail;
import com.grdp.studio.dynamicproductivity.dto.DynamicUnstableDtos.DefaultParameterDetail;
import com.grdp.studio.dynamicproductivity.dto.DynamicUnstableDtos.DefaultParameterRequest;
import com.grdp.studio.dynamicproductivity.dto.DynamicUnstableDtos.Input;
import com.grdp.studio.dynamicproductivity.dto.DynamicUnstableDtos.IprPoint;
import com.grdp.studio.dynamicproductivity.dto.DynamicUnstableDtos.Output;
import com.grdp.studio.dynamicproductivity.dto.DynamicUnstableDtos.RenameRequest;
import com.grdp.studio.dynamicproductivity.dto.DynamicUnstableDtos.SaveRequest;
import com.grdp.studio.dynamicproductivity.dto.DynamicUnstableDtos.Summary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 不稳定流五张子表的事务化读写服务。 */
@Service
public class DynamicUnstableStorageService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DynamicUnstableStorageService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<Summary> list(long projectId, long reservoirId, String wellName) {
        long wellId = findWellId(projectId, reservoirId, wellName);
        return jdbc.query("""
                SELECT u.id,u.unstable_no,u.unstable_name,u.pvt_id,u.pvt_name_snapshot,u.parameter_source
                FROM project_well_dynamic_unstable_calculation u
                JOIN project_well_dynamic_productivity d ON d.id=u.dynamic_productivity_id
                WHERE d.well_id=? ORDER BY u.unstable_no
                """, (rs, rowNum) -> summary(rs), wellId);
    }

    /**
     * 读取不稳定流的井级默认参数。公共气体/地层参数与稳定流共用主表字段，
     * 孔隙度、总压缩系数和流动时间使用不稳定流自己的默认字段。
     */
    public DefaultParameterDetail defaultParameters(long projectId, long reservoirId, String wellName) {
        long wellId = findWellId(projectId, reservoirId, wellName);
        try {
            return jdbc.queryForObject("""
                    SELECT well_type,gas_type,specific_gravity,hydrogen_sulfide,carbon_dioxide,nitrogen,
                           modification_method,deviation_factor_method,viscosity_method,permeability,
                           formation_thickness,skin_factor,default_porosity,default_total_compressibility,
                           default_flow_time,drainage_radius,wellbore_radius,horizontal_section_length,
                           original_formation_pressure,formation_temperature
                    FROM project_well_dynamic_productivity
                    WHERE well_id=? AND gas_type IS NOT NULL
                    """, (rs, rowNum) -> new DefaultParameterDetail(rs.getString(1),
                    new Input(rs.getString(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5),
                            rs.getDouble(6), rs.getString(7), rs.getString(8), rs.getString(9),
                            rs.getDouble(10), rs.getDouble(11), rs.getDouble(12), rs.getDouble(13),
                            rs.getDouble(14), rs.getDouble(15), rs.getDouble(16), rs.getDouble(17),
                            nullableDouble(rs, 18), rs.getDouble(19), rs.getDouble(20))), wellId);
        } catch (EmptyResultDataAccessException error) {
            // 未保存默认参数时沿用页面初始值继续计算，不把首次使用当成接口错误。
            return null;
        }
    }

    /** 更新井级默认参数，不创建不稳定流记录，也不修改 next_unstable_no。 */
    @Transactional
    public void saveDefaultParameters(DefaultParameterRequest request) {
        if (!List.of("vertical", "horizontal").contains(request.wellType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "井型只能是 vertical 或 horizontal");
        }
        long wellId = findWellId(request.projectId(), request.gasReservoirId(), request.wellName());
        long dynamicId = ensureDynamicRecord(wellId, request.wellType());
        Input input = request.input();
        jdbc.update("""
                UPDATE project_well_dynamic_productivity
                SET gas_type=?,specific_gravity=?,hydrogen_sulfide=?,carbon_dioxide=?,nitrogen=?,
                    modification_method=?,deviation_factor_method=?,viscosity_method=?,permeability=?,
                    formation_thickness=?,skin_factor=?,default_porosity=?,default_total_compressibility=?,
                    default_flow_time=?,drainage_radius=?,wellbore_radius=?,horizontal_section_length=?,
                    original_formation_pressure=?,formation_temperature=?
                WHERE id=?
                """, input.gasType(), input.specificGravity(), input.hydrogenSulfide(),
                input.carbonDioxide(), input.nitrogen(), input.modificationMethod(),
                input.deviationFactorMethod(), input.viscosityMethod(), input.permeability(),
                input.formationThickness(), input.skinFactor(), input.porosity(),
                input.totalCompressibility(), input.flowTime(), input.drainageRadius(),
                input.wellboreRadius(), input.horizontalSectionLength(), input.originalFormationPressure(),
                input.formationTemperature(), dynamicId);
    }

    public Detail detail(long unstableId, long projectId, long reservoirId, String wellName) {
        long wellId = findWellId(projectId, reservoirId, wellName);
        Summary record = requireSummary(unstableId, wellId);
        String wellType = jdbc.queryForObject("""
                SELECT d.well_type FROM project_well_dynamic_productivity d
                JOIN project_well_dynamic_unstable_calculation u ON u.dynamic_productivity_id=d.id
                WHERE u.id=? AND d.well_id=?
                """, String.class, unstableId, wellId);
        Map<String, CalculatedOperation> operations = new LinkedHashMap<>();
        List<Long> ids = jdbc.query("SELECT id FROM project_well_dynamic_unstable_operation WHERE unstable_calculation_id=?",
                (rs, rowNum) -> rs.getLong(1), unstableId);
        for (Long id : ids) {
            CalculatedOperation operation = loadOperation(id);
            operations.put(operation.operationType(), operation);
        }
        return new Detail(record, wellType, operations);
    }

    @Transactional
    public Summary save(SaveRequest request) {
        validateSave(request);
        long wellId = findWellId(request.projectId(), request.gasReservoirId(), request.wellName());
        validatePvt(request.pvtId(), wellId);
        long dynamicId = ensureDynamicRecord(wellId, request.wellType());
        long unstableId = request.unstableId() == null
                ? createCalculation(dynamicId, request) : updateCalculation(dynamicId, request);
        replaceOperation(unstableId, request.operation());
        // 记住该井最近使用的不稳定流默认项，但不覆盖稳定流公共参数快照。
        Input input = request.operation().input();
        jdbc.update("""
                UPDATE project_well_dynamic_productivity
                SET default_porosity=?,default_total_compressibility=?,default_flow_time=? WHERE id=?
                """, input.porosity(), input.totalCompressibility(), input.flowTime(), dynamicId);
        return requireSummary(unstableId, wellId);
    }

    @Transactional
    public Summary rename(long unstableId, RenameRequest request) {
        long wellId = findWellId(request.projectId(), request.gasReservoirId(), request.wellName());
        requireSummary(unstableId, wellId);
        try {
            jdbc.update("UPDATE project_well_dynamic_unstable_calculation SET unstable_name=? WHERE id=?",
                    request.unstableName().trim(), unstableId);
        } catch (DataIntegrityViolationException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前井下已经存在同名不稳定流", error);
        }
        return requireSummary(unstableId, wellId);
    }

    @Transactional
    public void delete(long unstableId, long projectId, long reservoirId, String wellName) {
        long wellId = findWellId(projectId, reservoirId, wellName);
        requireSummary(unstableId, wellId);
        if (jdbc.update("DELETE FROM project_well_dynamic_unstable_calculation WHERE id=?", unstableId) != 1)
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "不稳定流删除失败");
    }

    private long createCalculation(long dynamicId, SaveRequest request) {
        Integer number = jdbc.queryForObject("SELECT next_unstable_no FROM project_well_dynamic_productivity WHERE id=? FOR UPDATE",
                Integer.class, dynamicId);
        if (number == null) throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "不稳定流编号读取失败");
        String name = request.unstableName() == null || request.unstableName().isBlank()
                ? "不稳定流" + number : request.unstableName().trim();
        long id = insertAndReturnKey("""
                INSERT INTO project_well_dynamic_unstable_calculation
                  (dynamic_productivity_id,unstable_no,unstable_name,pvt_id,pvt_name_snapshot,parameter_source)
                VALUES(?,?,?,?,?,?)
                """, dynamicId, number, name, request.pvtId(), trimToNull(request.pvtName()), request.parameterSource());
        jdbc.update("UPDATE project_well_dynamic_productivity SET next_unstable_no=? WHERE id=?", number + 1, dynamicId);
        return id;
    }

    private long updateCalculation(long dynamicId, SaveRequest request) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM project_well_dynamic_unstable_calculation
                WHERE id=? AND dynamic_productivity_id=?
                """, Long.class, request.unstableId(), dynamicId);
        if (count == null || count == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前井下不存在该不稳定流");
        jdbc.update("""
                UPDATE project_well_dynamic_unstable_calculation
                SET unstable_name=COALESCE(NULLIF(?,''),unstable_name),pvt_id=?,pvt_name_snapshot=?,parameter_source=?
                WHERE id=?
                """, request.unstableName(), request.pvtId(), trimToNull(request.pvtName()),
                request.parameterSource(), request.unstableId());
        return request.unstableId();
    }

    private void replaceOperation(long calculationId, CalculatedOperation operation) {
        List<Long> ids = jdbc.query("""
                SELECT id FROM project_well_dynamic_unstable_operation
                WHERE unstable_calculation_id=? AND operation_type=? FOR UPDATE
                """, (rs, rowNum) -> rs.getLong(1), calculationId, operation.operationType());
        Timestamp now = new Timestamp(System.currentTimeMillis());
        long operationId;
        if (ids.isEmpty()) {
            operationId = insertAndReturnKey("""
                    INSERT INTO project_well_dynamic_unstable_operation
                      (unstable_calculation_id,operation_type,calculated_at) VALUES(?,?,?)
                    """, calculationId, operation.operationType(), now);
        } else {
            operationId = ids.getFirst();
            jdbc.update("UPDATE project_well_dynamic_unstable_operation SET calculated_at=? WHERE id=?", now, operationId);
            // output 删除后由外键继续删除 IPR，另一个注采方向保持不变。
            jdbc.update("DELETE FROM project_well_dynamic_unstable_output WHERE operation_id=?", operationId);
            jdbc.update("DELETE FROM project_well_dynamic_unstable_input WHERE operation_id=?", operationId);
        }
        insertInput(operationId, operation.input(), operation.derived());
        for (Output output : operation.outputs()) {
            long outputId = insertOutput(operationId, output, now);
            insertIpr(outputId, output.iprPoints());
        }
    }

    private void insertInput(long operationId, Input i, Derived d) {
        jdbc.update("""
                INSERT INTO project_well_dynamic_unstable_input
                  (operation_id,gas_type,specific_gravity,hydrogen_sulfide,carbon_dioxide,nitrogen,
                   modification_method,deviation_factor_method,viscosity_method,permeability,formation_thickness,
                   skin_factor,porosity,total_compressibility,flow_time,drainage_radius,wellbore_radius,
                   horizontal_section_length,original_formation_pressure,formation_temperature,
                   initial_gas_viscosity,initial_gas_deviation_factor,standard_gas_density,
                   non_darcy_coefficient_beta,diffusivity,transient_function_type,transient_function_value)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, operationId, i.gasType(), i.specificGravity(), i.hydrogenSulfide(), i.carbonDioxide(),
                i.nitrogen(), i.modificationMethod(), i.deviationFactorMethod(), i.viscosityMethod(),
                i.permeability(), i.formationThickness(), i.skinFactor(), i.porosity(),
                i.totalCompressibility(), i.flowTime(), i.drainageRadius(), i.wellboreRadius(),
                i.horizontalSectionLength(), i.originalFormationPressure(), i.formationTemperature(),
                d.initialGasViscosity(), d.initialGasDeviationFactor(), d.standardGasDensity(),
                d.nonDarcyCoefficientBeta(), d.diffusivity(), d.transientFunctionType(), d.transientFunctionValue());
    }

    private long insertOutput(long operationId, Output o, Timestamp now) {
        return insertAndReturnKey("""
                INSERT INTO project_well_dynamic_unstable_output
                  (operation_id,pressure_method,darcy_seepage_coefficient,non_darcy_seepage_coefficient,
                   open_flow_capacity,r_squared,calculated_at) VALUES(?,?,?,?,?,?,?)
                """, operationId, o.pressureMethod(), o.darcySeepageCoefficient(),
                o.nonDarcySeepageCoefficient(), o.openFlowCapacity(), o.rSquared(), now);
    }

    private void insertIpr(long outputId, List<IprPoint> points) {
        try {
            jdbc.update("INSERT INTO project_well_dynamic_unstable_ipr(output_id,ipr_json) VALUES(?,?)",
                    outputId, objectMapper.writeValueAsString(points));
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "不稳定流IPR序列化失败", error);
        }
    }

    private CalculatedOperation loadOperation(long operationId) {
        return jdbc.queryForObject("""
                SELECT o.operation_type,i.gas_type,i.specific_gravity,i.hydrogen_sulfide,i.carbon_dioxide,
                  i.nitrogen,i.modification_method,i.deviation_factor_method,i.viscosity_method,i.permeability,
                  i.formation_thickness,i.skin_factor,i.porosity,i.total_compressibility,i.flow_time,
                  i.drainage_radius,i.wellbore_radius,i.horizontal_section_length,i.original_formation_pressure,
                  i.formation_temperature,i.initial_gas_viscosity,i.initial_gas_deviation_factor,
                  i.standard_gas_density,i.non_darcy_coefficient_beta,i.diffusivity,
                  i.transient_function_type,i.transient_function_value
                FROM project_well_dynamic_unstable_operation o
                JOIN project_well_dynamic_unstable_input i ON i.operation_id=o.id WHERE o.id=?
                """, (rs, rowNum) -> {
            Input input = new Input(rs.getString(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5),
                    rs.getDouble(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getDouble(10),
                    rs.getDouble(11), rs.getDouble(12), rs.getDouble(13), rs.getDouble(14), rs.getDouble(15),
                    rs.getDouble(16), rs.getDouble(17), nullableDouble(rs, 18), rs.getDouble(19), rs.getDouble(20));
            Derived derived = new Derived(rs.getDouble(21), rs.getDouble(22), rs.getDouble(23), rs.getDouble(24),
                    nullableDouble(rs, 25), rs.getString(26), rs.getDouble(27));
            return new CalculatedOperation(rs.getString(1), input, derived, loadOutputs(operationId));
        }, operationId);
    }

    private List<Output> loadOutputs(long operationId) {
        return jdbc.query("""
                SELECT id,pressure_method,darcy_seepage_coefficient,non_darcy_seepage_coefficient,
                       open_flow_capacity,r_squared FROM project_well_dynamic_unstable_output WHERE operation_id=?
                ORDER BY FIELD(pressure_method,'pseudo_pressure','pressure_squared','pressure')
                """, (rs, rowNum) -> new Output(rs.getString(2), rs.getDouble(3), rs.getDouble(4),
                nullableDouble(rs, 5), nullableDouble(rs, 6), loadIpr(rs.getLong(1))), operationId);
    }

    private List<IprPoint> loadIpr(long outputId) {
        try {
            String json = jdbc.queryForObject("SELECT ipr_json FROM project_well_dynamic_unstable_ipr WHERE output_id=?",
                    String.class, outputId);
            return objectMapper.readValue(json, new TypeReference<List<IprPoint>>() {});
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "不稳定流IPR读取失败", error);
        }
    }

    private long ensureDynamicRecord(long wellId, String wellType) {
        List<Long> ids = jdbc.query("SELECT id FROM project_well_dynamic_productivity WHERE well_id=? FOR UPDATE",
                (rs, rowNum) -> rs.getLong(1), wellId);
        if (!ids.isEmpty()) {
            String storedType = jdbc.queryForObject(
                    "SELECT well_type FROM project_well_dynamic_productivity WHERE id=?",
                    String.class, ids.getFirst());
            if (!wellType.equals(storedType)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "当前井已经固定为另一种井型");
            }
            return ids.getFirst();
        }
        return insertAndReturnKey("""
                INSERT INTO project_well_dynamic_productivity(well_id,well_type,next_stable_no,next_unstable_no)
                VALUES(?,?,1,1)
                """, wellId, wellType);
    }

    private long findWellId(long projectId, long reservoirId, String wellName) {
        List<Long> ids = jdbc.query("""
                SELECT id FROM project_well_heads
                WHERE project_id=? AND project_gas_reservoir_id=? AND well_name=?
                """, (rs, rowNum) -> rs.getLong(1), projectId, reservoirId, wellName.trim());
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前项目和气藏下不存在井：" + wellName);
        if (ids.size() > 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "项目、气藏和井名对应多条记录");
        return ids.getFirst();
    }

    private Summary requireSummary(long id, long wellId) {
        try {
            return jdbc.queryForObject("""
                    SELECT u.id,u.unstable_no,u.unstable_name,u.pvt_id,u.pvt_name_snapshot,u.parameter_source
                    FROM project_well_dynamic_unstable_calculation u
                    JOIN project_well_dynamic_productivity d ON d.id=u.dynamic_productivity_id
                    WHERE u.id=? AND d.well_id=?
                    """, (rs, rowNum) -> summary(rs), id, wellId);
        } catch (EmptyResultDataAccessException error) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前井下不存在该不稳定流");
        }
    }

    private Summary summary(ResultSet rs) throws SQLException {
        long pvt = rs.getLong(4);
        return new Summary(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.wasNull() ? null : pvt,
                rs.getString(5), rs.getString(6));
    }

    private void validateSave(SaveRequest request) {
        if (!List.of("vertical", "horizontal").contains(request.wellType()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "井型不正确");
        if (!List.of("production", "injection").contains(request.operation().operationType()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "注采方向不正确");
        if (request.operation().outputs() == null || request.operation().outputs().size() != 3)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "保存必须包含三种压力处理结果");
        java.util.Set<String> methods = new java.util.HashSet<>();
        for (Output output : request.operation().outputs()) {
            if (!List.of("pressure", "pressure_squared", "pseudo_pressure").contains(output.pressureMethod())
                    || !methods.add(output.pressureMethod()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "压力处理结果缺失或重复");
        }
        if ("pvt".equals(request.parameterSource()) && request.pvtId() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PVT参数来源缺少pvtId");
    }

    private void validatePvt(Long pvtId, long wellId) {
        if (pvtId == null) return;
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM project_well_pvt WHERE id=? AND well_id=?",
                Long.class, pvtId, wellId);
        if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "所选PVT不属于当前井");
    }

    private long insertAndReturnKey(String sql, Object... args) {
        org.springframework.jdbc.support.GeneratedKeyHolder holder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < args.length; index++) statement.setObject(index + 1, args[index]);
            return statement;
        }, holder);
        Number key = holder.getKey();
        if (key == null) throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "数据库未返回新记录ID");
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
