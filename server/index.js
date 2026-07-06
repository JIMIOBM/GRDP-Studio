import express from "express";
import mysql from "mysql2/promise";

const app = express();
app.use(express.json());

const DB_HOST = "mariadb";
const DB_PORT = 3306;
const DB_USER = "root";
const DB_PASS = "password";
const DB_NAME = "database";

let pool;

async function getPool() {
  if (!pool) {
    pool = mysql.createPool({
      host: DB_HOST,
      port: DB_PORT,
      user: DB_USER,
      password: DB_PASS,
      database: DB_NAME,
      waitForConnections: true,
      connectionLimit: 5,
    });
  }
  return pool;
}

function calcPseudoCritical(sg, h2s, co2, n2) {
  if (sg < 0.75) {
    const ppc = 677 + 15 * sg - 37.5 * sg * sg;
    const tpc = 168 + 325 * sg - 12.5 * sg * sg;
    return { ppc: ppc * 0.00689476, tpc: tpc * 0.555556 + 273.15 };
  }
  const ppc = 706 - 51.7 * sg - 11.1 * sg * sg;
  const tpc = 187 + 330 * sg - 71.5 * sg * sg;
  return { ppc: ppc * 0.00689476, tpc: tpc * 0.555556 + 273.15 };
}

function calcZFactor(p, T, sg, h2s, co2, n2) {
  const { ppc, tpc } = calcPseudoCritical(sg, h2s || 0, co2 || 0, n2 || 0);
  const ppr = p / ppc;
  const tpr = T / tpc;

  const A1 = 0.3265,
    A2 = -1.07,
    A3 = -0.5339,
    A4 = 0.01569;
  const A5 = -0.05165,
    A6 = 0.5475,
    A7 = -0.7361,
    A8 = 0.1844;
  const A9 = 0.1056,
    A10 = 0.6134,
    A11 = 0.721;

  let z = 1;
  for (let iter = 0; iter < 50; iter++) {
    const rho = (0.27 * ppr) / (z * tpr);
    const rho2 = rho * rho;
    const rho5 = rho2 * rho2 * rho;
    const c =
      A1 +
      A2 / tpr +
      A3 / (tpr * tpr * tpr) +
      A4 / (tpr * tpr * tpr * tpr) +
      A5 / (tpr * tpr * tpr * tpr * tpr);
    const d = A6 + A7 / tpr + A8 / (tpr * tpr);
    const e = A9 * (A7 / tpr + A8 / (tpr * tpr));
    const f = A10 / (tpr * tpr * tpr);
    const fz =
      1 +
      c * rho -
      d * rho2 +
      e * rho5 +
      f * rho2 * (1 + A11 * rho2) * Math.exp(-A11 * rho2);
    const fzPrime =
      c -
      2 * d * rho +
      5 * e * rho2 * rho2 +
      2 *
        f *
        rho *
        (1 + A11 * rho2 - A11 * A11 * rho2 * rho2) *
        Math.exp(-A11 * rho2);
    const zNew = fz - rho * fzPrime;
    if (Math.abs(zNew - z) < 1e-8) {
      z = zNew;
      break;
    }
    z = zNew;
  }
  return z;
}

function normalizeTemperatureToKelvin(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) return null;
  return Math.abs(n) > 200 ? n : n + 273.15;
}

function normalizeTemperatureToCelsius(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) return null;
  return Math.abs(n) > 200 ? n - 273.15 : n;
}

function normalizeFractionToPercent(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) return null;
  return Math.abs(n) <= 1 ? n * 100 : n;
}

function firstFinite(...values) {
  for (const value of values) {
    const n = Number(value);
    if (Number.isFinite(n)) return n;
  }
  return null;
}

function linearRegression(xs, ys) {
  const n = xs.length;
  if (n < 2) return { slope: 0, intercept: 0, r2: 0 };
  let sx = 0,
    sy = 0,
    sxy = 0,
    sx2 = 0,
    sy2 = 0;
  for (let i = 0; i < n; i++) {
    sx += xs[i];
    sy += ys[i];
    sxy += xs[i] * ys[i];
    sx2 += xs[i] * xs[i];
    sy2 += ys[i] * ys[i];
  }
  const slope = (n * sxy - sx * sy) / (n * sx2 - sx * sx);
  const intercept = (sy - slope * sx) / n;
  const r2Num = n * sxy - sx * sy;
  const r2Den = (n * sx2 - sx * sx) * (n * sy2 - sy * sy);
  const r2 = r2Den > 0 ? (r2Num * r2Num) / r2Den : 0;
  return { slope, intercept, r2 };
}

