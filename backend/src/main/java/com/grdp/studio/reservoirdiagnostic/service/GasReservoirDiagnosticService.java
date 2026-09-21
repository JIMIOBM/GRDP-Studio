package com.grdp.studio.reservoirdiagnostic.service;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.diagnostic.dto.DiagnosticCurveModels;
import com.grdp.studio.diagnostic.service.DiagnosticCurveService;
import com.grdp.studio.reservoirdiagnostic.dto.GasReservoirDiagnosticModels;
import com.grdp.studio.reservoirdiagnostic.mapper.GasReservoirDiagnosticMapper;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 库级诊断曲线服务。
 *
 * <p>数据流程：</p>
 *
 * <pre>
 * 储气库全部井
 *      ↓
 * 每口井最新 CALCULATED 诊断方案
 *      ↓
 * project_well_diagnostic_input
 *      ↓
 * 按时间汇总 SUM(gas_volume)
 *      ↓
 * 10^4m3 -> 10^8m3
 *      ↓
 * 根据汇总后的正负值重新生成库级周期
 *      ↓
 * DiagnosticCurveService
 *      ↓
 * 库级诊断曲线
 * </pre>
 */
@Service
public class GasReservoirDiagnosticService {

    /**
     * 数据库 gas_volume：
     * 1 = 10^4 m3
     *
     * 现有 DiagnosticCurveService：
     * 1 = 10^8 m3
     */
    private static final double GAS_1E4_TO_1E8 =
            1.0e-4;

    private static final double EPSILON =
            1.0e-12;

