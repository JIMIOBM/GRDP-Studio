package com.grdp.studio.wellbore;

import com.grdp.studio.common.BusinessException;
import java.util.ArrayList;
import java.util.List;

/** Java port of the supplied linear and Alves engineering temperature profiles. */
public final class TemperatureCalculator {
    private TemperatureCalculator() {}
    public static final double PRODUCTION_TIME_DAYS = 30;
    public static final double WELLBORE_RADIUS_MM = 108;

    public record Thermal(double relaxationDistance, double dimensionlessTime, double timeFunction,
                          double mixtureHeatCapacity) {}
    public record Result(String tempModel, List<Double> depth, List<Double> temp, List<Double> tempFormation,
                         Thermal thermal, double inferredBottomTemperature, Double predictedWellheadTemperature,
                         double jtGradient, double gravityGradient, boolean pressureCoupled,
                         List<String> notices, String calculationPosition, double boundaryPressure,
                         double liquidDensity, Double liquidViscosity, int pvtIterations) {}

    public static double stdGasDensity(double gammaG) { return gammaG * 1.205; }

    public static Thermal thermalRelaxation(TemperatureRequest p) {
        double mGas = p.qGas * 10000 * stdGasDensity(p.gammaG) / 86400;
        double mLiq = p.qLiq * p.rhoL / 86400;
        double cpMix = (mGas * p.cpGas + mLiq * 4180) / (mGas + mLiq);
        double rTo = p.idTubing / 2000 + p.wallMm / 1000;
        double radius = WELLBORE_RADIUS_MM / 1000;
        double tD = p.formationK * PRODUCTION_TIME_DAYS * 86400 / (p.formationRhoCp * 1e6 * radius * radius);
        double sqrtTD = Math.sqrt(tD);
        double timeFunction = tD > 1.5 ? (0.4063 + 0.5 * Math.log(tD)) * (1 + 0.6 / tD)
                : 1.1281 * sqrtTD * (1 - 0.3 * sqrtTD);
        double a = (mGas + mLiq) * cpMix / (2 * Math.PI * rTo * p.uTo)
                * (p.formationK + rTo * p.uTo * timeFunction) / p.formationK;
        return new Thermal(a, tD, timeFunction, cpMix);
    }

    public static Result calculate(TemperatureRequest p) {
        return calculate(p, 0);
    }