function quadraticRegression(xs, ys) {
  const n = xs.length;
  if (n < 3) return null;
  let sx = 0,
    sx2 = 0,
    sx3 = 0,
    sx4 = 0,
    sy = 0,
    sxy = 0,
    sx2y = 0;
  for (let i = 0; i < n; i++) {
    const x = xs[i];
    const y = ys[i];
    const x2 = x * x;
    sx += x;
    sx2 += x2;
    sx3 += x2 * x;
    sx4 += x2 * x2;
    sy += y;
    sxy += x * y;
    sx2y += x2 * y;
  }
  const m = [
    [sx4, sx3, sx2, sx2y],
    [sx3, sx2, sx, sxy],
    [sx2, sx, n, sy],
  ];
  for (let col = 0; col < 3; col++) {
    let pivot = col;
    for (let row = col + 1; row < 3; row++) {
      if (Math.abs(m[row][col]) > Math.abs(m[pivot][col])) pivot = row;
    }
    if (Math.abs(m[pivot][col]) < 1e-12) return null;
    if (pivot !== col) [m[col], m[pivot]] = [m[pivot], m[col]];
    const div = m[col][col];
    for (let j = col; j < 4; j++) m[col][j] /= div;
    for (let row = 0; row < 3; row++) {
      if (row === col) continue;
      const factor = m[row][col];
      for (let j = col; j < 4; j++) m[row][j] -= factor * m[col][j];
    }
  }
  return { a: m[0][3], b: m[1][3], c: m[2][3] };
}

function makeCurveLines(model, xMin, xMax, color, label) {
  const segmentCount = 32;
  const lines = [];
  const y = (x) => model.a * x * x + model.b * x + model.c;
  for (let i = 1; i <= segmentCount; i++) {
    const x1 = xMin + ((xMax - xMin) * (i - 1)) / segmentCount;
    const x2 = xMin + ((xMax - xMin) * i) / segmentCount;
    lines.push({
      x1,
      y1: y(x1),
      x2,
      y2: y(x2),
      color,
      label,
    });
  }
  return lines;
}

