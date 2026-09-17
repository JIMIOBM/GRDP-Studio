package com.grdp.studio.pipeline;

import com.grdp.studio.common.BusinessException;
import org.springframework.stereotype.Component;

import java.util.*;

/** Gas-phase EOS and caloric properties. Formula provenance and corrections: sql/gas-properties-methods.md. */
@Component
public class PipelineGasProperties {
    public static final double R = 8.31446261815324;
    public record Fraction(String code, double moleFraction) {}
    public record ComponentInfo(String code, String name, double molarMassKgMol,
                                double criticalTemperatureK, double criticalPressurePa, double acentricFactor) {}
    public record State(double z, double cpJkgK, double densityKgM3, double molarMassKgMol) {}
    public record ParameterRow(String key, String label, Double value, String unit, String source) {}
    public record ParameterGroup(String key, String label, List<ParameterRow> rows) {
        public ParameterGroup { rows = List.copyOf(rows); }
    }
    public record SpeciesParameters(String code, String name, double moleFraction, Double usedMoleFraction,
                                    List<ParameterGroup> groups) {
        public SpeciesParameters { groups = List.copyOf(groups); }
    }
    public record ParameterSnapshot(String method, List<ParameterGroup> groups, List<SpeciesParameters> species, String issue) {
        public ParameterSnapshot { groups = List.copyOf(groups); species = List.copyOf(species); }
    }
    private static final String FLUID_SOURCE = "CoolProp 官方流体数据（当前内置值）";
    private static final String IDEAL_SOURCE = "CoolProp 理想气体 Helmholtz 能的解析温度导数";

    private record Species(ComponentInfo info, double criticalDensity, double reducingTemperature,
                           double logTau, double[] oscillatorN, double[] oscillatorTemperature,
                           double[] powerN, double[] powerT) {
        double idealCp(double t) {
            double cp = 1 + logTau;
            for (int i = 0; i < oscillatorN.length; i++) {
                double x = oscillatorTemperature[i] / t;
                double e = Math.exp(-x), denominator = -Math.expm1(-x);
                cp += oscillatorN[i] * x * x * e / (denominator * denominator);
            }
            for (int i = 0; i < powerN.length; i++)
                cp -= powerN[i] * powerT[i] * (powerT[i] - 1) * Math.pow(reducingTemperature / t, powerT[i]);
            return cp * R;
        }
        double idealEnthalpy(double t) {
            double h = (1 + logTau) * t;
            for (int i = 0; i < oscillatorN.length; i++)
                h += oscillatorN[i] * oscillatorTemperature[i] / Math.expm1(oscillatorTemperature[i] / t);
            for (int i = 0; i < powerN.length; i++)
                h += powerN[i] * powerT[i] * Math.pow(reducingTemperature / t, powerT[i]) * t;
            return h * R;
        }
    }

