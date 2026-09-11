package com.grdp.studio.dynamicproductivity.service;

import com.grdp.studio.common.BusinessException;
import com.grdp.studio.dynamicproductivity.dto.DynamicUnstableDtos.CalculateRequest;
import com.grdp.studio.dynamicproductivity.dto.DynamicUnstableDtos.CalculatedOperation;
import com.grdp.studio.dynamicproductivity.dto.DynamicUnstableDtos.CalculationResult;
import com.grdp.studio.dynamicproductivity.dto.DynamicUnstableDtos.Derived;
import com.grdp.studio.dynamicproductivity.dto.DynamicUnstableDtos.Input;
import com.grdp.studio.dynamicproductivity.dto.DynamicUnstableDtos.IprPoint;
import com.grdp.studio.dynamicproductivity.dto.DynamicUnstableDtos.Output;
import com.grdp.studio.integration.OriginalPlatformClient;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 后端完成不稳定流物性调用、单位换算、A/B 计算及 IPR 离散。 */
@Service
public class DynamicUnstableCalculationService {
    private static final String VISCOSITY = "GasPVT_Viscosity";
    private static final String DEVIATION = "GasPVT_DeviationFactor";
    private static final String DENSITY = "GasPVT_Density";
    private static final String PSEUDO_PRESSURE = "GasPVT_PseudoPressure";
    private static final double STANDARD_PRESSURE_MPA = 0.101325;
    private static final double STANDARD_TEMPERATURE_C = 20.0;
    private static final double STANDARD_TEMPERATURE_K = 293.15;
    private static final double STANDARD_Z = 1.0;
    private static final double GAMMA = 1.781;
    private static final double MPA_TO_PA = 1e6;
    private static final double MD_TO_M2 = 9.869233e-16;
    private static final double DAY_TO_SECOND = 86400.0;
    private static final double Q_SI_TO_DISPLAY = 8.64; // m3/s -> 10^4m3/d
    private static final int POINTS_PER_CURVE = 41;

    private final OriginalPlatformClient client;
    private final ObjectMapper objectMapper;

