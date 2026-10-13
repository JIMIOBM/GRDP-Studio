<# Replays a completed CC06-MW1 actual request and tests API rejection gates; not UI/native-coupling acceptance. #>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ApiBase,
    [Parameter(Mandatory)][string]$WorkerBase,
    [Parameter(Mandatory)][string]$CompletedEvidenceDirectory,
    [Parameter(Mandatory)][string]$EvidenceDirectory,
    [ValidateRange(30,1800)][int]$TimeoutSeconds = 600
)
$ErrorActionPreference = 'Stop'
foreach ($base in @($ApiBase,$WorkerBase)) {
    $uri = [uri]$base
    if (-not $uri.IsAbsoluteUri -or -not $uri.IsLoopback -or $uri.Scheme -ne 'http' -or $uri.UserInfo -or $uri.Query) { throw 'Local HTTP services required.' }
}
$parent = (Resolve-Path -LiteralPath $CompletedEvidenceDirectory).Path
$output = [IO.Path]::GetFullPath($EvidenceDirectory)
if (Test-Path -LiteralPath $output) { throw 'Use a fresh evidence directory.' }
foreach ($root in @($parent,[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')))) {
    if ($output.Equals($root,[StringComparison]::OrdinalIgnoreCase) -or $output.StartsWith($root.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Evidence must not be inside completed evidence or repository.' }
}
function Assert-Check([bool]$Condition,[string]$Code) { if (-not $Condition) { throw $Code } }
function Write-Json([string]$Name,$Value) {
    $bytes = [Text.Encoding]::UTF8.GetBytes(($Value | ConvertTo-Json -Depth 100))
    $file = [IO.File]::Open((Join-Path $output $Name),[IO.FileMode]::CreateNew)
    try { $file.Write($bytes,0,$bytes.Length) } finally { $file.Dispose() }
}
function Send-Api([string]$Method,[string]$Path,$Body=$null,[int]$Status=200) {
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method),$ApiBase.TrimEnd('/')+$Path)
    if ($null -ne $Body) { $request.Content = [Net.Http.StringContent]::new(($Body | ConvertTo-Json -Depth 30),[Text.Encoding]::UTF8,'application/json') }
    $response = $null
    try {
        $response = $http.SendAsync($request).GetAwaiter().GetResult()
        $json = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json -AsHashtable
        Assert-Check ([int]$response.StatusCode -eq $Status -and $json.code -eq $Status) ('API_STATUS:'+ [int]$response.StatusCode+':'+$json.code)
        return $json.data
    } finally { if ($response) { $response.Dispose() }; $request.Dispose() }
}
function Compare-Summary($Expected,$Actual) {
    Assert-Check ($Expected.series.Count -gt 0 -and $Expected.series.Count -eq $Actual.series.Count) 'SERIES_COUNT'
    $count = 0
    for ($i=0;$i -lt $Expected.series.Count;$i++) {
        $left=$Expected.series[$i]; $right=$Actual.series[$i]
        foreach ($field in @('keyword','objectName','unit')) { Assert-Check ($left[$field] -ceq $right[$field]) ('SERIES_IDENTITY:'+ $i+':'+$field) }
        Assert-Check ($left.points.Count -eq $right.points.Count -and $left.points.Count -gt 0) 'POINT_COUNT'
        for ($j=0;$j -lt $left.points.Count;$j++) {
            foreach ($field in @('timeDays','value')) {
                Assert-Check ([double]::IsFinite([double]$left.points[$j][$field]) -and [double]::IsFinite([double]$right.points[$j][$field]) -and $left.points[$j][$field] -eq $right.points[$j][$field]) ('REPLAY_NUMBER:'+ $i+':'+$j+':'+$field)
                $count++
            }
        }
    }
    return $count
}
$completed = Get-Content -LiteralPath (Join-Path $parent 'platform-summary.json') -Raw | ConvertFrom-Json -AsHashtable
Assert-Check ($completed.apiVerified -eq $true -and $completed.sourceUnchanged -eq $true -and $completed.schemaVersion -ceq 'cc06-mw1-platform-evidence/1') 'PARENT_EVIDENCE_UNVERIFIED'
$parameters = Get-Content -LiteralPath (Join-Path $parent 'frozen-group-request.json') -Raw | ConvertFrom-Json -AsHashtable
Assert-Check ($parameters.schemaVersion -ceq 'eclipse-schedule-parameters/6' -and $parameters.wells.Count -eq 2 -and $parameters.wells[0] -ceq 'G1' -and $parameters.wells[1] -ceq 'G2') 'FROZEN_REQUEST_IDENTITY'
$null = [IO.Directory]::CreateDirectory($output)
$http = [Net.Http.HttpClient]::new(); $http.Timeout = [TimeSpan]::FromSeconds(30)
$report = @{ schemaVersion='cc06-mw1-replay-evidence/1'; apiVerified=$false; manualUiVerified=$false; coupledSolveVerified=$false; rejections=@(); errors=@(); parentManifestSha256=(Get-FileHash -LiteralPath (Join-Path $parent 'platform-summary.json')).Hash }
try {
    $health = Invoke-RestMethod ($WorkerBase.TrimEnd('/')+'/api/health') -TimeoutSec 15
    Assert-Check ($health.idle -eq $true -and $null -eq $health.activeRunId) 'WORKER_BUSY'
    $original = Send-Api GET ('/runs/'+$completed.runIds[1])
    Assert-Check ($original.status -ceq 'SUCCEEDED' -and $original.resultContract -ceq 'VALID_FULL' -and $original.modelVersionId -eq $completed.versionId -and $original.parameters.baselineRunId -eq $parameters.baselineRunId) 'COMPLETED_RUN_IDENTITY'
    $historyPath='/model-versions/'+$completed.versionId+'/runs'
    $before = @(Send-Api GET $historyPath)
    foreach ($test in @('duplicate-well','injection-well','duplicate-date','missing-date','parameterized-baseline')) {
        $bad = $parameters | ConvertTo-Json -Depth 30 | ConvertFrom-Json -AsHashtable
        $expectedStatus = 400
        switch ($test) {
            'duplicate-well' { $bad.wells=@('G1','G1') }
            'injection-well' { $bad.wells=@('G1','D1') }
            'duplicate-date' { $bad.events[1].date=$bad.events[0].date }
            'missing-date' { $bad.events[0].date='1970-01-16' }
            'parameterized-baseline' { $bad.baselineRunId=$original.id; $expectedStatus=409 }
        }
        $null = Send-Api POST $historyPath @{runType='eclipse';study=$null;parameters=$bad} -Status $expectedStatus
        $report.rejections += @{test=$test;httpStatus=$expectedStatus;rejected=$true}
    }
    $after = @(Send-Api GET $historyPath)
    Assert-Check ($after.Count -eq $before.Count -and (@($after.id | Sort-Object) -join ',') -ceq (@($before.id | Sort-Object) -join ',')) 'REJECTED_REQUEST_CREATED_RUN'
    Write-Json 'frozen-replay-request.json' @{runType='eclipse';study=$null;parameters=$parameters}
    $accepted = Send-Api POST $historyPath @{runType='eclipse';study=$null;parameters=$parameters} -Status 201
    $report.replayRunId=$accepted.id
    $timer=[Diagnostics.Stopwatch]::StartNew()
    do {
        $run = Send-Api GET ('/runs/'+$accepted.id)
        if ($run.status -cin @('SUCCEEDED','PARTIAL_SUCCEEDED','FAILED','CANCELLED','TIMED_OUT')) { break }
        Assert-Check ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds) 'REPLAY_OBSERVATION_TIMEOUT'
        Start-Sleep -Milliseconds 750
    } while ($true)
    Write-Json 'replay-run.json' $run
    Assert-Check ($run.status -ceq 'SUCCEEDED' -and $run.resultContract -ceq 'VALID_FULL' -and $run.modelVersionId -eq $original.modelVersionId -and $run.parameters.wells.Count -eq 2 -and $run.cleanup.processTreeExitConfirmed -and $run.cleanup.inputDeleted -and $run.cleanup.workDirectoryDeleted) 'REPLAY_NOT_FULL_OR_CLEAN'
    $report.numericPaths = Compare-Summary $original.result.summary $run.result.summary
    $report.maximumAbsoluteDelta=0
    $report.apiVerified=$true
} catch { $report.errors += $_.Exception.Message; Write-Host ('BLOCKED: '+$_.Exception.Message) }
finally {
    try {
        $afterHealth=Invoke-RestMethod ($WorkerBase.TrimEnd('/')+'/api/health') -TimeoutSec 15
        Write-Json 'worker-after.json' $afterHealth
        Assert-Check ($afterHealth.idle -eq $true -and $null -eq $afterHealth.activeRunId -and $afterHealth.generationId -ceq $health.generationId) 'WORKER_STATE_OR_GENERATION_CHANGED'
    } catch { $report.errors += $_.Exception.Message; $report.apiVerified=$false }
    Write-Json 'replay-summary.json' $report
    $http.Dispose()
}
if (-not $report.apiVerified) { exit 2 }
Write-Host 'Real rejected requests created no Runs; full Summary replay is identical.'
