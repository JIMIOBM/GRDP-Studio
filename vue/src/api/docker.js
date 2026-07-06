import axios from "axios";

const dockerRequest = axios.create({
  baseURL: "/docker-api",
  timeout: 30000,
  withCredentials: true,
});

const backendRequest = axios.create({
  baseURL: "/api",
  timeout: 30000,
  withCredentials: true,
});

function offlineError(context) {
  return {
    ok: false,
    offline: true,
    message: `未连接后端服务 — ${context}`,
    data: null,
  };
}

function unauthenticatedError(context) {
  return {
    ok: false,
    offline: false,
    message: `未登录 — ${context}，请先在控制台登录`,
    data: null,
  };
}

async function callApi(requestFn, context) {
  try {
    const res = await requestFn();
    if (res?.status >= 200 && res.status < 300 && res.data) {
      return { ok: true, data: res.data };
    }
    if (res?.status === 401) {
      return unauthenticatedError(context);
    }
    return offlineError(context);
  } catch (e) {
    const status = e?.response?.status;
    if (status === 401) {
      return unauthenticatedError(context);
    }
    return offlineError(context);
  }
}

/* ===== 水侵动态分析 ===== */
export const waterInvasionApi = {
  analyze: (data) =>
    callApi(
      () => dockerRequest.post("/projectanalysis/waterinvasionanalysis", data),
      "水侵分析",
    ),
};

/* ===== 流动平衡分析 ===== */
export const flowBalanceApi = {
  analyze: (data) =>
    callApi(
      () => dockerRequest.post("/projectanalysis/flowbalanceanalysis", data),
      "流动平衡分析",
    ),
};

/* ===== 气藏列表 ===== */
export const gasReservoirsApi = {
  list: async (queryType) => {
    const params = new URLSearchParams({ _t: String(Date.now()) });
    if (queryType) params.set("gasReservoirType", queryType);
    const url = `/common/coredb/gasreservoirs?${params.toString()}`;
    return callApi(() => backendRequest.get(url), "气藏列表");
  },
  detail: async (gasReservoirId) => {
    const url = `/common/coredb/gasreservoirs/${gasReservoirId}?_t=${Date.now()}`;
    return callApi(() => backendRequest.get(url), "气藏详情");
  },
};

/* ===== 井列表 ===== */
export const wellApi = {
  getWells: (projectId, gasReservoirId) =>
    callApi(
      () =>
        dockerRequest.get(
          `/projectanalysis/${projectId}/${gasReservoirId}/wells`,
        ),
      "井列表",
    ),
};

/* ===== 分析参数 ===== */
export const parametersApi = {
  getMinWaterGasRatio: (projectId, gasReservoirId) =>
    callApi(
      () =>
        dockerRequest.get(
          `/projectanalysis/${projectId}/${gasReservoirId}/parameters/minmumWaterGasRatio`,
        ),
      "最小水气比参数",
    ),
};

/* ===== 节点树 ===== */
export const nodeApi = {
  getNode: (projectId, gasReservoirId, nodeType) =>
    callApi(
      () =>
        dockerRequest.get(
          `/projectanalysis/node/${projectId}/${gasReservoirId}/${nodeType}`,
        ),
      "节点树",
    ),
};

export default dockerRequest;