    public DynamicUnstableCalculationService(OriginalPlatformClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    public CalculationResult calculate(CalculateRequest request, String token, String cookie, String processEnv) {
        validate(request);
        Input input = request.input();
        Map<String, String> headers = forwardedHeaders(token, cookie, processEnv);

        // 四类物性使用各自的工具箱 ID；拟压力 ID 在本次全部压力点之间复用。
        long viscosityId = createToolbox(VISCOSITY, request.projectId(), headers);
        long deviationId = createToolbox(DEVIATION, request.projectId(), headers);
        long densityId = createToolbox(DENSITY, request.projectId(), headers);
        long pseudoId = createToolbox(PSEUDO_PRESSURE, request.projectId(), headers);

        double piMpa = input.originalFormationPressure();
        double viscosity = calculateValue(viscosityId, toolboxInput(input, piMpa, input.formationTemperature()),
                headers, List.of("viscosity"), "原始地层压力下天然气黏度");
        double deviation = calculateValue(deviationId, toolboxInput(input, piMpa, input.formationTemperature()),
                headers, List.of("deviationFactor", "gasDeviationFactor", "zFactor", "z"), "天然气偏差系数");
        double density = calculateValue(densityId, toolboxInput(input, STANDARD_PRESSURE_MPA, STANDARD_TEMPERATURE_C),
                headers, List.of("density"), "标准状态天然气密度");

        double k = input.permeability() * MD_TO_M2;
        double mu = viscosity * 1e-3; // mPa.s -> Pa.s
        double ct = input.totalCompressibility() / MPA_TO_PA; // MPa^-1 -> Pa^-1
        double t = input.flowTime() * DAY_TO_SECOND;
        double beta = 4.35e7 / Math.pow(1e12 * k, 1.1045);
        double diffusivity = k / (input.porosity() * mu * ct);
        String functionType = "horizontal".equals(request.wellType()) ? "FH" : "FT";
        double functionValue = "FH".equals(functionType)
                ? horizontalFunction(input)
                : Math.log(4.0 * k * t / (GAMMA * input.porosity() * mu * ct
                    * input.wellboreRadius() * input.wellboreRadius())) + 2.0 * input.skinFactor();
        if (!Double.isFinite(functionValue) || functionValue <= 0) {
            throw new BusinessException(400, functionType + " 计算结果必须大于0，请检查井径、时间和物性参数");
        }

        Derived derived = new Derived(viscosity, deviation, density, beta,
                "FT".equals(functionType) ? diffusivity : null, functionType, functionValue);
        Coefficients coefficients = coefficients(request.wellType(), input, derived);

        // 工具箱为有状态接口：相同 ID 必须严格 calc 后立即 GET，不能并发读取。
        Map<Double, Double> pseudoCache = new LinkedHashMap<>();
        java.util.function.DoubleFunction<Double> pseudo = pressure -> pseudoCache.computeIfAbsent(
                roundedPressure(pressure), key -> calculateValue(pseudoId,
                        toolboxInput(input, Math.max(key, 1e-6), input.formationTemperature()), headers,
                        List.of("outPressure", "pseudoPressure", "gasPseudoPressure"), "天然气拟压力"));

        List<Output> outputs = List.of(
                buildOutput("pressure", coefficients.pressureA, coefficients.pressureB,
                        input, request.operationType(), null),
                buildOutput("pressure_squared", coefficients.squaredA, coefficients.squaredB,
                        input, request.operationType(), null),
                buildOutput("pseudo_pressure", coefficients.pseudoA, coefficients.pseudoB,
                        input, request.operationType(), pseudo)
        );
        return new CalculationResult(request.wellType(),
                new CalculatedOperation(request.operationType(), input, derived, outputs));
    }

    private Output buildOutput(String method, double a, double b, Input input, String direction,
                               java.util.function.DoubleFunction<Double> pseudo) {
        List<IprPoint> points = new ArrayList<>(10 * POINTS_PER_CURVE);
        double maximumRate = 0;
        for (int curve = 1; curve <= 10; curve++) {
            double reservoirPressure = input.originalFormationPressure() * curve / 10.0;
            for (int index = 0; index < POINTS_PER_CURVE; index++) {
                double fraction = (double) index / (POINTS_PER_CURVE - 1);
                double bottomPressure = "production".equals(direction)
                        ? reservoirPressure * (1.0 - fraction)
                        : reservoirPressure + (input.originalFormationPressure() - reservoirPressure) * fraction;
                double delta = pressureDelta(method, reservoirPressure, bottomPressure, direction, pseudo);
                double rate = positiveRoot(a, b, Math.max(0, delta));
                maximumRate = Math.max(maximumRate, rate);
                points.add(new IprPoint(curve, rate, bottomPressure));
            }
        }
        // 当前方法属于公式计算而非试井回归，因此没有可计算的拟合优度。
        return new Output(method, a, b, maximumRate, null, List.copyOf(points));
    }

    private double pressureDelta(String method, double pr, double pwf, String direction,
                                 java.util.function.DoubleFunction<Double> pseudo) {
        boolean production = "production".equals(direction);
        return switch (method) {
            case "pressure" -> production ? pr - pwf : pwf - pr;
            case "pressure_squared" -> production ? pr * pr - pwf * pwf : pwf * pwf - pr * pr;
            case "pseudo_pressure" -> production ? pseudo.apply(pr) - pseudo.apply(pwf)
                    : pseudo.apply(pwf) - pseudo.apply(pr);
            default -> throw new IllegalArgumentException("未知压力处理方式");
        };
    }

    private double positiveRoot(double a, double b, double delta) {
        if (delta <= 0) return 0;
        if (Math.abs(b) < 1e-30) return delta / a;
        return (-a + Math.sqrt(a * a + 4.0 * b * delta)) / (2.0 * b);
    }

    private Coefficients coefficients(String wellType, Input input, Derived derived) {
        double psc = STANDARD_PRESSURE_MPA * MPA_TO_PA;
        double temperature = input.formationTemperature() + 273.15;
        double pi = input.originalFormationPressure() * MPA_TO_PA;
        double k = input.permeability() * MD_TO_M2;
        double mu = derived.initialGasViscosity() * 1e-3;
        double h = input.formationThickness();
        double effectiveLengthSquared = "horizontal".equals(wellType)
                ? input.horizontalSectionLength() * input.horizontalSectionLength() : h * h;
        double skinRadius = input.wellboreRadius() * Math.exp(-input.skinFactor());

        double pressureASi = psc * temperature * mu * derived.initialGasDeviationFactor()
                * derived.transientFunctionValue()
                / (4.0 * Math.PI * k * h * STANDARD_TEMPERATURE_K * STANDARD_Z * pi);
        double pressureBSi = psc * temperature * derived.nonDarcyCoefficientBeta()
                * derived.standardGasDensity() * derived.initialGasDeviationFactor()
                / (4.0 * Math.PI * Math.PI * effectiveLengthSquared * STANDARD_TEMPERATURE_K
                    * STANDARD_Z * pi * skinRadius);
        double squaredASi = psc * temperature * mu * derived.initialGasDeviationFactor()
                * derived.transientFunctionValue()
                / (2.0 * Math.PI * k * h * STANDARD_TEMPERATURE_K * STANDARD_Z);
        double squaredBSi = psc * temperature * derived.nonDarcyCoefficientBeta()
                * derived.standardGasDensity() * derived.initialGasDeviationFactor()
                / (2.0 * Math.PI * Math.PI * effectiveLengthSquared * STANDARD_TEMPERATURE_K
                    * STANDARD_Z * skinRadius);
        double pseudoASi = psc * temperature * derived.transientFunctionValue()
                / (2.0 * Math.PI * k * h * STANDARD_TEMPERATURE_K * STANDARD_Z);
        double pseudoBSi = psc * temperature * derived.nonDarcyCoefficientBeta()
                * derived.standardGasDensity()
                / (2.0 * Math.PI * Math.PI * effectiveLengthSquared * STANDARD_TEMPERATURE_K
                    * STANDARD_Z * mu * skinRadius);

        // 把 SI 方程系数换成页面方程：q=10^4m3/d，压差分别为 MPa、MPa2、MPa2/(mPa.s)。
        return new Coefficients(
                pressureASi / (MPA_TO_PA * Q_SI_TO_DISPLAY),
                pressureBSi / (MPA_TO_PA * Q_SI_TO_DISPLAY * Q_SI_TO_DISPLAY),
                squaredASi / (1e12 * Q_SI_TO_DISPLAY),
                squaredBSi / (1e12 * Q_SI_TO_DISPLAY * Q_SI_TO_DISPLAY),
                pseudoASi / (1e15 * Q_SI_TO_DISPLAY),
                pseudoBSi / (1e15 * Q_SI_TO_DISPLAY * Q_SI_TO_DISPLAY)
        );
    }

    private double horizontalFunction(Input input) {
        double length = input.horizontalSectionLength();
        double halfLength = length / 2.0;
        double a = halfLength * Math.sqrt(0.5
                + Math.sqrt(Math.pow(2.0 * input.drainageRadius() / length, 4) + 0.25));
        double geometry = Math.log((a + Math.sqrt(Math.max(0, a * a - halfLength * halfLength))) / halfLength);
        double skin = input.formationThickness() / length
                * Math.log(input.formationThickness()
                    / (2.0 * Math.PI * input.wellboreRadius() * Math.exp(-input.skinFactor())));
        return geometry + skin;
    }

    private Map<String, Object> toolboxInput(Input input, double pressure, double temperature) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("gasType", gasTypeIndex(input.gasType()));
        values.put("specificGravity", input.specificGravity());
        values.put("co2MoleFraction", input.carbonDioxide());
        values.put("n2MoleFraction", input.nitrogen());
        values.put("h2SMoleFraction", input.hydrogenSulfide());
        values.put("pressure", pressure);
        values.put("temperature", temperature);
        values.put("originalPressure", input.originalFormationPressure());
        values.put("pseudoPressure", 4e-8);
        values.put("regularizedPseudoPressure", input.originalFormationPressure());
        values.put("apparentPressure", input.originalFormationPressure());
        values.put("modificationMethod", methodIndex(input.modificationMethod(), "Carr"));
        values.put("deviationFactorMethod", methodIndex(input.deviationFactorMethod(), "Purvis", "Hall"));
        values.put("viscosityMethod", methodIndex(input.viscosityMethod(), "Carr", "Sutton"));
        return values;
    }

