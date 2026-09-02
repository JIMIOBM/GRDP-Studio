# GRDP-Studio Project Rules

This file is loaded automatically by OpenCode for every session started in this repository. Keep it concise and enforce it for primary agents and subagents.

## Required Context

For any software-integration task, read these files before proposing or making changes:

1. `docs/software-integration/requirements.md` - authoritative product scope, architecture constraints, and acceptance definition.
2. `PROGRESS.md` - verified current state, known gaps, and next milestone.
3. Relevant current code and `git diff` - the worktree may contain uncommitted user or Agent changes.

The original requirements backup remains at `C:\Users\Violet\Desktop\Bei\PRO.MD`, but the repository copy is the development reference. If the two files differ, stop and ask which change is authoritative rather than silently merging them.

## Protected Scope

Software integration is isolated from the existing GRDP business modules.

Allowed primary areas:

- `backend/src/main/java/com/grdp/studio/softwareintegration/`
- `backend/deploy/mysql/migrations/` for software-integration migrations
- `vue/src/views/SoftwareIntegration/`
- `vue/src/api/softwareIntegration.js`
- software-integration stores, components, tests, and documentation
- `worker/`
- Windows lifecycle scripts for Worker wiring

Shared files such as `IprInterface.vue`, `RibbonMenu.vue`, application startup, shared configuration, dependency files, and router files may receive only the smallest required change. Every shared branch must be isolated by `workspace=software-integration` or a stable software-integration command ID.

Do not change the behavior of parsing/fusion, PVT, productivity analysis, water-invasion analysis, the original project tree, the original GRDP platform, or unrelated modules. Do not refactor, rename, format, or clean unrelated code.

## Reference Baseline

`C:\Users\Violet\Desktop\Ava_desktop\Avalonia_oil` is read-only. It is the behavioral and result baseline for PIPESIM and later ECLIPSE migration. Never edit, format, restore, or generate files in that repository.

Preserve the approved parsing and normalization semantics, including field names, units, ordering, missing-value handling, partial-result behavior, and error classification. Do not invent Studies, progress percentages, simulator results, or success states.

## Architecture Invariants

- The browser talks only to Spring Boot. It must not call the Worker or launch local processes.
- Spring Boot is the source of truth for projects, model versions, runs, events, results, and artifacts.
- Long simulator calls must not run inside database transactions. State updates use short transactions.
- PIPESIM validation and execution are globally serialized because the Python Toolkit license is a singleton resource.
- The Worker listens only on loopback in the first release and never writes the business database directly.
- Browser input must not be trusted as a local path or executable command.
- Persist relative storage keys, checksums, metadata, and status. Do not put model or artifact contents in MySQL BLOBs.
- Do not log passwords, cookies, license contents, complete model contents, or sensitive local paths.
- A READY model version is required before creating a run.
- Run state, cancellation, timeout, restart recovery, partial results, and artifact publication require explicit, testable semantics.
- The first release supports existing Studies only and must not overwrite source model parameters.

## Multi-Agent Workflow

The built-in Build agent remains the primary coordinator. Project subagents are defined in `.opencode/agents/`:

- `@architect`: read-only architecture, contracts, work packages, dependencies, and test matrix.
- `@executor`: one bounded implementation work package with explicit file ownership.
- `@reviewer`: independent read-only diff review and verification; returns PASS, REWORK, or BLOCKED.
- `@vision`: screenshot and PDF analysis only; no implementation or architecture decisions.

For substantial work:

1. Ask Architect to freeze contracts and divide work by file ownership.
2. Run Executor instances in parallel only when their files do not overlap and their shared contract is frozen.
3. Serialize edits to shared Shell files, migrations, DTO contracts, and lifecycle scripts.
4. Never run real PIPESIM acceptance tasks in parallel.
5. Run Reviewer after implementation. Feed REWORK findings back to Executor and review again.
6. Update `PROGRESS.md` only after verification, not based on intent.

Agents share one worktree. Do not assume child sessions have isolated branches or files. If an unexpected concurrent edit conflicts with the current work package, stop and report the conflict. Otherwise preserve it.

## Implementation Standards

- Prefer the smallest correct change and existing project patterns.
- Java controllers bind and validate only; use request/response DTOs and do not expose entities.
- Use MyBatis-Plus mappers consistently and keep schema migration responsibilities explicit.
- Vue uses Vue 3 `<script setup>`, Element Plus, ECharts, scoped SCSS, stable IDs, and an isolated software-integration state store.
- Release timers, polling, event listeners, charts, SSE, and WebSocket resources on component unmount.
- .NET Worker code owns process supervision and simulator access; Python owns the frozen PTK execution/normalization behavior where applicable.
- Add comments only for non-obvious constraints or failure handling.
- Do not add backward-compatibility paths without a concrete persisted-data or external-consumer requirement.

## Verification

Run the smallest relevant checks first, then the complete checks for the touched layers.

Frontend, from `vue/`:

```powershell
npm run build
```

Worker, from `worker/`:

```powershell
dotnet build .\Grdp.SoftwareIntegration.Worker.csproj
```

Backend, from the repository root with the local JDK/Maven toolchain:

```powershell
$root = (Resolve-Path '..').Path
$jdk = (Get-ChildItem (Join-Path $root '.tools') -Directory | Where-Object { $_.Name -like 'jdk-21*' } | Select-Object -First 1).FullName
$mvn = Join-Path $root '.tools\apache-maven-3.9.16\bin\mvn.cmd'
$env:JAVA_HOME = $jdk
& $mvn -f backend/pom.xml package -DskipTests
```

Integrated lifecycle scripts are outside the repository:

```text
C:\Users\Violet\Desktop\Bei\start-grdp-ahks.ps1
C:\Users\Violet\Desktop\Bei\stop-grdp-ahks.ps1
```

Health endpoints:

```text
http://127.0.0.1:9919
http://127.0.0.1:9920
http://127.0.0.1:8080/actuator/health
http://127.0.0.1:5173/login
http://127.0.0.1:5150/api/health
http://127.0.0.1:5150/api/capabilities
```

Real PIPESIM acceptance models:

```text
C:\Program Files\Schlumberger\PIPESIM2022.1\Case Studies\Well Models\CSW_101_Basic Oil Well.pips
C:\Program Files\Schlumberger\PIPESIM2022.1\Case Studies\Well Models\CSW_102_Basic Gas Well.pips
```

Always distinguish implementation failures from license, installation, port, and environment failures. A build alone does not prove simulator behavior.

## Git And Handoff

- The worktree may be dirty. Never revert or overwrite changes you did not make.
- Do not commit, amend, push, reset, checkout, clean, or delete work unless the user explicitly requests it.
- Before ending a milestone, record completed behavior, exact verification, known gaps, and the next bounded goal in `PROGRESS.md`.
- Never mark a phase complete while required golden tests, recovery behavior, artifact retention, or regression checks are still missing.
