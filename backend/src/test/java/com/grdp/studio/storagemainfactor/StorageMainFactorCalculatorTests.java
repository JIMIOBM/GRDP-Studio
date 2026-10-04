package com.grdp.studio.storagemainfactor;

import com.grdp.studio.common.BusinessException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

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
        // 依据是平台自己返回的 fields.unit_label 与 inputRange：
        //   originalPressure MPa、formationTemperature ℃、originalGasInPlace 10⁸m³、
        //   waterSaturation %、rockCompressionCoefficient MPa⁻¹、maxH2SMoleFraction 100(%)
        // 即 calc 收**界面单位**，不是算法内部的 Pa/K/m³/小数。
        var input = new ToolboxInput(50_000_000d, 353.15, 23.398270898104453, 12.306372768,
                1.0E-10, 3.744512763331313E-10, 0.26158040988077613, 1, 0d, 0d, 0d, 0d,
                new GasPvtParam(0, 0.58, 0, 0.0462, 0.0396, 0, 0, 0));
        var payload = toolboxPayload(input);

        assertEquals(50d, numeric(payload, "originalPressure"), 1e-9);            // MPa
        assertEquals(80d, numeric(payload, "formationTemperature"), 1e-9);        // ℃
        assertEquals(23.398270898104453, numeric(payload, "originalGasInPlace"), 1e-9);   // 10⁸m³
        assertEquals(12.306372768, numeric(payload, "cumulativeGasProduction"), 1e-9);
        assertEquals(26.158040988077613, numeric(payload, "waterSaturation"), 1e-9);      // %
        assertEquals(1.0E-4, numeric(payload, "rockCompressionCoefficient"), 1e-18);      // MPa⁻¹
        var pvt = (Map<?, ?>) payload.get("gasPvtParam");
        assertEquals(4.62, ((Number) pvt.get("h2SMoleFraction")).doubleValue(), 1e-12);   // %
        assertEquals(3.96, ((Number) pvt.get("co2MoleFraction")).doubleValue(), 1e-12);
        assertEquals(80d, ((Number) pvt.get("temperature")).doubleValue(), 1e-9);
        assertEquals(50d, ((Number) pvt.get("originalPressure")).doubleValue(), 1e-9);
        assertEquals(1, payload.get("gasReservoirType"));
    }

    static double numeric(Map<String, Object> payload, String key) {
        return ((Number) payload.get(key)).doubleValue();
    }

    @Test
    void toolboxPayloadSerializesIntegralNumbersWithoutScientificNotation() throws Exception {
        // 原平台自己存的入参写的是 56340000；我们原来写出 5.0E7，
        // 它的参数校验便判为不合法：参数校验失败: 原始地层压力 取值范围 (0, 500000000]
        // ——数值明明在区间内，问题在写法。
        var input = new ToolboxInput(50_000_000d, 353.15, 23.398270898104453, 12.306372768,
                1.0E-10, 3.744512763331313E-10, 0.26158040988077613, 1, 0d, 0d, 0d, 0d,
                new GasPvtParam(0, 0.58, 0, 0.0462, 0.0396, 0, 0, 0));
        String json = new ObjectMapper().writeValueAsString(toolboxPayload(input));

        assertTrue(json.contains("\"originalPressure\":50"), json);
        assertTrue(json.contains("\"temperature\":80"), json);
        assertTrue(json.contains("\"h2SMoleFraction\":4.62"), json);
        assertFalse(json.contains("0.0,"), "整数值不应带小数点：" + json);
        // 没值的页岩气藏专属参数不要写 0：0 会被平台校验拒绝（值 <= 下限即拒绝），
        // 且会在合并模板时把平台自己的默认值覆盖成 0。
        assertFalse(json.contains("reservoirPorosity"), "0 值不应下发：" + json);
        assertFalse(json.contains("langmuirPressure"), "0 值不应下发：" + json);
    }

    @Test
    void toolboxPayloadStillSendsShaleParametersWhenTheyHaveRealValues() {
        var input = new ToolboxInput(50_000_000d, 353.15, 23.4, 12.3,
                1.0E-10, 3.7E-10, 0.26, 2, 0.0513, 2700d, 4_000_000d, 3000d,
                new GasPvtParam(0, 0.58, 0, 0.0462, 0.0396, 0, 0, 0));
        var payload = toolboxPayload(input);
        assertEquals(0.0513, numeric(payload, "reservoirPorosity"), 1e-12);
        assertEquals(2700d, numeric(payload, "rockDensity"), 1e-9);
        assertEquals(4_000_000d, numeric(payload, "langmuirPressure"), 1e-6);
        assertEquals(3000d, numeric(payload, "langmuirVolume"), 1e-9);
    }

    @Test
    void toolboxPayloadCarriesTheNestedPvtOriginalPressure() {
        // 原平台校验的是**嵌套 PVT 里**的原始地层压力。我们原来只发外层，嵌套缺省成 0，
        // 原平台便报：工具箱计算出错:invoke algorithm error:参数校验失败:
        //            原始地层压力 取值范围 (0, 500000000]
        // 而外层 5e7 本身完全合法 —— 所以缺的是这一层。
        // 原始地层压力在两层是同一个物理量，取外层值即可，不是编造。
        var input = new ToolboxInput(50_000_000d, 353.15, 23.398270898104453, 12.306372768,
                1.0E-10, 3.744512763331313E-10, 0.26158040988077613, 1, 0d, 0d, 0d, 0d,
                new GasPvtParam(0, 0.58, 0, 0.0462, 0.0396, 0, 0, 0));

        var pvt = (Map<?, ?>) toolboxPayload(input).get("gasPvtParam");

        // 嵌套 PVT 同样是界面单位：压力 MPa、温度 ℃
        assertEquals(50d, ((Number) pvt.get("originalPressure")).doubleValue(), 1e-9);
        assertEquals(80d, ((Number) pvt.get("temperature")).doubleValue(), 1e-9);
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