    private int gasTypeIndex(String value) {
        if (value.contains("湿")) return 1;
        if (value.contains("凝析")) return 2;
        return 0;
    }

    private int methodIndex(String value, String... laterMethodMarkers) {
        for (int index = 0; index < laterMethodMarkers.length; index++) {
            if (value.contains(laterMethodMarkers[index])) return index + 1;
        }
        return 0;
    }

    private long createToolbox(String algorithm, long projectId, Map<String, String> headers) {
        JsonNode created = client.post("/api/toolbox", Map.of("algorithm", algorithm, "projectId", projectId),
                JsonNode.class, headers);
        JsonNode id = findField(created, List.of("id"));
        if (id == null || !id.canConvertToLong()) throw new BusinessException(502, "创建" + algorithm + "工具箱失败");
        return id.longValue();
    }

    private double calculateValue(long id, Map<String, Object> input, Map<String, String> headers,
                                  List<String> fields, String label) {
        try {
            client.post("/api/toolbox/calc", Map.of("id", id, "input", objectMapper.writeValueAsString(input)),
                    JsonNode.class, headers);
        } catch (Exception error) {
            throw new BusinessException(502, label + "计算请求失败");
        }
        // 工具箱计算是异步落结果的。calc 返回只代表任务已受理，立即 GET 可能仍是上一次压力的结果，
        // 进而让整条拟压力曲线的压差都变成 0。这里以 input.pressure 作为本次结果的版本标记。
        double expectedPressure = ((Number) input.get("pressure")).doubleValue();
        for (int attempt = 0; attempt < 20; attempt++) {
            JsonNode result = client.get("/api/toolbox/" + id, JsonNode.class, headers);
            JsonNode resultInput = result == null ? null : result.get("input");
            JsonNode actualPressure = resultInput == null ? null : resultInput.get("pressure");
            if (actualPressure != null && actualPressure.isNumber()
                    && Math.abs(actualPressure.doubleValue() - expectedPressure) < 1e-8) {
                JsonNode value = findField(result.get("output"), fields);
                if (value == null || !value.isNumber() || !Double.isFinite(value.doubleValue())) {
                    throw new BusinessException(502, "工具箱结果中未找到" + label);
                }
                return value.doubleValue();
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new BusinessException(502, label + "计算等待被中断");
            }
        }
        throw new BusinessException(502, label + "计算结果更新超时");
    }