    public static Result calculate(TemperatureRequest p, int pvtIterations) {
        validate(p);
        int segments = (int) Math.ceil(p.depth / p.step);
        List<Double> depths = new ArrayList<>(), formation = new ArrayList<>(), temperatures = new ArrayList<>();
        for (int i = 0; i <= segments; i++) {
            double d = Math.min(i * p.step, p.depth);
            depths.add(d);
            formation.add(p.tSurf + p.tGrad / 100 * d);
            temperatures.add(p.tWh + p.tGrad / 100 * d);
        }
        if ("linear".equals(p.tempModel)) {
            return new Result(p.tempModel, depths, temperatures, formation, null,
                    temperatures.get(segments), null, 0, 0, false, List.of("井口锚定线性模型：仅使用井深、步长、地温梯度、地表温度和井口温度。"), p.calculationPosition, p.fWh, p.rhoL, p.muL, pvtIterations);
        }
        boolean coupled = p.pressureProfile != null;
        if (coupled) {
            require(p.pressureProfile.size() == depths.size(), "压力剖面点数必须与温度计算深度点数一致");
            for (Double pressure : p.pressureProfile) require(pressure != null && Double.isFinite(pressure) && pressure > 0, "压力剖面必须为有限正数（MPa）");
        }
        Thermal thermal = thermalRelaxation(p);
        double a = thermal.relaxationDistance();
        require(Double.isFinite(a) && a > 0, "热松弛距离无效，请检查产量和传热参数");
        double gravity = 9.80665 * Math.cos(p.angle * Math.PI / 180) / thermal.mixtureHeatCapacity();
        double jtSum = 0;
        boolean bottomBoundary = "bottomhole".equals(p.calculationPosition);
        temperatures.set(segments, bottomBoundary ? p.tWh : formation.get(segments));
        for (int i = segments; i > 0; i--) {
            double length = depths.get(i) - depths.get(i - 1);
            double rise = coupled ? Math.max(p.pressureProfile.get(i) - p.pressureProfile.get(i - 1), 0) : 0;
            double jt = p.muJt * rise / length;
            double offset = a * (p.tGrad / 100 - jt - gravity);
            double relaxation = Math.exp(-Math.min(length / a, 20));
            temperatures.set(i - 1, formation.get(i - 1) + offset
                    + (temperatures.get(i) - formation.get(i) - offset) * relaxation);
            jtSum += jt * length;
        }
        double predictedWellhead = temperatures.get(0);
        double correction = bottomBoundary ? 0 : p.tWh - predictedWellhead;
        for (int i = 0; i <= segments; i++) {
            double t = temperatures.get(i) + correction * Math.exp(-depths.get(i) / a);
            require(Double.isFinite(t) && t > -273.15, "计算温度超出有效范围，请检查输入参数");
            temperatures.set(i, t);
        }
        if (!bottomBoundary) temperatures.set(0, p.tWh);
        List<String> notices = new ArrayList<>();
        if (!coupled) notices.add("当前未接入压力剖面，未计入焦耳–汤姆逊温变；结果为传热与重力项的温度剖面。 ");
        notices.add("内部沿用原算法默认值：连续生产时间 30 d、井眼半径 108 mm。地温梯度按测井深度计算。");
        notices.add(bottomBoundary ? "采用生产记录中的井底温度作为井底边界，沿同一分段能量方程向井口积分。" : "采用生产记录中的井口温度进行井口边界校正。");
        if (pvtIterations > 0) notices.add("地层水密度按所选位置压力及剖面平均温度迭代；尚未进行沿深度的压力–温度全耦合。");
        return new Result(p.tempModel, depths, temperatures, formation, thermal, temperatures.get(segments),
                predictedWellhead, jtSum / p.depth, gravity, coupled, notices,
                p.calculationPosition, p.fWh, p.rhoL, p.muL, pvtIterations);
    }

    private static void validate(TemperatureRequest p) {
        require(p != null, "缺少温度模型参数");
        require("wellhead".equals(p.calculationPosition) || "bottomhole".equals(p.calculationPosition), "计算位置必须是井口或井底");
        range(p.fWh, 0.000001, 1000, "计算位置油压");
        range(p.roughness, 0, 100, "管内壁粗糙度");
        require("linear".equals(p.tempModel) || "alves".equals(p.tempModel), "不支持的温度模型");
        range(p.depth, 0.001, 100000, "测井深度");
        range(p.step, 0.001, 100000, "计算步长");
        require(Math.ceil(p.depth / p.step) <= 10000, "计算段数不能超过 10000，请增大步长");
        range(p.tGrad, 0, 100, "地温梯度");
        range(p.tSurf, -273.14, 1000, "地表温度");
        range(p.tWh, -273.14, 1000, "计算位置温度");
        if ("linear".equals(p.tempModel)) return;
        range(p.idTubing, 0.001, 2000, "油管内径");
        range(p.angle, 0, 90, "井斜角");
        range(p.gammaG, 0.001, 10, "气体相对密度");
        range(p.rhoL, 0.001, 10000, "液体密度");
        range(p.uTo, 0.001, 100000, "总传热系数");
        range(p.wallMm, 0, 500, "油管壁厚");
        range(p.muJt, -1000, 1000, "焦耳–汤姆逊系数");
        range(p.cpGas, 0.001, 100000, "气体定压比热");
        range(p.formationK, 0.001, 1000, "地层导热系数");
        range(p.formationRhoCp, 0.001, 1000, "地层体积热容");
        range(p.qGas, 0, 1000000, "日产气量");
        range(p.qLiq, 0, 1000000, "日产水量");
        require(p.qGas + p.qLiq > 0, "Alves 生产温度模型要求气量或水量大于 0");
    }

    private static void range(double value, double min, double max, String name) {
        require(Double.isFinite(value) && value >= min && value <= max, name + "必须在 " + min + " 至 " + max + " 之间");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new BusinessException(400, message);
    }
}
