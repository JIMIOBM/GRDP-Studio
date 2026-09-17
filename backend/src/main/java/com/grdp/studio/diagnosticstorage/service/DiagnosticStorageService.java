package com.grdp.studio.diagnosticstorage.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.grdp.studio.common.BusinessException;
import com.grdp.studio.diagnostic.dto.DiagnosticCurveModels;
import com.grdp.studio.diagnosticstorage.dto.DiagnosticRecordDetail;
import com.grdp.studio.diagnosticstorage.dto.DiagnosticRecordSummary;
import com.grdp.studio.diagnosticstorage.dto.DiagnosticSaveRequest;
import com.grdp.studio.diagnosticstorage.dto.DiagnosticSaveResponse;
import com.grdp.studio.diagnosticstorage.entity.DiagnosticEntity;
import com.grdp.studio.diagnosticstorage.entity.DiagnosticInputEntity;
import com.grdp.studio.diagnosticstorage.entity.DiagnosticPvtLookupEntity;
import com.grdp.studio.diagnosticstorage.entity.DiagnosticResultEntity;
import com.grdp.studio.diagnosticstorage.entity.DiagnosticWellLookupEntity;
import com.grdp.studio.diagnosticstorage.mapper.DiagnosticInputMapper;
import com.grdp.studio.diagnosticstorage.mapper.DiagnosticMapper;
import com.grdp.studio.diagnosticstorage.mapper.DiagnosticPvtLookupMapper;
import com.grdp.studio.diagnosticstorage.mapper.DiagnosticResultMapper;
import com.grdp.studio.diagnosticstorage.mapper.DiagnosticWellLookupMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 井控库存诊断曲线三表统一存储服务。
 *
 * <p>三表：</p>
 * <ul>
 *   <li>project_well_diagnostic：方案主表；</li>
 *   <li>project_well_diagnostic_input：注采输入明细；</li>
 *   <li>project_well_diagnostic_result：当前确认保存的计算结果。</li>
 * </ul>
 *
 * <p>与当前计算接口的单位约定：</p>
 * <ul>
 *   <li>productionData.gas：10^8 m3；</li>
 *   <li>数据库 gas_volume：10^4 m3；</li>
 *   <li>保存时乘 10000，回读时除 10000；</li>
 *   <li>库存：10^8 m3；压力和 P/Z：MPa。</li>
 * </ul>
 */
@Service
public class DiagnosticStorageService {

    private static final double EPSILON = 1e-12;
    private static final double GAS_1E8_TO_DB_1E4 = 10000.0;
    private static final int CREATE_RETRY_LIMIT = 5;

    /**
     * 当前 DiagnosticCurveService 对应的存储版本。
     * 如果以后把“重建梭形”改成实测压力算法，请同步升级该版本号。
     */
    private static final String CURRENT_CALCULATION_VERSION = "DC_RECONSTRUCT_V1";

    private final DiagnosticWellLookupMapper wellLookupMapper;
    private final DiagnosticPvtLookupMapper pvtLookupMapper;
    private final DiagnosticMapper diagnosticMapper;
    private final DiagnosticInputMapper inputMapper;
    private final DiagnosticResultMapper resultMapper;
    private final ObjectMapper objectMapper;

