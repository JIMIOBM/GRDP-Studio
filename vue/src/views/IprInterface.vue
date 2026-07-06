<script setup>
import { ref, computed, reactive, nextTick } from "vue";
import { ElMessage, ElNotification } from "element-plus";
import RibbonMenu from "@/components/RibbonMenu.vue";
import TreeNode from "@/views/TreeNode.vue";
import { flowBalanceApi, gasReservoirsApi } from "@/api/docker";

const NODETYPE = {
  GasReservoir: 1,
  Well: 2,
  ProductivityEvaluation: 3,
  ProductionDeclineAnalysis: 20,
  ProductionDeclineWell: 21,
  ProductionDeclineWells: 22,
  ProductionDeclineDaily: 23,
  ProductionDeclineCumulative: 24,
  ProductionInstabilityAnalysis: 25,
  DynamicOriginalGasInplace: 36,
  FlowBalanceTopPressure: 57,
  FlowBalanceBottomPressure: 58,
  DynamicPrediction: 60,
};

const currentMode = ref("flowBalance");

const reservoirs = ref([]);
const currentReservoir = ref(null);
const treeData = ref([]);
const DEFAULT_WELLS = ["X-1", "X-2", "X-3", "X-4", "X-5"];
const DEFAULT_ACTIVE_NODE_ID = "well-4";

function normalizeWellName(well, idx) {
  if (typeof well === "string" || typeof well === "number") return String(well);
  if (!well || typeof well !== "object") return `X-${idx + 1}`;
  return (
    pickStringField(well, [
      "wellName",
      "well_name",
      "name",
      "wellNo",
      "well_no",
      "label",
    ]) || `X-${idx + 1}`
  );
}

function buildTree(reservoir) {
  const wells = Array.isArray(reservoir?.wells)
    ? reservoir.wells
    : Array.isArray(reservoir?.wellList)
      ? reservoir.wellList
      : Array.isArray(reservoir?.gasWells)
        ? reservoir.gasWells
        : [];
  const wellList = (wells.length > 0 ? wells : DEFAULT_WELLS)
    .map(normalizeWellName)
    .filter(Boolean);

  const wellChildren = wellList.map((w, idx) => ({
    id: `well-${idx}`,
    label: w,
    nodeType: NODETYPE.Well,
    isFolder: true,
    wellName: w,
    children: [
      {
        id: `data-${idx}`,
        label: "数据管理",
        nodeType: NODETYPE.Well,
        isFolder: true,
        wellName: w,
        children: [],
      },
      {
        id: `wellcontrol-${idx}`,
        label: "井控库存",
        nodeType: NODETYPE.Well,
        isFolder: true,
        wellName: w,
        children: [
          {
            id: `fb-${idx}`,
            label: "流动平衡",
            nodeType: NODETYPE.FlowBalanceBottomPressure,
            analysisType: "flowBalance",
            isFolder: false,
            wellName: w,
            isAnalysis: true,
          },
        ],
      },
      {
        id: `productivity-${idx}`,
        label: "单井产能",
        nodeType: NODETYPE.ProductivityEvaluation,
        isFolder: true,
        wellName: w,
        children: [],
      },
      {
        id: `wellbore-${idx}`,
        label: "井筒能力",
        nodeType: NODETYPE.FlowBalanceTopPressure,
        isFolder: true,
        wellName: w,
        children: [],
      },
      {
        id: `bundle-${idx}`,
        label: "管束能力",
        nodeType: NODETYPE.DynamicPrediction,
        isFolder: true,
        wellName: w,
        children: [],
      },
      {
        id: `allocation-${idx}`,
        label: "配产配注",
        nodeType: NODETYPE.DynamicPrediction,
        isFolder: true,
        wellName: w,
        children: [],
      },
    ],
  }));

  return [
    {
      id: "g-well",
      label: "井",
      nodeType: NODETYPE.Well,
      isFolder: true,
      children: wellChildren,
    },
    {
      id: "g-layer",
      label: "层",
      nodeType: NODETYPE.GasReservoir,
      isFolder: true,
      children: [
        {
          id: "layer-project-1",
          label: "项目 1",
          nodeType: NODETYPE.GasReservoir,
          isFolder: false,
        },
      ],
    },
    {
      id: "g-layer-group",
      label: "层组",
      nodeType: NODETYPE.GasReservoir,
      isFolder: true,
      children: [
        {
          id: "layer-group-project-1",
          label: "项目 1",
          nodeType: NODETYPE.GasReservoir,
          isFolder: false,
        },
      ],
    },
  ];
}

function findNode(nodes, id) {
  for (const node of nodes) {
    if (node.id === id) return node;
    const child = findNode(node.children || [], id);
    if (child) return child;
  }
  return null;
}

function syncInitialTreeState(nodes) {
  const wellRoot = nodes.find((node) => node.id === "g-well");
  const fallbackWell = wellRoot?.children?.[0];
  const active =
    findNode(nodes, DEFAULT_ACTIVE_NODE_ID) || fallbackWell || nodes[0] || null;
  activeNodeId.value = active?.id || "";
  activeNode.value = active;
  autoExpandIds.value = nodes.map((node) => node.id);
}

function filterTreeNodes(nodes, keyword) {
  const text = keyword.trim().toLowerCase();
  if (!text) return nodes;

  return nodes
    .map((node) => {
      const children = filterTreeNodes(node.children || [], text);
      const matched = String(node.label || "").toLowerCase().includes(text);
      if (!matched && children.length === 0) return null;
      return { ...node, children };
    })
    .filter(Boolean);
}

const activeNodeId = ref(DEFAULT_ACTIVE_NODE_ID);
const activeNode = ref(null);
const activeSide = ref("input");
const treeKeyword = ref("");
const analysisWellNames = ref([]);
const analysisCache = new Map();
const analyzingWells = new Set();
const autoExpandIds = ref([]);
const filteredTreeData = computed(() =>
  filterTreeNodes(treeData.value, treeKeyword.value),
);

treeData.value = buildTree();
syncInitialTreeState(treeData.value);

const handleSelect = (node) => {
  activeNodeId.value = node.id;
  activeNode.value = node;

  if (node.isAnalysis) {
    analysisWellNames.value = [node.wellName];
    chartVisible.value = true;
    analysisCache.delete(node.wellName);
    analyzingWells.delete(node.wellName);
    nextTick(() => runAnalysis());
  } else {
    chartVisible.value = false;
    analysisResult.value = null;
  }
};

function findNodeWithPath(nodes, predicate, path = []) {
  for (const node of nodes) {
    const currentPath = [...path, node.id];
    if (predicate(node)) return { node, path: currentPath };
    const child = findNodeWithPath(node.children || [], predicate, currentPath);
    if (child) return child;
  }
  return null;
}

function startFlowBalanceForSelectedWell() {
  const wellName = activeNode.value?.wellName;
  if (!wellName) {
    ElMessage.warning("请先在左侧选择一口井");
    return;
  }

  const target = findNodeWithPath(
    treeData.value,
    (node) =>
      node.isAnalysis &&
      node.analysisType === "flowBalance" &&
      node.wellName === wellName,
  );

  if (!target) {
    ElMessage.warning(`未找到 ${wellName} 的流动平衡节点`);
    return;
  }

  treeKeyword.value = "";
  activeNodeId.value = target.node.id;
  activeNode.value = target.node;
  analysisWellNames.value = [wellName];
  chartVisible.value = true;
  analysisCache.delete(wellName);
  analyzingWells.delete(wellName);
  autoExpandIds.value = [];
  nextTick(() => {
    autoExpandIds.value = target.path.slice(0, -1);
    runAnalysis();
  });
  ElMessage.success(`开始分析：${wellName} 流动平衡`);
}

