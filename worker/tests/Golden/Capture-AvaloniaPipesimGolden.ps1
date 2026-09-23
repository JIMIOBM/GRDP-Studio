param(
    [string]$AvaloniaRoot = "",
    [string]$PythonPath = "$env:LOCALAPPDATA\Programs\Python\Python39\python.exe",
    [string]$PipesimRoot = "$env:ProgramFiles\Schlumberger\PIPESIM2022.1",
    [string]$PtkPath = "",
    [string]$Study = "Study 1",
    [string]$GoldenRoot = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
if ([string]::IsNullOrWhiteSpace($AvaloniaRoot)) {
    $desktopRoot = Split-Path (Split-Path $repositoryRoot -Parent) -Parent
    $AvaloniaRoot = Join-Path $desktopRoot "Ava_desktop\Avalonia_oil"
}
if ([string]::IsNullOrWhiteSpace($PtkPath)) {
    $PtkPath = Join-Path $PipesimRoot "Developer Tools\Python Toolkit\Modules\PythonToolkitModules.zip"
}
if ([string]::IsNullOrWhiteSpace($GoldenRoot)) {
    $GoldenRoot = Join-Path $repositoryRoot "docs\software-integration\golden\pipesim-well-result-v1"
}

$adapterPath = Join-Path $AvaloniaRoot "src\UnifiedConsole.Infrastructure\Simulators\Pipesim\ptk_worker.py"
$schemaVersion = "pipesim-well-result/1"
$cases = @(
    [ordered]@{
        Id = "CSW_101"
        ModelKind = "black_oil_liquid"
        ModelPath = Join-Path $PipesimRoot "Case Studies\Well Models\CSW_101_Basic Oil Well.pips"
    },
    [ordered]@{
        Id = "CSW_102"
        ModelKind = "basic_gas"
        ModelPath = Join-Path $PipesimRoot "Case Studies\Well Models\CSW_102_Basic Gas Well.pips"
    }
)
$runs = @(
    [ordered]@{ Task = "nodal"; FileName = "nodal.json" },
    [ordered]@{ Task = "profile"; FileName = "pt-profile.json" },
    [ordered]@{ Task = "combined"; FileName = "combined.json" }
)

function Get-FreeLoopbackPort {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try {
        return ([Net.IPEndPoint]$listener.LocalEndpoint).Port
    }
    finally {
        $listener.Stop()
    }
}

function Get-ConflictingSimulatorProcesses {
    try {
        $processes = @(Get-CimInstance Win32_Process -ErrorAction Stop)
    }
    catch {
        throw "COORDINATION_UNVERIFIED: unable to inspect local simulator processes"
    }

    $conflicts = @()
    foreach ($process in $processes) {
        if ($process.ProcessId -eq $PID) {
            continue
        }
        $rawName = [string]$process.Name
        $rawCommandLine = [string]$process.CommandLine
        $rawExecutablePath = [string]$process.ExecutablePath
        $name = $rawName.ToLowerInvariant()
        $isPotentialHost = $name -like "python*" -or
            $name -like "dotnet*" -or
            $name -like "grdp.softwareintegration.worker*" -or
            $name -match "^(pipesim|psim)" -or
            $name -match "unifiedconsole"
        if ($isPotentialHost -and
            ([string]::IsNullOrWhiteSpace($rawCommandLine) -or
                [string]::IsNullOrWhiteSpace($rawExecutablePath))) {
            throw "COORDINATION_UNVERIFIED_PROCESS_METADATA: cannot inspect CommandLine and ExecutablePath for potential simulator host $rawName#$($process.ProcessId)"
        }

        $commandLine = $rawCommandLine.ToLowerInvariant()
        $executablePath = $rawExecutablePath.ToLowerInvariant()
        $isWorkerHost = $name -like "grdp.softwareintegration.worker*" -or
            $commandLine -match "grdp\.softwareintegration\.worker" -or
            $executablePath -match "grdp\.softwareintegration\.worker"
        $isAvaloniaHost = $name -match "unifiedconsole" -or
            $commandLine -match "avalonia_oil.*unifiedconsole" -or
            $executablePath -match "avalonia_oil.*unifiedconsole"
        $isPipesimHost = $name -match "^(pipesim|psim)" -or
            $commandLine -match "[/\\]pipesim(?:\.exe)?(?:\s|$)" -or
            $executablePath -match "[/\\]pipesim(?:\.exe)?$"
        $isPythonToolkit = $name -like "python*" -and
            ($commandLine -match "(ptk_worker\.py|ptk_validate\.py|pythontoolkitmodules|sixgill)" -or
                $executablePath -match "pythontoolkit")
        if ($isWorkerHost -or $isAvaloniaHost -or $isPipesimHost -or $isPythonToolkit) {
            $conflicts += "$($process.Name)#$($process.ProcessId)"
        }
    }
    return $conflicts
}

function Assert-NoExternalSimulatorActivity([string]$Phase) {
    $conflicts = @(Get-ConflictingSimulatorProcesses)
    if ($conflicts.Count -gt 0) {
        throw "COORDINATION_BLOCKED: $Phase detected simulator-capable or PTK processes: $($conflicts -join ', '). Stop Worker, Avalonia, PIPESIM, and PTK adapters before capture."
    }
}

function Invoke-WorkerRequest([int]$Port, [hashtable]$Body) {
    $json = $Body | ConvertTo-Json -Depth 20 -Compress
    return Invoke-RestMethod `
        -Method Post `
        -Uri "http://127.0.0.1:$Port/api/run" `
        -ContentType "application/json; charset=utf-8" `
        -Body $json `
        -TimeoutSec 900
}

function Start-AvaloniaAdapter([string]$LogPath) {
    $port = Get-FreeLoopbackPort
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $PythonPath
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    [void]$startInfo.ArgumentList.Add("-u")
    [void]$startInfo.ArgumentList.Add($adapterPath)
    $startInfo.Environment["PYTHONPATH"] = $PtkPath
    $startInfo.Environment["PTK_MODULES_ZIP"] = $PtkPath
    $startInfo.Environment["PIPESIM_PTK_PATH"] = $PtkPath
    $startInfo.Environment["PIPESIM_WORKER_PORT"] = $port.ToString()
    $startInfo.Environment["PIPESIM_WORKER_LOG"] = $LogPath
    # The Worker cannot join this mutex within WP-01 ownership; scan immediately before launch to minimize, not eliminate, that TOCTOU window.
    Assert-NoExternalSimulatorActivity -Phase "immediately before adapter process start"
    $process = [Diagnostics.Process]::Start($startInfo)
    if ($null -eq $process) {
        throw "Failed to start the Avalonia PIPESIM adapter"
    }

    try {
        $deadline = [DateTimeOffset]::UtcNow.AddMinutes(2)
        while ([DateTimeOffset]::UtcNow -lt $deadline) {
            if ($process.HasExited) {
                throw "Avalonia PIPESIM adapter exited before becoming healthy"
            }
            try {
                $health = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/health" -TimeoutSec 5
                if (-not $health.sixgill_ok) {
                    throw "ENVIRONMENT_UNVERIFIED: PIPESIM Python Toolkit import is unavailable"
                }
                if (-not $health.license_ok) {
                    throw "LICENSE_UNVERIFIED: PIPESIM Python Toolkit license is unavailable"
                }
                return [ordered]@{ Process = $process; Port = $port }
            }
            catch {
                if ($_.Exception.Message -match "ENVIRONMENT_UNVERIFIED|LICENSE_UNVERIFIED") {
                    throw
                }
                Start-Sleep -Milliseconds 500
            }
        }
        throw "Timed out waiting for the Avalonia PIPESIM adapter"
    }
    catch {
        $exitConfirmed = $process.HasExited
        if (-not $process.HasExited) {
            $process.Kill($true)
            $exitConfirmed = $process.WaitForExit(30000) -and $process.HasExited
        }
        $process.Dispose()
        if (-not $exitConfirmed) {
            throw "Avalonia PIPESIM adapter cleanup failed; process-tree exit is unconfirmed"
        }
        Start-Sleep -Seconds 2
        Assert-NoExternalSimulatorActivity -Phase "after failed adapter start cleanup"
        throw
    }
}

function Stop-AvaloniaAdapter($Session) {
    if ($null -eq $Session -or $null -eq $Session.Process) {
        return
    }
    if (-not $Session.Process.HasExited) {
        $Session.Process.Kill($true)
    }
    if (-not $Session.Process.WaitForExit(30000) -or -not $Session.Process.HasExited) {
        throw "Avalonia PIPESIM adapter did not exit; license release is unconfirmed"
    }
    $Session.Process.Dispose()
    Start-Sleep -Seconds 2
    Assert-NoExternalSimulatorActivity -Phase "after adapter process-tree exit"
}

function Confirm-PtkLicenseReusable([string]$LogPath) {
    $probe = $null
    try {
        $probe = Start-AvaloniaAdapter -LogPath $LogPath
    }
    finally {
        Stop-AvaloniaAdapter -Session $probe
    }
    Assert-NoExternalSimulatorActivity -Phase "after PTK license release probe"
}

function Get-ResultContract([string]$RunTask, $Payload) {
    $iprCount = @($Payload.ipr).Count
    $vlpCount = @($Payload.vlp).Count
    $profileCount = @($Payload.profile).Count
    if ($RunTask -eq "nodal") {
        if ($iprCount -gt 0 -and $vlpCount -gt 0) { return "VALID_FULL" }
        return "INVALID_EMPTY_NODAL"
    }
    if ($RunTask -eq "profile") {
        if ($profileCount -gt 0) { return "VALID_FULL" }
        return "INVALID_EMPTY_PROFILE"
    }
    if ($iprCount -eq 0 -or $vlpCount -eq 0) { return "INVALID_EMPTY_NODAL" }
    if ($profileCount -eq 0) { return "VALID_PARTIAL" }
    return "VALID_FULL"
}

function New-UnspecifiedUnitDescriptor {
    return [ordered]@{ displayUnit = $null; semantics = "unspecified" }
}

function New-FlowUnitDescriptor([string]$ModelKind) {
    if ($ModelKind -eq "basic_gas") {
        return [ordered]@{
            displayUnit = "mmscf/d"
            semantics = "standard_gas_volume_rate"
        }
    }
    return (New-UnspecifiedUnitDescriptor)
}

function Write-Utf8Json([string]$Path, $Value) {
    $json = $Value | ConvertTo-Json -Depth 100
    [IO.File]::WriteAllText($Path, "$json`n", [Text.UTF8Encoding]::new($false))
}

function Publish-GoldenBatch([string]$StagingRoot, [string]$DestinationRoot, [string]$BackupRoot) {
    if ([IO.Path]::GetPathRoot($StagingRoot) -ne [IO.Path]::GetPathRoot($DestinationRoot)) {
        throw "Golden staging and destination must be on the same volume"
    }

    $oldVersionMoved = $false
    try {
        if ([IO.Directory]::Exists($DestinationRoot)) {
            [IO.Directory]::Move($DestinationRoot, $BackupRoot)
            $oldVersionMoved = $true
        }
        [IO.Directory]::Move($StagingRoot, $DestinationRoot)
    }
    catch {
        if ($oldVersionMoved -and
            -not [IO.Directory]::Exists($DestinationRoot) -and
            [IO.Directory]::Exists($BackupRoot)) {
            [IO.Directory]::Move($BackupRoot, $DestinationRoot)
        }
        throw
    }

    if ([IO.Directory]::Exists($BackupRoot)) {
        try {
            [IO.Directory]::Delete($BackupRoot, $true)
        }
        catch {
            Write-Warning "Published the new complete Golden batch, but the previous backup could not be removed."
        }
    }
}

$verifyScript = Join-Path $PSScriptRoot "Verify-Golden.ps1"
$GoldenRoot = [IO.Path]::GetFullPath($GoldenRoot)
$goldenParent = Split-Path $GoldenRoot -Parent
$goldenName = Split-Path $GoldenRoot -Leaf
$batchId = [Guid]::NewGuid().ToString("N")
$stagingRoot = Join-Path $goldenParent ".$goldenName.staging-$batchId"
$backupRoot = Join-Path $goldenParent ".$goldenName.backup-$batchId"
$logRoot = Join-Path ([IO.Path]::GetTempPath()) ("grdp-pipesim-golden-logs-" + $batchId)
$captureMutex = $null
$captureLockHeld = $false

try {
    $captureMutex = [Threading.Mutex]::new($false, "Global\GRDP-Pipesim-Golden-Capture")
    try {
        $captureLockHeld = $captureMutex.WaitOne(0)
    }
    catch [Threading.AbandonedMutexException] {
        $captureLockHeld = $true
    }
    if (-not $captureLockHeld) {
        throw "CAPTURE_BUSY: another machine-wide PIPESIM golden capture is running"
    }

    foreach ($requiredPath in @($PythonPath, $PtkPath, $adapterPath, $verifyScript)) {
        if (-not (Test-Path -LiteralPath $requiredPath)) {
            throw "ENVIRONMENT_UNVERIFIED: required capture dependency is unavailable: $([IO.Path]::GetFileName($requiredPath))"
        }
    }
    foreach ($case in $cases) {
        if (-not (Test-Path -LiteralPath $case.ModelPath)) {
            throw "ENVIRONMENT_UNVERIFIED: required acceptance model is unavailable: $($case.Id)"
        }
    }

    Assert-NoExternalSimulatorActivity -Phase "batch preflight"
    [void][IO.Directory]::CreateDirectory($goldenParent)
    [void][IO.Directory]::CreateDirectory($stagingRoot)
    [void][IO.Directory]::CreateDirectory($logRoot)

    $adapterRevision = (& git -C $AvaloniaRoot rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $adapterRevision -notmatch "^[0-9a-f]{40}$") {
        throw "Unable to identify the Avalonia adapter revision"
    }
    $adapterSha = (Get-FileHash -Algorithm SHA256 -LiteralPath $adapterPath).Hash.ToLowerInvariant()

    foreach ($case in $cases) {
        $sourceShaBefore = (Get-FileHash -Algorithm SHA256 -LiteralPath $case.ModelPath).Hash.ToLowerInvariant()
        $caseRoot = Join-Path $stagingRoot $case.Id
        [void][IO.Directory]::CreateDirectory($caseRoot)

        foreach ($run in $runs) {
            Assert-NoExternalSimulatorActivity -Phase "before $($case.Id) $($run.Task)"
            $session = $null
            try {
                $session = Start-AvaloniaAdapter -LogPath (Join-Path $logRoot "$($case.Id)-$($run.Task).log")
                $open = Invoke-WorkerRequest -Port $session.Port -Body @{
                    task = "open_model"
                    payload = @{ model_path = $case.ModelPath }
                }
                if ($open.status -ne "ok") {
                    throw "Avalonia adapter could not open $($case.Id)"
                }
                if ($open.payload.model_kind -ne $case.ModelKind) {
                    throw "Avalonia adapter returned an unexpected model kind for $($case.Id)"
                }
                if (@($open.payload.studies) -notcontains $Study) {
                    throw "Study '$Study' was not found in $($case.Id)"
                }

                $response = Invoke-WorkerRequest -Port $session.Port -Body @{
                    task = "run"
                    payload = @{
                        parameters = $null
                        study = $Study
                        run_task = $run.Task
                    }
                }
                if ($response.status -ne "ok") {
                    throw "Avalonia adapter failed $($case.Id) $($run.Task)"
                }
                $contract = Get-ResultContract -RunTask $run.Task -Payload $response.payload
                if ($contract -like "INVALID_*") {
                    throw "Avalonia adapter returned an invalid result contract for $($case.Id) $($run.Task): $contract"
                }

                $result = [ordered]@{
                    schemaVersion = $schemaVersion
                    model_kind = $response.payload.model_kind
                    runTask = $run.Task
                    resultContract = $contract
                    units = [ordered]@{
                        flow = New-FlowUnitDescriptor -ModelKind $case.ModelKind
                        pressure = New-UnspecifiedUnitDescriptor
                        depth = New-UnspecifiedUnitDescriptor
                        temperature = New-UnspecifiedUnitDescriptor
                    }
                    ipr = @($response.payload.ipr)
                    vlp = @($response.payload.vlp)
                    profile = @($response.payload.profile)
                }
                Write-Utf8Json -Path (Join-Path $caseRoot $run.FileName) -Value $result

                $metadata = [ordered]@{
                    source = [ordered]@{
                        fileName = [IO.Path]::GetFileName($case.ModelPath)
                        sha256Before = $sourceShaBefore
                        sha256After = $null
                    }
                    study = $Study
                    runType = $run.Task
                    schemaVersion = $schemaVersion
                    avaloniaAdapter = [ordered]@{
                        revision = $adapterRevision
                        relativePath = "src/UnifiedConsole.Infrastructure/Simulators/Pipesim/ptk_worker.py"
                        sha256 = $adapterSha
                    }
                    generatedAtUtc = [DateTimeOffset]::UtcNow.ToString("o")
                    sanitization = "No absolute paths, license data, credentials, logs, or input model content are included."
                }
                Write-Utf8Json `
                    -Path (Join-Path $caseRoot ($run.FileName -replace "\.json$", ".metadata.json")) `
                    -Value $metadata
            }
            finally {
                Stop-AvaloniaAdapter -Session $session
            }

            Confirm-PtkLicenseReusable -LogPath (Join-Path $logRoot "$($case.Id)-$($run.Task)-license-probe.log")
            Write-Output "$($case.Id) $($run.Task): adapter process tree exited and PTK license reuse probe succeeded."
        }

        $sourceShaAfter = (Get-FileHash -Algorithm SHA256 -LiteralPath $case.ModelPath).Hash.ToLowerInvariant()
        if ($sourceShaBefore -ne $sourceShaAfter) {
            throw "Source model changed during capture: $($case.Id)"
        }
        foreach ($run in $runs) {
            $metadataPath = Join-Path $caseRoot ($run.FileName -replace "\.json$", ".metadata.json")
            $metadata = Get-Content -Raw -LiteralPath $metadataPath | ConvertFrom-Json -AsHashtable -DateKind String
            $metadata.source.sha256After = $sourceShaAfter
            Write-Utf8Json -Path $metadataPath -Value $metadata
        }
        Write-Output "$($case.Id): all three source SHA-256 checks completed unchanged ($sourceShaAfter)."
    }

    $stagedFiles = [IO.Directory]::GetFiles($stagingRoot, "*", [IO.SearchOption]::AllDirectories)
    if ($stagedFiles.Count -ne 12) {
        throw "Golden staging must contain exactly six results and six metadata sidecars"
    }
    & $verifyScript -GoldenRoot $stagingRoot -VerifyLocalSources -AvaloniaRoot $AvaloniaRoot -PipesimRoot $PipesimRoot
    Assert-NoExternalSimulatorActivity -Phase "before atomic batch publication"
    Publish-GoldenBatch -StagingRoot $stagingRoot -DestinationRoot $GoldenRoot -BackupRoot $backupRoot
    Write-Output "Captured, verified, and atomically published six real Avalonia PIPESIM golden results."
}
finally {
    if (Test-Path -LiteralPath $stagingRoot) {
        [IO.Directory]::Delete($stagingRoot, $true)
    }
    if (Test-Path -LiteralPath $logRoot) {
        [IO.Directory]::Delete($logRoot, $true)
    }
    if ($captureLockHeld) {
        $captureMutex.ReleaseMutex()
    }
    if ($null -ne $captureMutex) {
        $captureMutex.Dispose()
    }
}