    public DiagnosticStorageService(
            DiagnosticWellLookupMapper wellLookupMapper,
            DiagnosticPvtLookupMapper pvtLookupMapper,
            DiagnosticMapper diagnosticMapper,
            DiagnosticInputMapper inputMapper,
            DiagnosticResultMapper resultMapper,
            ObjectMapper objectMapper
    ) {
        this.wellLookupMapper = wellLookupMapper;
        this.pvtLookupMapper = pvtLookupMapper;
        this.diagnosticMapper = diagnosticMapper;
        this.inputMapper = inputMapper;
        this.resultMapper = resultMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 左侧树：查询当前井已经真实保存的诊断方案。
     */
    public List<DiagnosticRecordSummary> list(
            long projectId,
            long gasReservoirId,
            String wellName
    ) {
        if (wellName == null || wellName.isBlank()) {
            return List.of();
        }

        List<Long> wellIds = findWellIds(
                projectId,
                gasReservoirId,
                wellName.trim()
        );

        if (wellIds.isEmpty()) {
            return List.of();
        }

        if (wellIds.size() > 1) {
            throw new BusinessException(
                    409,
                    "当前项目、气藏和井名对应多口井，请先清理重复井数据"
            );
        }

        long wellId = wellIds.getFirst();

        return diagnosticMapper.selectList(
                        new LambdaQueryWrapper<DiagnosticEntity>()
                                .eq(DiagnosticEntity::getProjectId, projectId)
                                .eq(DiagnosticEntity::getGasReservoirId, gasReservoirId)
                                .eq(DiagnosticEntity::getWellId, wellId)
                                .orderByAsc(DiagnosticEntity::getDiagnosticNo)
                ).stream()
                .map(DiagnosticRecordSummary::from)
                .toList();
    }

    /**
     * 再次打开：恢复主表、输入、PVT快照和结果快照，不自动重新计算。
     */
    public DiagnosticRecordDetail getDetail(
            long diagnosticId,
            long projectId,
            long gasReservoirId,
            String wellName
    ) {
        long wellId = requireWellId(
                projectId,
                gasReservoirId,
                wellName
        );

        DiagnosticEntity diagnostic = requireDiagnostic(
                diagnosticId,
                projectId,
                gasReservoirId,
                wellId
        );

        List<DiagnosticCurveModels.ProductionDataItem> productionData =
                inputMapper.selectList(
                                new LambdaQueryWrapper<DiagnosticInputEntity>()
                                        .eq(
                                                DiagnosticInputEntity::getDiagnosticId,
                                                diagnosticId
                                        )
                                        .orderByAsc(
                                                DiagnosticInputEntity::getSequenceNo
                                        )
                        ).stream()
                        .map(this::toProductionDataItem)
                        .toList();

        DiagnosticResultEntity savedResult =
                resultMapper.selectOne(
                        new LambdaQueryWrapper<DiagnosticResultEntity>()
                                .eq(
                                        DiagnosticResultEntity::getDiagnosticId,
                                        diagnosticId
                                )
                );

        DiagnosticCurveModels.PvtData pvtSnapshot =
                deserializeNullable(
                        diagnostic.getPvtSnapshot(),
                        DiagnosticCurveModels.PvtData.class,
                        "PVT快照"
                );

        DiagnosticCurveModels.CalculateResponse result =
                savedResult == null
                        ? null
                        : deserializeRequired(
                                savedResult.getResultSnapshot(),
                                DiagnosticCurveModels.CalculateResponse.class,
                                "诊断结果快照"
                        );

        return new DiagnosticRecordDetail(
                DiagnosticRecordSummary.from(diagnostic),
                diagnostic.getProjectId(),
                diagnostic.getGasReservoirId(),
                diagnostic.getWellId(),
                diagnostic.getPvtId(),
                diagnostic.getPvtName(),
                pvtSnapshot,
                diagnostic.getUpperPressureLimit(),
                diagnostic.getLowerPressureLimit(),
                diagnostic.getRemark(),
                productionData,
                result,
                savedResult == null
                        ? null
                        : savedResult.getCalculationVersion(),
                savedResult == null
                        ? null
                        : savedResult.getCalculatedAt()
        );
    }

    /**
     * 显式删除子表，再删除主表。
     * 即使数据库外键级联在某环境中配置异常，也不会留下孤儿数据。
     */
    @Transactional
    public void delete(
            long diagnosticId,
            long projectId,
            long gasReservoirId,
            String wellName
    ) {
        long wellId = requireWellId(
                projectId,
                gasReservoirId,
                wellName
        );

        requireDiagnostic(
                diagnosticId,
                projectId,
                gasReservoirId,
                wellId
        );

        resultMapper.delete(
                new LambdaQueryWrapper<DiagnosticResultEntity>()
                        .eq(
                                DiagnosticResultEntity::getDiagnosticId,
                                diagnosticId
                        )
        );

        inputMapper.delete(
                new LambdaQueryWrapper<DiagnosticInputEntity>()
                        .eq(
                                DiagnosticInputEntity::getDiagnosticId,
                                diagnosticId
                        )
        );

        if (diagnosticMapper.deleteById(diagnosticId) != 1) {
            throw new BusinessException(
                    500,
                    "删除诊断方案主记录失败"
            );
        }
    }

    /**
     * 在一个事务中完成：
     * 井定位 -> 方案创建/读取 -> 输入整组覆盖 -> 结果保存/删除 -> 主表状态更新。
     */
    @Transactional
    public DiagnosticSaveResponse save(
            DiagnosticSaveRequest request
    ) {
        if (request == null) {
            throw new BusinessException(
                    400,
                    "保存参数不能为空"
            );
        }

        String status = resolveStatus(request);

        long wellId = requireWellId(
                request.projectId(),
                request.gasReservoirId(),
                request.wellName()
        );

        List<DiagnosticCurveModels.ProductionDataItem> inputRows =
                validateAndSortInputs(
                        request.productionData(),
                        "CALCULATED".equals(status)
                );

        validatePressureLimits(
                request.lowerPressureLimit(),
                request.upperPressureLimit(),
                "CALCULATED".equals(status)
        );

        DiagnosticPvtLookupEntity sourcePvt =
                resolveSourcePvt(
                        request.pvtId(),
                        wellId
                );

        if (
                request.pvtSnapshot() != null
        ) {
            validatePvtSnapshot(
                    request.pvtSnapshot()
            );
        }

        if (
                "CALCULATED".equals(status)
        ) {
            if (
                    request.diagnosticId() == null
                            && sourcePvt == null
            ) {
                throw new BusinessException(
                        400,
                        "新建已计算诊断方案必须指定来源PVT"
                );
            }

            if (
                    request.pvtSnapshot() == null
            ) {
                throw new BusinessException(
                        400,
                        "已计算诊断方案必须保存PVT快照"
                );
            }

            if (
                    request.result() == null
            ) {
                throw new BusinessException(
                        400,
                        "已计算诊断方案必须保存完整计算结果"
                );
            }

            validateCalculateResult(
                    request.result(),
                    inputRows,
                    request.lowerPressureLimit(),
                    request.upperPressureLimit()
            );
        }

        DiagnosticEntity diagnostic;

        if (
                request.diagnosticId() == null
        ) {
            diagnostic = createNewDiagnostic(
                    request,
                    wellId
            );
        } else {
            if (
                    request.diagnosticId() <= 0
            ) {
                throw new BusinessException(
                        400,
                        "diagnosticId必须大于0"
                );
            }

            diagnostic = requireDiagnostic(
                    request.diagnosticId(),
                    request.projectId(),
                    request.gasReservoirId(),
                    wellId
            );
        }

        int savedRows =
                replaceInputs(
                        diagnostic.getId(),
                        inputRows
                );

        boolean hasResult;

        if (
                "CALCULATED".equals(status)
        ) {
            upsertResult(
                    diagnostic.getId(),
                    request.result()
            );
            hasResult = true;
        } else {
            resultMapper.delete(
                    new LambdaQueryWrapper<DiagnosticResultEntity>()
                            .eq(
                                    DiagnosticResultEntity::getDiagnosticId,
                                    diagnostic.getId()
                            )
            );
            hasResult = false;
        }

        updateDiagnosticMain(
                diagnostic,
                request,
                sourcePvt,
                status
        );

        if (
                diagnosticMapper.updateById(
                        diagnostic
                ) != 1
        ) {
            throw new BusinessException(
                    500,
                    "更新诊断方案主记录失败"
            );
        }

        return new DiagnosticSaveResponse(
                diagnostic.getId(),
                diagnostic.getDiagnosticNo(),
                diagnostic.getDiagnosticName(),
                savedRows,
                status,
                hasResult,
                hasResult
                        ? CURRENT_CALCULATION_VERSION
                        : null
        );
    }

    private void updateDiagnosticMain(
            DiagnosticEntity diagnostic,
            DiagnosticSaveRequest request,
            DiagnosticPvtLookupEntity sourcePvt,
            String status
    ) {
        diagnostic.setDiagnosticName(
                resolveDiagnosticName(
                        request.diagnosticName(),
                        diagnostic.getDiagnosticName(),
                        diagnostic.getDiagnosticNo()
                )
        );

        if (
                sourcePvt != null
        ) {
            diagnostic.setPvtId(
                    sourcePvt.getId()
            );
            diagnostic.setPvtName(
                    trimToNull(
                            sourcePvt.getPvtName()
                    )
            );
        } else {
            /*
             * pvt_id 允许为空。
             * 如果原 PVT 已被删除，数据库会把 pvt_id 置空，但 pvt_name 和历史快照继续保留。
             */
            diagnostic.setPvtId(
                    null
            );
        }

        /*
         * pvt_snapshot 表示“实际参与计算的PVT”。
         * 只有 CALCULATED 保存才替换；
         * 草稿保存不覆盖上一次计算快照。
         */
        if (
                "CALCULATED".equals(status)
        ) {
            diagnostic.setPvtSnapshot(
                    serialize(
                            request.pvtSnapshot(),
                            "PVT快照"
                    )
            );
        }

        diagnostic.setUpperPressureLimit(
                request.upperPressureLimit()
        );
        diagnostic.setLowerPressureLimit(
                request.lowerPressureLimit()
        );
        diagnostic.setRemark(
                trimToNull(
                        request.remark()
                )
        );
        diagnostic.setStatus(
                status
        );
    }

    /**
     * 新建方案时由后端分配 diagnostic_no。
     * 唯一键仍是最终并发保护；若发生并发冲突，重新读取最大编号再尝试。
     */
    private DiagnosticEntity createNewDiagnostic(
            DiagnosticSaveRequest request,
            long wellId
    ) {
        for (
                int attempt = 0;
                attempt < CREATE_RETRY_LIMIT;
                attempt++
        ) {
            int diagnosticNo =
                    nextDiagnosticNo(
                            request.projectId(),
                            request.gasReservoirId(),
                            wellId
                    );

            DiagnosticEntity entity =
                    new DiagnosticEntity();

            entity.setProjectId(
                    request.projectId()
            );
            entity.setGasReservoirId(
                    request.gasReservoirId()
            );
            entity.setWellId(
                    wellId
            );
            entity.setDiagnosticNo(
                    diagnosticNo
            );
            entity.setDiagnosticName(
                    resolveDiagnosticName(
                            request.diagnosticName(),
                            null,
                            diagnosticNo
                    )
            );
            entity.setStatus(
                    "DRAFT"
            );
            entity.setRemark(
                    trimToNull(
                            request.remark()
                    )
            );

            try {
                diagnosticMapper.insert(
                        entity
                );

                if (
                        entity.getId() == null
                ) {
                    throw new BusinessException(
                            500,
                            "创建诊断方案主记录失败"
                    );
                }

                return entity;

            } catch (
                    DuplicateKeyException duplicate
            ) {
                if (
                        attempt
                                == CREATE_RETRY_LIMIT
                                - 1
                ) {
                    throw new BusinessException(
                            409,
                            "并发创建诊断方案失败，请重试"
                    );
                }
            }
        }

        throw new BusinessException(
                500,
                "创建诊断方案失败"
        );
    }

    private int nextDiagnosticNo(
            long projectId,
            long gasReservoirId,
            long wellId
    ) {
        DiagnosticEntity last =
                diagnosticMapper.selectOne(
                        new LambdaQueryWrapper<DiagnosticEntity>()
                                .select(
                                        DiagnosticEntity::getId,
                                        DiagnosticEntity::getDiagnosticNo
                                )
                                .eq(
                                        DiagnosticEntity::getProjectId,
                                        projectId
                                )
                                .eq(
                                        DiagnosticEntity::getGasReservoirId,
                                        gasReservoirId
                                )
                                .eq(
                                        DiagnosticEntity::getWellId,
                                        wellId
                                )
                                .orderByDesc(
                                        DiagnosticEntity::getDiagnosticNo
                                )
                                .last(
                                        "LIMIT 1"
                                )
                );

        if (
                last == null
        ) {
            return 1;
        }

        if (
                last.getDiagnosticNo() == null
                        || last.getDiagnosticNo()
                        >= Integer.MAX_VALUE
        ) {
            throw new BusinessException(
                    500,
                    "诊断方案编号已达到上限"
            );
        }

        return last.getDiagnosticNo()
                + 1;
    }

    private int replaceInputs(
            long diagnosticId,
            List<DiagnosticCurveModels.ProductionDataItem> rows
    ) {
        inputMapper.delete(
                new LambdaQueryWrapper<DiagnosticInputEntity>()
                        .eq(
                                DiagnosticInputEntity::getDiagnosticId,
                                diagnosticId
                        )
        );

        for (
                DiagnosticCurveModels.ProductionDataItem row
                : rows
        ) {
            DiagnosticInputEntity entity =
                    new DiagnosticInputEntity();

            entity.setDiagnosticId(
                    diagnosticId
            );
            entity.setSequenceNo(
                    row.sequence()
            );
            entity.setTimeText(
                    trimToNull(
                            row.time()
                    )
            );
            entity.setCycleName(
                    trimToNull(
                            row.cycle()
                    )
            );

            /*
             * 当前 calculate 接口 gas 是 10^8 m3。
             * 数据库规定 gas_volume 为 10^4 m3。
             */
            double gasInDbUnit =
                    row.gas()
                            * GAS_1E8_TO_DB_1E4;

            if (
                    !Double.isFinite(
                            gasInDbUnit
                    )
            ) {
                throw new BusinessException(
                        400,
                        "第 "
                                + row.sequence()
                                + " 行注/采气量单位转换后无效"
                );
            }

            entity.setGasVolume(
                    gasInDbUnit
            );

            if (
                    inputMapper.insert(
                            entity
                    ) != 1
            ) {
                throw new BusinessException(
                        500,
                        "保存第 "
                                + row.sequence()
                                + " 行注采数据失败"
                );
            }
        }

        return rows.size();
    }

    private void upsertResult(
            long diagnosticId,
            DiagnosticCurveModels.CalculateResponse result
    ) {
        DiagnosticResultEntity entity =
                resultMapper.selectOne(
                        new LambdaQueryWrapper<DiagnosticResultEntity>()
                                .eq(
                                        DiagnosticResultEntity::getDiagnosticId,
                                        diagnosticId
                                )
                );

        boolean isNew =
                entity == null;

        if (
                isNew
        ) {
            entity =
                    new DiagnosticResultEntity();
            entity.setDiagnosticId(
                    diagnosticId
            );
        }

        entity.setBaseInventory(
                result.baseInventory()
        );
        entity.setMinInventory(
                result.minInventory()
        );
        entity.setMaxInventory(
                result.maxInventory()
        );
        entity.setStandardLineSlope(
                result.standardLineSlope()
        );
        entity.setResultSnapshot(
                serialize(
                        result,
                        "诊断计算结果"
                )
        );
        entity.setCalculationVersion(
                CURRENT_CALCULATION_VERSION
        );
        entity.setCalculatedAt(
                LocalDateTime.now()
        );

        if (
                isNew
        ) {
            if (
                    resultMapper.insert(
                            entity
                    ) != 1
            ) {
                throw new BusinessException(
                        500,
                        "保存诊断计算结果失败"
                );
            }
        } else {
            if (
                    resultMapper.updateById(
                            entity
                    ) != 1
            ) {
                throw new BusinessException(
                        500,
                        "更新诊断计算结果失败"
                );
            }
        }
    }

    private DiagnosticCurveModels.ProductionDataItem toProductionDataItem(
            DiagnosticInputEntity entity
    ) {
        if (
                entity.getGasVolume() == null
                        || !Double.isFinite(
                                entity.getGasVolume()
                        )
        ) {
            throw new BusinessException(
                    500,
                    "数据库中的诊断注采气量无效"
            );
        }

        /*
         * 数据库 10^4 m3 -> calculate 接口 10^8 m3。
         */
        double gasInCalculationUnit =
                entity.getGasVolume()
                        / GAS_1E8_TO_DB_1E4;

        return new DiagnosticCurveModels.ProductionDataItem(
                entity.getSequenceNo(),
                entity.getTimeText() == null
                        ? ""
                        : entity.getTimeText(),
                gasInCalculationUnit,
                entity.getCycleName() == null
                        ? ""
                        : entity.getCycleName()
        );
    }

    private List<DiagnosticCurveModels.ProductionDataItem> validateAndSortInputs(
            List<DiagnosticCurveModels.ProductionDataItem> rows,
            boolean calculated
    ) {
        if (
                rows == null
        ) {
            if (
                    calculated
            ) {
                throw new BusinessException(
                        400,
                        "已计算诊断方案必须包含注采输入"
                );
            }
            return List.of();
        }

        if (
                calculated
                        && rows.size() < 2
        ) {
            throw new BusinessException(
                    400,
                    "已计算诊断方案至少需要2行注采数据"
            );
        }

        Set<Integer> sequences =
                new HashSet<>();

        List<DiagnosticCurveModels.ProductionDataItem> sorted =
                new ArrayList<>(
                        rows.size()
                );

        for (
                DiagnosticCurveModels.ProductionDataItem row
                : rows
        ) {
            if (
                    row == null
            ) {
                throw new BusinessException(
                        400,
                        "注采输入中存在空行"
                );
            }

            if (
                    row.sequence() <= 0
            ) {
                throw new BusinessException(
                        400,
                        "注采数据行序号必须从1开始"
                );
            }

            if (
                    !sequences.add(
                            row.sequence()
                    )
            ) {
                throw new BusinessException(
                        400,
                        "注采数据行序号重复："
                                + row.sequence()
                );
            }

            if (
                    row.gas() == null
                            || !Double.isFinite(
                                    row.gas()
                            )
                            || Math.abs(
                                    row.gas()
                            ) <= EPSILON
            ) {
                throw new BusinessException(
                        400,
                        "第 "
                                + row.sequence()
                                + " 行注/采气量必须是非零有效数字"
                );
            }

            if (
                    row.cycle() == null
                            || row.cycle().isBlank()
            ) {
                throw new BusinessException(
                        400,
                        "第 "
                                + row.sequence()
                                + " 行周期不能为空"
                );
            }

            sorted.add(
                    row
            );
        }

        sorted.sort(
                Comparator.comparingInt(
                        DiagnosticCurveModels.ProductionDataItem::sequence
                )
        );

        return List.copyOf(
                sorted
        );
    }

    private void validatePressureLimits(
            Double lower,
            Double upper,
            boolean calculated
    ) {
        if (
                lower == null
                        && upper == null
        ) {
            if (
                    calculated
            ) {
                throw new BusinessException(
                        400,
                        "已计算诊断方案必须保存压力上下限"
                );
            }
            return;
        }

        if (
                lower == null
                        || upper == null
        ) {
            throw new BusinessException(
                    400,
                    "压力上限和下限必须同时填写"
            );
        }

        if (
                !Double.isFinite(
                        lower
                )
                        || !Double.isFinite(
                        upper
                )
                        || lower <= 0
                        || upper <= lower
        ) {
            throw new BusinessException(
                    400,
                    "压力上下限错误：上限必须大于下限，且下限必须大于0"
            );
        }
    }

    private void validatePvtSnapshot(
            DiagnosticCurveModels.PvtData pvt
    ) {
        List<DiagnosticCurveModels.PvtZPoint> curve =
                pvt.zCurve();

        if (
                curve != null
                        && !curve.isEmpty()
        ) {
            if (
                    curve.size() < 2
            ) {
                throw new BusinessException(
                        400,
                        "PVT快照中的Z(P)曲线至少需要2个点"
                );
            }

            for (
                    DiagnosticCurveModels.PvtZPoint point
                    : curve
            ) {
                if (
                        point == null
                                || point.pressure() == null
                                || point.zFactor() == null
                                || !Double.isFinite(
                                        point.pressure()
                                )
                                || !Double.isFinite(
                                        point.zFactor()
                                )
                                || point.pressure() <= 0
                                || point.zFactor() <= 0
                ) {
                    throw new BusinessException(
                            400,
                            "PVT快照中的Pressure-Z数据无效"
                    );
                }
            }

            return;
        }

        if (
                pvt.fixedZ() == null
                        || !Double.isFinite(
                                pvt.fixedZ()
                        )
                        || pvt.fixedZ() <= 0
        ) {
            throw new BusinessException(
                    400,
                    "PVT快照没有有效的fixedZ或zCurve"
            );
        }
    }

    private void validateCalculateResult(
            DiagnosticCurveModels.CalculateResponse result,
            List<DiagnosticCurveModels.ProductionDataItem> inputs,
            Double lowerPressure,
            Double upperPressure
    ) {
        requirePositiveFinite(
                result.baseInventory(),
                "baseInventory"
        );
        requirePositiveFinite(
                result.minInventory(),
                "minInventory"
        );
        requirePositiveFinite(
                result.maxInventory(),
                "maxInventory"
        );
        requirePositiveFinite(
                result.standardLineSlope(),
                "standardLineSlope"
        );

        if (
                result.maxInventory()
                        < result.minInventory()
        ) {
            throw new BusinessException(
                    400,
                    "计算结果中的最大库存量不能小于最小库存量"
            );
        }

        requireFinite(
                result.minPressureOverZ(),
                "minPressureOverZ"
        );
        requireFinite(
                result.maxPressureOverZ(),
                "maxPressureOverZ"
        );
        requireFinite(
                result.lowerPressureLimit(),
                "lowerPressureLimit"
        );
        requireFinite(
                result.upperPressureLimit(),
                "upperPressureLimit"
        );
        requirePositiveFinite(
                result.lowerZ(),
                "lowerZ"
        );
        requirePositiveFinite(
                result.upperZ(),
                "upperZ"
        );
        requirePositiveFinite(
                result.lowerPressureOverZ(),
                "lowerPressureOverZ"
        );
        requirePositiveFinite(
                result.upperPressureOverZ(),
                "upperPressureOverZ"
        );

        if (
                !nearlyEqual(
                        result.lowerPressureLimit(),
                        lowerPressure
                )
                        || !nearlyEqual(
                        result.upperPressureLimit(),
                        upperPressure
                )
        ) {
            throw new BusinessException(
                    400,
                    "计算结果中的压力上下限与当前保存参数不一致"
            );
        }

        if (
                result.standardLine() == null
                        || result.standardLine().size() < 2
        ) {
            throw new BusinessException(
                    400,
                    "计算结果缺少理论基准线"
            );
        }

        if (
                result.cycleCurves() == null
                        || result.cycleCurves().isEmpty()
        ) {
            throw new BusinessException(
                    400,
                    "计算结果缺少周期曲线"
            );
        }

        if (
                result.runningCurve() == null
                        || result.runningCurve().isEmpty()
        ) {
            throw new BusinessException(
                    400,
                    "计算结果缺少运行曲线"
            );
        }

        if (
                result.pvtMode() == null
                        || result.pvtMode().isBlank()
        ) {
            throw new BusinessException(
                    400,
                    "计算结果缺少PVT模式"
            );
        }

        validateResultMatchesInputs(
                result,
                inputs
        );
    }

    /**
     * 防止把“旧结果 + 新输入”一起保存。
     * synthetic 起始点不参与匹配；实际运行点按 sequence 对应输入。
     */
    private void validateResultMatchesInputs(
            DiagnosticCurveModels.CalculateResponse result,
            List<DiagnosticCurveModels.ProductionDataItem> inputs
    ) {
        Map<Integer, DiagnosticCurveModels.RunningPoint> actualPoints =
                new HashMap<>();

        for (
                DiagnosticCurveModels.RunningPoint point
                : result.runningCurve()
        ) {
            if (
                    point == null
                            || point.synthetic()
            ) {
                continue;
            }

            DiagnosticCurveModels.RunningPoint previous =
                    actualPoints.put(
                            point.sequence(),
                            point
                    );

            if (
                    previous != null
            ) {
                throw new BusinessException(
                        400,
                        "计算结果中的实际运行点序号重复："
                                + point.sequence()
                );
            }
        }

        if (
                actualPoints.size()
                        != inputs.size()
        ) {
            throw new BusinessException(
                    400,
                    "计算结果与注采输入行数不一致，请重新计算后再保存"
            );
        }

        for (
                DiagnosticCurveModels.ProductionDataItem input
                : inputs
        ) {
            DiagnosticCurveModels.RunningPoint point =
                    actualPoints.get(
                            input.sequence()
                    );

            if (
                    point == null
            ) {
                throw new BusinessException(
                        400,
                        "计算结果缺少第 "
                                + input.sequence()
                                + " 行对应运行点，请重新计算"
                );
            }

            if (
                    point.gas() == null
                            || !nearlyEqual(
                                    point.gas(),
                                    input.gas()
                            )
            ) {
                throw new BusinessException(
                        400,
                        "第 "
                                + input.sequence()
                                + " 行注采气量与计算结果不一致，请重新计算"
                );
            }

            String inputTime =
                    input.time() == null
                            ? ""
                            : input.time();

            String resultTime =
                    point.time() == null
                            ? ""
                            : point.time();

            if (
                    !inputTime.equals(
                            resultTime
                    )
            ) {
                throw new BusinessException(
                        400,
                        "第 "
                                + input.sequence()
                                + " 行时间与计算结果不一致，请重新计算"
                );
            }
        }
    }

    private String resolveStatus(
            DiagnosticSaveRequest request
    ) {
        String status =
                request.status() == null
                        ? ""
                        : request.status()
                        .trim()
                        .toUpperCase(
                                Locale.ROOT
                        );

        if (
                status.isBlank()
        ) {
            status =
                    request.result() == null
                            ? "DRAFT"
                            : "CALCULATED";
        }

        if (
                !List.of(
                        "DRAFT",
                        "CALCULATED"
                ).contains(
                        status
                )
        ) {
            throw new BusinessException(
                    400,
                    "诊断方案状态只能是DRAFT或CALCULATED"
            );
        }

        return status;
    }

    private long requireWellId(
            long projectId,
            long gasReservoirId,
            String wellName
    ) {
        if (
                wellName == null
                        || wellName.isBlank()
        ) {
            throw new BusinessException(
                    400,
                    "井名不能为空"
            );
        }

        List<Long> ids =
                findWellIds(
                        projectId,
                        gasReservoirId,
                        wellName.trim()
                );

        if (
                ids.isEmpty()
        ) {
            throw new BusinessException(
                    404,
                    "没有找到当前项目、气藏和井名对应的井"
            );
        }

        if (
                ids.size() > 1
        ) {
            throw new BusinessException(
                    409,
                    "当前项目、气藏和井名对应多口井，请先清理重复井数据"
            );
        }

        return ids.getFirst();
    }

    private List<Long> findWellIds(
            long projectId,
            long gasReservoirId,
            String wellName
    ) {
        return wellLookupMapper.selectList(
                        new LambdaQueryWrapper<DiagnosticWellLookupEntity>()
                                .select(
                                        DiagnosticWellLookupEntity::getId
                                )
                                .eq(
                                        DiagnosticWellLookupEntity::getProjectId,
                                        projectId
                                )
                                .eq(
                                        DiagnosticWellLookupEntity::getProjectGasReservoirId,
                                        gasReservoirId
                                )
                                .eq(
                                        DiagnosticWellLookupEntity::getWellName,
                                        wellName
                                )
                                .orderByAsc(
                                        DiagnosticWellLookupEntity::getId
                                )
                ).stream()
                .map(
                        DiagnosticWellLookupEntity::getId
                )
                .toList();
    }

    private DiagnosticEntity requireDiagnostic(
            long diagnosticId,
            long projectId,
            long gasReservoirId,
            long wellId
    ) {
        DiagnosticEntity entity =
                diagnosticMapper.selectOne(
                        new LambdaQueryWrapper<DiagnosticEntity>()
                                .eq(
                                        DiagnosticEntity::getId,
                                        diagnosticId
                                )
                                .eq(
                                        DiagnosticEntity::getProjectId,
                                        projectId
                                )
                                .eq(
                                        DiagnosticEntity::getGasReservoirId,
                                        gasReservoirId
                                )
                                .eq(
                                        DiagnosticEntity::getWellId,
                                        wellId
                                )
                );

        if (
                entity == null
        ) {
            throw new BusinessException(
                    404,
                    "没有找到当前井对应的诊断方案"
            );
        }

        return entity;
    }

    private DiagnosticPvtLookupEntity resolveSourcePvt(
            Long pvtId,
            long wellId
    ) {
        if (
                pvtId == null
        ) {
            return null;
        }

        if (
                pvtId <= 0
        ) {
            throw new BusinessException(
                    400,
                    "pvtId必须大于0"
            );
        }

        DiagnosticPvtLookupEntity pvt =
                pvtLookupMapper.selectOne(
                        new LambdaQueryWrapper<DiagnosticPvtLookupEntity>()
                                .eq(
                                        DiagnosticPvtLookupEntity::getId,
                                        pvtId
                                )
                                .eq(
                                        DiagnosticPvtLookupEntity::getWellId,
                                        wellId
                                )
                );

        if (
                pvt == null
        ) {
            throw new BusinessException(
                    404,
                    "所选PVT不存在或不属于当前井"
            );
        }

        return pvt;
    }

    private String resolveDiagnosticName(
            String requestedName,
            String existingName,
            int diagnosticNo
    ) {
        if (
                requestedName != null
                        && !requestedName.isBlank()
        ) {
            String value =
                    requestedName.trim();

            if (
                    value.length() > 200
            ) {
                throw new BusinessException(
                        400,
                        "诊断方案名称长度不能超过200"
                );
            }

            return value;
        }

        if (
                existingName != null
                        && !existingName.isBlank()
        ) {
            return existingName.trim();
        }

        return "诊断曲线"
                + diagnosticNo;
    }

    private void requireFinite(
            Double value,
            String field
    ) {
        if (
                value == null
                        || !Double.isFinite(
                                value
                        )
        ) {
            throw new BusinessException(
                    400,
                    field
                            + "必须是有效数字"
            );
        }
    }

    private void requirePositiveFinite(
            Double value,
            String field
    ) {
        requireFinite(
                value,
                field
        );

        if (
                value <= 0
        ) {
            throw new BusinessException(
                    400,
                    field
                            + "必须大于0"
            );
        }
    }

    private boolean nearlyEqual(
            Double left,
            Double right
    ) {
        if (
                left == null
                        || right == null
                        || !Double.isFinite(
                                left
                        )
                        || !Double.isFinite(
                                right
                        )
        ) {
            return false;
        }

        double tolerance =
                1e-9
                        * Math.max(
                                1.0,
                                Math.max(
                                        Math.abs(
                                                left
                                        ),
                                        Math.abs(
                                                right
                                        )
                                )
                        );

        return Math.abs(
                left
                        - right
        ) <= tolerance;
    }

    private String serialize(
            Object value,
            String label
    ) {
        try {
            return objectMapper.writeValueAsString(
                    value
            );
        } catch (
                JacksonException exception
        ) {
            throw new BusinessException(
                    500,
                    label
                            + "序列化失败"
            );
        }
    }

    private <T> T deserializeRequired(
            String json,
            Class<T> type,
            String label
    ) {
        if (
                json == null
                        || json.isBlank()
        ) {
            throw new BusinessException(
                    500,
                    label
                            + "为空"
            );
        }

        try {
            return objectMapper.readValue(
                    json,
                    type
            );
        } catch (
                JacksonException exception
        ) {
            throw new BusinessException(
                    500,
                    label
                            + "反序列化失败"
            );
        }
    }

    private <T> T deserializeNullable(
            String json,
            Class<T> type,
            String label
    ) {
        if (
                json == null
                        || json.isBlank()
        ) {
            return null;
        }

        return deserializeRequired(
                json,
                type,
                label
        );
    }

    private String trimToNull(
            String value
    ) {
        return value == null
                || value.isBlank()
                ? null
                : value.trim();
    }
}