    private static final List<DateTimeFormatter>
            DATE_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            // 四位年份在前
            DateTimeFormatter.ofPattern("uuuu-M-d"),
            DateTimeFormatter.ofPattern("uuuu/M/d"),
            DateTimeFormatter.ofPattern("uuuu.M.d"),
            // 四位年份在后（月/日/年）
            DateTimeFormatter.ofPattern("M/d/uuuu"),
            DateTimeFormatter.ofPattern("M-d-uuuu"),
            DateTimeFormatter.ofPattern("M.d.uuuu"),
            // 两位年份在后（月/日/年）
            DateTimeFormatter.ofPattern("M/d/yy"),
            DateTimeFormatter.ofPattern("M-d-yy"),
            DateTimeFormatter.ofPattern("M.d.yy"),
            // 两位年份在前（年/月/日）
            DateTimeFormatter.ofPattern("yy/M/d"),
            DateTimeFormatter.ofPattern("yy-M-d"),
            DateTimeFormatter.ofPattern("yy.M.d")
    );

    private static final List<DateTimeFormatter>
            YEAR_MONTH_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("uuuu-MM"),
            DateTimeFormatter.ofPattern("uuuu-M"),
            DateTimeFormatter.ofPattern("uuuu/MM"),
            DateTimeFormatter.ofPattern("uuuu/M"),
            DateTimeFormatter.ofPattern("uuuu.MM"),
            DateTimeFormatter.ofPattern("uuuu.M"),
            // 月/两位年份
            DateTimeFormatter.ofPattern("MM/yy"),
            DateTimeFormatter.ofPattern("M/yy"),
            DateTimeFormatter.ofPattern("MM-yy"),
            DateTimeFormatter.ofPattern("M-yy")
    );

    private final GasReservoirDiagnosticMapper mapper;

    private final DiagnosticCurveService
            diagnosticCurveService;

    private final ObjectMapper objectMapper;

    public GasReservoirDiagnosticService(
            GasReservoirDiagnosticMapper mapper,
            DiagnosticCurveService diagnosticCurveService,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.diagnosticCurveService =
                diagnosticCurveService;
        this.objectMapper = objectMapper;
    }

    /**
     * 页面初始化：
     *
     * <p>
     * 返回库内井数量、哪些井缺诊断数据、
     * 以及当前可选择的代表 PVT。
     * </p>
     */
    public GasReservoirDiagnosticModels.ContextResponse context(
            long projectId,
            long gasReservoirId,
            long storageId
    ) {
        if (projectId <= 0) {
            throw new BusinessException(400, "项目ID必须大于0");
        }

        if (gasReservoirId <= 0) {
            throw new BusinessException(400, "气藏ID必须大于0");
        }

        if (storageId <= 0) {
            throw new BusinessException(400, "储气库ID必须大于0");
        }
        final List<GasReservoirDiagnosticModels.WellRow> wells =
                mapper.selectStorageWells(projectId, gasReservoirId, storageId);

        final List<GasReservoirDiagnosticModels.LatestDiagnosticRow>
                diagnostics =
                mapper.selectLatestCalculatedDiagnostics(
                        projectId,
                        gasReservoirId,
                        storageId
                );

        final Map<Long,
                GasReservoirDiagnosticModels.LatestDiagnosticRow>
                diagnosticByWellId =
                new LinkedHashMap<>();

        for (
                GasReservoirDiagnosticModels.LatestDiagnosticRow
                        row : diagnostics
        ) {

            if (row.getWellId() != null) {
                diagnosticByWellId.put(
                        row.getWellId(),
                        row
                );
            }
        }

        final List<String> missingWells =
                new ArrayList<>();

        for (
                GasReservoirDiagnosticModels.WellRow
                        well : wells
        ) {

            final GasReservoirDiagnosticModels.LatestDiagnosticRow
                    diagnostic =
                    diagnosticByWellId.get(
                            well.getWellId()
                    );

            /*
             * 不仅要有 CALCULATED 主记录，
             * 还必须真正有 input。
             */
            if (
                    diagnostic == null
                            || diagnostic.getInputCount() == null
                            || diagnostic.getInputCount() <= 0
            ) {

                missingWells.add(
                        safeWellName(well)
                );
            }
        }

        /*
         * PVT ID 全局唯一，因此按 pvtId 去重。
         */
        final Map<Long,
                GasReservoirDiagnosticModels.PvtOption>
                pvtMap =
                new LinkedHashMap<>();

        for (
                GasReservoirDiagnosticModels.LatestDiagnosticRow
                        diagnostic : diagnostics
        ) {

            if (
                    diagnostic.getPvtId() == null
                            || diagnostic.getPvtId() <= 0
            ) {
                continue;
            }

            if (
                    diagnostic.getPvtSnapshot() == null
                            || diagnostic.getPvtSnapshot()
                            .isBlank()
            ) {
                continue;
            }

            pvtMap.putIfAbsent(
                    diagnostic.getPvtId(),
                    new GasReservoirDiagnosticModels.PvtOption(
                            diagnostic.getPvtId(),
                            diagnostic.getPvtName(),
                            diagnostic.getWellName()
                    )
            );
        }

        return new GasReservoirDiagnosticModels.ContextResponse(
                wells.size(),
                wells.size() - missingWells.size(),
                List.copyOf(missingWells),
                List.copyOf(pvtMap.values())
        );
    }

    /**
     * 计算库级诊断曲线。
     */
    public GasReservoirDiagnosticModels.CalculateResponse
    calculate(
            GasReservoirDiagnosticModels.CalculateRequest request,
            String token,
            String cookie,
            String processEnv
    ) {

        validateCalculateRequest(
                request
        );

        /*
         * ---------------------------------------------------------
         * 1. 查询库内全部井。
         * ---------------------------------------------------------
         */
        final List<GasReservoirDiagnosticModels.WellRow>
                allWells =
                mapper.selectStorageWells(
                        request.projectId(),
                        request.gasReservoirId(),
                        request.storageId()
                );

        if (
                allWells == null
                        || allWells.isEmpty()
        ) {
            throw new BusinessException(
                    404,
                    "当前储气库没有关联任何井"
            );
        }

        /*
         * ---------------------------------------------------------
         * 2. 每口井找编号最大的 CALCULATED 方案。
         * ---------------------------------------------------------
         */
        final List<GasReservoirDiagnosticModels.LatestDiagnosticRow>
                diagnostics =
                mapper.selectLatestCalculatedDiagnostics(
                        request.projectId(),
                        request.gasReservoirId(),
                        request.storageId()
                );

        final Map<Long,
                GasReservoirDiagnosticModels.LatestDiagnosticRow>
                diagnosticByWell =
                new LinkedHashMap<>();

        for (
                GasReservoirDiagnosticModels.LatestDiagnosticRow
                        diagnostic : diagnostics
        ) {

            if (
                    diagnostic.getWellId()
                            != null
            ) {

                diagnosticByWell.put(
                        diagnostic.getWellId(),
                        diagnostic
                );
            }
        }

        /*
         * ---------------------------------------------------------
         * 3. 强制所有井都必须有有效诊断 input。
         *
         * 不允许：
         *
         * 库有 10 口井，
         * 实际只拿 8 口井计算，
         * 但前端仍展示成“库诊断曲线”。
         * ---------------------------------------------------------
         */
        final List<String>
                missingWells =
                new ArrayList<>();

        for (
                GasReservoirDiagnosticModels.WellRow
                        well : allWells
        ) {

            final GasReservoirDiagnosticModels.LatestDiagnosticRow
                    diagnostic =
                    diagnosticByWell.get(
                            well.getWellId()
                    );

            if (
                    diagnostic == null
                            || diagnostic.getInputCount() == null
                            || diagnostic.getInputCount() <= 0
            ) {

                missingWells.add(
                        safeWellName(well)
                );
            }
        }

        if (!missingWells.isEmpty()) {

            throw new BusinessException(
                    400,
                    "以下井没有已计算且包含输入数据的诊断方案，"
                            + "库级诊断计算已停止："
                            + String.join(
                            "、",
                            missingWells
                    )
            );
        }

        /*
         * ---------------------------------------------------------
         * 4. 找用户选择的代表 PVT。
         *
         * 当前没有独立的库级 PVT 表，
         * 所以从当前有效单井诊断方案的 pvt_snapshot 中取得。
         * ---------------------------------------------------------
         */
        GasReservoirDiagnosticModels.LatestDiagnosticRow
                pvtSource = null;

        for (
                GasReservoirDiagnosticModels.LatestDiagnosticRow
                        diagnostic : diagnostics
        ) {

            if (
                    diagnostic.getPvtId() != null
                            && diagnostic.getPvtId()
                            == request.pvtId()
                            && diagnostic.getPvtSnapshot()
                            != null
                            && !diagnostic.getPvtSnapshot()
                            .isBlank()
            ) {

                pvtSource = diagnostic;
                break;
            }
        }

        if (pvtSource == null) {

            throw new BusinessException(
                    400,
                    "没有找到所选PVT对应的有效PVT快照。"
                            + "请重新进入库诊断页面并选择当前可用的PVT。"
            );
        }

        final DiagnosticCurveModels.PvtData
                pvt =
                parsePvtSnapshot(
                        pvtSource.getPvtSnapshot()
                );

        /*
         * ---------------------------------------------------------
         * 5. 查询所有井当前方案的 input。
         * ---------------------------------------------------------
         */
        final List<GasReservoirDiagnosticModels.DiagnosticInputRow>
                inputRows =
                mapper.selectLatestDiagnosticInputs(
                        request.projectId(),
                        request.gasReservoirId(),
                        request.storageId()
                );

        if (
                inputRows == null
                        || inputRows.isEmpty()
        ) {

            throw new BusinessException(
                    400,
                    "当前储气库没有可用于库级计算的诊断输入数据"
            );
        }

        /*
         * ---------------------------------------------------------
         * 6. 根据 time_text 汇总所有井 gas_volume。
         *
         * 数据库：
         *      gas_volume 单位 = 10^4m3
         *
         * 同一天：
         *
         * 井1 -700
         * 井2 -300
         * 井3 -500
         *
         * 库级 = -1500
         * ---------------------------------------------------------
         */
        final TreeMap<String, AggregateBucket>
                aggregateMap =
                new TreeMap<>();

        for (
                GasReservoirDiagnosticModels.DiagnosticInputRow
                        row : inputRows
        ) {

            if (
                    row.getTimeText() == null
                            || row.getTimeText()
                            .isBlank()
            ) {

                throw new BusinessException(
                        400,
                        "井【"
                                + safeText(
                                row.getWellName()
                        )
                                + "】存在时间为空的诊断输入数据"
                );
            }

            if (row.getGasVolume() == null) {

                throw new BusinessException(
                        400,
                        "井【"
                                + safeText(
                                row.getWellName()
                        )
                                + "】时间【"
                                + row.getTimeText()
                                + "】的注采气量为空"
                );
            }

            final String normalizedTime;

            try {
                normalizedTime =
                        normalizeTime(
                                row.getTimeText()
                        );
            } catch (BusinessException e) {

                throw new BusinessException(
                        400,
                        "井【"
                                + safeText(row.getWellName())
                                + "】诊断方案ID【"
                                + row.getDiagnosticId()
                                + "】第【"
                                + row.getSequenceNo()
                                + "】条输入数据时间非法：【"
                                + row.getTimeText()
                                + "】。"
                                + "请检查 project_well_diagnostic_input.time_text，"
                                + "建议统一保存为 yyyy-MM-dd"
                );
            }

            final AggregateBucket bucket =
                    aggregateMap.computeIfAbsent(
                            normalizedTime,
                            ignored ->
                                    new AggregateBucket()
                    );

            bucket.gasVolume1e4 =
                    bucket.gasVolume1e4.add(
                            row.getGasVolume()
                    );

            if (row.getWellId() != null) {
                bucket.wellIds.add(
                        row.getWellId()
                );
            }
        }

        /*
         * ---------------------------------------------------------
         * 7. 根据聚合后的净注采方向重新产生“库级周期”。
         *
         * 规则：
         *
         * gas < 0  = 注气
         * gas > 0  = 采气
         *
         * 第一次：
         * 注气 -> 采气
         *          属于第1周期
         *
         * 之后：
         * 采气 -> 注气
         *          开始第2周期
         * ---------------------------------------------------------
         */
        final BuildProductionResult
                productionResult =
                buildProductionData(
                        aggregateMap
                );

        if (
                productionResult.productionData()
                        .isEmpty()
        ) {

            throw new BusinessException(
                    400,
                    "库内全部井按时间汇总后，净注采气量全部为0，无法计算诊断曲线"
            );
        }

        /*
         * ---------------------------------------------------------
         * 8. 复用已有单井诊断算法。
         *
         * wellName 在现有算法中只是请求必填项，
         * 库级计算使用固定标识即可。
         * ---------------------------------------------------------
         */
        final DiagnosticCurveModels.CalculateRequest
                calculateRequest =
                new DiagnosticCurveModels.CalculateRequest(
                        request.projectId(),

                        request.gasReservoirId(),

                        "STORAGE-"
                                + request.storageId(),

                        request.pvtId(),

                        request.upperLimit(),

                        request.lowerLimit(),

                        pvt,

                        productionResult.productionData()
                );

        /*
         * ---------------------------------------------------------
         * 9. 真正调用原来的计算内核。
         * ---------------------------------------------------------
         */
        final DiagnosticCurveModels.CalculateResponse
                curveResult =
                diagnosticCurveService.calculate(
                        calculateRequest,
                        token,
                        cookie,
                        processEnv
                );

        /*
         * ---------------------------------------------------------
         * 10. 返回。
         * ---------------------------------------------------------
         */
        return new GasReservoirDiagnosticModels.CalculateResponse(
                allWells.size(),

                allWells.size(),

                productionResult.aggregatedRows(),

                curveResult
        );
    }

    /**
     * 把按时间 SUM 后的数据转换为现有
     * DiagnosticCurveService 的 productionData。
     */
    private BuildProductionResult
    buildProductionData(
            TreeMap<String, AggregateBucket>
                    aggregateMap
    ) {

        final List<DiagnosticCurveModels.ProductionDataItem>
                productionData =
                new ArrayList<>();

        final List<GasReservoirDiagnosticModels.AggregatedRow>
                aggregatedRows =
                new ArrayList<>();

        int sequence = 1;

        int cycleNo = 1;

        Direction previousDirection =
                null;

        /*
         * 用来提前检查每一个周期是否既有注气又有采气。
         */
        final Map<Integer, CycleState>
                cycleStates =
                new LinkedHashMap<>();

        for (
                Map.Entry<String, AggregateBucket>
                        entry : aggregateMap.entrySet()
        ) {

            final String time =
                    entry.getKey();

            final AggregateBucket bucket =
                    entry.getValue();

            final double gas1e4 =
                    bucket.gasVolume1e4
                            .doubleValue();

            final double gas1e8 =
                    gas1e4
                            * GAS_1E4_TO_1E8;

            /*
             * 同一时间各井正负气量正好抵消：
             *
             * -1000 + 1000 = 0
             *
             * 库库存不发生变化。
             * DiagnosticCurveService 当前不接受 gas=0，
             * 因此这里过滤。
             */
            if (
                    !Double.isFinite(
                            gas1e8
                    )
                            || Math.abs(
                            gas1e8
                    ) <= EPSILON
            ) {
                continue;
            }

            final Direction direction =
                    gas1e8 < 0
                            ? Direction.INJECTION
                            : Direction.PRODUCTION;

            /*
             * 只有：
             *
             * 采气 -> 注气
             *
             * 才表示进入下一个完整注采周期。
             */
            if (
                    previousDirection
                            == Direction.PRODUCTION
                            && direction
                            == Direction.INJECTION
            ) {

                cycleNo++;
            }

            final String cycleName =
                    direction
                            == Direction.INJECTION
                            ? "第"
                            + cycleNo
                            + "周期注气"

                            : "第"
                            + cycleNo
                            + "周期采气";

            final CycleState cycleState =
                    cycleStates.computeIfAbsent(
                            cycleNo,
                            ignored ->
                                    new CycleState()
                    );

            if (
                    direction
                            == Direction.INJECTION
            ) {
                cycleState.hasInjection =
                        true;
            } else {
                cycleState.hasProduction =
                        true;
            }

            productionData.add(
                    new DiagnosticCurveModels.ProductionDataItem(
                            sequence,
                            time,
                            gas1e8,
                            cycleName
                    )
            );

            aggregatedRows.add(
                    new GasReservoirDiagnosticModels.AggregatedRow(
                            sequence,
                            time,
                            gas1e4,
                            gas1e8,
                            direction.name(),
                            cycleName,
                            bucket.wellIds.size()
                    )
            );

            sequence++;

            previousDirection =
                    direction;
        }

        /*
         * DiagnosticCurveService 本身也会校验，
         * 这里提前给用户一个更直接的库级报错。
         */
        for (
                Map.Entry<Integer, CycleState>
                        entry : cycleStates.entrySet()
        ) {

            final CycleState state =
                    entry.getValue();

            if (
                    !state.hasInjection
                            || !state.hasProduction
            ) {

                throw new BusinessException(
                        400,
                        "库级聚合后的第"
                                + entry.getKey()
                                + "周期不完整：必须同时包含注气和采气数据。"
                                + "请检查所有单井当前诊断方案的时间范围是否完整。"
                );
            }
        }

        return new BuildProductionResult(
                List.copyOf(
                        productionData
                ),
                List.copyOf(
                        aggregatedRows
                )
        );
    }

    /**
     * 从 project_well_diagnostic.pvt_snapshot
     * 恢复计算时实际使用的 PVT。
     *
     * 期望 JSON：
     *
     * {
     *   "fixedZ": null,
     *   "zCurve": [
     *     {
     *       "pressure": 2,
     *       "zFactor": 0.95
     *     }
     *   ]
     * }
     */
    private DiagnosticCurveModels.PvtData
    parsePvtSnapshot(
            String json
    ) {

        if (
                json == null
                        || json.isBlank()
        ) {

            throw new BusinessException(
                    400,
                    "所选PVT没有保存计算快照"
            );
        }

        try {

            final DiagnosticCurveModels.PvtData
                    pvt =
                    objectMapper.readValue(
                            json,
                            DiagnosticCurveModels.PvtData.class
                    );

            validatePvtSnapshot(
                    pvt
            );

            return pvt;

        } catch (BusinessException e) {
            throw e;

        } catch (Exception e) {

            throw new BusinessException(
                    400,
                    "PVT快照解析失败，请重新计算并保存对应的单井诊断曲线"
            );
        }
    }

    private void validatePvtSnapshot(
            DiagnosticCurveModels.PvtData pvt
    ) {

        if (pvt == null) {
            throw new BusinessException(
                    400,
                    "PVT快照为空"
            );
        }

        final boolean validFixedZ =
                pvt.fixedZ() != null
                        && Double.isFinite(
                        pvt.fixedZ()
                )
                        && pvt.fixedZ() > 0;

        boolean validCurve =
                false;

        if (
                pvt.zCurve() != null
                        && pvt.zCurve()
                        .size() >= 2
        ) {

            validCurve =
                    pvt.zCurve()
                            .stream()
                            .allMatch(
                                    point ->
                                            point != null
                                                    && point.pressure()
                                                    != null
                                                    && point.zFactor()
                                                    != null
                                                    && Double.isFinite(
                                                    point.pressure()
                                            )
                                                    && Double.isFinite(
                                                    point.zFactor()
                                            )
                                                    && point.pressure()
                                                    > 0
                                                    && point.zFactor()
                                                    > 0
                            );
        }

        if (
                !validFixedZ
                        && !validCurve
        ) {

            throw new BusinessException(
                    400,
                    "PVT快照中既没有有效 fixedZ，"
                            + "也没有至少2个有效的 Pressure-Z 数据点"
            );
        }
    }

    private void validateCalculateRequest(
            GasReservoirDiagnosticModels.CalculateRequest
                    request
    ) {

        if (request == null) {

            throw new BusinessException(
                    400,
                    "计算参数不能为空"
            );
        }

        if (
                request.projectId()
                        <= 0
        ) {

            throw new BusinessException(
                    400,
                    "项目ID必须大于0"
            );
        }

        if (
                request.gasReservoirId()
                        <= 0
        ) {

            throw new BusinessException(
                    400,
                    "气藏ID必须大于0"
            );
        }

        if (
                request.storageId()
                        <= 0
        ) {

            throw new BusinessException(
                    400,
                    "储气库ID必须大于0"
            );
        }

        if (
                request.pvtId()
                        <= 0
        ) {

            throw new BusinessException(
                    400,
                    "PVT ID必须大于0"
            );
        }

        if (
                request.upperLimit() == null
                        || request.lowerLimit()
                        == null
                        || !Double.isFinite(
                        request.upperLimit()
                )
                        || !Double.isFinite(
                        request.lowerLimit()
                )
        ) {

            throw new BusinessException(
                    400,
                    "压力上下限必须是有效数字"
            );
        }

        if (
                request.lowerLimit()
                        <= 0
        ) {

            throw new BusinessException(
                    400,
                    "压力下限必须大于0"
            );
        }

        if (
                request.upperLimit()
                        <= request.lowerLimit()
        ) {

            throw new BusinessException(
                    400,
                    "压力上限必须大于压力下限"
            );
        }
    }

    /**
     * 把常见时间格式统一成 yyyy-MM-dd，
     * 这样不同井的：
     *
     * 2024/1/1
     * 2024-01-01
     *
     * 可以正确聚合到同一天。
     */
    private String normalizeTime(
            String raw
    ) {

        if (
                raw == null
                        || raw.isBlank()
        ) {

            throw new BusinessException(
                    400,
                    "诊断输入时间不能为空"
            );
        }

        String value =
                raw.trim();

        /*
         * 兼容：
         * 2024-01-01 00:00:00
         * 2024-01-01T00:00:00
         */
        final int spaceIndex =
                value.indexOf(' ');

        final int tIndex =
                value.indexOf('T');

        int cutIndex = -1;

        if (
                spaceIndex > 0
                        && tIndex > 0
        ) {

            cutIndex =
                    Math.min(
                            spaceIndex,
                            tIndex
                    );

        } else if (spaceIndex > 0) {

            cutIndex =
                    spaceIndex;

        } else if (tIndex > 0) {

            cutIndex =
                    tIndex;
        }

        if (cutIndex > 0) {
            value =
                    value.substring(
                            0,
                            cutIndex
                    );
        }

        for (
                DateTimeFormatter formatter
                : DATE_FORMATTERS
        ) {

            try {

                return LocalDate.parse(
                                value,
                                formatter
                        )
                        .toString();

            } catch (
                    DateTimeParseException ignored
            ) {
                // 尝试下一个格式
            }
        }

        /*
         * 如果原始数据只有月份：
         *
         * 2024-01
         *
         * 统一成：
         *
         * 2024-01-01
         */
        for (
                DateTimeFormatter formatter
                : YEAR_MONTH_FORMATTERS
        ) {

            try {

                return YearMonth.parse(
                                value,
                                formatter
                        )
                        .atDay(1)
                        .toString();

            } catch (
                    DateTimeParseException ignored
            ) {
                // 尝试下一个格式
            }
        }

        throw new BusinessException(
                400,
                "无法识别诊断输入时间格式：【"
                        + raw
                        + "】。"
                        + "建议统一使用 yyyy-MM-dd"
        );
    }

    private String safeWellName(
            GasReservoirDiagnosticModels.WellRow
                    well
    ) {

        if (
                well == null
                        || well.getWellName()
                        == null
                        || well.getWellName()
                        .isBlank()
        ) {

            return "井ID="
                    + (
                    well == null
                            ? "?"
                            : well.getWellId()
            );
        }

        return well.getWellName();
    }

    private String safeText(
            String value
    ) {

        return value == null
                || value.isBlank()
                ? "-"
                : value;
    }

    private enum Direction {
        INJECTION,
        PRODUCTION
    }

    private static final class AggregateBucket {

        private BigDecimal gasVolume1e4 =
                BigDecimal.ZERO;

        private final Set<Long> wellIds =
                new LinkedHashSet<>();
    }

    private static final class CycleState {

        private boolean hasInjection;

        private boolean hasProduction;
    }

    private record BuildProductionResult(
            List<DiagnosticCurveModels.ProductionDataItem>
            productionData,

            List<GasReservoirDiagnosticModels.AggregatedRow>
            aggregatedRows
    ) {
    }
}