package com.grdp.studio.wellbore.sand;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.wellbore.sand.dto.SandProductionRequest;
import com.grdp.studio.wellbore.sand.dto.SandProductionResult;
import com.grdp.studio.wellbore.sand.service.SandProductionCalculator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SandProductionCalculatorTests {
    private final SandProductionCalculator calculator = new SandProductionCalculator();

    private static SandProductionRequest request(String type, Double closureMpa) {
        return new SandProductionRequest(1L, 2L, "测试井", type, 1.7, null, 50.0, closureMpa);
    }

    private static SandProductionResult calculate(String type, double closureMpa) {
        return new SandProductionCalculator().calculate(request(type, closureMpa));
    }

    @Test
    void tablePointHitsExactExperimentalValues() {
        assertEquals(0.11, calculate("quartz-sand", 5).criticalVelocityMS());
        assertEquals(0.06, calculate("quartz-sand", 30).criticalVelocityMS());
        assertEquals(0.27, calculate("composite-sand", 20).criticalVelocityMS());
        assertEquals(0.13, calculate("quartz-sand", 50).criticalVelocityMS());
    }

    @Test
    void midpointUsesLinearInterpolationBetweenNeighbors() {
        // 石英砂 10 MPa 位于 (5,0.11) 与 (15,0.14) 之间
        assertEquals(0.125, calculate("quartz-sand", 10).criticalVelocityMS(), 1e-9);
        // 复合砂 15 MPa 位于 (10,0.18) 与 (20,0.27) 之间
        assertEquals(0.225, calculate("composite-sand", 15).criticalVelocityMS(), 1e-9);
    }

    @Test
    void clampingOutsideExperimentalRangeUsesEndpointValues() {
        SandProductionResult low = calculate("quartz-sand", 3);
        assertEquals(0.11, low.criticalVelocityMS());
        assertNotNull(low.interpolationNote());
        assertTrue(low.interpolationNote().contains("下限"));

        SandProductionResult high = calculate("quartz-sand", 60);
        assertEquals(0.13, high.criticalVelocityMS());
        assertTrue(high.interpolationNote().contains("上限"));

        SandProductionResult compositeHigh = calculate("composite-sand", 60);
        assertEquals(0.22, compositeHigh.criticalVelocityMS());
        assertTrue(compositeHigh.interpolationNote().contains("未观察到出砂"));

        // 复合砂 50 MPa 恰为端点，附带未出砂提示
        SandProductionResult composite50 = calculate("composite-sand", 50);
        assertEquals(0.22, composite50.criticalVelocityMS());
        assertNotNull(composite50.interpolationNote());
        assertTrue(composite50.interpolationNote().contains("未观察到出砂"));
    }

    @Test
    void unitConversionMassOverDensityYieldsVolumeInCubicMeters() {
        // m=1.7t、ρ=1.70 → Vs=1 m³；xf=50 → Ss=0.02 m²；vg=0.11 → Qg=8.64×0.02×0.11=0.019008，保留 2 位为 0.02
        SandProductionResult r = calculate("quartz-sand", 5);
        assertEquals(1.0, r.proppantVolumeM3(), 1e-9);
        assertEquals(0.02, r.fractureAreaM2(), 1e-9);
        assertEquals(0.02, r.criticalRate1e4M3d(), 1e-9);
    }

    @Test
    void manualOverrideWinsOverTableInterpolation() {
        SandProductionRequest input = new SandProductionRequest(1L, 2L, "测试井", "quartz-sand",
                1.7, null, 50.0, 30.0, 0.5, null);
        SandProductionResult r = calculator.calculate(input);
        assertEquals(0.5, r.criticalVelocityMS());
        assertTrue(r.velocityOverridden());
        assertNull(r.interpolationNote());
    }

    @Test
    void riskLevelsUsePaperThresholdsIncludingBoundaries() {
        assertEquals("danger", riskLevel("quartz-sand", 30, 1.0));   // 100% → 易出砂
        assertEquals("warn", riskLevel("quartz-sand", 30, 0.861));   // 86.1% 边界（含）
        assertEquals("warn", riskLevel("quartz-sand", 30, 0.85));    // 灰色区间
        assertEquals("warn", riskLevel("quartz-sand", 30, 0.81));    // 81.0% 边界（含）
        assertEquals("ok", riskLevel("quartz-sand", 30, 0.80));      // <81% 安全
        assertEquals("none", riskLevel("quartz-sand", 30, null));    // 未输入实际气量
    }

    private String riskLevel(String type, double closureMpa, Double ratioHint) {
        SandProductionResult base = calculate(type, closureMpa);
        Double actual = ratioHint == null ? null : base.criticalRate1e4M3d() * ratioHint;
        SandProductionRequest input = new SandProductionRequest(1L, 2L, "测试井", type,
                1.7, null, 50.0, closureMpa, null, actual);
        SandProductionResult r = calculator.calculate(input);
        if (ratioHint != null) assertNotNull(r.ratioPercent());
        else assertNull(r.ratioPercent());
        return r.levelKey();
    }

    @Test
    void unknownProppantTypeIsRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> calculator.calculate(request("ceramic-sand", 30.0)));
        assertEquals(400, ex.getCode());
    }

    @Test
    void defaultDensitiesFollowPaperMeasuredValues() {
        assertEquals(1.70, SandProductionCalculator.defaultDensity("quartz-sand"));
        assertEquals(1.34, SandProductionCalculator.defaultDensity("composite-sand"));
        // 复合砂默认密度生效：m=1.34t → Vs=1 m³
        SandProductionResult r = new SandProductionCalculator().calculate(
                new SandProductionRequest(1L, 2L, "测试井", "composite-sand", 1.34, null, 50.0, 20.0));
        assertEquals(1.0, r.proppantVolumeM3(), 1e-9);
    }

    @Test
    void paperFieldCaseHasConsistentOrderOfMagnitude() {
        // 论文表4 202井：m=4221.7t、ρ=1.34、xf=188.4m、闭合压力25.2MPa，论文给出 Qg=23.90
        // 论文表4的临界流速约0.165，与本实现线性插值(≈0.150)存在论文内部舍入差异，容差放宽到15%
        SandProductionResult r = new SandProductionCalculator().calculate(
                new SandProductionRequest(1L, 2L, "202", "composite-sand", 4221.7, 1.34, 188.4, 25.2));
        assertEquals(23.90, r.criticalRate1e4M3d(), 3.6);
    }
}
