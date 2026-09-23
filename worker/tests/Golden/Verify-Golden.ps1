param(
    [string]$GoldenRoot = "",
    [string]$AvaloniaRoot = "",
    [string]$PipesimRoot = "$env:ProgramFiles\Schlumberger\PIPESIM2022.1",
    [switch]$VerifyLocalSources
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$schemaPath = Join-Path $repositoryRoot "docs\software-integration\contracts\pipesim-well-result-v1.schema.json"
if ([string]::IsNullOrWhiteSpace($GoldenRoot)) {
    $GoldenRoot = Join-Path $repositoryRoot "docs\software-integration\golden\pipesim-well-result-v1"
}

$cases = [ordered]@{
    CSW_101 = "black_oil_liquid"
    CSW_102 = "basic_gas"
}
$runs = [ordered]@{
    "nodal.json" = "nodal"
    "pt-profile.json" = "profile"
    "combined.json" = "combined"
}

if (-not (Test-Path -LiteralPath $schemaPath)) {
    throw "PIPESIM well-result schema is missing"
}

function Assert-Condition([bool]$Condition, [string]$Message) {
    if (-not $Condition) {
        throw $Message
    }
}

function Assert-FiniteNumber($Value, [string]$Location) {
    Assert-Condition ($Value -is [ValueType]) "$Location is not numeric"
    $number = [double]$Value
    Assert-Condition ([double]::IsFinite($number)) "$Location is not finite"
}

function Test-Contract([string]$RunTask, $Result) {
    $iprCount = @($Result.ipr).Count
    $vlpCount = @($Result.vlp).Count
    $profileCount = @($Result.profile).Count
    if ($RunTask -eq "nodal") {
        return ($iprCount -gt 0 -and $vlpCount -gt 0 -and $Result.resultContract -eq "VALID_FULL")
    }
    if ($RunTask -eq "profile") {
        return ($profileCount -gt 0 -and $Result.resultContract -eq "VALID_FULL")
    }
    if ($iprCount -eq 0 -or $vlpCount -eq 0) { return $false }
    if ($profileCount -eq 0) {
        return $Result.resultContract -eq "VALID_PARTIAL"
    }
    return $Result.resultContract -eq "VALID_FULL"
}

$verified = 0
$expectedFiles = @()
$sourceHashes = @{}
$adapterRevision = $null
$adapterSha = $null
$previousGeneratedAt = $null
foreach ($caseId in $cases.Keys) {
    foreach ($fileName in $runs.Keys) {
        $runTask = $runs[$fileName]
        $resultPath = Join-Path $GoldenRoot "$caseId\$fileName"
        $metadataName = $fileName -replace "\.json$", ".metadata.json"
        $metadataPath = Join-Path $GoldenRoot "$caseId\$metadataName"
        $expectedFiles += "$caseId\$fileName", "$caseId\$metadataName"
        Assert-Condition (Test-Path -LiteralPath $resultPath) "BLOCKED_MISSING_REAL_GOLDEN: $caseId/$fileName"
        Assert-Condition (Test-Path -LiteralPath $metadataPath) "BLOCKED_MISSING_REAL_GOLDEN_METADATA: $caseId/$metadataName"

        $schemaValid = Test-Json -LiteralPath $resultPath -SchemaFile $schemaPath -ErrorAction Stop
        Assert-Condition $schemaValid "Schema validation failed: $caseId/$fileName"
        $resultText = [IO.File]::ReadAllText($resultPath)
        Assert-Condition ($resultText -notmatch "(?i)\b(?:NaN|Infinity|-Infinity)\b") "Non-JSON numeric value found: $caseId/$fileName"
        $result = $resultText | ConvertFrom-Json
        $metadata = [IO.File]::ReadAllText($metadataPath) | ConvertFrom-Json -DateKind String

        Assert-Condition ($result.schemaVersion -eq "pipesim-well-result/1") "Wrong schema version: $caseId/$fileName"
        Assert-Condition ($result.model_kind -eq $cases[$caseId]) "Wrong model kind: $caseId/$fileName"
        Assert-Condition ($result.runTask -eq $runTask) "Wrong run task: $caseId/$fileName"
        Assert-Condition (Test-Contract -RunTask $runTask -Result $result) "Wrong result semantics: $caseId/$fileName"
        Assert-Condition ($result.units.pressure.displayUnit -eq $null) "Pressure unit was guessed: $caseId/$fileName"
        Assert-Condition ($result.units.depth.displayUnit -eq $null) "Depth unit was guessed: $caseId/$fileName"
        Assert-Condition ($result.units.temperature.displayUnit -eq $null) "Temperature unit was guessed: $caseId/$fileName"
        Assert-Condition ($result.units.pressure.semantics -eq "unspecified") "Wrong pressure unit semantics: $caseId/$fileName"
        Assert-Condition ($result.units.depth.semantics -eq "unspecified") "Wrong depth unit semantics: $caseId/$fileName"
        Assert-Condition ($result.units.temperature.semantics -eq "unspecified") "Wrong temperature unit semantics: $caseId/$fileName"
        if ($caseId -eq "CSW_101") {
            Assert-Condition ($result.units.flow.displayUnit -eq $null) "Black-oil flow unit was guessed: $caseId/$fileName"
            Assert-Condition ($result.units.flow.semantics -eq "unspecified") "Wrong black-oil flow semantics: $caseId/$fileName"
        }
        else {
            Assert-Condition ($result.units.flow.displayUnit -eq "mmscf/d") "Basic-gas flow unit is missing: $caseId/$fileName"
            Assert-Condition ($result.units.flow.semantics -eq "standard_gas_volume_rate") "Wrong basic-gas flow semantics: $caseId/$fileName"
        }

        foreach ($point in @($result.ipr) + @($result.vlp)) {
            Assert-FiniteNumber $point.flow "$caseId/$fileName curve flow"
            Assert-FiniteNumber $point.pressure "$caseId/$fileName curve pressure"
        }
        $previousDepth = $null
        foreach ($point in @($result.profile)) {
            Assert-FiniteNumber $point.depth "$caseId/$fileName profile depth"
            Assert-FiniteNumber $point.pressure "$caseId/$fileName profile pressure"
            Assert-FiniteNumber $point.temperature "$caseId/$fileName profile temperature"
            Assert-Condition ([double]$point.depth -ge 0) "Negative normalized depth: $caseId/$fileName"
            if ($null -ne $previousDepth) {
                Assert-Condition ([double]$point.depth -ge $previousDepth) "Profile order changed: $caseId/$fileName"
            }
            $previousDepth = [double]$point.depth
        }

        Assert-Condition ($metadata.source.sha256Before -match "^[0-9a-f]{64}$") "Missing source SHA before capture: $caseId/$metadataName"
        Assert-Condition ($metadata.source.sha256After -eq $metadata.source.sha256Before) "Source changed during capture: $caseId/$metadataName"
        Assert-Condition ($metadata.study -eq "Study 1") "Wrong Study metadata: $caseId/$metadataName"
        Assert-Condition ($metadata.runType -eq $runTask) "Wrong run metadata: $caseId/$metadataName"
        Assert-Condition ($metadata.schemaVersion -eq $result.schemaVersion) "Schema metadata mismatch: $caseId/$metadataName"
        Assert-Condition ($metadata.avaloniaAdapter.revision -match "^[0-9a-f]{40}$") "Missing Avalonia revision: $caseId/$metadataName"
        Assert-Condition ($metadata.avaloniaAdapter.sha256 -match "^[0-9a-f]{64}$") "Missing Avalonia adapter SHA: $caseId/$metadataName"
        Assert-Condition (-not [string]::IsNullOrWhiteSpace($metadata.generatedAtUtc)) "Missing generation time: $caseId/$metadataName"
        Assert-Condition (-not [string]::IsNullOrWhiteSpace($metadata.sanitization)) "Missing sanitization note: $caseId/$metadataName"

        $generatedAt = [DateTimeOffset]::Parse($metadata.generatedAtUtc, [Globalization.CultureInfo]::InvariantCulture)
        Assert-Condition ($generatedAt.Offset -eq [TimeSpan]::Zero) "Generation time is not UTC: $caseId/$metadataName"
        if ($null -ne $previousGeneratedAt) {
            Assert-Condition ($generatedAt -gt $previousGeneratedAt) "Golden generation order is not strictly serial: $caseId/$metadataName"
        }
        $previousGeneratedAt = $generatedAt

        if ($null -eq $adapterRevision) {
            $adapterRevision = $metadata.avaloniaAdapter.revision
            $adapterSha = $metadata.avaloniaAdapter.sha256
        }
        Assert-Condition ($metadata.avaloniaAdapter.revision -eq $adapterRevision) "Avalonia revision differs within the Golden batch: $caseId/$metadataName"
        Assert-Condition ($metadata.avaloniaAdapter.sha256 -eq $adapterSha) "Avalonia adapter SHA differs within the Golden batch: $caseId/$metadataName"
        if (-not $sourceHashes.ContainsKey($caseId)) {
            $sourceHashes[$caseId] = $metadata.source.sha256Before
        }
        Assert-Condition ($metadata.source.sha256Before -eq $sourceHashes[$caseId]) "Source SHA differs within the Golden batch: $caseId/$metadataName"

        foreach ($text in @($resultText, [IO.File]::ReadAllText($metadataPath))) {
            Assert-Condition ($text -notmatch "(?i)[A-Z]:\\") "Absolute Windows path found in golden data: $caseId/$fileName"
            Assert-Condition ($text -notmatch "\\\\") "UNC or escaped Windows path found in golden data: $caseId/$fileName"
            Assert-Condition ($text -notmatch '(?i)["'':\s]/(?:home|users|opt|var|tmp|etc)/') "Absolute POSIX path found in golden data: $caseId/$fileName"
            Assert-Condition ($text -notmatch "(?i)\b(token|cookie|password|license)\b.{0,3}[:=]") "Sensitive field found in golden data: $caseId/$fileName"
        }
        $verified++
    }
}

$actualFiles = @(
    [IO.Directory]::GetFiles($GoldenRoot, "*", [IO.SearchOption]::AllDirectories) |
        ForEach-Object { [IO.Path]::GetRelativePath($GoldenRoot, $_).Replace('/', '\') }
)
$missingFiles = @($expectedFiles | Where-Object { $actualFiles -notcontains $_ })
$extraFiles = @($actualFiles | Where-Object { $expectedFiles -notcontains $_ })
Assert-Condition ($missingFiles.Count -eq 0) "Golden batch is missing expected files: $($missingFiles -join ', ')"
Assert-Condition ($extraFiles.Count -eq 0) "Golden batch contains unexpected files: $($extraFiles -join ', ')"
Assert-Condition ($actualFiles.Count -eq 12) "Golden batch must contain exactly twelve files"

if ($VerifyLocalSources) {
    if ([string]::IsNullOrWhiteSpace($AvaloniaRoot)) {
        $desktopRoot = Split-Path (Split-Path $repositoryRoot -Parent) -Parent
        $AvaloniaRoot = Join-Path $desktopRoot "Ava_desktop\Avalonia_oil"
    }
    $adapterPath = Join-Path $AvaloniaRoot "src\UnifiedConsole.Infrastructure\Simulators\Pipesim\ptk_worker.py"
    Assert-Condition (Test-Path -LiteralPath $adapterPath) "Current Avalonia adapter is unavailable"
    $currentRevision = (& git -C $AvaloniaRoot rev-parse HEAD).Trim()
    Assert-Condition ($LASTEXITCODE -eq 0 -and $currentRevision -eq $adapterRevision) "Golden batch does not match the current Avalonia revision"
    $currentAdapterSha = (Get-FileHash -Algorithm SHA256 -LiteralPath $adapterPath).Hash.ToLowerInvariant()
    Assert-Condition ($currentAdapterSha -eq $adapterSha) "Golden batch does not match the current Avalonia adapter"

    $modelFiles = @{
        CSW_101 = "CSW_101_Basic Oil Well.pips"
        CSW_102 = "CSW_102_Basic Gas Well.pips"
    }
    foreach ($caseId in $cases.Keys) {
        $modelPath = Join-Path $PipesimRoot "Case Studies\Well Models\$($modelFiles[$caseId])"
        Assert-Condition (Test-Path -LiteralPath $modelPath) "Current acceptance model is unavailable: $caseId"
        $currentSourceSha = (Get-FileHash -Algorithm SHA256 -LiteralPath $modelPath).Hash.ToLowerInvariant()
        Assert-Condition ($currentSourceSha -eq $sourceHashes[$caseId]) "Golden batch does not match the current acceptance model: $caseId"
    }
}

Write-Output "Verified $verified real PIPESIM golden results and metadata sidecars."