const handleCommand = ({ group, name }) => {
  switch (name) {
    case "流动平衡":
      startFlowBalanceForSelectedWell();
      break;
    case "动态平衡":
    case "物质平衡":
      ElMessage.success(`[${group}] ${name}`);
      break;
    default:
      ElMessage.success(`[${group}] ${name}`);
  }
};

const params = reactive({
  gasType: "干气",
  gasGravity: 0.56,
  h2sMole: 0.5,
  co2Mole: 1.2,
  n2Mole: 1.8,
  correctionMethod: "Wichert-Aziz修正方法",
  deviationMethod: "Dranchuk-Abu-Kassem方法",
  initialPressure: 36.5,
  bottomTemp: 110,
  middleDepth: 3600,
  swc: 25,
  waterGasRatioLimit: 0.14,
  enableWaterGasRatio: true,
});

function pickField(obj, keys) {
  if (!obj) return null;
  for (const k of keys) {
    const v = obj[k];
    if (v == null || v === "") continue;
    const n = Number(v);
    if (!isNaN(n) && isFinite(n)) return n;
  }
  return null;
}

function pickStringField(obj, keys) {
  if (!obj) return "";
  for (const k of keys) {
    const v = obj[k];
    if (typeof v === "string" && v.trim()) return v.trim();
    if (typeof v === "number" && !isNaN(v)) return String(v);
  }
  return "";
}

function normalizePressureToMpa(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) return null;
  const abs = Math.abs(n);
  if (abs >= 100000) return n / 1000000;
  if (abs >= 1000) return n / 1000;
  return n;
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

function getGasVolumeDivisor(values) {
  const nums = values
    .map((value) => Math.abs(Number(value)))
    .filter((value) => Number.isFinite(value) && value > 0);
  if (!nums.length) return 1;
  const max = Math.max(...nums);
  if (max >= 10000000) return 100000000;
  if (max >= 10) return 10000;
  return 1;
}

function normalizeGasVolumeToYiM3(value, divisor) {
  const n = Number(value);
  if (!Number.isFinite(n)) return null;
  return n / (divisor || getGasVolumeDivisor([n]));
}

function linearRegressionValues(xs, ys) {
  const n = xs.length;
  if (n < 2) return null;
  let sx = 0;
  let sy = 0;
  let sxy = 0;
  let sx2 = 0;
  let sy2 = 0;
  for (let i = 0; i < n; i += 1) {
    sx += xs[i];
    sy += ys[i];
    sxy += xs[i] * ys[i];
    sx2 += xs[i] * xs[i];
    sy2 += ys[i] * ys[i];
  }
  const den = n * sx2 - sx * sx;
  if (Math.abs(den) < 1e-12) return null;
  const slope = (n * sxy - sx * sy) / den;
  const intercept = (sy - slope * sx) / n;
  const r2Num = n * sxy - sx * sy;
  const r2Den = (n * sx2 - sx * sx) * (n * sy2 - sy * sy);
  const r2 = r2Den > 0 ? (r2Num * r2Num) / r2Den : 0;
  return { slope, intercept, r2 };
}

function buildDisplayFitLines(points, sourceLines) {
  const validPoints = points.filter(
    (point) => Number.isFinite(point.x) && Number.isFinite(point.y),
  );
  const uniqueXs = new Set(validPoints.map((point) => point.x.toFixed(8)));
  if (validPoints.length < 2 || uniqueXs.size < 2) return [];

  const xs = validPoints.map((point) => point.x);
  const ys = validPoints.map((point) => point.y);
  const fit = linearRegressionValues(xs, ys);
  if (!fit || fit.slope >= 0 || fit.r2 < 0.6) return [];

  const xMin = Math.min(...xs);
  const xMax = Math.max(...xs);
  const lines = [
    {
      x1: xMin,
      y1: fit.slope * xMin + fit.intercept,
      x2: xMax,
      y2: fit.slope * xMax + fit.intercept,
      color: "#2a2a2a",
      label: "拟合线",
    },
  ];

  const shiftLine =
    sourceLines.find((line) => line.color === "#ffbb55") || sourceLines[1];
  const tx1 = Number(shiftLine?.x1 ?? shiftLine?.start?.x);
  const ty1 = Number(shiftLine?.y1 ?? shiftLine?.start?.y);
  const tx2 = Number(shiftLine?.x2 ?? shiftLine?.end?.x);
  const ty2 = Number(shiftLine?.y2 ?? shiftLine?.end?.y);
  const theorySlope =
    Number.isFinite(tx1) &&
    Number.isFinite(tx2) &&
    Math.abs(tx2 - tx1) > 1e-12 &&
    Number.isFinite(ty1) &&
    Number.isFinite(ty2)
      ? (ty2 - ty1) / (tx2 - tx1)
      : 0;
  const theoryIntercept =
    Number.isFinite(ty1) && Number.isFinite(tx1)
      ? ty1 - theorySlope * tx1
      : null;
  if (Number.isFinite(theoryIntercept)) {
    lines.push({
      x1: xMin,
      y1: theoryIntercept + fit.slope * xMin,
      x2: xMax,
      y2: theoryIntercept + fit.slope * xMax,
      color: "#ffbb55",
      label: "理论线",
    });
  }

  return lines;
}

const fieldNormalizers = {
  h2sMole: normalizeFractionToPercent,
  co2Mole: normalizeFractionToPercent,
  n2Mole: normalizeFractionToPercent,
  initialPressure: normalizePressureToMpa,
  bottomTemp: normalizeTemperatureToCelsius,
  swc: normalizeFractionToPercent,
};

function normalizeChartUnits(chart) {
  if (!chart) return chart;
  const rawLines = chart.fitLines || [];
  const xValues = [
    ...(chart.points || []).map((point) => point.x),
    ...rawLines.flatMap((line) => [
      line.x1,
      line.x2,
      line.start?.x,
      line.end?.x,
    ]),
  ];
  const gasDivisor = getGasVolumeDivisor(xValues);
  const points = (chart.points || []).map((point) => ({
    ...point,
    x: normalizeGasVolumeToYiM3(point.x, gasDivisor) ?? point.x,
    y: normalizePressureToMpa(point.y) ?? point.y,
    p: normalizePressureToMpa(point.p) ?? point.p,
  }));
  const sourceLines = rawLines.map((line) => ({
    ...line,
    x1: normalizeGasVolumeToYiM3(line.x1, gasDivisor) ?? line.x1,
    y1: normalizePressureToMpa(line.y1) ?? line.y1,
    x2: normalizeGasVolumeToYiM3(line.x2, gasDivisor) ?? line.x2,
    y2: normalizePressureToMpa(line.y2) ?? line.y2,
    start: line.start
      ? {
          ...line.start,
          x: normalizeGasVolumeToYiM3(line.start.x, gasDivisor) ?? line.start.x,
          y: normalizePressureToMpa(line.start.y) ?? line.start.y,
        }
      : line.start,
    end: line.end
      ? {
          ...line.end,
          x: normalizeGasVolumeToYiM3(line.end.x, gasDivisor) ?? line.end.x,
          y: normalizePressureToMpa(line.end.y) ?? line.end.y,
        }
      : line.end,
  }));
  return {
    ...chart,
    points,
    fitLines: sourceLines.length
      ? sourceLines
      : buildDisplayFitLines(points, sourceLines),
  };
}

