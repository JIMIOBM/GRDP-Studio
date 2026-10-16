package com.grdp.studio.solutiongeneration.service;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.solutiongeneration.dto.ConstraintRecordSaveRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;

@Service
public class ConstraintRecordStorageService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ConstraintRecordStorageService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public Map<String, Object> save(ConstraintRecordSaveRequest save) {
        validateSaveRequest(save);
        String finalRecordName = name(save.recordName());
        if (finalRecordName == null) {
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM multi_period_constraint_records" +
                            " WHERE project_id=? AND gas_reservoir_id=? AND well_name=?",
                    Long.class,
                    save.projectId(), save.gasReservoirId(), save.wellName().trim());
            finalRecordName = "约束条件" + (count + 1);
        }
        KeyHolder key = new GeneratedKeyHolder();
        String sql = """
                INSERT INTO multi_period_constraint_records(
                    project_id, gas_reservoir_id, well_name, record_name,
                    wellbore_structure, tubing_inner_diameter_mm,
                    erosion_limit, sand_production_limit, liquid_loading_limit,
                    wellhead_export_pressure, lower_bound, upper_bound,
                    complete, feasible, usable_for_plan,
                    input_schema_version, inputs_json, selected_constraints_json,
                    constraint_results_json, boundary_conditions_json,
                    calculation_meta_json, algorithm_requests_json, calculated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        String nameForInsert = finalRecordName;
        jdbc.update(c -> {
            PreparedStatement p = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            p.setLong(i++, save.projectId());
            p.setLong(i++, save.gasReservoirId());
            p.setString(i++, save.wellName().trim());
            p.setString(i++, nameForInsert);
            p.setInt(i++, save.wellboreStructure());
            p.setBigDecimal(i++, save.tubingInnerDiameterMm());
            nullableDecimal(p, i++, save.erosionLimit());
            p.setBigDecimal(i++, save.sandProductionLimit());
            p.setBigDecimal(i++, save.liquidLoadingLimit());
            p.setBigDecimal(i++, save.wellheadExportPressure());
            p.setBigDecimal(i++, save.lowerBound());
            p.setBigDecimal(i++, save.upperBound());
            p.setBoolean(i++, Boolean.TRUE.equals(save.complete()));
            p.setBoolean(i++, Boolean.TRUE.equals(save.feasible()));
            p.setBoolean(i++, Boolean.TRUE.equals(save.usableForPlan()));
            p.setInt(i++, save.inputSchemaVersion() != null ? save.inputSchemaVersion() : 1);
            p.setString(i++, write(save.inputsJson()));
            p.setString(i++, write(effectiveSelections(save)));
            p.setString(i++, write(save.constraintResultsJson()));
            p.setString(i++, write(save.boundaryConditionsJson()));
            p.setString(i++, write(save.calculationMetaJson()));
            nullableString(p, i++, save.algorithmRequestsJson() != null ? write(save.algorithmRequestsJson()) : null);
            p.setTimestamp(i, Timestamp.from(Instant.parse(save.calculatedAt())));
            return p;
        }, key);
        return detail(key.getKey().longValue());
    }

    @Transactional
    public Map<String, Object> update(long id, ConstraintRecordSaveRequest update) {
        validateSaveRequest(update);
        Map<String, Object> existing = detail(id);
        if (((Number) existing.get("projectId")).longValue() != update.projectId()
                || ((Number) existing.get("gasReservoirId")).longValue() != update.gasReservoirId()
                || !Objects.equals(existing.get("wellName"), update.wellName().trim())) {
            throw new BusinessException(404, "该历史方案不属于当前项目、气藏或井，不允许覆盖");
        }
        String finalRecordName = name(update.recordName());
        if (finalRecordName == null) {
            finalRecordName = String.valueOf(existing.getOrDefault("recordName", "约束条件" + id));
        }
        int changed = jdbc.update(
                "UPDATE multi_period_constraint_records SET" +
                        " record_name=?, wellbore_structure=?, tubing_inner_diameter_mm=?," +
                        " erosion_limit=?, sand_production_limit=?, liquid_loading_limit=?," +
                        " wellhead_export_pressure=?, lower_bound=?, upper_bound=?," +
                        " complete=?, feasible=?, usable_for_plan=?," +
                        " input_schema_version=?, inputs_json=?, selected_constraints_json=?," +
                        " constraint_results_json=?, boundary_conditions_json=?," +
                        " calculation_meta_json=?, algorithm_requests_json=?, calculated_at=?" +
                        " WHERE id=? AND project_id=? AND gas_reservoir_id=? AND well_name=?",
                finalRecordName,
                update.wellboreStructure(), update.tubingInnerDiameterMm(),
                update.erosionLimit(), update.sandProductionLimit(), update.liquidLoadingLimit(),
                update.wellheadExportPressure(), update.lowerBound(), update.upperBound(),
                Boolean.TRUE.equals(update.complete()), Boolean.TRUE.equals(update.feasible()),
                Boolean.TRUE.equals(update.usableForPlan()),
                update.inputSchemaVersion() != null ? update.inputSchemaVersion() : 1,
                write(update.inputsJson()), write(effectiveSelections(update)),
                write(update.constraintResultsJson()), write(update.boundaryConditionsJson()),
                write(update.calculationMetaJson()),
                update.algorithmRequestsJson() != null ? write(update.algorithmRequestsJson()) : null,
                Timestamp.from(Instant.parse(update.calculatedAt())),
                id, update.projectId(), update.gasReservoirId(), update.wellName().trim()
        );
        if (changed != 1) {
            throw new BusinessException(404, "数据库中未找到当前井的这条记录，更新已取消");
        }
        return detail(id);
    }

    public List<Map<String, Object>> list(long projectId, long gasReservoirId, String wellName) {
        return jdbc.queryForList(
                "SELECT id, project_id AS projectId, gas_reservoir_id AS gasReservoirId," +
                        "well_name AS wellName, record_name AS recordName," +
                        "wellbore_structure AS wellboreStructure," +
                        "tubing_inner_diameter_mm AS tubingInnerDiameterMm," +
                        "erosion_limit AS erosionLimit, sand_production_limit AS sandProductionLimit," +
                        "liquid_loading_limit AS liquidLoadingLimit," +
                        "wellhead_export_pressure AS wellheadExportPressure," +
                        "lower_bound AS lowerBound, upper_bound AS upperBound," +
                        "complete, feasible, usable_for_plan AS usableForPlan," +
                        "inputs_json AS inputsJson," +
                        "selected_constraints_json AS selectedConstraintsJson," +
                        "constraint_results_json AS constraintResultsJson," +
                        "boundary_conditions_json AS boundaryConditionsJson," +
                        "calculation_meta_json AS calculationMetaJson," +
                        "calculated_at AS calculatedAt, created_at AS createdAt" +
                        " FROM multi_period_constraint_records" +
                        " WHERE project_id=? AND gas_reservoir_id=? AND well_name=?" +
                        " ORDER BY id DESC",
                projectId, gasReservoirId, wellName.trim());
    }

    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, project_id AS projectId, gas_reservoir_id AS gasReservoirId," +
                        "well_name AS wellName, record_name AS recordName," +
                        "wellbore_structure AS wellboreStructure," +
                        "tubing_inner_diameter_mm AS tubingInnerDiameterMm," +
                        "erosion_limit AS erosionLimit, sand_production_limit AS sandProductionLimit," +
                        "liquid_loading_limit AS liquidLoadingLimit," +
                        "wellhead_export_pressure AS wellheadExportPressure," +
                        "lower_bound AS lowerBound, upper_bound AS upperBound," +
                        "complete, feasible, usable_for_plan AS usableForPlan," +
                        "input_schema_version AS inputSchemaVersion," +
                        "inputs_json AS inputsJson," +
                        "selected_constraints_json AS selectedConstraintsJson," +
                        "constraint_results_json AS constraintResultsJson," +
                        "boundary_conditions_json AS boundaryConditionsJson," +
                        "calculation_meta_json AS calculationMetaJson," +
                        "algorithm_requests_json AS algorithmRequestsJson," +
                        "calculated_at AS calculatedAt, created_at AS createdAt" +
                        " FROM multi_period_constraint_records WHERE id=?",
                id);
        if (rows.size() != 1)
            throw new BusinessException(404, "未找到约束记录");
        return rows.getFirst();
    }

    public void delete(long id, long projectId, long gasReservoirId, String wellName) {
        int count = jdbc.update(
                "DELETE FROM multi_period_constraint_records WHERE id=? AND project_id=? AND gas_reservoir_id=? AND well_name=?",
                id, projectId, gasReservoirId, wellName.trim());
        if (count == 0)
            throw new BusinessException(404, "未找到约束记录");
    }


    // 保证数据库独立数值列与界面完整输入快照、计算结果JSON完全对应。
    // 计算参数变更但未重算时不允许用旧上限覆盖新输入。
    @SuppressWarnings("unchecked")
    private static void validateSaveRequest(ConstraintRecordSaveRequest r) {
        if (r.projectId() == null || r.projectId() <= 0 ||
                r.gasReservoirId() == null || r.gasReservoirId() <= 0 ||
                r.wellName() == null || r.wellName().trim().isEmpty()) {
            throw new BusinessException(400, "项目、气藏和井号不能为空");
        }
        if (r.wellboreStructure() == null || r.wellboreStructure() < 1 || r.wellboreStructure() > 4) {
            throw new BusinessException(422, "情况5、6、7没有油管，无法计算约束条件");
        }
        if (!Boolean.TRUE.equals(r.complete()) || !Boolean.TRUE.equals(r.feasible()) ||
                !Boolean.TRUE.equals(r.usableForPlan())) {
            throw new BusinessException(422, "计算结果未完成或上下界不可行，不能保存");
        }
        Map<String, Object> inputs = r.inputsJson();
        Map<String, Object> pipe = subObject(inputs, "pipeData");
        Map<String, Object> other = subObject(inputs, "otherData");
        if (!Objects.equals(pipe.get("wellboreStructure"), "情况" + r.wellboreStructure())) {
            throw new BusinessException(422, "井身结构与表单中选择的不一致");
        }
        requireEqualNumber(pipe.get("tubingInnerDiameter"), r.tubingInnerDiameterMm(), "油管内径");
        requireEqualNumber(other.get("wellheadExportPressure"), r.wellheadExportPressure(), "井口外输压力");

        Map<String, Object> meta = r.calculationMetaJson();
        if (meta == null || !Objects.equals(meta.get("wellboreStructure"),
                "情况" + r.wellboreStructure())) {
            throw new BusinessException(422, "保存的计算工况与井身结构不一致");
        }
        requireEqualNumber(meta.get("wellStructureCode"),
                new BigDecimal(r.wellboreStructure()), "计算工况结构编号");
        requireEqualNumber(meta.get("tubingInnerDiameterMm"), r.tubingInnerDiameterMm(), "计算工况油管内径");
        requireEqualNumber(meta.get("pressureMpa"), parseDecimal(other.get("initialPressure"), "原始地层压力"), "计算工况地层压力");
        requireEqualNumber(meta.get("temperatureC"), parseDecimal(other.get("reservoirTemperature"), "地层温度"), "计算工况地层温度");
        requireEqualNumber(meta.get("evaluatedRate1e4M3d"), parseDecimal(other.get("productionRate"), "产气量"), "计算工况产气量");

        Map<String, Object> selected = r.selectedConstraintsJson();
        Map<String, Object> results = r.constraintResultsJson();
        Map<String, Object> bounds = r.boundaryConditionsJson();
        if (selected == null || results == null || bounds == null ||
                !Boolean.TRUE.equals(selected.get("sandProductionLimit")) ||
                !Boolean.TRUE.equals(selected.get("liquidLoadingLimit"))) {
            throw new BusinessException(422, "必须先完成并选中出砂、积液约束");
        }
        boolean erosionRequested = Boolean.TRUE.equals(selected.get("erosionLimit"));
        // 冲蚀为非必需：请求曾勾选冲蚀但本次值为空时，自动按未启用处理。
        // 数据库 erosion_limit 保持 NULL，constraint_results_json 仍完整保存失败原因。
        boolean erosionApplied = erosionRequested && r.erosionLimit() != null;
        requireVerifiedResult(results, "sandProductionLimit", r.sandProductionLimit());
        requireVerifiedResult(results, "liquidLoadingLimit", r.liquidLoadingLimit());
        requireVerifiedResult(results, "wellheadExportPressure", r.wellheadExportPressure());
        if (erosionApplied) {
            requireVerifiedResult(results, "erosionLimit", r.erosionLimit());
        } else if (!erosionRequested && r.erosionLimit() != null) {
            throw new BusinessException(422, "冲蚀约束未选择，却提交了冲蚀上限");
        }
        BigDecimal upper = erosionApplied
                ? r.sandProductionLimit().min(r.erosionLimit()) : r.sandProductionLimit();
        if (r.liquidLoadingLimit().compareTo(upper) > 0 ||
                r.lowerBound().compareTo(r.liquidLoadingLimit()) != 0 ||
                r.upperBound().compareTo(upper) != 0) {
            throw new BusinessException(422, "提交的上下界与本次计算结果不一致");
        }
        requireEqualNumber(bounds.get("sandProductionLimit"), r.sandProductionLimit(), "出砂边界JSON");
        requireEqualNumber(bounds.get("liquidLoadingLimit"), r.liquidLoadingLimit(), "积液边界JSON");
        requireEqualNumber(bounds.get("wellheadExportPressure"), r.wellheadExportPressure(), "外输压力边界JSON");
        if (erosionApplied) {
            requireEqualNumber(bounds.get("erosionLimit"), r.erosionLimit(), "冲蚀边界JSON");
        } else if (bounds.get("erosionLimit") != null) {
            throw new BusinessException(422, "冲蚀未生效，但提交的边界JSON中仍含冲蚀数值");
        }
    }

    // 客户端可能勾选过冲蚀但计算失败。数据库保存实际生效的约束开关，
    // 防止方案生成误把该项当作必须满足的有效边界。
    private static Map<String, Object> effectiveSelections(ConstraintRecordSaveRequest r) {
        Map<String, Object> flags = new LinkedHashMap<>(r.selectedConstraintsJson());
        if (r.erosionLimit() == null) flags.put("erosionLimit", false);
        return flags;
    }

    private static Map<String, Object> subObject(Map<String, Object> parent, String field) {
        Object child = parent == null ? null : parent.get(field);
        if (!(child instanceof Map<?, ?>)) {
            throw new BusinessException(422, "输入快照缺少 " + field);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) child;
        return typed;
    }

    private static BigDecimal parseDecimal(Object value, String label) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw new BusinessException(422, label + "不能为空");
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new BusinessException(422, label + "不是有效数字");
        }
    }

    private static void requireEqualNumber(Object fromJson, BigDecimal column, String label) {
        if (column == null || parseDecimal(fromJson, label).compareTo(column) != 0) {
            throw new BusinessException(422, label + "与本次输入或计算结果不一致，不能保存");
        }
    }

    private static void requireVerifiedResult(Map<String, Object> results, String key, BigDecimal expected) {
        Map<String, Object> item = subObject(results, key);
        if (!Boolean.TRUE.equals(item.get("available")) ||
                !Boolean.TRUE.equals(item.get("verified"))) {
            throw new BusinessException(422, key + "计算结果尚未验证");
        }
        requireEqualNumber(item.get("value"), expected, key + "结果");
    }

    private static void nullableDecimal(PreparedStatement p, int index, BigDecimal value) throws java.sql.SQLException {
        if (value == null) p.setNull(index, java.sql.Types.DECIMAL);
        else p.setBigDecimal(index, value);
    }

    private static void nullableString(PreparedStatement p, int index, String value) throws java.sql.SQLException {
        if (value == null) p.setNull(index, java.sql.Types.VARCHAR);
        else p.setString(index, value);
    }

    private String write(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (JacksonException e) {
            throw new BusinessException(500, "约束快照序列化失败");
        }
    }

    private static String name(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}