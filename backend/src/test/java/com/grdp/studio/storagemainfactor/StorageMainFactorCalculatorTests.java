package com.grdp.studio.storagemainfactor;

import com.grdp.studio.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.grdp.studio.storagemainfactor.StorageMainFactorCalculator.*;
import static com.grdp.studio.storagemainfactor.StorageMainFactorDtos.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 四因素计算、差异与工具箱载荷的纯函数测试。
 * 入参一律是**原平台口径**（Pa / K / 10⁸m³ / 小数 / 1/Pa）：换算已在 Task 1 完成，
 * 这里再换算一次就会得到错误的结果，所以断言里写的是未缩放的原值。
 */
class StorageMainFactorCalculatorTests {

    @Test
    void differenceIsActualMinusTheoreticalWithSign() {
        assertEquals(-0.35, difference(31.80, 32.15), 1e-9);
        assertEquals(2.0, difference(14.0, 12.0), 1e-9);
        assertNull(difference(null, 1d));
        assertNull(difference(1d, null));
    }

    @Test
    void directionFollowsSign() {
        assertEquals("POSITIVE", direction(0.5));
        assertEquals("NEGATIVE", direction(-0.5));
        assertEquals("ZERO", direction(0d));
        assertNull(direction(null));
    }

    @Test
    void deviationPercentIsNullWhenTheoreticalIsZeroOrMissing() {
        assertEquals(10.0, deviationPercent(110d, 100d), 1e-9);
        assertNull(deviationPercent(5d, 0d));
        assertNull(deviationPercent(5d, null));
        assertNull(deviationPercent(null, 100d));
    }

    @Test
    void poreVolumeUsesGasVolumeOverGasSaturation() {
        // Vp = G * Bg / (1 - Swi)
        assertEquals(100.0, poreVolume(90d, 1d, 0.10), 1e-9);
    }

    @Test
    void poreVolumeIsNullInsteadOfInfiniteWhenSwiIsOneOrMore() {
        assertNull(poreVolume(90d, 1d, 1.0));
        assertNull(poreVolume(90d, 1d, 1.2));
        assertNull(poreVolume(null, 1d, 0.1));
    }

    @Test
    void gasSaturationIsNullWhenPoreVolumeIsZero() {
        // Sg = (G - Gp) * Bg / Vp
        assertEquals(0.8, gasSaturation(100d, 20d, 1d, 100d), 1e-9);
        assertNull(gasSaturation(100d, 20d, 1d, 0d));
        assertNull(gasSaturation(100d, 20d, 1d, null));
    }

    @Test
    void factorValueFactoriesCarryTheirOwnSource() {
        assertEquals(1d, auto(1d, null).value());
        assertEquals("AUTO", auto(1d, null).source());
        assertEquals("MANUAL", manual(1d, null).source());
        assertEquals("MISSING", missing(null).source());
        assertNull(missing(null).value());
        // 没有值就不可能是"自动读取"，两个工厂都要把 null 归一成 MISSING
        assertEquals("MISSING", auto(null, null).source());
        assertEquals("MISSING", manual(null, null).source());
    }

    @Test
    void rowsCarryAllFourFactorsInFixedOrderAndPerSideSources() {
        var rows = rows(
                Map.of("formationPressure", auto(32.15, null), "poreVolume", manual(100d, null),
                        "gas", manual(23.4, null), "gasSaturation", manual(0.8, null)),
                Map.of("formationPressure", auto(31.80, null), "gas", auto(23.4, null)));
        assertEquals(List.of("formationPressure", "poreVolume", "gas", "gasSaturation"),
                rows.stream().map(FactorRow::key).toList());
        assertEquals("MPa", rows.get(0).unit());
        assertEquals("NEGATIVE", rows.get(0).direction());
        // 理论侧来自手输、实际侧来自数据库读取 —— 两侧来源必须各自独立
        assertEquals("MANUAL", rows.get(1).theoretical().source());
        assertEquals("MISSING", rows.get(1).actual().source());
        assertNull(rows.get(1).difference());
        assertNull(rows.get(3).difference());
        assertEquals("MISSING", rows.get(3).actual().source());
    }

    @Test
    void rowsGiveMissingTheoreticalItsOwnMissingSource() {
        var rows = rows(Map.of(), Map.of("gas", auto(23.4, null)));
        assertEquals("MISSING", rows.get(2).theoretical().source());
        assertEquals("AUTO", rows.get(2).actual().source());
        assertNull(rows.get(2).deviationPercent());
    }

    @Test
    void toolboxPayloadSpeaksThePlatformsOwnUnits() {
        // 期望值取自原平台自己存下来的调用记录（toolbox_result 里
        // MaterialBalanceEquationAppl_FormationPressure 的 inputs）：
        //   "originalGasInPlace":347222200, "h2SMoleFraction":0.1462, "waterSaturation":0.1804
        // 即**体积是 m³（不是 10⁸m³）、摩尔分数是小数（不是百分数）**。
        var input = new ToolboxInput(50_000_000d, 353.15, 23.398270898104453, 12.306372768,
                1.0E-10, 3.744512763331313E-10, 0.26158040988077613, 1, 0d, 0d, 0d, 0d,
                new GasPvtParam(0, 0.58, 0, 0.0462, 0.0396, 0, 0, 0));
        var payload = toolboxPayload(input);

        // 压力与温度本来就是平台口径，不缩放
        assertEquals(50_000_000d, payload.get("originalPressure"));
        assertEquals(353.15, payload.get("formationTemperature"));
        // 体积必须换算成 m³（×10⁸）
        assertEquals(2_339_827_089.8104453, payload.get("originalGasInPlace"));
        assertEquals(1_230_637_276.8, payload.get("cumulativeGasProduction"));
        // 摩尔分数与饱和度保持小数，不乘 100
        assertEquals(0.26158040988077613, payload.get("waterSaturation"));
        var pvt = (Map<?, ?>) payload.get("gasPvtParam");
        assertEquals(0.0462, pvt.get("h2SMoleFraction"));
        assertEquals(0.0396, pvt.get("co2MoleFraction"));
        assertEquals(0, pvt.get("viscosityMethod"));
        assertEquals(1, payload.get("gasReservoirType"));
    }

    @Test
    void toolboxPayloadRejectsMissingRequiredFieldsInsteadOfSendingZeros() {
        // 少了动态地质储量却照发，原平台会拿 0 算出一个"看着正常"的压力——
        // 这种静默的错误结果比直接报错危险得多，所以必填项缺一即抛。
        var noGasInPlace = new ToolboxInput(50_000_000d, 353.15, null, 12.306372768,
                1.0E-10, 3.744512763331313E-10, 0.26158040988077613, 1, 0d, 0d, 0d, 0d,
                new GasPvtParam(0, 0.58, 0, 0.0462, 0.0396, 0, 0, 0));
        assertThrows(BusinessException.class, () -> toolboxPayload(noGasInPlace));

        var noPvt = new ToolboxInput(50_000_000d, 353.15, 23.398270898104453, 12.306372768,
                1.0E-10, 3.744512763331313E-10, 0.26158040988077613, 1, 0d, 0d, 0d, 0d, null);
        assertThrows(BusinessException.class, () -> toolboxPayload(noPvt));
    }
}
