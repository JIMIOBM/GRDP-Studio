package com.grdp.studio.nodal;

import com.grdp.studio.wellbore.pressure.dto.PressureCalculateRequest;
import java.util.*;

public final class NodalModels {
    private NodalModels() {}
    public static class Input {
        public long projectId, gasReservoirId, coefficientId;
        public Long pressureSourceId;
        public Double coefficientA, coefficientB;
        public String wellName, operationMode = "production", coefficientSet = "corrected";
        public List<Double> reservoirPressures = new ArrayList<>();
        public double minimumPressure = 0.101325, maximumPressure = 40;
        public int samples = 80;
        public PressureCalculateRequest wellbore;
        public Constraints constraints = new Constraints();
    }
    public static class Constraints {
        public boolean liquidLoading, hydrate, erosion, sanding;
        public double surfaceTension = 30, hydrateMargin = 0, fugacityScale = 2;
        public Double liquidHoldupPercent, sandContentPercent, sandDensity, erosionLiquidDensity;
        public Map<String, Double> composition = new LinkedHashMap<>();
    }
    public record Point(double rate, Double pressure, String reason) {}
    public record Check(String code, String status, Double margin, String reason) {}
    public record Candidate(double rate, double pressure, Double margin, List<Check> checks, String reason) {
        public boolean valid() { return margin != null && Double.isFinite(margin); }
        public boolean feasible() { return valid() && margin >= 0 && checks.stream().allMatch(c -> "PASS".equals(c.status())); }
    }
    public record Interval(Point from, Point to) {}
    public record Scenario(double reservoirPressure, List<Point> formation, List<Point> intersections,
            List<Candidate> candidates, List<Interval> intervals, Point maximum, String controllingCondition, String status, List<String> notes) {}
    public record Result(String version, Object coefficientSnapshot, PressureCalculateRequest wellboreSnapshot,
            Object pressureSourceSnapshot, List<Point> wellboreCurve, List<Scenario> scenarios, List<Check> constraintStates, List<String> warnings,
            List<Scenario> regionScenarios) {
        public Result(String version, Object coefficientSnapshot, PressureCalculateRequest wellboreSnapshot,
                Object pressureSourceSnapshot, List<Point> wellboreCurve, List<Scenario> scenarios, List<Check> constraintStates, List<String> warnings) {
            this(version, coefficientSnapshot, wellboreSnapshot, pressureSourceSnapshot, wellboreCurve, scenarios, constraintStates, warnings, List.of());
        }
    }
    public record Save(Long id, String name, Input input) {
        public Save(String name, Input input) { this(null, name, input); }
    }
}