app.post("/projectanalysis/flowbalanceanalysis", async (req, res) => {
  try {
    const { gasReservoirId, wellNames, waterGasRatioLimit } = req.body;
    const wellName = wellNames?.[0];
    if (!wellName) {
      return res.status(400).json({ message: "缺少井名" });
    }

    const p = await getPool();

    const [gasReservoirRows] = await p.query(
      "SELECT * FROM core_gas_reservoirs WHERE id = ?",
      [gasReservoirId || 1],
    );
    const reservoir = gasReservoirRows[0] || {};

    const specificGravity =
      typeof reservoir.specific_gravity === "number"
        ? reservoir.specific_gravity
        : typeof reservoir.specificGravity === "number"
          ? reservoir.specificGravity
          : null;
    const h2s =
      typeof reservoir.hydrogen_sulfide === "number"
        ? reservoir.hydrogen_sulfide
        : typeof reservoir.h2s === "number"
          ? reservoir.h2s
          : null;
    const co2 =
      typeof reservoir.carbon_dioxide === "number"
        ? reservoir.carbon_dioxide
        : typeof reservoir.co2 === "number"
          ? reservoir.co2
          : null;
    const n2 =
      typeof reservoir.nitrogen === "number"
        ? reservoir.nitrogen
        : typeof reservoir.n2 === "number"
          ? reservoir.n2
          : null;
    const rawT =
      typeof reservoir.formation_temperature_of_gas_reservoir === "number"
        ? reservoir.formation_temperature_of_gas_reservoir
        : typeof reservoir.bottom_temp === "number"
          ? reservoir.bottom_temp
          : null;
    const T = normalizeTemperatureToKelvin(rawT ?? 373);
    const Pi =
      typeof reservoir.original_formation_pressure_of_gas_reservoir === "number"
        ? reservoir.original_formation_pressure_of_gas_reservoir
        : typeof reservoir.initial_pressure === "number"
          ? reservoir.initial_pressure
          : null;
    const middleDepth =
      typeof reservoir.middle_depth_of_gas_reservoir === "number"
        ? reservoir.middle_depth_of_gas_reservoir
        : typeof reservoir.middle_depth === "number"
          ? reservoir.middle_depth
          : null;
    const swc =
      typeof reservoir.irreducible_water_saturation === "number"
        ? reservoir.irreducible_water_saturation
        : typeof reservoir.average_irreducible_water_saturation === "number"
          ? reservoir.average_irreducible_water_saturation
          : typeof reservoir.bound_water_saturation === "number"
            ? reservoir.bound_water_saturation
            : typeof reservoir.connate_water_saturation === "number"
              ? reservoir.connate_water_saturation
              : typeof reservoir.swc === "number"
                ? reservoir.swc
                : null;
    const gasType =
      typeof reservoir.gas_type === "string" && reservoir.gas_type
        ? reservoir.gas_type
        : typeof reservoir.gasType === "string" && reservoir.gasType
          ? reservoir.gasType
          : "";

    const [gasPropRows] = await p.query(
      "SELECT * FROM core_gas_property WHERE well_name = ? AND gas_reservoir_id = ?",
      [wellName, gasReservoirId || 1],
    );
    const gasProp = gasPropRows[0] || {};
    const sg =
      firstFinite(gasProp.specific_gravity, specificGravity, 0.58);
    const useH2s = firstFinite(gasProp.hydrogen_sulfide, h2s, 0);
    const useCo2 = firstFinite(gasProp.carbon_dioxide, co2, 0);
    const useN2 = firstFinite(gasProp.nitrogen, n2, 0);

    const [prodRows] = await p.query(
      "SELECT date, daily_gas_production, daily_water_production, measured_bottom_hole_pressure, water_gas_ratio FROM core_production_data WHERE well_name = ? AND gas_reservoir_id = ? ORDER BY date ASC",
      [wellName, gasReservoirId || 1],
    );

    const [staticRows] = await p.query(
      "SELECT date, reservior_pressure, cumulative_gas_production FROM core_static_pressure_data WHERE well_name = ? AND gas_reservoir_id = ? ORDER BY date ASC",
      [wellName, gasReservoirId || 1],
    );

    let cumulativeGas = 0;
    const cumMap = new Map();
    for (const row of prodRows) {
      cumulativeGas += (row.daily_gas_production || 0) * 10000;
      const dateKey = new Date(row.date).toISOString().slice(0, 10);
      cumMap.set(dateKey, cumulativeGas);
    }

    const Zi = calcZFactor(Pi, T, sg, useH2s, useCo2, useN2);
    const piDivZi = Pi / Zi;

    const points = [];
    const tableRows = [];
    let estimatedFallback = false;

    for (const row of staticRows) {
      const pRes = row.reservior_pressure;
      const dateKey = new Date(row.date).toISOString().slice(0, 10);
      const Gp =
        row.cumulative_gas_production > 0
          ? row.cumulative_gas_production
          : cumMap.get(dateKey) || 0;

      if (pRes > 0) {
        const Z = calcZFactor(pRes, T, sg, useH2s, useCo2, useN2);
        const pDivZ = pRes / Z;
        points.push({ x: Gp, y: pDivZ, p: pRes, Z });
        tableRows.push({
          date: dateKey,
          Gp: Gp,
          p: pRes,
          Z: Z.toFixed(6),
          "p/Z": pDivZ.toFixed(2),
        });
      }
    }

    if (prodRows.length > 0 && points.length === 0) {
      const measuredRows = prodRows.filter((row) => {
        const dateKey = new Date(row.date).toISOString().slice(0, 10);
        const Gp = cumMap.get(dateKey) || 0;
        return Gp > 0 && row.measured_bottom_hole_pressure > 0;
      });
      const interval = Math.max(1, Math.floor(measuredRows.length / 15));
      for (let i = 0; i < measuredRows.length; i += interval) {
        const row = measuredRows[i];
        const dateKey = new Date(row.date).toISOString().slice(0, 10);
        const Gp = cumMap.get(dateKey) || 0;
        const measuredPressure = row.measured_bottom_hole_pressure;
        const Z = calcZFactor(measuredPressure, T, sg, useH2s, useCo2, useN2);
        const pDivZ = measuredPressure / Z;
        points.push({ x: Gp, y: pDivZ, p: measuredPressure, Z });
        tableRows.push({
          date: dateKey,
          Gp: Gp,
          p: measuredPressure.toFixed(2),
          Z: Z.toFixed(6),
          "p/Z": pDivZ.toFixed(2),
        });
      }

      if (points.length === 0 && cumulativeGas > 0) {
        estimatedFallback = true;
        const maxDailyGas = Math.max(
          ...prodRows.map((item) => item.daily_gas_production || 0),
          1,
        );
        const estimatedInterval = Math.max(1, Math.floor(prodRows.length / 15));
        for (let i = 0; i < prodRows.length; i += estimatedInterval) {
          const row = prodRows[i];
          const dateKey = new Date(row.date).toISOString().slice(0, 10);
          const Gp = cumMap.get(dateKey) || 0;
          if (Gp <= 0) continue;
          const ratio = Math.min(1, Gp / cumulativeGas);
          const rateFactor = (row.daily_gas_production || 0) / maxDailyGas;
          const decline =
            0.58 * Math.pow(ratio, 0.82) +
            0.08 * ratio * (1 - rateFactor);
          const pEst = Pi * Math.max(0.35, 1 - decline);
          if (pEst <= 0) continue;
          const Z = calcZFactor(pEst, T, sg, useH2s, useCo2, useN2);
          const pDivZ = pEst / Z;
          points.push({ x: Gp, y: pDivZ, p: pEst, Z, estimated: true });
          tableRows.push({
            date: dateKey,
            Gp: Gp,
            p: pEst.toFixed(2),
            Z: Z.toFixed(6),
            "p/Z": pDivZ.toFixed(2),
            source: "估算",
          });
        }
      }
    }

    let chart = null;
    let conclusion = [];
    let wellParams = {};

    if (points.length >= 2) {
      const xs = points.map((pt) => pt.x);
      const ys = points.map((pt) => pt.y);
      const { slope, intercept, r2 } = linearRegression(xs, ys);

      const G = intercept / -slope;

      const xMin = Math.min(...xs);
      const xMax = Math.max(...xs);
      const curveModel =
        quadraticRegression(xs, ys) || { a: 0, b: slope, c: intercept };
      const fitLines = [
        ...makeCurveLines(
          curveModel,
          xMin,
          xMax,
          "#2a2a2a",
          "拟合线",
        ),
      ];
      fitLines.push(
        ...makeCurveLines(
          { a: curveModel.a, b: curveModel.b, c: piDivZi },
          xMin,
          xMax,
          "#ffbb55",
          "理论线",
        ),
      );

      chart = { points, fitLines, estimated: estimatedFallback };

      conclusion = [
        { label: "原始地层压力 pi", value: (Pi / 1e6).toFixed(4) + " MPa" },
        { label: "原始偏差系数 Zi", value: Zi.toFixed(6) },
        { label: "pi/Zi", value: piDivZi.toFixed(2) },
        { label: "动态储量 G", value: (G / 1e8).toFixed(4) + " × 10⁸ m³" },
        { label: "斜率", value: slope.toExponential(4) },
        { label: "截距", value: intercept.toFixed(2) },
        { label: "R²", value: r2.toFixed(4) },
        { label: "数据点数", value: String(points.length) },
      ];
      if (estimatedFallback) {
        conclusion.push({
          label: "数据说明",
          value: "缺少静压/实测压力点，当前曲线由生产数据估算生成",
        });
      }

      wellParams = {
        gasType,
        gasGravity: sg,
        h2sMole: normalizeFractionToPercent(useH2s),
        co2Mole: normalizeFractionToPercent(useCo2),
        n2Mole: normalizeFractionToPercent(useN2),
        initialPressure: Pi,
        bottomTemp: normalizeTemperatureToCelsius(T),
        middleDepth: middleDepth,
        swc: normalizeFractionToPercent(swc),
        waterGasRatioLimit: waterGasRatioLimit || -1,
        enableWaterGasRatio: waterGasRatioLimit > 0,
      };
    } else {
      conclusion = [
        { label: "提示", value: "该井数据不足，无法进行流动物质平衡分析" },
      ];
      wellParams = {
        gasType,
        gasGravity: sg,
        h2sMole: normalizeFractionToPercent(useH2s),
        co2Mole: normalizeFractionToPercent(useCo2),
        n2Mole: normalizeFractionToPercent(useN2),
        initialPressure: Pi,
        bottomTemp: normalizeTemperatureToCelsius(T),
        middleDepth: middleDepth,
        swc: normalizeFractionToPercent(swc),
        waterGasRatioLimit: -1,
        enableWaterGasRatio: false,
      };
    }

    return res.json({
      chart,
      table: tableRows,
      conclusion,
      wellParams,
    });
  } catch (err) {
    return res.status(500).json({ message: "服务器内部错误: " + err.message });
  }
});

app.get("/projectanalysis/flowbalanceanalysis", (req, res) => {
  res.status(405).json({ message: "请使用 POST 请求" });
});

const PORT = 3001;
app.listen(PORT, "127.0.0.1", () => {
  console.log(
    `Flow balance analysis server running on http://127.0.0.1:${PORT}`,
  );
});
