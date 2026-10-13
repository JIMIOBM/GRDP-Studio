param(
    [Parameter(Mandatory)][string]$SourceModel,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [string]$ApiBase = 'http://127.0.0.1:8080/software-integration',
    [double]$TargetDiameter = 3.4,
    [int]$TimeoutSeconds = 660
)
$ErrorActionPreference = 'Stop'
if (-not [double]::IsFinite($TargetDiameter) -or $TargetDiameter -le 0 -or $TargetDiameter -gt 1000) { throw 'Target diameter is outside the supported finite range' }
if (-not (Test-Path -LiteralPath $SourceModel -PathType Leaf)) { throw 'Source model is missing' }
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Use a new output directory; existing data will not be overwritten' }
$output = New-Item -ItemType Directory -Path $OutputDirectory
$sourceHash = (Get-FileHash -LiteralPath $SourceModel -Algorithm SHA256).Hash
function Api($Method, $Path, $Body) {
    $arguments = @{ Method=$Method; Uri="$ApiBase/$Path" }
    if ($null -ne $Body) { $arguments.ContentType='application/json'; $arguments.Body=($Body | ConvertTo-Json -Depth 20 -Compress) }
    $response = Invoke-RestMethod @arguments
    if ($response.code -notin @(0,200,201,202)) { throw "API rejected $Path : $($response | ConvertTo-Json -Compress)" }
    return $response.data
}
function WaitVersion($ProjectId, $VersionId) {
    $limit=(Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $detail=Api GET "projects/$ProjectId" $null
        $version=@($detail.models | ForEach-Object { $_.versions } | Where-Object id -eq $VersionId)[0]
        if ($version.status -eq 'READY') { return $version }
        if ($version.status -in @('INVALID','FAILED')) { throw ($version | ConvertTo-Json -Depth 12) }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $limit)
    throw 'Model validation timed out'
}
function WaitRun($RunId) {
    $limit=(Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $run=Api GET "runs/$RunId" $null
        if ($run.status -eq 'SUCCEEDED' -and $run.resultContract -eq 'VALID_FULL') { return $run }
        if ($run.status -in @('FAILED','PARTIAL_SUCCEEDED','CANCELLED','TIMED_OUT','WORKER_LOST')) { throw ($run | ConvertTo-Json -Depth 15) }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $limit)
    throw "Run $RunId timed out; do not submit it again"
}
function Upload($ProjectId, $Path) {
    # Explicit read-only sharing: official installation models may be read-only.
    $client=[System.Net.Http.HttpClient]::new()
    $multipart=[System.Net.Http.MultipartFormDataContent]::new()
    $stream=[System.IO.File]::Open($Path,[System.IO.FileMode]::Open,[System.IO.FileAccess]::Read,[System.IO.FileShare]::Read)
    try {
        $multipart.Add([System.Net.Http.StreamContent]::new($stream),'file',[System.IO.Path]::GetFileName($Path))
        $http=$client.PostAsync("$ApiBase/projects/$ProjectId/models",$multipart).GetAwaiter().GetResult()
        $http.EnsureSuccessStatusCode() | Out-Null
        $response=$http.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
    } finally { $multipart.Dispose(); $stream.Dispose(); $client.Dispose() }
    if ($response.code -notin @(0,200,201,202)) { throw 'Upload rejected' }
    return @($response.data.models | ForEach-Object { $_.versions } | Sort-Object id -Descending)[0]
}
$project=Api POST 'projects' @{name=('P101 tubing acceptance '+(Get-Date -Format 'yyyyMMdd-HHmmss'));description='Official P101 platform verification; generated test project retained for inspection'}
Write-Output "Project $($project.id): uploading official source"
$uploaded=Upload $project.id $SourceModel
$version=WaitVersion $project.id $uploaded.id
if ($version.inspection.schemaVersion -ne 'pipesim-well-inspection/8') { throw 'Expected current inspection contract' }
$wells=@($version.inspection.wellContexts)
if ($wells.Count -ne 1) { throw 'This slice supports a single well only' }
$tubings=@($wells[0].tubingComponents)
if ($tubings.Count -ne 1) { throw 'Choose a model with exactly one inspected tubing for this acceptance' }
$tubing=$tubings[0]
$study=@($version.studies)[0]
Write-Output "Version $($version.id): running baseline Profile"
$accepted=Api POST "model-versions/$($version.id)/runs" @{study=$study;runType='profile';parameters=$null}
$baseline=WaitRun $accepted.id
$parameters=@{schemaVersion='pipesim-well-tubing-diameter-parameters/1';baselineRunId=$baseline.id;wellContext=$wells[0].context;tubingContext=$tubing.context;originalInnerDiameter=$tubing.innerDiameter;unit=$tubing.unit;targetInnerDiameter=$TargetDiameter}
Write-Output "Baseline $($baseline.id): creating isolated tubing edit"
$accepted=Api POST "model-versions/$($version.id)/runs" @{study=$study;runType='profile';parameters=$parameters}
$derived=WaitRun $accepted.id
$artifact=@($derived.artifacts | Where-Object name -eq 'pipesim-well-tubing-applied.pips')[0]
if ($null -eq $artifact) { throw 'Derived model Artifact is missing' }
$download=Join-Path $output.FullName 'pipesim-well-tubing-applied.pips'
Invoke-WebRequest -Uri "$ApiBase/runs/$($derived.id)/artifacts/$($artifact.id)/download" -OutFile $download
if ((Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash -ine $artifact.sha256) { throw 'Downloaded Artifact SHA mismatch' }
Write-Output "Derived run $($derived.id): downloaded through business API, reuploading"
$reuploaded=Upload $project.id $download
$reopened=WaitVersion $project.id $reuploaded.id
$readback=@($reopened.inspection.wellContexts[0].tubingComponents)[0]
if ($readback.context -ne $tubing.context -or $readback.unit -ne $tubing.unit -or [Math]::Abs($readback.innerDiameter-$TargetDiameter) -gt 1e-9) { throw 'Reuploaded model readback mismatch' }
$accepted=Api POST "model-versions/$($reopened.id)/runs" @{study=@($reopened.studies)[0];runType='profile';parameters=$null}
$rerun=WaitRun $accepted.id
if (($derived.result.units | ConvertTo-Json -Depth 6 -Compress) -ne ($rerun.result.units | ConvertTo-Json -Depth 6 -Compress)) { throw 'Reimport Profile units changed' }
$rerunDeltas=@()
foreach ($group in ($derived.result.profile | Group-Object depth | Where-Object Count -eq 1)) {
    $point=@($rerun.result.profile | Where-Object { $_.depth -eq $group.Group[0].depth })
    if ($point.Count -eq 1) {
        foreach ($field in @('pressure','temperature')) {
            $delta=[Math]::Abs($point[0].$field-$group.Group[0].$field)
            if ($delta -gt 1e-8*[Math]::Max(1,[Math]::Abs($group.Group[0].$field))) { throw 'Reimport Profile does not reproduce derived values at matched depth' }
            $rerunDeltas+=$delta
        }
    }
}
if ($rerunDeltas.Count -eq 0) { throw 'Reimport Profile has no unambiguous matched depths' }
if ((Get-FileHash -LiteralPath $SourceModel -Algorithm SHA256).Hash -ne $sourceHash) { throw 'Official source was modified' }
$baselineGroups=@($baseline.result.profile | Group-Object depth | Where-Object Count -eq 1)
$matched=@()
foreach ($group in $baselineGroups) {
    $target=@($derived.result.profile | Where-Object { $_.depth -eq $group.Group[0].depth })
    if ($target.Count -eq 1) { $matched+=@{pressure=[Math]::Abs($target[0].pressure-$group.Group[0].pressure);temperature=[Math]::Abs($target[0].temperature-$group.Group[0].temperature)} }
}
if ($matched.Count -eq 0 -or (@($matched.pressure | Measure-Object -Maximum)[0].Maximum -eq 0)) { throw 'No unambiguous physical Profile difference' }
@{projectId=$project.id;sourceVersionId=$version.id;baselineRunId=$baseline.id;derivedRunId=$derived.id;reimportVersionId=$reopened.id;rerunId=$rerun.id;baselinePoints=@($baseline.result.profile).Count;derivedPoints=@($derived.result.profile).Count;rerunPoints=@($rerun.result.profile).Count;matchedDepths=$matched.Count;maxPressureDelta=($matched.pressure | Measure-Object -Maximum).Maximum;maxTemperatureDelta=($matched.temperature | Measure-Object -Maximum).Maximum;rerunMaxMatchedDelta=($rerunDeltas | Measure-Object -Maximum).Maximum;sourceSha256=$sourceHash;artifactSha256=$artifact.sha256;downloadPath=$download;parameters=$parameters;readback=$readback;units=$derived.result.units} | ConvertTo-Json -Depth 15
