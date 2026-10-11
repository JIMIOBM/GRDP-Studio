package com.grdp.studio.storagemainfactor;

import com.grdp.studio.reservoirloss.service.StorageCatalogService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;

import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.SaveRequest;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.SavedAnalysis;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.SavedMainFactor;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.SeriesPointInput;

/**
 * 库级主控因素分析的**保存与读取**。
 *
 * <p>计算归 {@link StorageMainFactorSeriesService}（本库导入的时序数据 → 逐点调原平台工具箱
 * → 理论地层压力序列）；这里只管"一个库一份"的落库与读回。
 *
 * <p>时序数据由页面**导入**到本模块自己的表，运行时不读原平台或团队其他模块的任何表。
 */
@Service
public class StorageMainFactorService {

    private final JdbcTemplate jdbc;
    private final StorageCatalogService catalog;
    private final StorageMainFactorSavedStorage savedStorage;

    public StorageMainFactorService(JdbcTemplate jdbc, StorageCatalogService catalog,
            StorageMainFactorSavedStorage savedStorage) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.savedStorage = savedStorage;
    }

    /**
     * 保存本库的一份分析（存在则更新）。
     *
     * <p>只存"本库一份"，所以没有记录名、没有记录列表、也不接左侧树——
     * 那是微观损耗那种"同一口井多个比选方案"才需要的结构。
     */
    public void save(SaveRequest request) {
        catalog.requireScope(jdbc, request.projectId(), request.gasReservoirId(), request.storageId());
        savedStorage.save(request.projectId(), request.gasReservoirId(), request.storageId(),
                new SavedMainFactor(request.inputs(), request.points()));
    }

    /**
     * 读取本库已保存的一份；从未保存过返回 {@link Optional#empty()}。
     *
     * <p>差异与百分比偏差在这里**逐点重算**：它们不入库（由两侧压力推导），
     * 读取时不重算，页面上就会出现"两侧都有值、差异却是 —"的残缺结果。
     */
    public Optional<SavedAnalysis> loadSaved(long projectId, long gasReservoirId, long storageId) {
        catalog.requireScope(jdbc, projectId, gasReservoirId, storageId);
        return savedStorage.load(projectId, gasReservoirId, storageId).map(saved -> new SavedAnalysis(
                saved.inputs(),
                saved.points().stream()
                        .map(point -> StorageMainFactorCalculator.seriesPoint(
                                new SeriesPointInput(point.date(), point.cumulativeGasProduction(),
                                        point.measuredPressure()),
                                point.theoreticalPressure()))
                        .toList()));
    }
}