    private static Species component(String code, String name, double mass, double tc, double pc,
            double omega, double rhoc, double reducingT, double logTau,
            double[] oscillatorN, double[] oscillatorT, double[] powerN, double[] powerT) {
        return new Species(new ComponentInfo(code, name, mass, tc, pc, omega), rhoc, reducingT,
                logTau, oscillatorN, oscillatorT, powerN, powerT);
    }
    // Factual constants and ideal-gas coefficients from CoolProp's official fluid data (see method document).
    private static final List<Species> SPECIES = List.of(
        component("CH4","甲烷",0.0160428,190.564,4599200.0,0.01142,10139.128,190.564,3.0016,new double[]{0.008449,4.6942,3.4865,1.6572,1.4115},new double[]{648,1957,3895,5705,15080},new double[]{},new double[]{}),
        component("C2H6","乙烷",0.03006904,305.322,4872200.0,0.099,6856.8866849999995,305.322,3.003039265,new double[]{1.117433359,3.467773215,6.94194464,5.970850948},new double[]{430.230827950026,1224.315899951862,2014.120639936548,4268.34363125694},new double[]{},new double[]{}),
        component("C3H8","丙烷",0.04409562,369.89,4251200.0,0.1521,5000.000000000001,369.89,3,new double[]{3.043,5.874,9.337,7.922},new double[]{392.99998742,1236.99982393,1984.0000767299998,4351.00016473},new double[]{},new double[]{}),
        component("IC4","异丁烷",0.0581222,407.817,3629000.0,0.183531783208,3879.756788283995,407.81,3.05956619,new double[]{4.94641014,4.09475197,15.6632824,9.73918122},new double[]{387.94064121462,973.8078208618499,1772.71102994089,4228.5242419784},new double[]{},new double[]{}),
        component("NC4","正丁烷",0.0581222,425.125,3796000.0,0.200810094644,3922.769612987809,425.125,3.24680487,new double[]{5.54913289,11.4648996,7.59987584,9.66033239},new double[]{329.404044180625,1420.173659919,2113.089379937,4240.85729987225},new double[]{},new double[]{}),
        component("IC5","异戊烷",0.07214878,460.35,3378000.0,0.2274,3271.0,460.35,3,new double[]{7.4056,9.5772,15.765,12.119},new double[]{442.0,1108.9999999999998,2069.0,4193.0},new double[]{},new double[]{}),
        component("NC5","正戊烷",0.07214878,469.70000000000005,3367518.9947363394,0.251031912680427,3215.5775884221466,469.7,3,new double[]{6.618,15.97,15.29},new double[]{154.0,1324.0,2634.0},new double[]{},new double[]{}),
        component("NC6","正己烷",0.08617535999999999,507.82,3044115.328359688,0.3003189315498438,2706,507.82,3,new double[]{9.21,6.04,25.3,10.96},new double[]{190.0,3000.0,1500.0,4500.0},new double[]{},new double[]{}),
        // NC7–NC10 are identified pure n-alkanes, never C7+/C10+ or an unspecified carbon-number cut.
        // CoolProp v7.2.0, commit 98b3523d5daa98454618d381d2ae53f7471d216b: STATES.critical and EOS[0].
        // NC7 Aly–Lee Cp0 is represented exactly by existing Planck–Einstein terms:
        // (x/cosh(x))² = (x/sinh(x))² - (2x/sinh(2x))²; its constant 4 makes logTau=3.
        component("NC7","正庚烷",0.100202,540.13,2736000.0,0.349,2315.3230474441625,540.13,3,new double[]{13.7266,30.4707,-30.4707,43.5561},new double[]{339.578,1672.39,3344.78,3520.92},new double[]{},new double[]{}),
        component("NC8","正辛烷",0.114229,568.74,2483591.199677694,0.39752829818330415,2031,568.74,3,new double[]{17.47,33.25,15.63},new double[]{380.0,1724.0,3881.0},new double[]{},new double[]{}),
        component("NC9","正壬烷",0.1282551,594.5500000000001,2281000.0,0.4433,1810.0,594.55,16.349,new double[]{24.926,24.842,11.188,17.483},new double[]{1221.0,2244.0000000000005,5008.000000000001,11724.0},new double[]{},new double[]{}),
        component("NC10","正癸烷",0.14228168,617.7,2103000.0,0.4884,1640.0000000000002,617.7,18.109,new double[]{25.685,28.233,12.417,10.035},new double[]{1193.0,2140.0000000000005,4763.0,10861.999999999998},new double[]{},new double[]{}),
        component("N2","氮气",0.02801348,126.192,3395800.0,0.0372,11183.9,126.192,2.5,new double[]{1.012941},new double[]{3364.011},new double[]{-0.0001934819,-1.247742e-05,6.678326e-08},new double[]{-1,-2,-3}),
        component("CO2","二氧化碳",0.0440098,304.1282,7377300.0,0.22394,10624.9063,304.1282,2.5,new double[]{1.99427042,0.62105248,0.41195293,1.04028922,0.08327678},new double[]{958.499558966,1858.8011455800001,2061.101141656,3443.8990762880003,8238.200351344},new double[]{},new double[]{}),
        component("H2S","硫化氢",0.03408088,373.1,9000000.0,0.1005,10190.0,373.1,3,new double[]{1.1364,1.9721},new double[]{1823.0,3964.999999999999},new double[]{-0.002753352822675789},new double[]{-1.5})
    );
    private static final Map<String, Species> BY_CODE = new LinkedHashMap<>();
    static { for (Species s : SPECIES) BY_CODE.put(s.info.code(), s); }
    private record Key(String method, List<Fraction> fractions) {}
    private final Map<Key, Prepared> preparedCache = Collections.synchronizedMap(new LinkedHashMap<>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Key, Prepared> entry) { return size() > 128; }
    });

    public List<ComponentInfo> catalog() { return SPECIES.stream().map(Species::info).toList(); }
    public void validateComposition(List<Fraction> composition) { normalize(composition); }
    public State calculate(String method, List<Fraction> composition, double pressureMpa, double temperatureC) {
        return prepare(method, composition).at(pressureMpa, temperatureC);
    }
    public Prepared prepare(String method, List<Fraction> composition) {
        require(method != null && Set.of("PR", "SRK", "BWRS").contains(method), "请选择 PR、SRK 或 BWRS 状态方程");
        Key key = new Key(method, normalize(composition));
        synchronized (preparedCache) { return preparedCache.computeIfAbsent(key, k -> new Prepared(k.method, k.fractions)); }
    }
    /** Preview is read-only and does not solve the EOS; incomplete drafts still expose their known constants. */
    public ParameterSnapshot parameters(String method, List<Fraction> composition, Double pressureMpa, Double temperatureC) {
        return parameters(method, composition, pressureMpa, temperatureC, "");
    }
    public ParameterSnapshot parameters(String method, List<Fraction> composition, Double pressureMpa, Double temperatureC, String sourceIssue) {
        require(method != null && Set.of("PR", "SRK", "BWRS").contains(method), "请选择 PR、SRK 或 BWRS 状态方程");
        Prepared prepared = null; String issue = sourceIssue == null ? "" : sourceIssue;
        try { if (issue.isBlank()) prepared = prepare(method, composition); }
        catch (BusinessException e) { issue = e.getMessage(); }
        return parameterSnapshot(method, composition, prepared, pressureMpa, temperatureC, null, issue);
    }
    private static ParameterRow parameter(String key, String label, double value, String unit, String source) {
        return new ParameterRow(key, label, Double.isFinite(value) ? value : null, unit, source);
    }
    private static ParameterGroup group(String key, String label, ParameterRow... rows) {
        return new ParameterGroup(key, label, List.of(rows));
    }
    private static ParameterSnapshot parameterSnapshot(String method, List<Fraction> composition, Prepared prepared,
            Double pressureMpa, Double temperatureC, State state, String compositionIssue) {
        var groups = new ArrayList<ParameterGroup>();
        groups.add(group("constants", "公共常数", parameter("R", "摩尔气体常数 R", R, "J/(mol·K)", "当前计算使用的摩尔气体常数；老师资料显示约值 8.314")));
        groups.add(methodConstants(method));
        String issue = compositionIssue;
        Double p = pressureMpa != null && Double.isFinite(pressureMpa) && pressureMpa > 0 && Double.isFinite(pressureMpa * 1e6) ? pressureMpa * 1e6 : null;
        Double t = temperatureC != null && Double.isFinite(temperatureC) && temperatureC > -273.15 ? temperatureC + 273.15 : null;
        if (pressureMpa != null && p == null) issue = appendIssue(issue, "压力须为可换算为 Pa 的有限正绝压。");
        if (temperatureC != null && t == null) issue = appendIssue(issue, "温度须为高于绝对零度的有限值。");
        var conditions = new ArrayList<ParameterRow>();
        if (p != null) conditions.add(parameter("pressurePa", "气体绝对压力 P", p, "Pa", "本页压力 MPa × 10⁶"));
        if (t != null) conditions.add(parameter("temperatureK", "气体绝对温度 T", t, "K", "本页温度 ℃ + 273.15"));
        if (!conditions.isEmpty()) groups.add(new ParameterGroup("condition", "内部计算工况", conditions));
        var species = new ArrayList<SpeciesParameters>();
        for (Fraction f : composition == null ? List.<Fraction>of() : composition) {
            if (f == null || !BY_CODE.containsKey(f.code()) || !Double.isFinite(f.moleFraction())) continue;
            Species s = BY_CODE.get(f.code());
            Double used = prepared == null ? null : prepared.fractions.stream().filter(x -> x.code().equals(f.code())).map(Fraction::moleFraction).findFirst().orElse(0.0);
            var data = new ArrayList<ParameterGroup>();
            data.add(group("physical", "组分物性常数",
                    parameter("molarMassKgMol", "摩尔质量 Mᵢ", s.info.molarMassKgMol(), "kg/mol", FLUID_SOURCE),
                    parameter("criticalTemperatureK", "临界温度 Tcᵢ", s.info.criticalTemperatureK(), "K", FLUID_SOURCE),
                    parameter("criticalPressurePa", "临界压力 Pcᵢ", s.info.criticalPressurePa(), "Pa", FLUID_SOURCE),
                    parameter("acentricFactor", "偏心因子 ωᵢ", s.info.acentricFactor(), "无量纲", FLUID_SOURCE),
                    parameter("criticalMolarDensityMolM3", "临界摩尔密度 ρcᵢ", s.criticalDensity, "mol/m³", FLUID_SOURCE)));
            var ideal = new ArrayList<ParameterRow>();
            ideal.add(parameter("reducingTemperatureK", "理想气体项约化参考温度 Tᵣ,ᵢ", s.reducingTemperature, "K", IDEAL_SOURCE));
            ideal.add(parameter("logTau", "ln(τ) 项系数", s.logTau, "无量纲", IDEAL_SOURCE));
            for (int i = 0; i < s.oscillatorN.length; i++) {
                ideal.add(parameter("oscillatorN" + (i + 1), "振动项 n" + (i + 1), s.oscillatorN[i], "无量纲", IDEAL_SOURCE));
                ideal.add(parameter("oscillatorTemperatureK" + (i + 1), "振动项特征温度 θ" + (i + 1), s.oscillatorTemperature[i], "K", IDEAL_SOURCE));
            }
            for (int i = 0; i < s.powerN.length; i++) {
                ideal.add(parameter("powerN" + (i + 1), "幂项 n" + (i + 1), s.powerN[i], "无量纲", IDEAL_SOURCE));
                ideal.add(parameter("powerT" + (i + 1), "幂项指数 t" + (i + 1), s.powerT[i], "无量纲", IDEAL_SOURCE));
            }
            if (t != null) ideal.add(parameter("idealCpJmolK", "当前温度理想气体 Cp⁰ᵢ", s.idealCp(t), "J/(mol·K)", IDEAL_SOURCE));
            data.add(new ParameterGroup("idealGas", "理想气体热容数据", ideal));
            if (method.equals("BWRS")) data.add(bwrsCoefficients("bwrsPure", "组分 BWRS 系数", Bwrs.pure(s), "由当前组分临界常数、偏心因子及通用系数计算"));
            species.add(new SpeciesParameters(f.code(), s.info.name(), f.moleFraction(), used, data));
        }
        if (prepared != null) {
            groups.add(group("mixture", "实际混合物参数",
                    parameter("molarMassKgMol", "混合物摩尔质量 M", prepared.mass, "kg/mol", "Σ yᵢ Mᵢ；使用归一化后的实际摩尔分数"),
                    parameter("criticalTemperatureK", "混合物拟临界温度 Tc", prepared.tc, "K", "Σ yᵢ Tcᵢ"),
                    parameter("criticalPressurePa", "混合物拟临界压力 Pc", prepared.pc, "Pa", method.equals("BWRS") ? "Σ yᵢ Pcᵢ；仅作混合物参考，BWRS 系数按组分混合" : "Σ yᵢ Pcᵢ；老师资料的拟临界混合方式"),
                    parameter("acentricFactor", "混合物偏心因子 ω", prepared.omega, "无量纲", method.equals("BWRS") ? "Σ yᵢ ωᵢ；仅作混合物参考，BWRS 系数按组分混合" : "Σ yᵢ ωᵢ"),
                    parameter("criticalMolarDensityMolM3", "混合物临界摩尔密度 ρc", prepared.rhoc, "mol/m³", method.equals("BWRS") ? "1 / Σ(yᵢ / ρcᵢ)；用于气相根搜索与相态保护" : "1 / Σ(yᵢ / ρcᵢ)；当前三次方程未使用，仅作参考")));
            if (prepared.cubic != null) {
                Cubic c = prepared.cubic;
                var coefficients = new ArrayList<ParameterRow>(List.of(
                        parameter("a0", "吸引项基准系数 a₀", c.a0, "Pa·m⁶/mol²", "当前状态方程实例"),
                        parameter("b", "共体积系数 b", c.b, "m³/mol", "当前状态方程实例"),
                        parameter("kappa", "温度函数系数 κ", c.k, "无量纲", "当前状态方程实例")));
                if (t != null) {
                    double alpha = Math.pow(c.alphaRoot(t), 2), a = c.a0 * alpha;
                    coefficients.add(parameter("reducedTemperature", "相对温度 Tr", t / prepared.tc, "无量纲", "T / Tc"));
                    coefficients.add(parameter("alpha", "温度修正函数 α(T)", alpha, "无量纲", "当前状态方程温度函数"));
                    coefficients.add(parameter("aTemperature", "当前温度吸引项 a(T)", a, "Pa·m⁶/mol²", "a₀ α(T)"));
                    if (p != null) {
                        coefficients.add(parameter("reducedPressure", "相对压力 Pr", p / prepared.pc, "无量纲", "P / Pc"));
                        coefficients.add(parameter("A", "三次方程无量纲参数 A", a * p / (R * R * t * t), "无量纲", "a(T) P / (R² T²)"));
                        coefficients.add(parameter("B", "三次方程无量纲参数 B", c.b * p / (R * t), "无量纲", "b P / (R T)"));
                    }
                }
                groups.add(new ParameterGroup("eosCoefficients", "实际状态方程系数", coefficients));
            } else groups.add(bwrsCoefficients("eosCoefficients", "实际混合 BWRS 系数", prepared.bwrs, "当前 BWRS 实例；老师资料混合规则，全部 Kᵢⱼ = 0（未标定）"));
            if (t != null) {
                var caloric = new ArrayList<ParameterRow>();
                caloric.add(parameter("idealCpJmolK", "混合物理想气体 Cp⁰", prepared.idealCpMolar(t), "J/(mol·K)", "Σ yᵢ Cp⁰ᵢ(T)"));
                caloric.add(parameter("idealCpJkgK", "混合物理想气体质量 Cp⁰", prepared.idealCpMolar(t) / prepared.mass, "J/(kg·K)", "混合物摩尔理想热容 / M"));
                if (state != null) {
                    double rho = state.densityKgM3() / state.molarMassKgMol();
                    Derivatives d = prepared.derivatives(rho, t);
                    double coupling = t * Math.pow(d.dpT / rho, 2) / d.dpRho;
                    caloric.add(parameter("molarDensityMolM3", "求解所得摩尔密度 ρ", rho, "mol/m³", "同次状态方程求解结果"));
                    caloric.add(parameter("molarVolumeM3Mol", "求解所得摩尔体积 V", 1 / rho, "m³/mol", "1 / ρ"));
                    caloric.add(parameter("pressureDerivativeTemperature", "定密度压力温度导数 (∂P/∂T)ρ", d.dpT, "Pa/K", "所选状态方程解析导数"));
                    caloric.add(parameter("pressureDerivativeMolarDensity", "定温压力密度导数 (∂P/∂ρ)T", d.dpRho, "Pa·m³/mol", "所选状态方程解析导数"));
                    caloric.add(parameter("residualCvJmolK", "剩余定容热容 Cvᴿ", d.cvResidual, "J/(mol·K)", "所选状态方程解析导数"));
                    caloric.add(parameter("cpMinusCvJmolK", "定压与定容热容之差 Cp − Cv", coupling, "J/(mol·K)", "T × [(∂P/∂T)ρ / ρ]² / (∂P/∂ρ)T"));
                    caloric.add(parameter("cpJmolK", "实际摩尔定压热容 Cp", state.cpJkgK() * state.molarMassKgMol(), "J/(mol·K)", "[Cp⁰ − R + Cvᴿ + (Cp − Cv)]"));
                }
                groups.add(new ParameterGroup("caloric", "当前温度热容参数", caloric));
            }
        }
        return new ParameterSnapshot(method, groups, species, issue);
    }
    private static String appendIssue(String issue, String extra) { return issue == null || issue.isBlank() ? extra : issue + "；" + extra; }
    private static ParameterGroup methodConstants(String method) {
        if (method.equals("BWRS")) {
            var rows = new ArrayList<ParameterRow>();
            for (int i = 0; i < Bwrs.AA.length; i++) {
                rows.add(parameter("A" + (i + 1), "通用常数 A" + (i + 1), Bwrs.AA[i], "无量纲", "Starling–Han 通用常数表；资料 7、9 注明查表"));
                rows.add(parameter("B" + (i + 1), "通用常数 B" + (i + 1), Bwrs.BB[i], "无量纲", "Starling–Han 通用常数表；资料 7、9 注明查表"));
            }
            rows.add(parameter("binaryInteraction", "全部组分对交互系数 Kᵢⱼ", 0, "无量纲", "当前模型假设为 0（未标定）"));
            rows.add(parameter("eOmegaExponent", "E₀ 偏心因子指数常数", Bwrs.E_OMEGA_EXPONENT, "无量纲", "BWRS 通用参数关系"));
            return new ParameterGroup("methodConstants", "BWRS 方法常数", rows);
        }
        CubicConstants c = method.equals("PR") ? PR_CONSTANTS : SRK_CONSTANTS;
        String source = method.equals("PR") ? "老师资料 5、10：PR 方程" : "老师资料 6、8：SRK 方程";
        return group("methodConstants", method + " 方法常数",
                parameter("omegaA", "a₀ 系数 Ωa", c.omegaA, "无量纲", source),
                parameter("omegaB", "b 系数 Ωb", c.omegaB, "无量纲", source),
                parameter("kappa0", "κ 常数项", c.kappa0, "无量纲", source),
                parameter("kappa1", "κ 偏心因子一次项系数", c.kappa1, "无量纲", source),
                parameter("kappa2", "κ 偏心因子二次项系数", c.kappa2, "无量纲", source));
    }
    private static ParameterGroup bwrsCoefficients(String key, String label, Bwrs c, String source) {
        return group(key, label,
                parameter("A0", "A₀", c.A0, "Pa·m⁶/mol²", source), parameter("B0", "B₀", c.B0, "m³/mol", source),
                parameter("C0", "C₀", c.C0, "Pa·m⁶·K²/mol²", source), parameter("D0", "D₀", c.D0, "Pa·m⁶·K³/mol²", source),
                parameter("E0", "E₀", c.E0, "Pa·m⁶·K⁴/mol²", source), parameter("a", "a", c.a, "Pa·m⁹/mol³", source),
                parameter("b", "b", c.b, "m⁶/mol²", source), parameter("c", "c", c.c, "Pa·m⁹·K²/mol³", source),
                parameter("d", "d", c.d, "Pa·m⁹·K/mol³", source), parameter("alpha", "α", c.alpha, "m⁹/mol³", source),
                parameter("gamma", "γ", c.gamma, "m⁶/mol²", source));
    }
    private static List<Fraction> normalize(List<Fraction> fractions) {
        require(fractions != null && !fractions.isEmpty() && fractions.size() <= SPECIES.size(), "请填写完整的气体摩尔组成");
        Map<String, Double> values = new TreeMap<>();
        double total = 0;
        for (Fraction f : fractions) {
            require(f != null && f.code != null && BY_CODE.containsKey(f.code), "气体组成包含未支持的组分");
            require(Double.isFinite(f.moleFraction) && f.moleFraction >= 0 && f.moleFraction <= 1, "组分摩尔分数必须在 0～1 之间");
            require(values.putIfAbsent(f.code, f.moleFraction) == null, "气体组成不能包含重复组分：" + f.code);
            total += f.moleFraction;
        }
        require(Math.abs(total - 1) <= 1e-6, "气体组分摩尔分数之和必须为 1（100%）");
        final double sum = total;
        return values.entrySet().stream().filter(e -> e.getValue() > 0).map(e -> new Fraction(e.getKey(), e.getValue() / sum)).toList();
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new BusinessException(400, message);
    }
    private static void validateState(double p, double t) {
        require(Double.isFinite(p) && p > 0, "物性计算压力须为大于 0 的有限绝压（MPa）");
        require(Double.isFinite(t) && t > 0, "物性计算温度须高于绝对零度（−273.15 ℃）且为有限值");
    }

    /** Immutable composition preparation, safe to reuse during every pipe integration step. */
    public static final class Prepared {
        private final String method;
        private final List<Fraction> fractions;
        private final double mass, tc, pc, omega, rhoc;
        private final Cubic cubic;
        private final Bwrs bwrs;
        private Prepared(String method, List<Fraction> fractions) {
            this.method = method; this.fractions = fractions;
            double m = 0, t = 0, p = 0, w = 0, reciprocalRho = 0;
            for (Fraction f : fractions) {
                Species s = BY_CODE.get(f.code);
                m += f.moleFraction * s.info.molarMassKgMol;
                t += f.moleFraction * s.info.criticalTemperatureK;
                p += f.moleFraction * s.info.criticalPressurePa;
                w += f.moleFraction * s.info.acentricFactor;
                reciprocalRho += f.moleFraction / s.criticalDensity;
            }
            mass = m; tc = t; pc = p; omega = w; rhoc = 1 / reciprocalRho;
            cubic = method.equals("BWRS") ? null : new Cubic(method.equals("PR"), tc, pc, omega);
            bwrs = method.equals("BWRS") ? Bwrs.mix(fractions) : null;
        }
        public State at(double pressureMpa, double temperatureC) {
            double t = temperatureC + 273.15; validateState(pressureMpa, t);
            double p = pressureMpa * 1e6;
            require(Double.isFinite(p) && p > 0, "压力单位换算超出数值计算能力，请检查压力数量级");
            double rho = density(p, t);
            require(Double.isFinite(rho) && rho > 0, "状态方程未得到有效气相密度，请检查组成和工况");
            Derivatives d = derivatives(rho, t);
            require(Double.isFinite(d.pressure) && Double.isFinite(d.dpT) && Double.isFinite(d.dpRho)
                    && Double.isFinite(d.cvResidual), "状态方程数值计算异常，请检查组成与温压数量级");
            require(Math.abs(d.pressure-p) <= Math.max(p*1e-8,8*Math.ulp(p)),
                    "状态方程密度求解未达到压力回代精度，请检查组成和工况");
            require(d.dpRho > R * t * 1e-5, "工况接近临界点或处于不稳定区，不能按单一气相计算");
            // Divide before squaring so the ideal-gas limit remains representable at low density.
            double pressureTemperaturePerMole = d.dpT / rho;
            double cp = idealCpMolar(t) - R + d.cvResidual
                    + t * pressureTemperaturePerMole * pressureTemperaturePerMole / d.dpRho;
            double z = p / (rho * R * t);
            require(Double.isFinite(cp / mass) && cp > R && Double.isFinite(z) && z > 0
                    && Double.isFinite(rho * mass) && rho * mass > 0,
                    "状态方程未得到有效气相物性，请检查组成和工况");
            return new State(z, cp / mass, rho * mass, mass);
        }
        /** Transport correlation is separate from the PR/SRK/BWRS state equation. */
        public PipelineStandingViscosity.Result standingViscosity(double pressureMpa, double temperatureC) {
            double n2 = 0, co2 = 0, h2s = 0;
            for (Fraction fraction : fractions) {
                switch (fraction.code()) {
                    case "N2" -> n2 = fraction.moleFraction();
                    case "CO2" -> co2 = fraction.moleFraction();
                    case "H2S" -> h2s = fraction.moleFraction();
                    default -> { }
                }
            }
            // Same Kay mixture and dry-air molar-mass convention as the existing pipeline EOS model.
            return PipelineStandingViscosity.calculate(pressureMpa, temperatureC, mass / (R / 287.05),
                    tc, pc / 1e6, n2, co2, h2s);
        }
        public ParameterSnapshot parameters(Double pressureMpa, Double temperatureC) {
            return parameters(pressureMpa, temperatureC, null);
        }
        public ParameterSnapshot parameters(Double pressureMpa, Double temperatureC, State state) {
            return parameterSnapshot(method, fractions, this, pressureMpa, temperatureC, state, "");
        }
        public ParameterSnapshot parameters(List<Fraction> sourceComposition, Double pressureMpa, Double temperatureC, State state) {
            require(fractions.equals(normalize(sourceComposition)), "参数快照的原始组成与本次状态方程不一致");
            return parameterSnapshot(method, sourceComposition, this, pressureMpa, temperatureC, state, "");
        }
        private double density(double p, double t) {
            if (cubic != null) return cubic.density(p, t);
            double low = 0, high = Math.min(p / (R * t), rhoc / 50);
            // Follow the dilute-gas branch and stop at its spinodal; do not silently jump to a liquid root.
            for (int i = 0; i < 180; i++) {
                Derivatives d = bwrs.at(high, t);
                if (d.pressure >= p) {
                    for (int k = 0; k < 80; k++) {
                        double mid = (low + high) / 2;
                        if (mid == low || mid == high) break;
                        if (bwrs.at(mid, t).pressure < p) low = mid; else high = mid;
                        if (high - low <= 1e-10 * high) break;
                    }
                    double rho = (low + high) / 2;
                    require(t >= tc || rho < rhoc, "该工况落在致密液相区域，管流模型只支持气相");
                    return rho;
                }
                require(d.dpRho > 0 && high < rhoc * 5, "BWRS 在当前工况未找到连续稳定气相根，请检查是否发生凝析");
                low = high; high *= 1.12;
            }
            throw new BusinessException(400, "BWRS 气相密度求解未收敛");
        }
        private Derivatives derivatives(double rho, double t) { return cubic != null ? cubic.at(rho, t) : bwrs.at(rho, t); }
        double idealCpMolar(double t) {
            double cp = 0;
            for (Fraction f : fractions) cp += f.moleFraction * BY_CODE.get(f.code).idealCp(t);
            return cp;
        }
        // Used to independently check Cp = (dh/dT)p. The enthalpy zero is arbitrary and never reported to users.
        double enthalpyJkg(double pressureMpa, double temperatureC) {
            double t = temperatureC + 273.15; validateState(pressureMpa, t);
            double p = pressureMpa * 1e6, rho = density(p, t), h = 0;
            for (Fraction f : fractions) h += f.moleFraction * BY_CODE.get(f.code).idealEnthalpy(t);
            return (h + derivatives(rho, t).uResidual + p / rho - R * t) / mass;
        }
        double eosPressurePa(double molarDensity, double temperatureK) { return derivatives(molarDensity, temperatureK).pressure; }
    }
    private record Derivatives(double pressure, double dpT, double dpRho, double cvResidual, double uResidual) {}

    private record CubicConstants(double omegaA, double omegaB, double kappa0, double kappa1, double kappa2) {}
    private static final CubicConstants PR_CONSTANTS = new CubicConstants(.45724, .07780, .37464, 1.54226, -.26992);
    private static final CubicConstants SRK_CONSTANTS = new CubicConstants(.42748, .08664, .48508, 1.55171, -.15613);
    private static final class Cubic {
        private final boolean pr;
        private final double tc, b, a0, k;
        private Cubic(boolean pr, double tc, double pc, double w) {
            this.pr = pr; this.tc = tc;
            CubicConstants constants = pr ? PR_CONSTANTS : SRK_CONSTANTS;
            a0 = constants.omegaA * R * R * tc * tc / pc;
            b = constants.omegaB * R * tc / pc;
            k = constants.kappa0 + constants.kappa1 * w + constants.kappa2 * w * w;
        }
        private double alphaRoot(double t) { return 1 + k * (1 - Math.sqrt(t / tc)); }
        Derivatives at(double rho, double t) {
            double root = alphaRoot(t), a = a0 * root * root;
            double aT = -a0 * k * root / Math.sqrt(t * tc);
            double aTT = a0 * k * (1 + k) / (2 * Math.sqrt(tc) * Math.pow(t, 1.5));
            double br = b * rho, u = pr ? 2 : 1, w = pr ? -1 : 0;
            double q = 1 + u * br + w * br * br;
            double p = rho * R * t / (1 - br) - a * rho * rho / q;
            double pT = rho * R / (1 - br) - aT * rho * rho / q;
            double pRho = R * t / ((1 - br) * (1 - br)) - a * rho * (2 + u * br) / (q * q);
            double integral = pr ? (Math.log1p((1 + Math.sqrt(2)) * br) - Math.log1p((1 - Math.sqrt(2)) * br)) / (2 * Math.sqrt(2) * b)
                    : Math.log1p(br) / b;
            return new Derivatives(p, pT, pRho, t * aTT * integral, (t * aT - a) * integral);
        }
        double density(double p, double t) {
            double A = a0 * Math.pow(alphaRoot(t), 2) * p / (R * R * t * t), B = b * p / (R * t);
            double c2 = pr ? B - 1 : -1;
            double c1 = pr ? A - 2 * B - 3 * B * B : A - B - B * B;
            double c0 = pr ? -A * B + B * B + B * B * B : -A * B;
            double[] roots = cubicRoots(c2, c1, c0);
            double max = Double.NEGATIVE_INFINITY; int stableRoots = 0;
            for (double z : roots) if (z > B && z > 0) {
                double rho = p / (z * R * t);
                if (at(rho, t).dpRho > 0) { max = Math.max(max, z); stableRoots++; }
            }
            require(stableRoots > 0, "状态方程未找到稳定的气相根");
            require(stableRoots == 1, "状态方程出现多个稳定根，需先进行相态判别，不能直接按单一气相计算");
            double rho = p / (max * R * t);
            require(t >= tc || rho * b < (pr ? .253 : .26), "该工况落在液相区域，管流模型只支持气相");
            return rho;
        }
    }
    private static double[] cubicRoots(double a, double b, double c) {
        double p = b - a * a / 3, q = 2 * a * a * a / 27 - a * b / 3 + c;
        double discriminant = q * q / 4 + p * p * p / 27;
        double largest;
        if (discriminant >= 0) {
            double s = Math.sqrt(discriminant);
            largest = Math.cbrt(-q / 2 + s) + Math.cbrt(-q / 2 - s) - a / 3;
        } else {
            double r = 2 * Math.sqrt(-p / 3);
            double angle = Math.acos(Math.max(-1, Math.min(1, (3 * q / (2 * p)) * Math.sqrt(-3 / p)))) / 3;
            largest = r * Math.cos(angle) - a / 3;
        }
        // Near the dilute-gas limit the depressed-cubic discriminant subtracts nearly equal
        // constants. Recover the small roots from their sum/product instead of that subtraction,
        // so roundoff cannot turn a complex pair into extra stable gas roots.
        for (int i = 0; i < 3; i++) {
            double residual = Math.fma(Math.fma(largest, largest + a, b), largest, c);
            double derivative = Math.fma(3 * largest, largest, Math.fma(2 * a, largest, b));
            double next = largest - residual / derivative;
            if (!Double.isFinite(next) || next == largest) break;
            largest = next;
        }
        if (!Double.isFinite(largest) || largest == 0) return new double[]{largest};
        double product = -c / largest, sum = (b - product) / largest;
        double pairDiscriminant = Math.fma(sum, sum, -4 * product);
        if (!(pairDiscriminant >= 0)) return new double[]{largest};
        double first = (sum + Math.copySign(Math.sqrt(pairDiscriminant), sum)) / 2;
        double second = first == 0 ? 0 : product / first;
        return new double[]{largest, first, second};
    }

    private record Bwrs(double A0, double B0, double C0, double D0, double E0, double a, double b,
                        double c, double d, double alpha, double gamma) {
        private static final double[] AA = {.443690, 1.28438, .356306, .544979, .528629, .484011, .0705233, .504087, .0307452, .0732828, .006450};
        private static final double[] BB = {.115449, -.920731, 1.70871, -.270896, .349261, .754130, -.044448, 1.32245, .179433, .463492, -.022143};
        private static final double E_OMEGA_EXPONENT = -3.8;
        static Bwrs pure(Species s) {
            double t = s.info.criticalTemperatureK, w = s.info.acentricFactor, rho = s.criticalDensity;
            double[] v = new double[11]; for (int i = 0; i < 11; i++) v[i] = AA[i] + BB[i] * w;
            double rho2 = rho * rho, t2 = t * t, t3 = t2 * t;
            return new Bwrs(v[1] * R * t / rho, v[0] / rho, v[2] * R * t3 / rho,
                    v[8] * R * t3 * t / rho, (AA[10] + BB[10] * w * Math.exp(E_OMEGA_EXPONENT * w)) * R * t3 * t2 / rho,
                    v[5] * R * t / rho2, v[4] / rho2, v[7] * R * t3 / rho2, v[9] * R * t2 / rho2,
                    v[6] / (rho2 * rho), v[3] / rho2);
        }
        static Bwrs mix(List<Fraction> fractions) {
            double A = 0, B = 0, C = 0, D = 0, E = 0, a = 0, b = 0, c = 0, d = 0, alpha = 0, gamma = 0;
            // Teacher's double-sum mixing rules reduce to squared sums when every Kij is explicitly zero.
            for (Fraction f : fractions) {
                Bwrs v = pure(BY_CODE.get(f.code)); double x = f.moleFraction;
                A += x * Math.sqrt(v.A0); B += x * v.B0; C += x * Math.sqrt(v.C0);
                D += x * Math.sqrt(v.D0); E += x * Math.sqrt(v.E0);
                a += x * Math.cbrt(v.a); b += x * Math.cbrt(v.b); c += x * Math.cbrt(v.c);
                d += x * Math.cbrt(v.d); alpha += x * Math.cbrt(v.alpha); gamma += x * Math.sqrt(v.gamma);
            }
            return new Bwrs(A * A, B, C * C, D * D, E * E, a * a * a, b * b * b, c * c * c,
                    d * d * d, alpha * alpha * alpha, gamma * gamma);
        }
        Derivatives at(double rho, double t) {
            double t2 = t * t, t3 = t2 * t, t4 = t3 * t, t5 = t4 * t, t6 = t5 * t;
            double r2 = rho * rho, r3 = r2 * rho, r5 = r3 * r2, r6 = r5 * rho, x = gamma * r2;
            double exponential = Math.exp(-x), expTerm = r3 * (1 + x) * exponential;
            double q2 = B0 * R * t - A0 - C0 / t2 + D0 / t3 - E0 / t4;
            double q3 = b * R * t - a - d / t, q6 = alpha * (a + d / t);
            double q2T = B0 * R + 2 * C0 / t3 - 3 * D0 / t4 + 4 * E0 / t5;
            double q3T = b * R + d / t2, q6T = -alpha * d / t2;
            double pressure = rho * R * t + q2 * r2 + q3 * r3 + q6 * r6 + c / t2 * expTerm;
            double pT = rho * R + q2T * r2 + q3T * r3 + q6T * r6 - 2 * c / t3 * expTerm;
            double pRho = R * t + 2 * q2 * rho + 3 * q3 * r2 + 6 * q6 * r5
                    + c / t2 * r2 * (3 + 3 * x - 2 * x * x) * exponential;
            // Integral from 0 to rho of r (1+gamma*r^2) exp(-gamma*r^2) dr; expm1 avoids low-density cancellation.
            double integral = (-2 * Math.expm1(-x) - x * exponential) / (2 * gamma);
            double q2TT = -6 * C0 / t4 + 12 * D0 / t5 - 20 * E0 / t6;
            double cv = -t * (q2TT * rho - d / t3 * r2 + 2 * alpha * d / t3 * r5 / 5 + 6 * c / t4 * integral);
            double u = (q2 - t * q2T) * rho + (q3 - t * q3T) * r2 / 2
                    + (q6 - t * q6T) * r5 / 5 + 3 * c / t2 * integral;
            return new Derivatives(pressure, pT, pRho, cv, u);
        }
    }
}
