import { execSync } from "child_process";

function mysql(query) {
  const result = execSync(
    `docker exec mariadb mysql -uroot -ppassword -D database -N -B -e "${query.replace(/"/g, '\\"')}"`,
    { encoding: "utf-8", timeout: 30000 },
  );
  return result.trim();
}

function calcPseudoCritical(sg) {
  sg = sg || 0.58;
  if (sg < 0.75) {
    const ppc = 677 + 15 * sg - 37.5 * sg * sg;
    const tpc = 168 + 325 * sg - 12.5 * sg * sg;
    return { ppc: ppc * 0.00689476, tpc: tpc * 0.555556 + 273.15 };
  }
  const ppc = 706 - 51.7 * sg - 11.1 * sg * sg;
  const tpc = 187 + 330 * sg - 71.5 * sg * sg;
  return { ppc: ppc * 0.00689476, tpc: tpc * 0.555556 + 273.15 };
}

function calcZFactor(p, T, sg) {
  const { ppc, tpc } = calcPseudoCritical(sg);
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
  const n = parseFloat(value);
  if (isNaN(n)) return null;
  return Math.abs(n) > 200 ? n : n + 273.15;
}

function normalizeTemperatureToCelsius(value) {
  const n = parseFloat(value);
  if (isNaN(n)) return null;
  return Math.abs(n) > 200 ? n - 273.15 : n;
}

function normalizeFractionToPercent(value) {
  const n = parseFloat(value);
  if (isNaN(n)) return null;
  return Math.abs(n) <= 1 ? n * 100 : n;
}

