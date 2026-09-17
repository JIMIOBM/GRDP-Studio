package com.grdp.studio.pipeline;

import java.util.*;
import static com.grdp.studio.pipeline.PipelineGasProperties.Fraction;

/** Empirical hydrate-equilibrium screening; independent of the gas-phase EOS. See sql/hydrate-method.md. */
public final class PipelineHydrateModel {
    public static final String METHOD = "SAFAMIRZAEI_2015";
    public static final String VERSION = "safamirzaei-2015-v1";
    public static final String METHOD_LABEL = "Safamirzaei（2015）天然气水合物经验法";
    public static final String SOURCE = "https://gasprocessingnews.com/articles/2015/08/predict-gas-hydrate-formation-temperature-with-a-simple-correlation/";
    public static final String ASSUMPTIONS = "按存在可用水、纯水且未加抑制剂评价水合物形成条件；不预测生成速率、生成量或堵塞。";
    public static final double MIN_PRESSURE_MPA = .591, MAX_PRESSURE_MPA = 62.011;
    public static final double MIN_RELATIVE_DENSITY = .58, MAX_RELATIVE_DENSITY = .8;
    public static final double AIR_MOLAR_MASS_KG_MOL = .02896;
    private static final double A = 194.681789, B = .044232, C = .189829;
    private static final Map<String, PipelineGasProperties.ComponentInfo> COMPONENTS;
    static {
        var values = new LinkedHashMap<String, PipelineGasProperties.ComponentInfo>();
        for (var component : new PipelineGasProperties().catalog()) values.put(component.code(), component);
        COMPONENTS = Collections.unmodifiableMap(values);
    }
    private PipelineHydrateModel() {}

    /** Null temperature bounds mean the source did not publish a separate temperature range. */
    public record Range(double minPressureMpa, double maxPressureMpa, Double minTemperatureC,
                        Double maxTemperatureC, double minRelativeDensity, double maxRelativeDensity) {}
    public record Metadata(String version, String method, String name, String assumptions, Range range,
                           String source, Double relativeDensity) {}
    public record Equilibrium(Double temperatureC, String status, String reason) {
        public boolean evaluated() { return "valid".equals(status); }
    }

    public static Prepared prepare(PipelineGasModel.Snapshot source) {
        if (source == null) return invalid("当前井尚未保存完整 PVT 气体组成");
        return prepareComposition(source.composition());
    }
    public static Metadata metadata(Prepared prepared) {
        return new Metadata(VERSION, METHOD, METHOD_LABEL, ASSUMPTIONS,
                new Range(MIN_PRESSURE_MPA, MAX_PRESSURE_MPA, null, null,
                        MIN_RELATIVE_DENSITY, MAX_RELATIVE_DENSITY), SOURCE,
                prepared == null ? null : prepared.relativeDensity());
    }
    private static Prepared invalid(String issue) { return new Prepared(null, null, issue); }
    private static Prepared prepareComposition(List<Fraction> composition) {
        if (composition == null || composition.isEmpty()) return invalid("当前 PVT 气体组成为空");
        var fractions = new LinkedHashMap<String, Double>();
        double total = 0, mass = 0;
        for (var fraction : composition) {
            if (fraction == null || !COMPONENTS.containsKey(fraction.code()))
                return invalid("PVT 含未支持或缺失的气体组分，未评价水合物");
            double value = fraction.moleFraction();
            if (!Double.isFinite(value) || value < 0 || value > 1)
                return invalid("PVT 气体摩尔分数必须为 0～1 的有限值");
            if (fractions.putIfAbsent(fraction.code(), value) != null)
                return invalid("PVT 气体组分重复：" + fraction.code());
            total += value;
            mass += value * COMPONENTS.get(fraction.code()).molarMassKgMol();
        }
        if (Math.abs(total - 1) > 1e-6)
            return invalid("PVT 气体摩尔含量合计须为 100%，未评价水合物");
        double relativeDensity = mass / AIR_MOLAR_MASS_KG_MOL;
        var issues = new ArrayList<String>();
        // Table 2's natural-gas validation envelope is used as an explicit implementation limit.
        // This is not a claim that every mixture inside the rectangular envelope was measured.
        range(issues, "甲烷", fractions.getOrDefault("CH4", 0.0), .654, .965);
        range(issues, "乙烷", fractions.getOrDefault("C2H6", 0.0), .009, .127);
        range(issues, "丙烷", fractions.getOrDefault("C3H8", 0.0), 0, .103);
        range(issues, "异丁烷", fractions.getOrDefault("IC4", 0.0), 0, .0099);
        range(issues, "正丁烷", fractions.getOrDefault("NC4", 0.0), 0, .037);
        range(issues, "戊烷合计", fractions.getOrDefault("IC5", 0.0) + fractions.getOrDefault("NC5", 0.0), 0, .0101);
        range(issues, "正己烷", fractions.getOrDefault("NC6", 0.0), 0, .0005);
        range(issues, "硫化氢", fractions.getOrDefault("H2S", 0.0), 0, .0025);
        range(issues, "二氧化碳", fractions.getOrDefault("CO2", 0.0), 0, .0325);
        range(issues, "氮气", fractions.getOrDefault("N2", 0.0), 0, .15);
        for (String code : List.of("NC7", "NC8", "NC9", "NC10")) {
            if (fractions.getOrDefault(code, 0.0) > 0) {
                issues.add("PVT 含 C7～C10，原文未给出这些重组分的量化验证范围");
                break;
            }
        }
        if (relativeDensity < MIN_RELATIVE_DENSITY || relativeDensity > MAX_RELATIVE_DENSITY)
            issues.add("气体相对密度超出本实现采用的原文表 2 数据范围 0.58～0.80");
        return new Prepared(relativeDensity, mass, String.join("；", issues));
    }
    private static void range(List<String> issues, String name, double value, double min, double max) {
        if (value < min || value > max) issues.add(name + "摩尔含量超出本实现采用的原文表 2 数据范围 "
                + percent(min) + "～" + percent(max) + "%");
    }
    private static String percent(double value) {
        return java.math.BigDecimal.valueOf(value).movePointRight(2).stripTrailingZeros().toPlainString();
    }

    public static final class Prepared {
        private final Double relativeDensity, molarMassKgMol;
        private final String issue;
        private Prepared(Double relativeDensity, Double molarMassKgMol, String issue) {
            this.relativeDensity = relativeDensity; this.molarMassKgMol = molarMassKgMol; this.issue = issue;
        }
        public Double relativeDensity() { return relativeDensity; }
        public Double molarMassKgMol() { return molarMassKgMol; }
        public String issue() { return issue; }
        public Metadata metadata() { return PipelineHydrateModel.metadata(this); }
        /** Expected invalid inputs produce an explicit non-evaluation, never a fabricated safe value. */
        public Equilibrium at(double pressureMpa) {
            if (!issue.isBlank()) return new Equilibrium(null, "not_evaluated", issue);
            if (!Double.isFinite(pressureMpa) || pressureMpa < MIN_PRESSURE_MPA || pressureMpa > MAX_PRESSURE_MPA)
                return new Equilibrium(null, "not_evaluated", "压力超出本实现采用的原文表 2 数据范围 0.591～62.011 MPa（绝压）");
            double temperatureK = A * Math.pow(relativeDensity, B) * Math.pow(Math.log(pressureMpa * 1000), C);
            if (!Double.isFinite(temperatureK) || temperatureK <= 0)
                return new Equilibrium(null, "not_evaluated", "水合物平衡温度计算未得到有效结果");
            return new Equilibrium(temperatureK - 273.15, "valid", "");
        }
    }
}