function applyBackendData(backendObj) {
  if (!backendObj) return;

  const gtv = pickStringField(backendObj, [
    "gasType",
    "gas_type",
    "gasReservoirType",
    "gas_reservoir_type",
    "type",
    "gasTypeName",
  ]);
  if (gtv) {
    const typeMap = {
      干气: "干气",
      gas: "干气",
      1: "干气",
      湿气: "湿气",
      wet: "湿气",
      2: "湿气",
      凝析气: "凝析气",
      condensate: "凝析气",
      3: "凝析气",
    };
    params.gasType =
      typeMap[gtv] ||
      (["干气", "湿气", "凝析气"].includes(gtv) ? gtv : params.gasType);
  }

  const map = {
    gasGravity: [
      "specificGravity",
      "specific_gravity",
      "gasGravity",
      "gas_gravity",
      "relativeDensity",
      "relative_density",
    ],
    h2sMole: [
      "hydrogenSulfide",
      "hydrogen_sulfide",
      "h2sMole",
      "h2s_mole",
      "h2s",
      "h2SMoleFraction",
      "h2s_mole_fraction",
    ],
    co2Mole: [
      "carbonDioxide",
      "carbon_dioxide",
      "co2Mole",
      "co2_mole",
      "co2",
      "co2MoleFraction",
      "co2_mole_fraction",
    ],
    n2Mole: [
      "nitrogen",
      "n2Mole",
      "n2_mole",
      "n2",
      "n2MoleFraction",
      "n2_mole_fraction",
    ],
    initialPressure: [
      "initialPressure",
      "initial_pressure",
      "originalFormationPressure",
      "original_formation_pressure",
      "originalFormationPressureOfGasReservoir",
      "original_formation_pressure_of_gas_reservoir",
      "formationPressure",
      "formation_pressure",
      "reservoirPressure",
      "reservoir_pressure",
      "reserviorPressure",
      "reservior_pressure",
    ],
    bottomTemp: [
      "bottomTemp",
      "bottom_temp",
      "formationTemperature",
      "formation_temperature",
      "formationTemperatureOfGasReservoir",
      "formation_temperature_of_gas_reservoir",
      "reservoirTemperature",
      "reservoir_temperature",
    ],
    middleDepth: [
      "middleDepth",
      "middle_depth",
      "middleDepthOfGasReservoir",
      "middle_depth_of_gas_reservoir",
      "depth",
    ],
    swc: [
      "irreducibleWaterSaturation",
      "irreducible_water_saturation",
      "averageIrreducibleWaterSaturation",
      "average_irreducible_water_saturation",
      "boundWaterSaturation",
      "bound_water_saturation",
      "connateWaterSaturation",
      "connate_water_saturation",
      "swc",
    ],
    waterGasRatioLimit: [
      "waterGasRatioLimit",
      "water_gas_ratio_limit",
      "waterGasRatio",
    ],
  };

  for (const [field, keys] of Object.entries(map)) {
    const v = pickField(backendObj, keys);
    if (v !== null) params[field] = fieldNormalizers[field]?.(v) ?? v;
  }

  if (typeof backendObj.enableWaterGasRatio === "boolean")
    params.enableWaterGasRatio = backendObj.enableWaterGasRatio;
}

async function loadReservoirs() {
  analysisCache.clear();
  analyzingWells.clear();
  try {
    const listRes = await gasReservoirsApi.list();
    if (listRes?.ok && listRes.data?.gasReservoirs?.length > 0) {
      reservoirs.value = listRes.data.gasReservoirs;
      currentReservoir.value = reservoirs.value[0];
      const r = currentReservoir.value;
      applyBackendData(r);
      const backendTree = buildTree(r);
      treeData.value = backendTree;
      syncInitialTreeState(backendTree);
      ElMessage.success(
        `已加载：${r.gasReservoirName || r.name || "气藏数据"}`,
      );
      return true;
    }
  } catch (e) {
    void e;
  }
  const fallbackTree = buildTree();
  currentReservoir.value = null;
  treeData.value = fallbackTree;
  syncInitialTreeState(fallbackTree);
  return false;
}

const isAnalyzing = ref(false);
const analysisResult = ref(null);
const chartVisible = ref(false);

async function analyzeWell(wellName) {
  if (analyzingWells.has(wellName)) {
    return new Promise((resolve) => {
      const check = setInterval(() => {
        if (analysisCache.has(wellName)) {
          clearInterval(check);
          resolve(analysisCache.get(wellName));
        }
      }, 200);
    });
  }

  analyzingWells.add(wellName);

  try {
    const api = flowBalanceApi;
    const gasReservoirId =
      currentReservoir.value?.gasReservoirId || currentReservoir.value?.id || 1;

    const payload = {
      gasReservoirId: gasReservoirId,
      projectId: 1,
      analysisType: 0,
      wellNames: [wellName],
      waterGasRatioLimit: params.enableWaterGasRatio
        ? params.waterGasRatioLimit
        : -1,
    };

    let backendResult = null;
    let apiRaw = null;
    try {
      apiRaw = await api.analyze(payload);
      if (apiRaw?.ok && apiRaw?.data) {
        backendResult = apiRaw.data;
      }
    } catch (e) {
      void e;
    }

    const fromBackend = !!backendResult;

    let chart;
    let table;
    let conclusion;

    if (fromBackend && (backendResult.chart || backendResult.points)) {
      chart = normalizeChartUnits(backendResult.chart || {
        points: backendResult.points || [],
        fitLines: [],
      });

      const rawTable = backendResult.table || [];
      const rawConclusion = backendResult.conclusion || [];

      const slopeObj = rawConclusion.find(
        (c) => c.label && c.label.includes("斜率"),
      );
      const interceptObj = rawConclusion.find(
        (c) => c.label && c.label.includes("截距"),
      );
      const slope = parseFloat(slopeObj?.value || "0");
      const intercept = parseFloat(interceptObj?.value || "0");

      table = rawTable.map((row, idx) => {
        const rawGp = Number(row.Gp) || 0;
        const rawP = Number(row.p) || 0;
        const Gp = normalizeGasVolumeToYiM3(rawGp) ?? rawGp;
        const p = normalizePressureToMpa(rawP) ?? rawP;
        const pDivZFit = intercept + slope * rawGp;
        const pFit = pDivZFit * (Number(row.Z) || 1);
        const pFitMpa = normalizePressureToMpa(pFit) ?? pFit;
        const diff = p - pFitMpa;
        return {
          seq: idx + 1,
          g: Gp.toFixed(4),
          gp: p.toFixed(4),
          gpfit: pFitMpa.toFixed(4),
          diff: diff.toFixed(4),
        };
      });

      conclusion = rawConclusion.map((c) =>
        c.label && c.value ? `${c.label}: ${c.value}` : String(c),
      );

      if (backendResult.wellParams) {
        applyBackendData(backendResult.wellParams);
      }
    } else {
      chart = null;
      table = [];
      conclusion = [];
    }

    const result = {
      chart,
      table,
      conclusion,
      wellName: wellName,
      isSingleWell: true,
      wellsAnalyzed: [wellName],
      gasReservoirName:
        currentReservoir.value?.gasReservoirName ||
        currentReservoir.value?.name,
      fromBackend: fromBackend,
      offline: !fromBackend,
      offlineMessage: apiRaw?.message || null,
    };

    analysisCache.set(wellName, result);
    return result;
  } catch (e) {
    void e;
    const errorResult = {
      chart: null,
      table: [],
      conclusion: [],
      wellName: wellName,
      isSingleWell: true,
      wellsAnalyzed: [wellName],
      fromBackend: false,
      offline: true,
      offlineMessage: "分析出错: " + (e.message || "未知错误"),
    };
    analysisCache.set(wellName, errorResult);
    return errorResult;
  } finally {
    analyzingWells.delete(wellName);
  }
}

