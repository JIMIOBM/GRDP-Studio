package com.grdp.studio.pipeline;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.grdp.studio.pipeline.PipelineGasProperties.Fraction;
import static org.junit.jupiter.api.Assertions.*;

class PipelineHydrateModelTests {
    private static List<Fraction> gas() {
        return List.of(new Fraction("CH4", .94), new Fraction("C2H6", .04), new Fraction("C3H8", .01),
                new Fraction("N2", .005), new Fraction("CO2", .005));
    }
    private static PipelineGasModel.Snapshot source(String method, List<Fraction> fractions) {
        return new PipelineGasModel.Snapshot(1, 1, "composition-1", method, fractions);
    }
    private static PipelineHydrateModel.Prepared prepare(List<Fraction> fractions) {
        return PipelineHydrateModel.prepare(source("PR", fractions));
    }
    @Test void reproducesIndependentDecimalEvaluationWithKpaAndKelvinConversions() {
        // Python Decimal at precision 55, evaluating exp(B*ln(gamma)+C*ln(ln(P_kPa))).
        var prepared = prepare(gas());
        assertEquals("", prepared.issue());
        assertEquals(.01708406620, prepared.molarMassKgMol(), 1e-17);
        assertEquals(.58991941298342541436, prepared.relativeDensity(), 1e-15);
        double[][] values = {{.591, -2.7611377686209875307}, {1, 1.3343092541016086952},
                {6, 13.617831630652812648}, {10, 16.740857374337288177}, {62.011, 26.860159440352643838}};
        for (var row : values) {
            var result = prepared.at(row[0]);
            assertTrue(result.evaluated());
            assertEquals("valid", result.status());
            assertEquals(row[1], result.temperatureC(), 1e-11);
        }
    }
    @Test void usesTheSameCompositionForEveryEosAndDoesNotSolveItsGasPhaseRoot() {
        var expected = prepare(gas()).at(6);
        for (String method : List.of("PR", "SRK", "BWRS", "")) {
            var prepared = PipelineHydrateModel.prepare(source(method, gas()));
            assertEquals(expected, prepared.at(6));
            assertEquals(PipelineHydrateModel.VERSION, prepared.metadata().version());
        }
    }
    @Test void rejectsInvalidPressureAndNeverClampsToTheValidityBoundary() {
        var prepared = prepare(gas());
        for (double pressure : new double[]{Math.nextDown(.591), Math.nextUp(62.011), 0, -1,
                Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE}) {
            var result = prepared.at(pressure);
            assertFalse(result.evaluated());
            assertEquals("not_evaluated", result.status());
            assertNull(result.temperatureC());
            assertTrue(result.reason().contains("压力"));
        }
    }
    @Test void rejectsMissingIncompleteUnknownDuplicateAndNonfiniteCompositionWithoutThrowing() {
        var lists = new ArrayList<List<Fraction>>();
        lists.add(null); lists.add(List.of()); lists.add(Arrays.asList((Fraction)null));
        lists.add(List.of(new Fraction(null, 1)));
        lists.add(List.of(new Fraction("CH4", .9)));
        lists.add(List.of(new Fraction("CH4", .5), new Fraction("CH4", .5)));
        lists.add(List.of(new Fraction("unknown", 1)));
        lists.add(List.of(new Fraction("CH4", Double.NaN)));
        lists.add(List.of(new Fraction("CH4", Double.POSITIVE_INFINITY)));
        lists.add(List.of(new Fraction("CH4", -1)));
        lists.add(List.of(new Fraction("CH4", 1.1)));
        assertNull(PipelineHydrateModel.prepare(null).at(6).temperatureC());
        for (var fractions : lists) {
            var result = assertDoesNotThrow(() -> prepare(fractions).at(6));
            assertEquals("not_evaluated", result.status());
            assertFalse(result.reason().isBlank());
            assertNull(result.temperatureC());
        }
    }
    @Test void rejectsSourRichLeanAndUnvalidatedHeavyGasEvenWithACompletePvt() {
        Map<List<Fraction>, String> cases = new LinkedHashMap<>();
        cases.put(List.of(new Fraction("CH4", .93), new Fraction("C2H6", .04), new Fraction("CO2", .03)), "");
        cases.put(List.of(new Fraction("CH4", .94), new Fraction("C2H6", .04), new Fraction("H2S", .02)), "硫化氢");
        cases.put(List.of(new Fraction("CH4", .90), new Fraction("C2H6", .04), new Fraction("CO2", .06)), "二氧化碳");
        cases.put(List.of(new Fraction("CH4", 1)), "甲烷");
        cases.put(List.of(new Fraction("CH4", .90), new Fraction("C2H6", .04), new Fraction("NC7", .06)), "C7～C10");
        cases.put(List.of(new Fraction("CH4", .8), new Fraction("C2H6", .04), new Fraction("NC5", .16)), "戊烷");
        cases.put(List.of(new Fraction("CH4", .90), new Fraction("C2H6", .04), new Fraction("NC6", .06)), "正己烷");
        for (var entry : cases.entrySet()) {
            var result = prepare(entry.getKey()).at(6);
            if (entry.getValue().isBlank()) assertTrue(result.evaluated(), result.reason());
            else {
                assertFalse(result.evaluated());
                assertNull(result.temperatureC());
                assertTrue(result.reason().contains(entry.getValue()), result.reason());
            }
        }
    }
    @Test void reportsTheActualSourceAndDoesNotInventATemperatureValidityRange() {
        var metadata = prepare(gas()).metadata();
        assertEquals("SAFAMIRZAEI_2015", metadata.method());
        assertTrue(metadata.source().startsWith("https://gasprocessingnews.com/articles/2015/08/"));
        assertEquals(.591, metadata.range().minPressureMpa());
        assertEquals(62.011, metadata.range().maxPressureMpa());
        assertNull(metadata.range().minTemperatureC());
        assertNull(metadata.range().maxTemperatureC());
        assertTrue(metadata.assumptions().contains("纯水"));
        assertTrue(metadata.assumptions().contains("未加抑制剂"));
        assertNull(PipelineHydrateModel.metadata(null).relativeDensity());
    }
    @Test void sourceListMutationCannotChangeAnAlreadyPreparedCalculation() {
        var fractions = new ArrayList<>(gas());
        var prepared = prepare(fractions); var before = prepared.at(6);
        fractions.clear(); fractions.add(new Fraction("CO2", 1));
        assertEquals(before, prepared.at(6));
    }
}
