#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$FrontendBase='http://127.0.0.1:5173',
    [string]$WorkerBase='http://127.0.0.1:5150'
)
$ErrorActionPreference='Stop'
try {
    foreach($url in @($FrontendBase,$WorkerBase)) {
        $uri=[uri]$url
        if(-not $uri.IsAbsoluteUri -or -not $uri.IsLoopback -or $uri.UserInfo -or $uri.Query) { throw 'Local service addresses required.' }
    }
    $page=Invoke-WebRequest -Uri ($FrontendBase.TrimEnd('/')+'/ipr?workspace=software-integration') -TimeoutSec 15
    if($page.StatusCode -ne 200) { throw 'Main page unavailable.' }
    $response=Invoke-RestMethod -Uri ($FrontendBase.TrimEnd('/')+'/api/software-integration/capabilities') -TimeoutSec 20
    if($response.code -ne 200) { throw 'Main API rejected capability request.' }
    $cap=$response.data
    foreach($component in @('worker','pipesimWell','pipesimNetwork','eclipse100')) {
        if($cap.$component.status -cne 'AVAILABLE') { throw "Unavailable component: $component" }
    }
    foreach($task in @('nodal','profile','combined','sensitivity','gas-lift-performance','gas-lift-diagnostics','vfp-tables','esp-curves','esp-parameter','trajectory')) {
        if($cap.pipesimWell.runTasks -cnotcontains $task) { throw "Missing well task: $task" }
    }
    foreach($task in @('network','system-analysis','network-optimizer','network-well-performance-curves')) {
        if($cap.pipesimNetwork.runTasks -cnotcontains $task) { throw "Missing network task: $task" }
    }
    $health=Invoke-RestMethod -Uri ($WorkerBase.TrimEnd('/')+'/api/health') -TimeoutSec 15
    $worker=Invoke-RestMethod -Uri ($WorkerBase.TrimEnd('/')+'/api/capabilities') -TimeoutSec 20
    if($health.status -cne 'UP' -or $health.idle -ne $true -or $worker.idle -ne $true -or $null -ne $health.activeRunId) { throw 'Worker unavailable or busy.' }
    if($worker.generationId -cne $health.generationId -or $cap.worker.idle -ne $true) { throw 'Worker identity changed or main API reports busy.' }
    foreach($component in @('pipesimWell','pipesimNetwork','eclipse100')) {
        if(($worker.$component.runTasks|ConvertTo-Json -Compress) -cne ($cap.$component.runTasks|ConvertTo-Json -Compress)) { throw "API/Worker task mismatch: $component" }
    }
    [pscustomobject]@{preflightVerified=$true;frontend=$FrontendBase;worker=$WorkerBase;generationId=$health.generationId;wellTasks=$cap.pipesimWell.runTasks;networkTasks=$cap.pipesimNetwork.runTasks;nativeCalculationVerified=$false}|ConvertTo-Json -Depth 8
} catch {
    [pscustomobject]@{preflightVerified=$false;error=$_.Exception.Message}|ConvertTo-Json -Compress
    exit 1
}