async function runAnalysis() {
  const wellName = activeNode.value?.wellName;
  if (!wellName) {
    isAnalyzing.value = false;
    return;
  }
  isAnalyzing.value = true;
  try {
    const result = await analyzeWell(wellName);
    analysisResult.value = result;
  } catch (e) {
    void e;
    analysisResult.value = {
      chart: null,
      table: [],
      conclusion: [],
      wellName: wellName,
      isSingleWell: true,
      wellsAnalyzed: [wellName],
      fromBackend: false,
      offline: true,
      offlineMessage: "分析出错: " + (e.message || "未知错误"),
    };
  } finally {
    isAnalyzing.value = false;
  }
}

const chartTitle = computed(() => {
  const wellTag = analysisResult.value?.wellName
    ? ` · ${analysisResult.value.wellName}`
    : "";
  return `流动物质平衡分析${wellTag}`;
});

const svgWidth = 900;
const svgHeight = 520;
const padding = { left: 24, right: 4, top: 24, bottom: 28 };
const plotW = svgWidth - padding.left - padding.right;
const plotH = svgHeight - padding.top - padding.bottom;

const axisRange = computed(() => {
  const pts = analysisResult.value?.chart?.points;
  if (!pts || pts.length === 0) {
    return { xMin: 0, xMax: 12, yMin: 22, yMax: 44.5 };
  }
  let xMin = Infinity,
    xMax = -Infinity,
    yMin = Infinity,
    yMax = -Infinity;
  for (const p of pts) {
    if (p.x < xMin) xMin = p.x;
    if (p.x > xMax) xMax = p.x;
    if (p.y < yMin) yMin = p.y;
    if (p.y > yMax) yMax = p.y;
  }
  const xPad = (xMax - xMin) * 0.05 || 1;
  const yPad = (yMax - yMin) * 0.08 || 1;
  return {
    xMin: xMin - xPad,
    xMax: xMax + xPad,
    yMin: yMin - yPad,
    yMax: yMax + yPad,
  };
});

function xToPx(x) {
  return (
    padding.left +
    ((x - axisRange.value.xMin) /
      (axisRange.value.xMax - axisRange.value.xMin)) *
      plotW
  );
}
function yToPx(y) {
  return (
    padding.top +
    (1 -
      (y - axisRange.value.yMin) /
        (axisRange.value.yMax - axisRange.value.yMin)) *
      plotH
  );
}
function pxToX(px) {
  return (
    axisRange.value.xMin +
    ((px - padding.left) / plotW) *
      (axisRange.value.xMax - axisRange.value.xMin)
  );
}
function pxToY(py) {
  return (
    axisRange.value.yMin +
    (1 - (py - padding.top) / plotH) *
      (axisRange.value.yMax - axisRange.value.yMin)
  );
}

const hoverVisible = ref(false);
const hoverPxX = ref(0);
const hoverPxY = ref(0);
const hoverDataX = ref(0);
const hoverDataY = ref(0);

function handleChartMouseMove(ev) {
  const svg = ev.currentTarget;
  const rect = svg.getBoundingClientRect();

  const vbRatio = svgWidth / svgHeight;
  const domRatio = rect.width / rect.height;

  let scale;
  let offsetX = 0;
  let offsetY = 0;

  if (domRatio > vbRatio) {
    scale = rect.height / svgHeight;
    offsetX = (rect.width - svgWidth * scale) / 2;
  } else {
    scale = rect.width / svgWidth;
    offsetY = (rect.height - svgHeight * scale) / 2;
  }

  const localX = (ev.clientX - rect.left - offsetX) / scale;
  const localY = (ev.clientY - rect.top - offsetY) / scale;

  const inPlot =
    localX >= padding.left &&
    localX <= padding.left + plotW &&
    localY >= padding.top &&
    localY <= padding.top + plotH;
  if (!inPlot) {
    hoverVisible.value = false;
    return;
  }

  hoverPxX.value = localX;
  hoverPxY.value = localY;
  hoverDataX.value = pxToX(localX);
  hoverDataY.value = pxToY(localY);
  hoverVisible.value = true;
}
function handleChartMouseLeave() {
  hoverVisible.value = false;
}

function niceRange(lo, hi, tickCount) {
  if (lo === hi) {
    lo -= 1;
    hi += 1;
  }
  const range = hi - lo;
  const roughStep = range / (tickCount - 1);
  const exponent = Math.floor(Math.log10(roughStep));
  const fraction = roughStep / Math.pow(10, exponent);
  let niceStep;
  if (fraction <= 1.5) niceStep = 1;
  else if (fraction <= 3) niceStep = 2;
  else if (fraction <= 7) niceStep = 5;
  else niceStep = 10;
  niceStep *= Math.pow(10, exponent);
  const niceLo = Math.floor(lo / niceStep) * niceStep;
  const niceHi = Math.ceil(hi / niceStep) * niceStep;
  const ticks = [];
  for (let v = niceLo; v <= niceHi + 1e-9; v += niceStep) ticks.push(v);
  return ticks;
}

function niceValues(lo, hi) {
  const tickCount = 7;
  return niceRange(lo, hi, tickCount);
}

function formatAxisValue(value, digits = 4) {
  const n = Number(value);
  if (!Number.isFinite(n)) return "";
  return n
    .toFixed(digits)
    .replace(/\.?0+$/, "");
}

const xTicks = computed(() =>
  niceValues(axisRange.value.xMin, axisRange.value.xMax),
);
const yTicks = computed(() =>
  niceValues(axisRange.value.yMin, axisRange.value.yMax),
);

const scatterDots = computed(() => {
  if (!analysisResult.value || !analysisResult.value.chart) return [];
  const { xMin, xMax, yMin, yMax } = axisRange.value;
  return analysisResult.value.chart.points.map((p) => ({
    cx: xToPx(Math.max(xMin, Math.min(xMax, p.x))),
    cy: yToPx(Math.max(yMin, Math.min(yMax, p.y))),
  }));
});

const fitLinePaths = computed(() => {
  if (!analysisResult.value || !analysisResult.value.chart) return [];
  const { yMin, yMax } = axisRange.value;
  return (analysisResult.value.chart.fitLines || []).map((ln) => {
    const sx = ln.start?.x ?? ln.x1 ?? 0;
    const sy = ln.start?.y ?? ln.y1 ?? 0;
    const ex = ln.end?.x ?? ln.x2 ?? 0;
    const ey = ln.end?.y ?? ln.y2 ?? 0;
    const x1 = xToPx(sx);
    const y1 = yToPx(sy > yMax ? yMax : sy < yMin ? yMin : sy);
    const x2 = xToPx(ex);
    const y2 = yToPx(ey > yMax ? yMax : ey < yMin ? yMin : ey);
    return {
      x1,
      y1,
      x2,
      y2,
      color: ln.color || "#e74c3c",
      label: ln.label || "",
    };
  });
});

