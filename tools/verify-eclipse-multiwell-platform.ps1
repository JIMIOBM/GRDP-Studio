<# CC06-MW1 real API -> Worker -> ECLIPSE verification. No synthetic solver results or UI pass. #>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$OfficialDirectory,
    [Parameter(Mandatory)][string]$EvidenceDirectory,
    [Parameter(Mandatory)][string]$ApiBase,
    [Parameter(Mandatory)][string]$WorkerBase,
    [switch]$UnformattedOutput,
    [ValidateRange(30,1800)][int]$TimeoutSeconds = 600
)
$ErrorActionPreference = 'Stop'
$source = (Resolve-Path -LiteralPath $OfficialDirectory).Path
$output = [IO.Path]::GetFullPath($EvidenceDirectory)
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
foreach ($root in @($source, $repo)) {
    if ($output.Equals($root, [StringComparison]::OrdinalIgnoreCase) -or
        $output.StartsWith($root.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Evidence must be outside source and repository.' }
}
foreach ($base in @($ApiBase, $WorkerBase)) {
    $uri = [uri]$base
    if (-not $uri.IsAbsoluteUri -or -not $uri.IsLoopback -or $uri.Scheme -ne 'http' -or $uri.UserInfo -or $uri.Query) { throw 'Local HTTP services without credentials required.' }
}
if (Test-Path -LiteralPath $output) { throw 'Use a new evidence directory; never overwrite an earlier attempt.' }
$null = [IO.Directory]::CreateDirectory($output)
$http = [Net.Http.HttpClient]::new()
$http.Timeout = [TimeSpan]::FromSeconds(30)
function Assert-Check([bool]$Condition, [string]$Code) { if (-not $Condition) { throw $Code } }
function Write-Json([string]$Name, $Value) {
    $bytes = [Text.Encoding]::UTF8.GetBytes(($Value | ConvertTo-Json -Depth 100))
    $file = [IO.File]::Open((Join-Path $output $Name), [IO.FileMode]::CreateNew)
    try { $file.Write($bytes, 0, $bytes.Length) } finally { $file.Dispose() }
}
function Send-Api([string]$Method, [string]$Path, $Body = $null, $Content = $null, [int]$Status = 200) {
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), ($ApiBase.TrimEnd('/') + $Path))
    if ($null -ne $Content) { $request.Content = $Content }
    elseif ($null -ne $Body) { $request.Content = [Net.Http.StringContent]::new(($Body | ConvertTo-Json -Depth 30), [Text.Encoding]::UTF8, 'application/json') }
    $response = $null
    try {
        $response = $http.SendAsync($request).GetAwaiter().GetResult()
        $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $json = $text | ConvertFrom-Json -AsHashtable
        Assert-Check ([int]$response.StatusCode -eq $Status -and $json.code -eq $Status) ('API_STATUS:' + [int]$response.StatusCode + ':' + $json.code + ':' + $json.msg)
        return $json.data
    } finally { if ($null -ne $response) { $response.Dispose() }; $request.Dispose() }
}
function Wait-Run([long]$RunId) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        $run = Send-Api GET ('/runs/' + $RunId)
        if ($run.status -cin @('SUCCEEDED','PARTIAL_SUCCEEDED','FAILED','CANCELLED','TIMED_OUT')) { return $run }
        Assert-Check ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds) ('RUN_TIMEOUT:' + $RunId)
        Start-Sleep -Milliseconds 750
    } while ($true)
}
function Assert-Run($Run, [long]$VersionId) {
    Assert-Check ($Run.modelVersionId -eq $VersionId -and $Run.runType -ceq 'eclipse' -and $Run.status -ceq 'SUCCEEDED' -and $Run.resultContract -ceq 'VALID_FULL' -and $null -eq $Run.error -and -not $Run.resultExpired) ('RUN_NOT_FULL:' + $Run.id + ':' + $Run.status)
    Assert-Check ($Run.cleanup.processTreeExitConfirmed -and $Run.cleanup.inputDeleted -and $Run.cleanup.workDirectoryDeleted) 'CLEANUP_UNCONFIRMED'
    Assert-Check ($Run.result.eclEnd.problems -eq 0 -and $Run.result.eclEnd.errors -eq 0 -and $Run.result.eclEnd.bugs -eq 0) 'SOLVER_DIAGNOSTICS'
}
function Download-Artifacts($Run) {
    $directory = Join-Path $output ('run-' + $Run.id + '-artifacts')
    $null = [IO.Directory]::CreateDirectory($directory)
    $downloads = @()
    foreach ($artifact in $Run.artifacts) {
        Assert-Check ([IO.Path]::GetFileName($artifact.name) -ceq $artifact.name) 'UNSAFE_ARTIFACT_NAME'
        $response = $http.GetAsync($ApiBase.TrimEnd('/') + '/runs/' + $Run.id + '/artifacts/' + $artifact.id + '/download').GetAwaiter().GetResult()
        try {
            Assert-Check ([int]$response.StatusCode -eq 200) 'ARTIFACT_HTTP_STATUS'
            $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
            Assert-Check ($bytes.Length -eq $artifact.sizeBytes) 'ARTIFACT_LENGTH'
            $path = Join-Path $directory $artifact.name
            $file = [IO.File]::Open($path, [IO.FileMode]::CreateNew)
            try { $file.Write($bytes, 0, $bytes.Length) } finally { $file.Dispose() }
            Assert-Check ((Get-FileHash -LiteralPath $path).Hash -ieq $artifact.sha256) 'ARTIFACT_SHA'
            $downloads += @{ id = $artifact.id; name = $artifact.name; sha256 = $artifact.sha256 }
        } finally { $response.Dispose() }
    }
    # ECLIPSE's current artifact contract has no request.json (PIPESIM's does).
    # Submit conditions are preserved in frozen-group-request.json and the actual Run.
    Assert-Check ($downloads.name -ccontains 'normalized-result.json' -and $downloads.name -ccontains 'run.log' -and $downloads.name -ccontains 'manifest.json') 'ECLIPSE_ARTIFACT_MISSING'
    return ,$downloads
}
$sourceHashes = @()
$report = @{ schemaVersion = 'cc06-mw1-platform-evidence/1'; apiVerified = $false; manualUiVerified = $false; coupledSolveVerified = $false; runIds = @(); errors = @(); sourceUnchanged = $false; outputOnlyVariant = [bool]$UnformattedOutput }
try {
    $health = Invoke-RestMethod -Uri ($WorkerBase.TrimEnd('/') + '/api/health') -TimeoutSec 15
    Assert-Check ($health.idle -eq $true -and $null -eq $health.activeRunId) 'WORKER_BUSY'
    Write-Json 'worker-before.json' $health
    $capability = Send-Api GET '/capabilities'
    Assert-Check ($capability.eclipse100.status -ceq 'AVAILABLE') 'ECLIPSE_UNAVAILABLE'
    $files = @('EX5.DATA','BASE.SCH','EX5.GRDECL')
    $sourceHashes = @($files | ForEach-Object {
        $path = Join-Path $source $_
        Assert-Check (-not ((Get-Item -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReparsePoint)) 'SOURCE_LINK'
        @{ name = $_; sha256 = (Get-FileHash -LiteralPath $path).Hash }
    })
    Write-Json 'official-source-manifest.json' $sourceHashes
    $zipName = if ($UnformattedOutput) { 'official-ex5-binary-output-multiwell.zip' } else { 'official-ex5-multiwell.zip' }
    $zipPath = Join-Path $output $zipName
    $zip = [IO.Compression.ZipFile]::Open($zipPath, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($file in $files) {
            if ($UnformattedOutput -and $file -ceq 'EX5.DATA') {
                # Official Reference Manual FMTOUT: only output encoding, not reservoir physics.
                $original = [IO.File]::ReadAllText((Join-Path $source $file))
                $pattern = '(?m)^FMTOUT\r?$'
                Assert-Check ([regex]::Matches($original, $pattern).Count -eq 1) 'FMTOUT_NOT_UNIQUE'
                $changed = [regex]::Replace($original, $pattern, '-- CC06 verification: default unformatted output; physics unchanged')
                $entry = $zip.CreateEntry($file)
                $bytes = [Text.Encoding]::UTF8.GetBytes($changed)
                $stream = $entry.Open()
                try { $stream.Write($bytes,0,$bytes.Length) } finally { $stream.Dispose() }
                Write-Json 'output-only-transformation.json' @{ keyword = 'FMTOUT'; occurrences = 1; replacement = 'comment'; physicalKeywordsChanged = 0; entry = $file; entrySha256 = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)) }
            } else { $null = [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $source $file), $file) }
        }
    }
    finally { $zip.Dispose() }
    $project = Send-Api POST '/projects' @{ name = ('CC06-MW1-' + [IO.Path]::GetFileName($output)); description = 'Isolated real ECLIPSE group scheduling verification.' }
    $report.projectId = $project.id
    $form = [Net.Http.MultipartFormDataContent]::new()
    $form.Add([Net.Http.StreamContent]::new([IO.File]::OpenRead($zipPath)), 'file', [IO.Path]::GetFileName($zipPath))
    $form.Add([Net.Http.StringContent]::new('EX5.DATA'), 'mainFile')
    $null = Send-Api POST ('/projects/' + $project.id + '/models') -Content $form
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        $project = Send-Api GET ('/projects/' + $report.projectId)
        $versions = @($project.models.versions)
        Assert-Check ($versions.Count -eq 1) 'VERSION_IDENTITY_AMBIGUOUS'
        $version = $versions[0]
        if ($version.status -ceq 'READY') { break }
        Assert-Check ($version.status -cnotin @('INVALID','FAILED') -and $timer.Elapsed.TotalSeconds -lt $TimeoutSeconds) ('VERSION_NOT_READY:' + $version.status)
        Start-Sleep -Milliseconds 750
    } while ($true)
    $report.versionId = $version.id
    Write-Json 'ready-version.json' $version
    $accepted = Send-Api POST ('/model-versions/' + $version.id + '/runs') @{ runType = 'eclipse'; study = $null; parameters = $null } -Status 201
    $report.runIds += $accepted.id
    $baseline = Wait-Run $accepted.id
    Write-Json 'baseline-run.json' $baseline
    Assert-Run $baseline $version.id
    Assert-Check ($baseline.result.summary.series.Count -gt 0) 'SUMMARY_FORMAT_UNSUPPORTED_NO_CURVES'
    $report.baselineDownloads = Download-Artifacts $baseline
    $parameters = @{ schemaVersion = 'eclipse-schedule-parameters/6'; baselineRunId = $baseline.id; wells = @('G1','G2');
        events = @(@{ date = '1970-01-15'; status = 'SHUT' }, @{ date = '1970-02-01'; status = 'OPEN' }) }
    Write-Json 'frozen-group-request.json' $parameters
    $derived = Send-Api POST ('/model-versions/' + $version.id + '/runs') @{ runType = 'eclipse'; study = $null; parameters = $parameters } -Status 201
    $report.runIds += $derived.id
    $run = Wait-Run $derived.id
    Write-Json 'group-run.json' $run
    Assert-Run $run $version.id
    Assert-Check ($run.parameters.wells.Count -eq 2 -and $run.parameters.wells[0] -ceq 'G1' -and $run.parameters.wells[1] -ceq 'G2') 'GROUP_REQUEST_IDENTITY'
    $report.groupDownloads = Download-Artifacts $run
    $report.comparisons = @()
    foreach ($well in $parameters.wells) {
        $baseSeries = @($baseline.result.summary.series | Where-Object { $_.keyword -ceq 'WOPR' -and $_.objectName -ceq $well })
        $groupSeries = @($run.result.summary.series | Where-Object { $_.keyword -ceq 'WOPR' -and $_.objectName -ceq $well })
        Assert-Check ($baseSeries.Count -eq 1 -and $groupSeries.Count -eq 1 -and $baseSeries[0].unit -and $baseSeries[0].unit -ceq $groupSeries[0].unit) ('WOPR_IDENTITY_UNIT:' + $well)
        $points = @{}
        foreach ($point in $baseSeries[0].points) { Assert-Check (-not $points.ContainsKey($point.timeDays)) 'DUPLICATE_TIME'; $points[$point.timeDays] = $point.value }
        $rows = @($groupSeries[0].points | Where-Object { $points.ContainsKey($_.timeDays) } | ForEach-Object {
            @{ well = $well; unit = $groupSeries[0].unit; timeDays = $_.timeDays; baseline = $points[$_.timeDays]; current = $_.value; difference = ($_.value - $points[$_.timeDays]) }
        })
        $changed = @($rows | Where-Object { [Math]::Abs($_.difference) -gt [Math]::Max(1e-9, [Math]::Abs($_.baseline) * 1e-8) })
        Assert-Check ($rows.Count -gt 0 -and $changed.Count -gt 0) ('NO_NUMERIC_WELL_EFFECT:' + $well)
        $report.comparisons += @{ well = $well; unit = $groupSeries[0].unit; alignedPoints = $rows.Count; changedPoints = $changed.Count }
        Write-Json ($well + '-aligned-wopr.json') $rows
    }
    $report.apiVerified = $true
} catch { $report.errors += $_.Exception.Message; Write-Host ('BLOCKED: ' + $_.Exception.Message) }
finally {
    $report.sourceUnchanged = $sourceHashes.Count -eq 3
    foreach ($item in $sourceHashes) { if ((Get-FileHash -LiteralPath (Join-Path $source $item.name)).Hash -cne $item.sha256) { $report.sourceUnchanged = $false } }
    try { Write-Json 'worker-after.json' (Invoke-RestMethod -Uri ($WorkerBase.TrimEnd('/') + '/api/health') -TimeoutSec 15) }
    catch { $report.errors += 'WORKER_HEALTH_UNAVAILABLE'; $report.apiVerified = $false }
    $report.apiVerified = $report.apiVerified -and $report.sourceUnchanged -and $report.errors.Count -eq 0
    Write-Json 'platform-summary.json' $report
    $http.Dispose()
}
if (-not $report.apiVerified) { exit 2 }
Write-Host 'Real ECLIPSE group calculation and per-well WOPR changes verified. Manual UI acceptance remains separate.'
