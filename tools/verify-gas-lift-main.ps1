#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$SourceModel,
    [Parameter(Mandatory)][string]$PortModel,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [string]$ApiBase='http://127.0.0.1:5173/api/software-integration',
    [string]$WorkerBase='http://127.0.0.1:5150',
    [int]$TimeoutSeconds=660
)
$ErrorActionPreference='Stop'
if ($ApiBase -cne 'http://127.0.0.1:5173/api/software-integration' -or $WorkerBase -cne 'http://127.0.0.1:5150') { throw 'This acceptance targets only the unified main console.' }
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Use a new evidence directory.' }
foreach ($file in @($SourceModel,$PortModel)) { if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw 'Official input is missing.' } }
$health=Invoke-RestMethod "$WorkerBase/api/health"
if ($health.idle -ne $true -or $null -ne $health.activeRunId) { throw 'Worker busy; no acceptance submitted.' }
$caps=Invoke-RestMethod "$ApiBase/capabilities"
if ($caps.data.pipesimWell.status -ne 'AVAILABLE' -or 'gas-lift-performance' -notin $caps.data.pipesimWell.runTasks) { throw 'Main deployment does not advertise gas lift.' }
# AVAILABLE is not a borrowed-license proof. Only the real runs below prove it.
$output=(New-Item -ItemType Directory -Path $OutputDirectory).FullName
$sourceHash=(Get-FileHash -LiteralPath $SourceModel).Hash
$portHash=(Get-FileHash -LiteralPath $PortModel).Hash
function Save($name,$value) { [IO.File]::WriteAllText((Join-Path $output $name),($value|ConvertTo-Json -Depth 80),[Text.UTF8Encoding]::new($false)) }
function Api($method,$path,$body=$null) {
    $apiArguments=@{Method=$method;Uri="$ApiBase/$path";TimeoutSec=30}
    if ($null -ne $body) { $apiArguments.ContentType='application/json';$apiArguments.Body=$body|ConvertTo-Json -Depth 25 -Compress }
    $reply=Invoke-RestMethod @apiArguments
    if ($reply.code -notin @(0,200,201,202)) { throw "Business API rejected $path" }
    return $reply.data
}
function Upload($project,$file) {
    $client=[Net.Http.HttpClient]::new();$form=[Net.Http.MultipartFormDataContent]::new()
    $stream=[IO.File]::Open($file,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
    try {
        $form.Add([Net.Http.StreamContent]::new($stream),'file',[IO.Path]::GetFileName($file))
        $reply=$client.PostAsync("$ApiBase/projects/$project/models",$form).GetAwaiter().GetResult()
        $null=$reply.EnsureSuccessStatusCode()
        $envelope=$reply.Content.ReadAsStringAsync().GetAwaiter().GetResult()|ConvertFrom-Json
        if ($envelope.code -notin @(0,200,201,202)) { throw 'Upload rejected.' }
        $id=@($envelope.data.models|ForEach-Object {$_.versions}|Sort-Object id -Descending)[0].id
    } finally { $form.Dispose();$stream.Dispose();$client.Dispose() }
    $limit=[DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $version=@((Api GET "projects/$project").models|ForEach-Object {$_.versions}|Where-Object id -eq $id)[0]
        if ($version.status -eq 'READY') { return $version }
        if ($version.status -in @('INVALID','FAILED')) { Save 'validation-failed.json' $version;throw 'Native model validation failed.' }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $limit)
    throw 'Model validation timed out; do not resubmit.'
}
function Reject($version,$request,$name) {
    $reply=Invoke-WebRequest -Method POST -Uri "$ApiBase/model-versions/$version/runs" -ContentType 'application/json' -Body ($request|ConvertTo-Json -Depth 20 -Compress) -SkipHttpErrorCheck
    $body=$reply.Content|ConvertFrom-Json
    Save "$name.json" @{httpStatus=$reply.StatusCode;response=$body}
    if ($reply.StatusCode -notin @(400,409,422) -or $body.code -in @(0,200,201,202)) { throw "Negative acceptance unexpectedly succeeded: $name" }
    Write-Output "Rejected before run creation: $name"
}
function Run($version,$request,$name) {
    $created=Api POST "model-versions/$version/runs" $request
    Write-Host "Native gas lift $name : Run $($created.id)"
    $limit=[DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $run=Api GET "runs/$($created.id)"
        if ($run.status -in @('SUCCEEDED','PARTIAL_SUCCEEDED','FAILED','CANCELLED','TIMED_OUT','WORKER_LOST')) {
            Save "$name.json" $run
            if ($run.status -ne 'SUCCEEDED' -or $run.resultContract -ne 'VALID_FULL') { throw "Native run $($run.id) failed or was incomplete: $($run.error.code)" }
            return $run
        }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $limit)
    throw "Run $($created.id) timed out; leave it inspectable, do not resubmit."
}
$project=Api POST 'projects' @{name=('Gas lift repair acceptance '+(Get-Date -Format 'yyyyMMdd-HHmmss'));description='Official valve and port applicability; main-console real replay. Retain for inspection.'}
Save 'project.json' $project
$positive=Upload $project.id $SourceModel
Save 'positive-version.json' $positive
$port=Upload $project.id $PortModel
Save 'port-version.json' $port
if ($positive.inspection.schemaVersion -ne 'pipesim-well-inspection/9' -or $port.inspection.schemaVersion -ne 'pipesim-well-inspection/9') { throw 'Main metadata is not current.' }
$well=$positive.inspection.well;$study=@($positive.studies)[0]
$support=@($positive.inspection.gasLiftCapabilities|Where-Object {$_.well -ceq $well -and $_.study -ceq $study})
if ($support.Count -ne 1 -or $support[0].state -ne 'supported') { throw 'Official valve sample was not discovered as supported.' }
$values=@(0.55,0.65,0.75,0.85,0.95,1.1,1.4,1.7,2.0,2.4,2.8)
$parameters=@{schemaVersion='pipesim-gas-lift-performance-parameters/1';producer=$well;outletPressurePsi=151;surfaceInjectionTemperatureF=110;targetInjectionRateMmscfd=1.1;reservoirPressurePsi=1700;gorScfPerStb=400;waterCutPercent=80;valuesMmscfd=$values}
$request=@{study=$study;runType='gas-lift-performance';parameters=$parameters}
$portParameters=$parameters.Clone();$portParameters.producer=$port.inspection.well
Reject $port.id @{study=@($port.studies)[0];runType='gas-lift-performance';parameters=$portParameters} 'reject-port'
Reject $positive.id @{study='Nonexistent Study';runType='gas-lift-performance';parameters=$parameters} 'reject-study'
$invalid=$parameters.Clone();$invalid.valuesMmscfd=@(1,0.5)
Reject $positive.id @{study=$study;runType='gas-lift-performance';parameters=$invalid} 'reject-axis'
$invalid=$parameters.Clone();$invalid.outletPressurePsi=-1
Reject $positive.id @{study=$study;runType='gas-lift-performance';parameters=$invalid} 'reject-pressure'
$first=Run $positive.id $request 'first-run'
$second=Run $positive.id $request 'replay-run'
foreach ($run in @($first,$second)) {
    if ($run.result.scanUnit -ne 'mmscf/d' -or $run.result.productionUnit -ne 'STB/d' -or @($run.result.cases).Count -ne 11) { throw 'Units or complete case count mismatch.' }
    for ($i=0;$i -lt 11;$i++) {
        if ([Math]::Abs($run.result.cases[$i].injectionRateMmscfd-$values[$i]) -gt 1e-6 -or -not [double]::IsFinite($run.result.cases[$i].liquidRateStbPerDay)) { throw 'Injection axis or finite production does not match request.' }
    }
    foreach ($artifact in @($run.artifacts|Where-Object name -in @('request.json','raw-response.json','normalized-result.json'))) {
        $path=Join-Path $output ("run-$($run.id)-"+$artifact.name)
        Invoke-WebRequest "$ApiBase/runs/$($run.id)/artifacts/$($artifact.id)/download" -OutFile $path
        if ((Get-FileHash -LiteralPath $path).Hash -ine $artifact.sha256) { throw 'Artifact download SHA mismatch.' }
    }
}
$maximumDelta=0.0
for ($i=0;$i -lt 11;$i++) {
    $delta=[Math]::Abs($first.result.cases[$i].liquidRateStbPerDay-$second.result.cases[$i].liquidRateStbPerDay)
    if ($delta -gt 1e-8*[Math]::Max(1,[Math]::Abs($first.result.cases[$i].liquidRateStbPerDay))) { throw 'Same-input native replay differs.' }
    $maximumDelta=[Math]::Max($maximumDelta,$delta)
}
if ((Get-FileHash -LiteralPath $SourceModel).Hash -cne $sourceHash -or (Get-FileHash -LiteralPath $PortModel).Hash -cne $portHash) { throw 'Official source was modified.' }
$report=@{mainEntry='http://127.0.0.1:5173';projectId=$project.id;positiveVersionId=$positive.id;portVersionId=$port.id;runIds=@($first.id,$second.id);cases=11;replayMaxDelta=$maximumDelta;sourceSha256=$sourceHash;portSha256=$portHash;negativeTests=4;nativeCalculationVerified=$true;manualUiVerified=$false}
Save 'acceptance.json' $report
$report|ConvertTo-Json -Depth 8