function firstFinite(...values) {
  for (const value of values) {
    const n = parseFloat(value);
    if (!isNaN(n)) return n;
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

function parseMysqlRows(text) {
  if (!text || !text.trim()) return [];
  const lines = text.trim().split("\n");
  return lines.map((line) => line.split("\t"));
}

function mysqlObj(query) {
  const raw = execSync(
    `docker exec mariadb mysql -uroot -ppassword -D database -B -e "${query.replace(/"/g, '\\"')}"`,
    { encoding: "utf-8", timeout: 30000 },
  ).trim();
  if (!raw) return [];
  const lines = raw.split("\n");
  if (lines.length < 1) return [];
  const headers = lines[0].split("\t");
  const rows = [];
  for (let i = 1; i < lines.length; i++) {
    const vals = lines[i].split("\t");
    const obj = {};
    for (let j = 0; j < headers.length; j++) {
      const h = headers[j].trim();
      obj[h] =
        vals[j] === "NULL" ? null : vals[j] === undefined ? null : vals[j];
    }
    rows.push(obj);
  }
  return rows;
}

export default function flowBalancePlugin() {
  return {
    name: "flow-balance-plugin",
    configureServer(server) {
      server.middlewares.use(
        "/docker-api/projectanalysis/flowbalanceanalysis",
        async (req, res) => {
          if (req.method !== "POST") {
            res.statusCode = 405;
            res.setHeader("Content-Type", "application/json");
            res.end(JSON.stringify({ message: "请使用 POST 请求" }));
            return;
          }

          let body = "";
          req.on("data", (chunk) => {
            body += chunk;
          });
          req.on("end", async () => {
            try {
              const { gasReservoirId, wellNames, waterGasRatioLimit } =
                JSON.parse(body);
              const wellName = wellNames?.[0];
              const gid = gasReservoirId || 1;
              const wrl = waterGasRatioLimit || -1;

              if (!wellName) {
                res.statusCode = 400;
                res.setHeader("Content-Type", "application/json");
                res.end(JSON.stringify({ message: "缺少井名" }));
                return;
              }

              const reservoirList = mysqlObj(
                `SELECT * FROM core_gas_reservoirs WHERE id = ${gid}`,
              );
              const reservoir = reservoirList[0] || {};
              const pick = (keys) => {
                for (const k of keys) {
                  const v = reservoir[k];
                  if (v !== null && v !== undefined && v !== "") {
                    const num = parseFloat(v);
                    if (!isNaN(num)) return num;
                  }
                }
                return null;
              };
              const sg = pick([
                "specific_gravity",
                "specificGravity",
                "gas_gravity",
              ]);
              const h2s = pick([
                "hydrogen_sulfide",
                "hydrogenSulfide",
                "h2s_mole",
                "h2s",
              ]);
              const co2 = pick([
                "carbon_dioxide",
                "carbonDioxide",
                "co2_mole",
                "co2",
              ]);
              const n2 = pick(["nitrogen", "n2_mole", "n2"]);
              const rawT = pick([
                "formation_temperature_of_gas_reservoir",
                "bottom_temp",
                "formation_temperature",
                "reservoir_temperature",
                "formation_temperature_of_gas",
              ]);
              const T = normalizeTemperatureToKelvin(rawT ?? 373);
              let Pi = pick([
                "original_formation_pressure_of_gas_reservoir",
                "original_pressure",
                "initial_pressure",
                "initial_pressure_of_gas_reservoir",
              ]);
              if (!Pi || Pi <= 0) {
                const pressureRows = parseMysqlRows(
                  mysql(
                    `SELECT reservior_pressure FROM core_static_pressure_data WHERE well_name = '${wellName}' AND gas_reservoir_id = ${gid} AND reservior_pressure > 0 ORDER BY date ASC LIMIT 1`,
                  ),
                );
                Pi = pressureRows[0] ? parseFloat(pressureRows[0][0]) : 31608000;
                if (!Pi || Pi <= 0) Pi = 31608000;
              }
              const middleDepth = pick([
                "middle_depth_of_gas_reservoir",
                "middle_depth",
                "depth",
              ]);
              const swc = pick([
                "irreducible_water_saturation",
                "average_irreducible_water_saturation",
                "swc",
                "bound_water_saturation",
                "connate_water_saturation",
              ]);
              const gasTypeRaw =
                reservoir.gas_type || reservoir.gasType || reservoir.type || "";
              const gasType = typeof gasTypeRaw === "string" ? gasTypeRaw : "";

              const gasPropRows = parseMysqlRows(
                mysql(
                  `SELECT specific_gravity, hydrogen_sulfide, carbon_dioxide, nitrogen FROM core_gas_property WHERE well_name = '${wellName}' AND gas_reservoir_id = ${gid}`,
                ),
              );
              const wellSg = gasPropRows[0]
                ? parseFloat(gasPropRows[0][0])
                : null;
              const wellH2s = gasPropRows[0]
                ? parseFloat(gasPropRows[0][1])
                : null;
              const wellCo2 = gasPropRows[0]
                ? parseFloat(gasPropRows[0][2])
                : null;
              const wellN2 = gasPropRows[0]
                ? parseFloat(gasPropRows[0][3])
                : null;
              const useSg = firstFinite(wellSg, sg, 0.58);
              const useH2s = firstFinite(wellH2s, h2s, 0);
              const useCo2 = firstFinite(wellCo2, co2, 0);
              const useN2 = firstFinite(wellN2, n2, 0);

              const prodRows = parseMysqlRows(
                mysql(
                  `SELECT date, daily_gas_production, measured_bottom_hole_pressure FROM core_production_data WHERE well_name = '${wellName}' AND gas_reservoir_id = ${gid} ORDER BY date ASC`,
                ),
              );

              const staticRows = parseMysqlRows(
                mysql(
                  `SELECT date, reservior_pressure, cumulative_gas_production FROM core_static_pressure_data WHERE well_name = '${wellName}' AND gas_reservoir_id = ${gid} ORDER BY date ASC`,
                ),
              );

              let cumulativeGas = 0;
              const cumMap = new Map();
              for (const row of prodRows) {
                cumulativeGas += (parseFloat(row[1]) || 0) * 10000;
                const dateKey = row[0].slice(0, 10);
                cumMap.set(dateKey, cumulativeGas);
              }

              const Zi = calcZFactor(Pi, T, useSg);
              const piDivZi = Pi / Zi;

              const points = [];
              const tableRows = [];
              let estimatedFallback = false;

              const hasBaseData =
                typeof Pi === "number" &&
                !isNaN(Pi) &&
                typeof T === "number" &&
                !isNaN(T);

              if (hasBaseData) {
                for (const row of staticRows) {
                  const pRes = parseFloat(row[1]);
                  const dateKey = row[0].slice(0, 10);
                  const Gp =
                    parseFloat(row[2]) > 0
                      ? parseFloat(row[2])
                      : cumMap.get(dateKey) || 0;

                  if (pRes > 0) {
                    const Z = calcZFactor(pRes, T, useSg);
                    const pDivZ = pRes / Z;
                    points.push({ x: Gp, y: pDivZ, p: pRes, Z });
                    tableRows.push({
                      date: dateKey,
                      Gp,
                      p: pRes,
                      Z: Z.toFixed(6),
                      "p/Z": pDivZ.toFixed(2),
                    });
                  }
                }

                if (prodRows.length > 0 && points.length === 0) {
                  const measuredRows = prodRows.filter((row) => {
                    const dateKey = row[0].slice(0, 10);
                    const Gp = cumMap.get(dateKey) || 0;
                    const measuredPressure = parseFloat(row[2]);
                    return Gp > 0 && measuredPressure > 0;
                  });
                  const interval = Math.max(
                    1,
                    Math.floor(measuredRows.length / 15),
                  );
                  for (let i = 0; i < measuredRows.length; i += interval) {
                    const row = measuredRows[i];
                    const dateKey = row[0].slice(0, 10);
                    const Gp = cumMap.get(dateKey) || 0;
                    const measuredPressure = parseFloat(row[2]);
                    const Z = calcZFactor(measuredPressure, T, useSg);
                    const pDivZ = measuredPressure / Z;
                    points.push({ x: Gp, y: pDivZ, p: measuredPressure, Z });
                    tableRows.push({
                      date: dateKey,
                      Gp,
                      p: measuredPressure.toFixed(2),
                      Z: Z.toFixed(6),
                      "p/Z": pDivZ.toFixed(2),
                    });
                  }

                  if (points.length === 0 && cumulativeGas > 0) {
                    estimatedFallback = true;
                    const maxDailyGas = Math.max(
                      ...prodRows.map((item) => parseFloat(item[1]) || 0),
                      1,
                    );
                    const interval = Math.max(
                      1,
                      Math.floor(prodRows.length / 15),
                    );
                    for (let i = 0; i < prodRows.length; i += interval) {
                      const row = prodRows[i];
                      const dateKey = row[0].slice(0, 10);
                      const Gp = cumMap.get(dateKey) || 0;
                      if (Gp <= 0) continue;
                      const ratio = Math.min(1, Gp / cumulativeGas);
                      const rateFactor =
                        (parseFloat(row[1]) || 0) / maxDailyGas;
                      const decline =
                        0.58 * Math.pow(ratio, 0.82) +
                        0.08 * ratio * (1 - rateFactor);
                      const pEst = Pi * Math.max(0.35, 1 - decline);
                      if (pEst <= 0) continue;
                      const Z = calcZFactor(pEst, T, useSg);
                      const pDivZ = pEst / Z;
                      points.push({
                        x: Gp,
                        y: pDivZ,
                        p: pEst,
                        Z,
                        estimated: true,
                      });
                      tableRows.push({
                        date: dateKey,
                        Gp,
                        p: pEst.toFixed(2),
                        Z: Z.toFixed(6),
                        "p/Z": pDivZ.toFixed(2),
                        source: "估算",
                      });
                    }
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
                const quad = quadraticRegression(xs, ys);
                const fitModel = quad || { a: 0, b: slope, c: intercept };
                const theoryModel = {
                  a: fitModel.a,
                  b: fitModel.b,
                  c: piDivZi,
                };
                const fitLines = [
                  ...makeCurveLines(fitModel, xMin, xMax, "#2a2a2a", "拟合线"),
                  ...makeCurveLines(
                    theoryModel,
                    xMin,
                    xMax,
                    "#ffbb55",
                    "理论线",
                  ),
                ];

                chart = { points, fitLines, estimated: estimatedFallback };

                conclusion = [
                  {
                    label: "原始地层压力 pi",
                    value: (Pi / 1e6).toFixed(4) + " MPa",
                  },
                  { label: "原始偏差系数 Zi", value: Zi.toFixed(6) },
                  { label: "pi/Zi", value: piDivZi.toFixed(2) },
                  {
                    label: "动态储量 G",
                    value: (G / 1e8).toFixed(4) + " x10^8 m3",
                  },
                  { label: "斜率", value: slope.toExponential(4) },
                  { label: "截距", value: intercept.toFixed(2) },
                  { label: "R2", value: r2.toFixed(4) },
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
                  gasGravity: useSg,
                  h2sMole: normalizeFractionToPercent(useH2s),
                  co2Mole: normalizeFractionToPercent(useCo2),
                  n2Mole: normalizeFractionToPercent(useN2),
                  initialPressure: Pi / 1e6,
                  bottomTemp: normalizeTemperatureToCelsius(T),
                  middleDepth,
                  swc: swc != null ? normalizeFractionToPercent(swc) : 25,
                  waterGasRatioLimit: wrl,
                  enableWaterGasRatio: wrl > 0,
                };
              } else {
                conclusion = [
                  {
                    label: "提示",
                    value: "该井数据不足，无法进行流动物质平衡分析",
                  },
                ];
                wellParams = {
                  gasType,
                  gasGravity: useSg,
                  h2sMole: normalizeFractionToPercent(useH2s),
                  co2Mole: normalizeFractionToPercent(useCo2),
                  n2Mole: normalizeFractionToPercent(useN2),
                  initialPressure: Pi / 1e6,
                  bottomTemp: normalizeTemperatureToCelsius(T),
                  middleDepth,
                  swc: swc != null ? normalizeFractionToPercent(swc) : 25,
                  waterGasRatioLimit: -1,
                  enableWaterGasRatio: false,
                };
              }

              res.statusCode = 200;
              res.setHeader("Content-Type", "application/json");
              const respBody = JSON.stringify({
                chart,
                table: tableRows,
                conclusion,
                wellParams,
              });
              res.end(respBody);
            } catch (err) {
              res.statusCode = 500;
              res.setHeader("Content-Type", "application/json");
              res.end(
                JSON.stringify({ message: "服务器内部错误: " + err.message }),
              );
            }
          });
        },
      );
    },
  };
}
