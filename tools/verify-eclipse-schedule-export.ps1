<# Downloads the actual /6 schedule ZIP, verifies its inputs, and reimports it for a real parameter-free calculation. #>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ApiBase,
    [Parameter(Mandatory)][string]$WorkerBase,
    [Parameter(Mandatory)][ValidateRange(1,2147483647)][long]$GroupRunId,
    [Parameter(Mandatory)][string]$OfficialDirectory,
    [Parameter(Mandatory)][string]$EvidenceDirectory,
    [ValidateRange(30,1800)][int]$TimeoutSeconds = 600
)
$ErrorActionPreference = 'Stop'
foreach ($base in @($ApiBase,$WorkerBase)) {
    $uri = [uri]$base
    if (-not $uri.IsAbsoluteUri -or -not $uri.IsLoopback -or $uri.Scheme -ne 'http' -or $uri.UserInfo -or $uri.Query -or $uri.Fragment) { throw 'Local HTTP services without credentials required.' }
}
$source = (Resolve-Path -LiteralPath $OfficialDirectory).Path
$output = [IO.Path]::GetFullPath($EvidenceDirectory)
foreach ($root in @($source,[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')))) {
    if ($output.Equals($root,[StringComparison]::OrdinalIgnoreCase) -or $output.StartsWith($root.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Evidence must be outside official source and repository.' }
}
if (Test-Path -LiteralPath $output) { throw 'Use a fresh evidence directory; do not rewrite an earlier failure.' }
$null = [IO.Directory]::CreateDirectory($output)
$http = [Net.Http.HttpClient]::new(); $http.Timeout = [TimeSpan]::FromSeconds(30)
$files = @('EX5.DATA','BASE.SCH','EX5.GRDECL')
$before = @()
$report = @{schemaVersion='cc06-edit1a-export-evidence/1';apiVerified=$false;manualUiVerified=$false;coupledSolveVerified=$false;sourceRunId=$GroupRunId;errors=@()}
function Assert-Check([bool]$Condition,[string]$Code) { if (-not $Condition) { throw $Code } }
function Write-New([string]$Name,[byte[]]$Bytes) {
    $stream=[IO.File]::Open((Join-Path $output $Name),[IO.FileMode]::CreateNew)
    try { $stream.Write($Bytes,0,$Bytes.Length) } finally { $stream.Dispose() }
}
function Write-Json([string]$Name,$Value) { Write-New $Name ([Text.Encoding]::UTF8.GetBytes(($Value | ConvertTo-Json -Depth 100))) }
function Send-Api([string]$Method,[string]$Path,$Body=$null,$Content=$null,[int]$Status=200) {
    $request=[Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method),$ApiBase.TrimEnd('/')+$Path)
    if ($Content) { $request.Content=$Content }
    elseif ($null -ne $Body) { $request.Content=[Net.Http.StringContent]::new(($Body | ConvertTo-Json -Depth 30),[Text.Encoding]::UTF8,'application/json') }
    $response=$null
    try {
        $response=$http.SendAsync($request).GetAwaiter().GetResult()
        $json=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json -AsHashtable
        Assert-Check ([int]$response.StatusCode -eq $Status -and $json.code -eq $Status) ('API_STATUS:'+ [int]$response.StatusCode+':'+$json.code)
        return $json.data
    } finally { if ($response) { $response.Dispose() }; $request.Dispose() }
}
function Assert-Run($Run) {
    Assert-Check ($Run.runType -ceq 'eclipse' -and $Run.status -ceq 'SUCCEEDED' -and $Run.resultContract -ceq 'VALID_FULL' -and $null -eq $Run.error -and -not $Run.resultExpired) 'RUN_NOT_FULL'
    Assert-Check ($Run.result.eclEnd.problems -eq 0 -and $Run.result.eclEnd.errors -eq 0 -and $Run.result.eclEnd.bugs -eq 0) 'SOLVER_DIAGNOSTICS'
    Assert-Check ($Run.cleanup.processTreeExitConfirmed -and $Run.cleanup.inputDeleted -and $Run.cleanup.workDirectoryDeleted) 'CLEANUP_UNCONFIRMED'
}
function Compare-Summary($Left,$Right) {
    Assert-Check ($Left.series.Count -eq 76 -and $Right.series.Count -eq 76) 'SERIES_COUNT'
    $count=0
    for ($i=0;$i -lt $Left.series.Count;$i++) {
        $a=$Left.series[$i];$b=$Right.series[$i]
        foreach ($field in @('keyword','objectName','unit')) { Assert-Check ($a[$field] -ceq $b[$field]) ('SERIES_IDENTITY:'+ $i+':'+$field) }
        Assert-Check ($a.points.Count -eq 81 -and $b.points.Count -eq 81) 'POINT_COUNT'
        for ($j=0;$j -lt $a.points.Count;$j++) {
            foreach ($field in @('timeDays','value')) {
                Assert-Check ([double]::IsFinite([double]$a.points[$j][$field]) -and [double]::IsFinite([double]$b.points[$j][$field]) -and $a.points[$j][$field] -eq $b.points[$j][$field]) ('REIMPORT_NUMBER:'+ $i+':'+$j+':'+$field)
                $count++
            }
        }
    }
    return $count
}
try {
    $health=Invoke-RestMethod ($WorkerBase.TrimEnd('/')+'/api/health') -TimeoutSec 15
    Assert-Check ($health.idle -eq $true -and $null -eq $health.activeRunId) 'WORKER_BUSY'
    Write-Json 'worker-before.json' $health
    $before=@($files | ForEach-Object {
        $path=Join-Path $source $_
        Assert-Check (-not ((Get-Item -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReparsePoint)) 'SOURCE_LINK'
        @{name=$_;sha256=(Get-FileHash -LiteralPath $path).Hash}
    })
    Write-Json 'source-manifest.json' $before
    $group=Send-Api GET ('/runs/'+$GroupRunId)
    Assert-Run $group
    $parameters=$group.parameters
    Assert-Check ($parameters.schemaVersion -ceq 'eclipse-schedule-parameters/6' -and $parameters.wells.Count -eq 2 -and $parameters.wells[0] -ceq 'G1' -and $parameters.wells[1] -ceq 'G2' -and $parameters.events.Count -eq 2) 'GROUP_IDENTITY'
    Assert-Check ($parameters.events[0].date -ceq '1970-01-15' -and $parameters.events[0].status -ceq 'SHUT' -and $parameters.events[1].date -ceq '1970-02-01' -and $parameters.events[1].status -ceq 'OPEN') 'GROUP_EVENTS'
    Write-Json 'source-group-run.json' $group
    $artifacts=@($group.artifacts | Where-Object { $_.name -ceq 'eclipse-schedule-applied-package.zip' -and $_.contentType -ceq 'application/zip' })
    Assert-Check ($artifacts.Count -eq 1) 'SCHEDULE_ZIP_NOT_PUBLISHED'
    $artifact=$artifacts[0]
    $response=$http.GetAsync($ApiBase.TrimEnd('/')+'/runs/'+$GroupRunId+'/artifacts/'+$artifact.id+'/download').GetAwaiter().GetResult()
    try {
        Assert-Check ([int]$response.StatusCode -eq 200) 'DOWNLOAD_STATUS'
        $bytes=$response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        Assert-Check ($bytes.Length -eq $artifact.sizeBytes -and [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)) -ieq $artifact.sha256) 'DOWNLOAD_BYTES_SHA'
        Write-New 'eclipse-schedule-applied-package.zip' $bytes
    } finally { $response.Dispose() }
    $zipPath=Join-Path $output 'eclipse-schedule-applied-package.zip'
    $archive=[IO.Compression.ZipFile]::OpenRead($zipPath)
    try {
        Assert-Check ($archive.Entries.Count -eq 3 -and (@($archive.Entries.FullName | Sort-Object) -join '|') -ceq (@($files | Sort-Object) -join '|')) 'ZIP_INPUTS'
        foreach ($entry in $archive.Entries) {
            Assert-Check ($entry.Length -gt 0 -and $entry.Length -le 64MB) 'ENTRY_SIZE'
            $stream=$entry.Open();$memory=[IO.MemoryStream]::new()
            try { $stream.CopyTo($memory);$entryBytes=$memory.ToArray() } finally { $memory.Dispose();$stream.Dispose() }
            $original=[IO.File]::ReadAllBytes((Join-Path $source $entry.FullName))
            if ($entry.FullName -ceq 'BASE.SCH') {
                $encoding=[Text.UTF8Encoding]::new($false,$true)
                $originalText=$encoding.GetString($original);$text=$encoding.GetString($entryBytes)
                $newline=if ($originalText.Contains("`r`n")) { "`r`n" } elseif ($originalText.Contains("`n")) { "`n" } else { "`r" }
                foreach ($status in @('SHUT','OPEN')) {
                    $fragment=$newline+(@('-- GRDP-Studio controlled final well-state event; original schedule records are retained.','WELOPEN',"       'G1' '$status' /","       'G2' '$status' /",'/') -join $newline)+$newline
                    Assert-Check ([regex]::Matches($text,[regex]::Escape($fragment)).Count -eq 1) ('EXACT_EVENT_FRAGMENT:'+ $status)
                    $text=$text.Replace($fragment,'')
                }
                $entryBytes=$encoding.GetBytes($text)
            }
            Assert-Check ([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($entryBytes)) -ceq [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($original))) ('UNCHANGED_INPUT:'+ $entry.FullName)
        }
    } finally { $archive.Dispose() }
    $report.packageVerified=$true;$report.packageSha256=$artifact.sha256
    $project=Send-Api POST '/projects' @{name=('CC06-EDIT1A-'+[IO.Path]::GetFileName($output));description=('Reimport actual schedule Artifact from Run '+$GroupRunId+'; no parameters applied.')}
    $report.projectId=$project.id
    $form=[Net.Http.MultipartFormDataContent]::new()
    $form.Add([Net.Http.StreamContent]::new([IO.File]::OpenRead($zipPath)),'file','eclipse-schedule-applied-package.zip')
    $form.Add([Net.Http.StringContent]::new('EX5.DATA'),'mainFile')
    $null=Send-Api POST ('/projects/'+$project.id+'/models') -Content $form
    $timer=[Diagnostics.Stopwatch]::StartNew()
    do {
        $project=Send-Api GET ('/projects/'+$report.projectId)
        $versions=@($project.models.versions);Assert-Check ($versions.Count -eq 1) 'VERSION_COUNT'
        $version=$versions[0]
        if ($version.status -ceq 'READY') { break }
        Assert-Check ($version.status -cnotin @('INVALID','FAILED') -and $timer.Elapsed.TotalSeconds -lt $TimeoutSeconds) ('VERSION_NOT_READY:'+ $version.status)
        Start-Sleep -Milliseconds 750
    } while ($true)
    $report.versionId=$version.id;Write-Json 'reimported-version.json' $version
    $request=@{runType='eclipse';study=$null;parameters=$null};Write-Json 'frozen-reimport-request.json' $request
    $accepted=Send-Api POST ('/model-versions/'+$version.id+'/runs') $request -Status 201
    $report.reimportRunId=$accepted.id;$timer.Restart()
    do {
        $run=Send-Api GET ('/runs/'+$accepted.id)
        if ($run.status -cin @('SUCCEEDED','PARTIAL_SUCCEEDED','FAILED','CANCELLED','TIMED_OUT')) { break }
        Assert-Check ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds) 'RUN_OBSERVATION_TIMEOUT'
        Start-Sleep -Milliseconds 750
    } while ($true)
    Write-Json 'reimport-run.json' $run;Assert-Run $run
    Assert-Check ($run.modelVersionId -eq $version.id -and $null -eq $run.parameters) 'PARAMETER_FREE_REIMPORT'
    $report.numericPaths=Compare-Summary $group.result.summary $run.result.summary
    $report.maximumAbsoluteDelta=0;$report.apiVerified=$true
} catch { $report.errors+=$_.Exception.Message;Write-Host ('FAILED: '+$_.Exception.Message) }
finally {
    $report.sourceUnchanged=$before.Count -eq 3
    foreach ($item in $before) { if ((Get-FileHash -LiteralPath (Join-Path $source $item.name)).Hash -cne $item.sha256) { $report.sourceUnchanged=$false } }
    try {
        $afterHealth=Invoke-RestMethod ($WorkerBase.TrimEnd('/')+'/api/health') -TimeoutSec 15
        Write-Json 'worker-after.json' $afterHealth
        Assert-Check ($afterHealth.idle -eq $true -and $null -eq $afterHealth.activeRunId -and $afterHealth.generationId -ceq $health.generationId) 'WORKER_STATE_CHANGED'
    } catch { $report.errors+=$_.Exception.Message;$report.apiVerified=$false }
    $report.apiVerified=$report.apiVerified -and $report.sourceUnchanged -and $report.errors.Count -eq 0
    Write-Json 'export-summary.json' $report;$http.Dispose()
}
if (-not $report.apiVerified) { exit 2 }
Write-Host 'Actual schedule ZIP reimported without parameters; every Summary value matches exactly.'