    private JsonNode findField(JsonNode node, List<String> names) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) {
            try { return findField(objectMapper.readTree(node.textValue()), names); }
            catch (Exception ignored) { return null; }
        }
        if (node.isObject()) {
            for (String name : names) {
                JsonNode direct = node.get(name);
                if (direct != null && !direct.isNull()) return direct;
            }
        }
        if (node.isObject() || node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = findField(child, names);
                if (found != null) return found;
            }
        }
        return null;
    }

    private Map<String, String> forwardedHeaders(String token, String cookie, String processEnv) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("token", token);
        headers.put(HttpHeaders.COOKIE, cookie);
        headers.put("Process-Env", processEnv == null || processEnv.isBlank() ? "prod" : processEnv);
        return headers;
    }

    private double roundedPressure(double pressure) {
        return Math.rint(pressure * 1e8) / 1e8;
    }

    private void validate(CalculateRequest request) {
        if (!List.of("vertical", "horizontal").contains(request.wellType()))
            throw new BusinessException(400, "井型只能是 vertical 或 horizontal");
        if (!List.of("production", "injection").contains(request.operationType()))
            throw new BusinessException(400, "注采方向不正确");
        if (request.input().porosity() > 1) throw new BusinessException(400, "孔隙度必须在0到1之间");
        if ("horizontal".equals(request.wellType())
                && (request.input().horizontalSectionLength() == null || request.input().horizontalSectionLength() <= 0))
            throw new BusinessException(400, "水平井必须提供大于0的水平段长度");
    }

    private record Coefficients(double pressureA, double pressureB,
                                double squaredA, double squaredB,
                                double pseudoA, double pseudoB) {}
}