const hasFitLines = computed(() => fitLinePaths.value.length > 0);
const hasSecondFitLine = computed(() =>
  fitLinePaths.value.some((line) => line.color === "#ffbb55"),
);
loadReservoirs();
</script>

<template>
  <div class="ipr-container">
    <RibbonMenu @command="handleCommand" />

    <div class="ipr-main">
      <aside class="side-panel">
        <div class="side-search">
          <el-input
            v-model="treeKeyword"
            placeholder="搜索节点"
            clearable
            size="small"
          />
        </div>
        <div class="side-tree">
          <TreeNode
            v-for="node in filteredTreeData"
            :key="node.id"
            :node="node"
            :active-id="activeNodeId"
            :auto-expand-ids="autoExpandIds"
            @select="handleSelect"
          />
        </div>

        <div class="side-tabs">
          <div
            class="side-tab"
            :class="{ active: activeSide === 'input' }"
            @click="activeSide = 'input'"
          >
            输入
          </div>
          <div
            class="side-tab"
            :class="{ active: activeSide === 'output' }"
            @click="activeSide = 'output'"
          >
            输出
          </div>
        </div>
      </aside>

      <div class="right-cols" :class="{ 'has-workspace': chartVisible }">
        <template v-if="chartVisible">
          <div class="panel-header-row" v-if="activeSide === 'input'">
            <span class="panel-header-left">
              <span class="panel-header-title">参数设置</span>
              <span class="panel-header-icon">
                <svg
                  width="16"
                  height="16"
                  viewBox="0 0 22 22"
                  fill="none"
                  xmlns="http://www.w3.org/2000/svg"
                >
                  <path
                    d="M5 3 H17 V7 H5 V3 Z"
                    stroke="#8a9099"
                    stroke-width="1.2"
                    fill="#e8ebef"
                  />
                  <path
                    d="M7 7 V19"
                    stroke="#8a9099"
                    stroke-width="1.2"
                    stroke-linecap="round"
                  />
                  <path
                    d="M15 7 V19"
                    stroke="#8a9099"
                    stroke-width="1.2"
                    stroke-linecap="round"
                  />
                  <path
                    d="M7 19 L11 16 L15 19"
                    stroke="#8a9099"
                    stroke-width="1.2"
                    fill="none"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                  />
                </svg>
              </span>
            </span>
            <span class="panel-header-right"></span>
          </div>
          <div class="panel-header-row" v-else>
            <span class="panel-header-left">
              <span class="panel-header-title">分析输出</span>
            </span>
            <span class="panel-header-right"></span>
          </div>

          <div class="right-cols-inner">
            <section class="param-panel">
              <div v-if="activeSide === 'input'" class="panel-inner">
                <div class="param-group">
                  <div class="group-title">气体性质</div>
                  <div class="param-row">
                    <label>天然气类型</label>
                    <el-select v-model="params.gasType" size="default">
                      <el-option label="干气" value="干气" />
                      <el-option label="湿气" value="湿气" />
                      <el-option label="凝析气" value="凝析气" />
                    </el-select>
                  </div>
                  <div class="param-row">
                    <label>天然气比重</label>
                    <el-input-number
                      v-model="params.gasGravity"
                      :controls="false"
                      :step="0.0001"
                      :precision="4"
                      style="width: 100%"
                    />
                  </div>
                  <div class="param-row">
                    <label>H₂S 摩尔百分含量(%)</label>
                    <el-input-number
                      v-model="params.h2sMole"
                      :controls="false"
                      :precision="2"
                      style="width: 100%"
                    />
                  </div>
                  <div class="param-row">
                    <label>CO₂ 摩尔百分含量(%)</label>
                    <el-input-number
                      v-model="params.co2Mole"
                      :controls="false"
                      :precision="2"
                      style="width: 100%"
                    />
                  </div>
                  <div class="param-row">
                    <label>N₂ 摩尔百分含量(%)</label>
                    <el-input-number
                      v-model="params.n2Mole"
                      :controls="false"
                      :precision="2"
                      style="width: 100%"
                    />
                  </div>
                </div>

                <!-- 计算方法 -->
                <div class="param-group">
                  <div class="group-title">计算方法</div>
                  <div class="param-row">
                    <label>非烃气体修正方法</label>
                    <el-select v-model="params.correctionMethod" size="default">
                      <el-option
                        label="Wichert-Aziz修正方法"
                        value="Wichert-Aziz修正方法"
                      />
                      <el-option
                        label="Standing修正方法"
                        value="Standing修正方法"
                      />
                      <el-option label="Carr修正方法" value="Carr修正方法" />
                    </el-select>
                  </div>
                  <div class="param-row">
                    <label>天然气偏差系数计算方法</label>
                    <el-select v-model="params.deviationMethod" size="default">
                      <el-option
                        label="Dranchuk-Abu-Kassem方法"
                        value="Dranchuk-Abu-Kassem方法"
                      />
                      <el-option
                        label="Dranchuk-Purvis-Robinson方法"
                        value="Dranchuk-Purvis-Robinson方法"
                      />
                      <el-option
                        label="Hall-Yarborough方法"
                        value="Hall-Yarborough方法"
                      />
                    </el-select>
                  </div>
                </div>

                <div class="param-group">
                  <div class="group-title">其他数据</div>
                  <div class="param-row">
                    <label>原始地层压力 (MPa)</label>
                    <el-input-number
                      v-model="params.initialPressure"
                      :controls="false"
                      :precision="2"
                      style="width: 100%"
                    />
                  </div>
                  <div class="param-row">
                    <label>地层温度 (℃)</label>
                    <el-input-number
                      v-model="params.bottomTemp"
                      :controls="false"
                      :precision="1"
                      style="width: 100%"
                    />
                  </div>
                  <div class="param-row">
                    <label>束缚水饱和度 (%)</label>
                    <el-input-number
                      v-model="params.swc"
                      :controls="false"
                      :precision="2"
                      style="width: 100%"
                    />
                  </div>
                  <div class="param-row row-switch">
                    <label style="margin: 0; line-height: 1.1"
                      >生产水气比上限(m³/10⁴m³)</label
                    >
                    <el-switch
                      v-model="params.enableWaterGasRatio"
                      style="
                        --el-switch-on-color: #f5c518;
                        --el-switch-on-border-color: #f5c518;
                        margin: 0;
                      "
                    />
                  </div>
                  <div
                    v-if="params.enableWaterGasRatio"
                    class="param-row"
                    style="padding-top: 0; margin-top: 2px"
                  >
                    <el-input-number
                      v-model="params.waterGasRatioLimit"
                      :controls="false"
                      :precision="4"
                      style="width: 100%"
                    />
                  </div>
                </div>

                <div class="param-group">
                  <div class="group-title">生产数据</div>
                  <div class="action-row" style="width: 50%">
                    <el-button size="default">模板下载</el-button>
                    <el-button size="default">导入</el-button>
                  </div>
                </div>
              </div>

              <div v-else class="panel-inner">
                <div v-if="analysisResult" class="output-title">
                  {{ analysisResult.isSingleWell ? "单井分析" : "整体分析" }}
                  <span v-if="analysisResult.wellName">
                    · {{ analysisResult.wellName }}</span
                  >
                  <span class="output-title-sub">
                    （{{ analysisResult.wellsAnalyzed?.length || 1 }}
                    口井参与计算）
                  </span>
                </div>

                <div v-if="analysisResult?.offline" class="offline-notice">
                  <p>
                    {{
                      analysisResult.offlineMessage ||
                      "未连接后端服务，无法获取分析数据"
                    }}
                  </p>
                </div>

                <template v-else>
                  <div class="output-group">
                    <div class="group-title">
                      计算结果表（压力 / 拟合值对比）
                    </div>
                    <div class="output-table-wrap">
                      <table class="output-table">
                        <thead>
                          <tr>
                            <th style="width: 60px">序号</th>
                            <th>累产气量 G(10⁸m³)</th>
                            <th>压力 P(MPa)</th>
                            <th>拟合压力 P* (MPa)</th>
                            <th>偏差 ΔP</th>
                          </tr>
                        </thead>
                        <tbody>
                          <tr
                            v-for="row in analysisResult?.table || []"
                            :key="row.seq"
                          >
                            <td>{{ row.seq }}</td>
                            <td>{{ row.g }}</td>
                            <td>{{ row.gp }}</td>
                            <td>{{ row.gpfit }}</td>
                            <td>{{ row.diff }}</td>
                          </tr>
                        </tbody>
                      </table>
                    </div>
                  </div>

                  <div class="output-group">
                    <div class="group-title">分析结论</div>
                    <div class="conclusion-box">
                      <p
                        v-for="(line, idx) in analysisResult?.conclusion || []"
                        :key="idx"
                      >
                        <span class="conclusion-bullet"></span>
                        {{ line }}
                      </p>
                    </div>
                  </div>
                </template>
              </div>
            </section>

            <section class="chart-panel">
              <div class="chart-inner">
                <template v-if="chartVisible">
                  <div
                    class="chart-heading-row"
                    style="
                      display: flex;
                      align-items: center;
                      justify-content: space-between;
                      padding: 6px 12px 10px 12px;
                      gap: 12px;
                    "
                  >
                    <span
                      class="chart-title-text"
                      style="font-size: 13px; font-weight: 600; color: #333"
                      >{{ chartTitle }}</span
                    >
                  </div>

                  <div v-if="analysisResult?.offline" class="offline-notice">
                    <p>
                      {{
                        analysisResult.offlineMessage ||
                        "未连接后端服务，无法获取图表数据"
                      }}
                    </p>
                  </div>

                  <template v-else>
                    <div class="chart-svg-wrap">
                      <svg
                        width="100%"
                        :height="svgHeight"
                        :viewBox="`0 0 ${svgWidth} ${svgHeight}`"
                        class="chart-svg"
                        preserveAspectRatio="xMidYMid meet"
                        style="overflow: visible"
                        @mousemove="handleChartMouseMove"
                        @mouseleave="handleChartMouseLeave"
                      >
                        <rect
                          :x="padding.left"
                          :y="padding.top"
                          :width="plotW"
                          :height="plotH"
                          fill="#fbfbfb"
                          stroke="#d5d5d5"
                        />

                        <g stroke="#e6e6e6" stroke-width="1">
                          <line
                            v-for="yt in yTicks"
                            :key="'y' + yt"
                            :x1="padding.left"
                            :x2="padding.left + plotW"
                            :y1="yToPx(yt)"
                            :y2="yToPx(yt)"
                          />
                        </g>
                        <g font-size="11" fill="#666" text-anchor="end">
                          <text
                            v-for="yt in yTicks"
                            :key="'yt' + yt"
                            :x="padding.left - 8"
                            :y="yToPx(yt) + 4"
                          >
                            {{ yt }}
                          </text>
                        </g>

                        <g stroke="#e6e6e6" stroke-width="1">
                          <line
                            v-for="xt in xTicks"
                            :key="'x' + xt"
                            :y1="padding.top"
                            :y2="padding.top + plotH"
                            :x1="xToPx(xt)"
                            :x2="xToPx(xt)"
                          />
                        </g>
                        <g font-size="11" fill="#666" text-anchor="middle">
                          <text
                            v-for="xt in xTicks"
                            :key="'xt' + xt"
                            :x="xToPx(xt)"
                            :y="padding.top + plotH + 18"
                          >
                            {{ formatAxisValue(xt) }}
                          </text>
                        </g>

                        <line
                          :x1="padding.left"
                          :y1="padding.top"
                          :x2="padding.left"
                          :y2="padding.top + plotH"
                          stroke="#888"
                          stroke-width="1.2"
                        />
                        <line
                          :x1="padding.left"
                          :y1="padding.top + plotH"
                          :x2="padding.left + plotW"
                          :y2="padding.top + plotH"
                          stroke="#888"
                          stroke-width="1.2"
                        />

                        <g fill="#3f73c4">
                          <circle
                            v-for="(d, idx) in scatterDots"
                            :key="'s' + idx"
                            :cx="d.cx"
                            :cy="d.cy"
                            r="3.2"
                            fill="#3f73c4"
                            stroke="#2e5a9a"
                            stroke-width="0.6"
                            opacity="0.88"
                          />
                        </g>

                        <g stroke-width="2" fill="none">
                          <line
                            v-for="(ln, idx) in fitLinePaths"
                            :key="'fl' + idx"
                            :x1="ln.x1"
                            :y1="ln.y1"
                            :x2="ln.x2"
                            :y2="ln.y2"
                            :stroke="ln.color"
                          />
                        </g>

                        <g
                          :transform="`translate(${padding.left + plotW - 130}, ${padding.top + 10})`"
                          font-size="11"
                          fill="#333"
                        >
                          <circle
                            cx="6"
                            cy="12"
                            r="3.5"
                            fill="#3f73c4"
                            stroke="#2e5a9a"
                            stroke-width="0.6"
                          />
                          <text x="16" y="16">数据点 (MPa)</text>

                          <template v-if="hasFitLines">
                            <line
                              x1="2"
                              y1="28"
                              x2="20"
                              y2="28"
                              stroke="#2a2a2a"
                              stroke-width="2"
                            />
                            <text x="28" y="32">
                              拟合线 (MPa)
                            </text>

                            <template v-if="hasSecondFitLine">
                              <line
                                x1="2"
                                y1="42"
                                x2="20"
                                y2="42"
                                stroke="#ffbb55"
                                stroke-width="2"
                              />
                              <text x="28" y="46">
                                理论线 (MPa)
                              </text>
                            </template>
                          </template>
                        </g>

                        <g v-if="hoverVisible" pointer-events="none">
                          <line
                            :x1="hoverPxX"
                            :y1="padding.top"
                            :x2="hoverPxX"
                            :y2="padding.top + plotH"
                            stroke="#888"
                            stroke-width="1"
                            stroke-dasharray="4 3"
                          />
                          <line
                            :x1="padding.left"
                            :y1="hoverPxY"
                            :x2="padding.left + plotW"
                            :y2="hoverPxY"
                            stroke="#888"
                            stroke-width="1"
                            stroke-dasharray="4 3"
                          />
                          <circle
                            :cx="hoverPxX"
                            :cy="hoverPxY"
                            r="4"
                            fill="none"
                            stroke="#3f73c4"
                            stroke-width="1.2"
                          />

                          <g
                            :transform="`translate(${padding.left - 58}, ${hoverPxY - 11})`"
                          >
                            <rect
                              width="54"
                              height="22"
                              rx="3"
                              ry="3"
                              fill="rgba(30, 34, 42, 0.95)"
                              stroke="#333"
                              stroke-width="1"
                            />
                            <text
                              x="27"
                              y="15"
                              fill="#fff"
                              font-size="11"
                              font-family="Consolas, monospace"
                              text-anchor="middle"
                            >
                              {{ hoverDataY.toFixed(4) }}
                            </text>
                          </g>

                          <g
                            :transform="`translate(${hoverPxX - 27}, ${padding.top + plotH - 22})`"
                          >
                            <rect
                              width="54"
                              height="20"
                              rx="3"
                              ry="3"
                              fill="rgba(30, 34, 42, 0.95)"
                              stroke="#333"
                              stroke-width="1"
                            />
                            <text
                              x="27"
                              y="14"
                              fill="#fff"
                              font-size="11"
                              font-family="Consolas, monospace"
                              text-anchor="middle"
                            >
                              {{ formatAxisValue(hoverDataX) }}
                            </text>
                          </g>

                          <g
                            :transform="`translate(${
                              hoverPxX + 12 > padding.left + plotW - 120
                                ? hoverPxX - 128
                                : hoverPxX + 12
                            }, ${
                              hoverPxY + 36 > padding.top + plotH
                                ? hoverPxY - 48
                                : hoverPxY + 12
                            })`"
                          >
                            <rect
                              width="116"
                              height="36"
                              rx="4"
                              ry="4"
                              fill="rgba(40, 44, 52, 0.92)"
                              stroke="#555"
                              stroke-width="1"
                            />
                            <text
                              x="10"
                              y="15"
                              fill="#fff"
                              font-size="11"
                              font-family="Consolas, monospace"
                            >
                              X: {{ formatAxisValue(hoverDataX) }}
                            </text>
                            <text
                              x="10"
                              y="30"
                              fill="#fff"
                              font-size="11"
                              font-family="Consolas, monospace"
                            >
                              Y: {{ hoverDataY.toFixed(4) }}
                            </text>
                          </g>
                        </g>
                      </svg>
                    </div>

                    <div v-if="isAnalyzing" class="chart-loading">分析中…</div>
                  </template>
                </template>
                <div v-else class="chart-empty">
                  <div class="chart-empty-title">未开始分析</div>
                  <div class="chart-empty-desc">
                    从顶部功能区点击 "流动平衡" 并选择井
                  </div>
                </div>
              </div>
            </section>
          </div>
        </template>
        <div v-else class="right-cols-empty"></div>
      </div>
    </div>
  </div>
</template>

<style lang="scss">
.el-notification {
  top: 12px !important;
  right: auto !important;
  left: 50% !important;
  transform: translateX(-50%) !important;
  padding: 6px 14px !important;
  min-height: 0 !important;
  min-width: 0 !important;
  width: auto !important;
  max-width: 280px !important;
  text-align: center !important;
  display: inline-flex !important;
  align-items: center !important;
  justify-content: center !important;
}
.el-notification__icon {
  margin: 0 10px 0 0 !important;
  padding: 0 !important;
  vertical-align: baseline !important;
  display: inline-block !important;
}
.el-notification__icon .el-icon {
  font-size: 14px !important;
  display: inline-block !important;
  transform: translateY(2px) !important;
  margin: 0 !important;
  padding: 0 !important;
}
.el-notification__group {
  margin: 0 !important;
  padding: 0 !important;
  display: inline-block !important;
}
.el-notification__title {
  font-size: 13px !important;
  margin: 0 !important;
  padding: 0 !important;
  line-height: 1 !important;
  text-align: center !important;
}
.el-notification__content {
  font-size: 12px !important;
  margin-top: 4px !important;
}
.el-notification__close-icon,
.el-notification .el-notification__close,
.el-notification [class*="close"] {
  display: none !important;
  visibility: hidden !important;
  width: 0 !important;
  height: 0 !important;
  overflow: hidden !important;
}
</style>

<style lang="scss" scoped>
$accent-yellow: #f4d000;
$border: #dedede;
$panel-bg: #ffffff;

.ipr-container {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background-color: #f0f2f5;
  overflow: hidden;
}

.ipr-main {
  flex: 1;
  display: flex;
  min-height: 0;
  padding: 0;
  gap: 0;
}

.right-cols {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  border-top: 1px solid #dedede;
  border-bottom: 1px solid #dedede;
  border-left: none;
  border-right: none;
  margin-left: 0;
  background-color: $panel-bg;
  position: relative;

  &.has-workspace::before {
    content: "";
    position: absolute;
    left: 240px;
    top: 0;
    bottom: 0;
    width: 1px;
    background-color: #dedede;
    z-index: 1;
  }

  .panel-header-row {
    display: flex;
    align-items: stretch;
    height: 32px;
    border-bottom: 1px solid #dedede;
    background-color: #e5e7eb;

    .panel-header-left {
      width: 240px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 0 8px;

      .panel-header-title {
        font-size: 13px;
        color: #333;
        font-weight: 600;
      }

      .panel-header-icon {
        display: inline-flex;
        align-items: center;
        font-size: 13px;
        color: #888;
        cursor: pointer;
      }
    }

    .panel-header-right {
      flex: 1;
      display: flex;
      align-items: center;
    }
  }

  .right-cols-inner {
    flex: 1;
    display: flex;
    min-height: 0;
  }
}

/* ============ 左侧项目树 ============ */
.side-panel {
  width: 168px;
  flex: 0 0 168px;
  display: flex;
  flex-direction: column;
  background-color: $panel-bg;
  border-top: 1px solid $border;
  border-bottom: 1px solid $border;
  border-left: none;
  border-right: 1px solid #e5e5e5;
  border-radius: 0;
  overflow: hidden;

  .side-search {
    padding: 4px 4px 5px;
    border-bottom: 1px solid #eeeeee;

    :deep(.el-input) {
      --el-input-height: 22px;
      font-size: 11px;
    }

    :deep(.el-input__wrapper) {
      border-radius: 2px;
      padding: 0 5px;
      box-shadow: 0 0 0 1px #e0e0e0 inset;
    }

    :deep(.el-input__inner) {
      height: 22px;
      line-height: 22px;
    }
  }

  .side-tree {
    flex: 1;
    overflow: auto;
    padding: 5px 4px;
  }

  .side-tabs {
    display: flex;
    height: 28px;
    border-top: 1px solid $border;

    .side-tab {
      flex: 1;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 12px;
      color: #555;
      cursor: pointer;
      background: #f5f5f5;
      transition: background-color 0.15s;

      &:hover {
        background: #eef1f5;
      }
      &.active {
        background-color: $accent-yellow;
        color: #1a1a1a;
        font-weight: 600;
      }
    }

    .right-cols-empty {
      flex: 1;
      display: flex;
      align-items: center;
      justify-content: center;
      background-color: $panel-bg;

      .right-cols-empty-inner {
        text-align: center;

        .right-cols-empty-title {
          font-size: 16px;
          font-weight: 600;
          color: #666;
          margin-bottom: 8px;
        }
        .right-cols-empty-desc {
          font-size: 12px;
          color: #999;
        }
      }
    }
  }
}

.right-cols-empty {
  flex: 1;
  background-color: $panel-bg;
}

/* ============ 中间参数面板 ============ */
.param-panel {
  width: 240px;
  background-color: $panel-bg;
  border: none;
  border-radius: 0;
  margin-left: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  min-height: 0;

  .panel-inner {
    flex: 1;
    display: flex;
    flex-direction: column;
    justify-content: flex-start;
    padding: 2px 8px;
    min-height: 0;
    gap: 4px;
  }

  .param-group {
    margin: 0;
    flex: 0 0 auto;
    display: flex;
    flex-direction: column;
    gap: 4px;

    .group-title {
      font-size: 11px;
      color: #333;
      font-weight: 600;
      padding: 2px 4px;
      margin: 0;
    }

    .param-row {
      display: flex;
      flex-direction: column;
      align-items: stretch;
      padding: 0;
      gap: 1px;

      label {
        font-size: 11px;
        color: #333;
        line-height: 1.2;
        padding: 0;
        margin: 0;
      }

      :deep(.el-select),
      :deep(.el-input-number),
      :deep(.el-input) {
        width: 100%;
        text-align: left;
        --el-border-color: #8a9099;
        --el-border-color-hover: #5d636c;
        --el-border-color-focus: #5d636c;
        --el-input-border-color: #8a9099;
        --el-input-hover-border-color: #5d636c;
        --el-input-focus-border-color: #5d636c;
        --el-font-size-base: 11px;
        height: 22px;
        --el-component-size: 22px;
      }
      :deep(.el-input__wrapper),
      :deep(.el-input-number .el-input__inner) {
        text-align: left;
        padding: 0 6px;
        height: 22px;
        line-height: 22px;
        font-size: 11px;
        border-width: 2px;
      }
      :deep(.el-select__wrapper) {
        height: 22px;
        min-height: 22px;
        font-size: 11px;
        border-width: 2px;
      }
      :deep(.el-input-number__decrease),
      :deep(.el-input-number__increase) {
        width: 18px;
        font-size: 11px;
        line-height: 22px;
      }
      :deep(.el-select__caret) {
        font-size: 11px;
      }

      &.row-switch {
        flex-direction: row;
        align-items: center;
        justify-content: space-between;
        padding: 0;
        margin: 0;
        gap: 0;

        :deep(.el-switch) {
          height: 18px;
          line-height: 18px;
          margin: 0;
          --el-switch-on-color: #f5c842;
          --el-switch-off-color: #dcdfe6;
          --el-switch-on-border-color: #f5c842;
        }
        :deep(.el-switch__core) {
          height: 18px;
          width: 32px !important;
        }
        :deep(.el-switch__core:after) {
          width: 14px;
          height: 14px;
        }
      }
    }

    /* 生产水气比上限：开关行与输入行之间紧贴 */
    .param-row.row-switch {
      label {
        margin: 0;
        line-height: 1;
      }
    }
    .param-row.row-switch + .param-row {
      padding: 0;
      margin: 0;
      gap: 1px;
      :deep(.el-input-number) {
        height: 20px;
        --el-component-size: 20px;
      }
      :deep(.el-input-number .el-input__inner) {
        height: 20px;
        line-height: 20px;
        font-size: 11px;
        border-width: 2px;
      }
    }

    .action-row {
      display: flex;
      gap: 6px;
      padding: 0;
      margin: 0;

      :deep(.el-button) {
        --el-border-color: #8a9099;
        --el-button-bg-color: #fff;
        border-color: #8a9099;
        color: #444;
        padding: 4px 10px;
        font-size: 11px;
        height: 20px;
        flex: 1;
      }
      :deep(.el-button:hover) {
        --el-border-color: #5d636c;
        border-color: #5d636c;
        color: #333;
      }
    }
  }

  /* 最后一个分组：按钮抵底，与上方间距最小化 */
  .param-group:last-child {
    flex: 1 1 auto;
    gap: 2px;
  }
  .param-group:last-child .action-row {
    margin-top: auto;
  }
}

/* ============ 输出结果 ============ */
.output-title {
  font-size: 13px;
  font-weight: 600;
  color: #222;
  margin-bottom: 10px;

  .output-title-sub {
    font-size: 12px;
    font-weight: 400;
    color: #666;
    margin-left: 8px;
  }
}

.output-group {
  margin-bottom: 6px;

  .group-title {
    font-size: 12px;
    color: #333;
    font-weight: 600;
    padding: 3px 6px;
    background: #f2f4f7;
    border: 1px solid #e4e7eb;
    border-radius: 3px;
    margin-bottom: 3px;
  }

  .output-table-wrap {
    max-height: 200px;
    overflow: auto;
    border: 1px solid #e8eaed;
    border-radius: 3px;
  }

  .output-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 12px;

    th,
    td {
      padding: 3px 6px;
      text-align: center;
      border-bottom: 1px solid #f0f0f0;
    }

    thead th {
      background: #f7f8fa;
      color: #444;
      font-weight: 600;
      position: sticky;
      top: 0;
    }

    tbody tr:hover {
      background: #f9fbff;
    }
  }

  .conclusion-box {
    padding: 6px 10px;
    background: #f8fbff;
    border: 1px solid #d9e6f8;
    border-radius: 3px;

    p {
      font-size: 12px;
      color: #3a4560;
      line-height: 1.5;
      margin: 0 0 3px;
    }

    .conclusion-bullet {
      display: inline-block;
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: #3f73c4;
      margin-right: 8px;
      vertical-align: middle;
    }
  }
}

/* ============ 右侧图表区 ============ */
.chart-panel {
  flex: 1;
  background-color: $panel-bg;
  border: none;
  border-radius: 0;
  margin-left: 0;
  overflow: hidden;
  position: relative;

  .chart-inner {
    height: 100%;
    display: flex;
    flex-direction: column;

    .chart-header {
      padding: 2px 8px 0px;
      border-bottom: 1px solid #eee;
      display: flex;
      flex-direction: column;
      gap: 0;

      .chart-title {
        font-size: 12px;
        font-weight: 600;
        color: #222;
      }
      .chart-subtitle {
        font-size: 10px;
        color: #888;
      }
    }

    .chart-svg-wrap {
      flex: 1;
      display: flex;
      align-items: flex-start;
      justify-content: center;
      padding: 0;
      overflow: hidden;
    }

    .chart-svg {
      background: #ffffff;
      border: none;
      border-radius: 4px;
      display: block;
      height: auto;
      max-height: 100%;
      width: 100%;
    }

    .chart-loading {
      position: absolute;
      top: 50%;
      left: 50%;
      transform: translate(-50%, -50%);
      font-size: 14px;
      color: #888;
      background: rgba(255, 255, 255, 0.8);
      padding: 10px 24px;
      border-radius: 4px;
    }

    .chart-empty {
      flex: 1;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      color: #aaa;

      .chart-empty-title {
        font-size: 16px;
        font-weight: 600;
        color: #666;
        margin-bottom: 8px;
      }
      .chart-empty-desc {
        font-size: 12px;
        color: #999;
      }
    }
  }
}

.offline-notice {
  margin: 16px;
  padding: 20px;
  background-color: #fff2f0;
  border: 1px solid #ffccc7;
  border-radius: 4px;
  p {
    margin: 0;
    color: #cf1322;
    font-size: 14px;
  }
}
</style>
