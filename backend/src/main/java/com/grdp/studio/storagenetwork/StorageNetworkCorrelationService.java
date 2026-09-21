package com.grdp.studio.storagenetwork;

import com.grdp.studio.pipeline.PipelineBatch;
import com.grdp.studio.pipeline.PipelineNetworkCalculator;
import com.grdp.studio.reservoirloss.service.StorageCatalogService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/** 从库内各井最近一次已保存的管流批次提取真实工况，不补零、不插值。 */
@Service
public class StorageNetworkCorrelationService {
    public record Sample(long wellId, String wellName, String operatingAt,
                         double inletPressureMpa, double outletPressureMpa, double pressureDropMpa,
                         double flowRate10k, double outletTemperatureC, double compressionPowerKw,
                         int pipeCount) {}
    public record Coverage(long wellId, String wellName, long batchId, int validSampleCount, String status, String reason) {}
    public record Source(List<Sample> samples, List<Coverage> coverage) {}
    record BatchRow(long wellId, String wellName, Long batchId, String resultJson) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final StorageCatalogService catalog;

    public StorageNetworkCorrelationService(JdbcTemplate jdbc, ObjectMapper json, StorageCatalogService catalog) {
        this.jdbc = jdbc;
        this.json = json;
        this.catalog = catalog;
    }

    public Source source(long projectId, long gasReservoirId, long storageId) {
        catalog.requireScope(projectId, gasReservoirId, storageId);
        List<BatchRow> batches = jdbc.query("""
                SELECT w.id,w.well_name,r.id,r.result_json
                FROM project_storage_well sw
                JOIN project_well_heads w ON w.id=sw.well_id
                LEFT JOIN pipeline_batch_run r ON r.id=(
                  SELECT MAX(pr.id) FROM pipeline_batch_run pr WHERE pr.well_id=w.id
                )
                WHERE sw.storage_id=? AND w.project_id=? AND w.project_gas_reservoir_id=?
                ORDER BY w.well_name,w.id
                """, (rs, n) -> new BatchRow(rs.getLong(1), rs.getString(2),
                rs.getObject(3) == null ? null : rs.getLong(3), rs.getString(4)),
                storageId, projectId, gasReservoirId);
        List<Sample> samples = new ArrayList<>();
        List<Coverage> coverage = new ArrayList<>();
        for (BatchRow batch : batches) {
            if (batch.batchId() == null || batch.resultJson() == null) {
                coverage.add(new Coverage(batch.wellId(), batch.wellName(), 0, 0, "missing", "尚未保存管流批量计算结果"));
                continue;
            }
            try {
                PipelineBatch.Result result = json.readValue(batch.resultJson(), PipelineBatch.Result.class);
                List<Sample> extracted = samplesFrom(batch.wellId(), batch.wellName(), result);
                samples.addAll(extracted);
                coverage.add(new Coverage(batch.wellId(), batch.wellName(), batch.batchId(), extracted.size(),
                        extracted.isEmpty() ? "invalid" : "ready",
                        extracted.isEmpty() ? "最近批次没有成功且完整的管流工况" : ""));
            } catch (RuntimeException ex) {
                coverage.add(new Coverage(batch.wellId(), batch.wellName(), batch.batchId(), 0, "invalid", "最近批次结果格式不可读"));
            }
        }
        samples.sort(Comparator.comparing(Sample::operatingAt).thenComparing(Sample::wellName).thenComparingLong(Sample::wellId));
        return new Source(List.copyOf(samples), List.copyOf(coverage));
    }

    static List<Sample> samplesFrom(long wellId, String wellName, PipelineBatch.Result result) {
        if (result == null || result.cases() == null) return List.of();
        List<Sample> samples = new ArrayList<>();
        for (PipelineBatch.CaseResult row : result.cases()) {
            if (row == null || !"success".equals(row.status()) || row.operatingAt() == null
                    || row.pipes() == null || row.pipes().isEmpty()) continue;
            PipelineNetworkCalculator.PipeResult first = row.pipes().getFirst();
            PipelineNetworkCalculator.PipeResult last = row.pipes().getLast();
            double power = row.equipment() == null ? 0d : row.equipment().stream()
                    .filter(Objects::nonNull).mapToDouble(PipelineNetworkCalculator.DeviceResult::powerKw).sum();
            double[] values = {first.inletMpa(), last.outletMpa(), first.inletMpa() - last.outletMpa(),
                    first.rate10k(), last.outletC(), power};
            if (Arrays.stream(values).anyMatch(value -> !Double.isFinite(value))) continue;
            samples.add(new Sample(wellId, wellName, row.operatingAt(), values[0], values[1], values[2],
                    values[3], values[4], values[5], row.pipes().size()));
        }
        return samples;
    }
}
