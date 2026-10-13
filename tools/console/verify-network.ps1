#requires -Version 7.0
<#! Main-port acceptance. Reuses the unchanged frozen native comparator, NOT the test deployment. !#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$PipesimHome,
    [Parameter(Mandatory)][string]$PythonPath,
    [Parameter(Mandatory)][string]$NativeEvidenceDirectory,
    [Parameter(Mandatory)][string]$EvidenceDirectory,
    [Parameter(Mandatory)][string]$StorageRoot,
    [string]$ApiBase='http://127.0.0.1:5173/api/software-integration',
    [string]$WorkerBase='http://127.0.0.1:5150',
    [ValidateRange(30,1800)][int]$TimeoutSeconds=600
)
$ErrorActionPreference='Stop'
$platformOutput=[IO.Path]::GetFullPath($EvidenceDirectory)
$platformApi=$ApiBase
$platformWorker=$WorkerBase
$platformStorage=$StorageRoot
if(Test-Path -LiteralPath $platformOutput) { throw 'Use a new acceptance directory.' }
$null=[IO.Directory]::CreateDirectory($platformOutput)
$comparator=Join-Path $PSScriptRoot '../verify-pipesim-system-analysis-platform.ps1'
. $comparator -CompareOnly -PipesimHome $PipesimHome -PythonPath $PythonPath -NativeEvidenceDirectory $NativeEvidenceDirectory -EvidenceDirectory (Join-Path $platformOutput 'comparator-proof') -WorkerBase $platformWorker
# The sourced comparator's parameters/output are intentionally restored for these real main API calls.
$output=$platformOutput
$ApiBase=$platformApi
$WorkerBase=$platformWorker
$StorageRoot=$platformStorage
$refs=Get-FrozenReferences
$health=Invoke-RestMethod -Uri ($WorkerBase+'/api/health') -TimeoutSec 15
Assert-Evidence ($health.idle -eq $true -and $null -eq $health.activeRunId) 'MAIN_WORKER_BUSY'
$script:saHttp=[Net.Http.HttpClient]::new()
$script:saHttp.Timeout=[TimeSpan]::FromSeconds(30)
$report=@{apiVerified=$false;frontendApi=$ApiBase;models=@();generationId=$health.generationId;manualUiVerified=$false;coupledSolveVerified=$false}
try {
    $project=(Invoke-SaHttp POST ($ApiBase+'/projects') @{name=('统一发布管网验收-'+[IO.Path]::GetFileName($output));description='Main persistent database: real network and frozen-reference system analysis.'}).data
    $report.projectId=$project.id
    foreach($item in $refs) {
        $form=[Net.Http.MultipartFormDataContent]::new()
        $form.Add([Net.Http.StreamContent]::new([IO.File]::OpenRead($item.source)),'file',[IO.Path]::GetFileName($item.source))
        $null=Invoke-SaHttp POST ($ApiBase+'/projects/'+$project.id+'/models') -Content $form
        $clock=[Diagnostics.Stopwatch]::StartNew()
        do {
            $detail=(Invoke-SaHttp GET ($ApiBase+'/projects/'+$project.id)).data
            $versions=@($detail.models.versions|Where-Object originalName -ceq ([IO.Path]::GetFileName($item.source)))
            Assert-Evidence ($versions.Count -eq 1) 'MODEL_VERSION_AMBIGUOUS'
            $version=$versions[0]
            if($version.status -ceq 'READY') { break }
            Assert-Evidence ($version.status -cnotin @('FAILED','INVALID') -and $clock.Elapsed.TotalSeconds -lt $TimeoutSeconds) 'MODEL_NOT_READY'
            Start-Sleep -Milliseconds 750
        } while($true)
        Assert-Hash $item.source $item.sourceSha256
        Write-EvidenceJson ($item.model+'-ready.json') $version
        $entry=@{model=$item.model;versionId=$version.id;runIds=@();comparisons=@();downloads=@()}
        $report.models+=,$entry
        $accepted=(Invoke-SaHttp POST ($ApiBase+'/model-versions/'+$version.id+'/runs') @{study=$item.study;runType='network';parameters=$null} -ExpectedStatus 201).data
        $network=Wait-SaRun $accepted.id
        Write-EvidenceJson ('run-'+$network.id+'-network.json') $network
        Assert-Evidence ($network.status -ceq 'SUCCEEDED' -and $network.resultContract -ceq 'VALID_FULL' -and $network.result.schemaVersion -ceq 'pipesim-network-result/1' -and $network.result.simulationState -ceq 'Completed' -and $network.result.topology.nodes.Count -gt 0 -and $network.result.topology.edges.Count -gt 0) 'NETWORK_BASELINE_NOT_FULL'
        Assert-Evidence ($network.cleanup.processTreeExitConfirmed -and $network.cleanup.inputDeleted -and $network.cleanup.workDirectoryDeleted) 'NETWORK_CLEANUP'
        $entry.networkRunId=$network.id
        for($replay=0;$replay -lt 2;$replay++) {
            $accepted=(Invoke-SaHttp POST ($ApiBase+'/model-versions/'+$version.id+'/runs') @{study=$item.study;runType='system-analysis';parameters=$item.request} -ExpectedStatus 201).data
            $run=Wait-SaRun $accepted.id
            Write-EvidenceJson ('run-'+$run.id+'-detail.json') $run
            $entry.runIds+=,$run.id
            $entry.comparisons+=,(Assert-SaRun $run $item $version.id)
            $entry.downloads+=,(Download-SaArtifacts $run $item)
            Write-Output ($item.model+' main Run '+$run.id+' native comparison and downloads passed')
        }
        Assert-Hash $item.source $item.sourceSha256
    }
    $after=Invoke-RestMethod -Uri ($WorkerBase+'/api/health') -TimeoutSec 15
    Assert-Evidence ($after.idle -eq $true -and $null -eq $after.activeRunId -and $after.generationId -ceq $health.generationId) 'FINAL_WORKER_STATE'
    $report.apiVerified=$true
    Write-EvidenceJson 'main-network-summary.json' $report
    Write-Output 'Main-port Network and System Analysis verified against frozen native values.'
} catch {
    Write-EvidenceJson 'main-network-failure.json' @{report=$report;error=$_.Exception.Message}
    throw
} finally { $script:saHttp.Dispose() }
