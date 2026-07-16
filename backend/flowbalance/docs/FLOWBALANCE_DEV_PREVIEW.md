# FlowBalance real-data development preview

This preview is intentionally non-persistent. It reads the existing MariaDB data with `mysql2/promise`, calculates in memory, and returns runtime node 57/58 objects. Refreshing the Vue page removes those runtime nodes.

## 1. Start the independent backend

From `D:\Project\GRDP-Studio-flow-balance\backend\flowbalance` in PowerShell:

```powershell
npm ci
npm run check
npm test
npm run test:integration:docker
powershell -ExecutionPolicy Bypass -File scripts\start-dev.ps1
Invoke-RestMethod http://127.0.0.1:8891/flowbalance/health
```

`start-dev.ps1` reads the existing `mariadb` container environment without printing the password, joins its Docker network, and publishes only the FlowBalance HTTP port `8891`. It does not publish the database port, migrate, or write business data.

Expected health response:

```json
{"status":"ok","service":"grdp-flowbalance","persistence":false}
```

Stop it with:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\stop-dev.ps1
```

## 2. Start Vue

From `D:\Project\GRDP-Studio-flow-balance\vue`:

```powershell
npm ci
npm run dev
```

Open:

```text
http://localhost:5174/ipr?projectId=4&gasReservoirId=2
```

The Vite proxy forwards `/flowbalance/*` to `http://127.0.0.1:8891` and leaves all existing `/docker-api` routes unchanged.

## 3. Real interaction

1. Select well `X-1` in the left tree.
2. Click the existing `流动平衡` ribbon entry.
3. Wait for the calculation response.
4. Under `X-1 → 井控库存`, two runtime entries appear:
   - `流动平衡-井口流压` (`nodeType=57`, `pressureSource=well_head_tubing_pressure`)
   - `流动平衡-井底流压` (`nodeType=58`, `pressureSource=calculated_bottom_hole_pressure`, `pressureKind=calculated`)
5. Click either node to switch between its real chart and regression result.

No tree node is persisted. Page refresh removes both entries by design.

## 4. Browser Network evidence

The browser must show exactly this calculation request:

```text
POST http://localhost:5174/flowbalance/calc
```

Request body for X-1:

```json
{
  "projectId": 4,
  "gasReservoirId": 2,
  "wellNames": ["X-1"],
  "waterGasRatioLimit": 0.0602
}
```

The `200` response must contain:

- top level `persisted=false`, `idKind=runtime`, and a UUID `runId`;
- `nodes` containing real node types 57 and 58;
- each node containing `resultId`, `nodeType`, `pressureSource`, `pressureKind`, `input`, `result`, `data`, `diagnostics`;
- `result.regression.slope`, `intercept`, `rSquared`, and `sampleCount` calculated from X-1 rows;
- `data` containing the real dated calculation series, not a fixed demo array.

There must be no request to `/projectanalysis/dynamicoriginalgasInplace/fmb/calc`.

## 5. Interpretation boundary

The development algorithm is `mattar-mcneil-flowing-pz-preview-v1`: real cumulative gas versus real flowing `P/Z`, with a parallel line through initial `Pi/Zi`. It is explicitly marked `engineeringValidation=required`; it is not presented as a variable-rate pseudo-time implementation.

Because the database unit metadata for `cumulative_gas_production` is empty, `originalGasVolume` remains in that database source unit. The UI does not relabel or rescale it to `10^8 m³`.

## TRAE validation instruction

If the host-only checks fail, give IDEA TRAE this task:

```text
在 D:\Project\GRDP-Studio-flow-balance 中只做验证和必要的 FlowBalance 范围修复。不得修改水侵、原物质平衡、共享 main，不得 migration、写数据库、commit 或 push。

1. 在 backend\flowbalance 运行 npm ci、npm run check、npm test、npm run test:integration:docker。
2. 运行 scripts\start-dev.ps1，确认 GET http://127.0.0.1:8891/flowbalance/health 返回 persistence=false。
3. 用 POST /flowbalance/calc 请求 projectId=4、gasReservoirId=2、wellNames=["X-1"]、waterGasRatioLimit=0.0602。
4. 必须确认 response.nodes 同时含 nodeType 57/58；57 来源 well_head_tubing_pressure；58 来源 calculated_bottom_hole_pressure 且 pressureKind=calculated；两者 persisted=false、idKind=runtime、data 来自 3556 条真实 X-1 行。禁止 mock、fallback、固定输出或调用原 /fmb/calc。
5. 在 vue 运行 npm ci、npm run build、npm run dev，打开 http://localhost:5174/ipr?projectId=4&gasReservoirId=2，选择 X-1 后点击流动平衡。确认 Network 只有 POST /flowbalance/calc，左树出现临时 57/58，点击均显示真实回归页。
6. 报告命令结果、HTTP 状态、57/58 样本数/斜率/截距/R²/OGIP、浏览器 Network 和 git status --short。不要扩大任务范围。
```
