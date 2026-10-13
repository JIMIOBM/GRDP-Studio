#requires -Version 7.0
<#! Updates only explicitly identified main processes. Never starts another public port. !#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$BackupDirectory,
    [Parameter(Mandatory)][string]$BackendJar,
    [Parameter(Mandatory)][string]$WorkerDirectory,
    [Parameter(Mandatory)][string]$StorageRoot,
    [Parameter(Mandatory)][string]$EclrunPath,
    [Parameter(Mandatory)][int]$BackendContainerProcessId,
    [Parameter(Mandatory)][int]$WorkerProcessId,
    [string]$BackendContainer='grdp-studio-dev-backend',
    [string]$BackendContainerJarPrefix='/workspace',
    [string]$OriginalPlatformBase='http://host.docker.internal:9920',
    [string]$TemplateSessionUrl='http://host.docker.internal:9919/services/ory/kratos/sessions/whoami'
)
$ErrorActionPreference='Stop'
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$backup=(Resolve-Path -LiteralPath $BackupDirectory).Path
$certificate=Get-Content -LiteralPath (Join-Path $backup 'backup-certified.json') -Raw|ConvertFrom-Json
if($certificate.restoreVerified -ne $true -or $certificate.storageVerified -ne $true) { throw 'Certified backup required.' }
if((Get-FileHash -LiteralPath (Join-Path $backup 'database.sql')).Hash -cne $certificate.sqlSha256) { throw 'Backup changed.' }
$storage=(Resolve-Path -LiteralPath $StorageRoot).Path
if($storage.TrimEnd('\') -ine ([string]$certificate.storageRoot).TrimEnd('\')) { throw 'Do not switch the preserved storage root.' }
$jar=(Resolve-Path -LiteralPath $BackendJar).Path
$worker=(Resolve-Path -LiteralPath $WorkerDirectory).Path
$dll=Join-Path $worker 'Grdp.SoftwareIntegration.Worker.dll'
$launcher=(Resolve-Path -LiteralPath $EclrunPath).Path
foreach($path in @($jar,$worker)) {
    if(-not $path.StartsWith($repo.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Release must be inside this checkout.' }
    if((Get-Item -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'No linked release paths.' }
}
if(-not (Test-Path -LiteralPath $dll -PathType Leaf)) { throw 'Worker DLL missing.' }
foreach($name in @('ptk_run.py','ptk_validate.py','ptk_network.py','ptk_well_inspection.py','ptk_normalization.py','ptk_create_template.py')) {
    if((Get-FileHash -LiteralPath (Join-Path $repo ('worker/'+$name))).Hash -cne (Get-FileHash -LiteralPath (Join-Path $worker $name)).Hash) { throw "Worker helper mismatch: $name" }
}
$process=Get-CimInstance Win32_Process -Filter "ProcessId=$WorkerProcessId"
if($null -eq $process -or $process.CommandLine -notmatch 'Grdp\.SoftwareIntegration\.Worker\.(exe|dll)' -or
   $process.CommandLine -notlike ('*'+$repo+'*')) { throw 'Supplied Worker is not owned by this checkout.' }
if(-not @(Get-NetTCPConnection -State Listen -LocalPort 5150 -ErrorAction Stop|Where-Object OwningProcess -eq $WorkerProcessId).Count) { throw 'Worker PID/5150 mismatch.' }
$health=Invoke-RestMethod -Uri 'http://127.0.0.1:5150/api/capabilities' -TimeoutSec 20
if($health.idle -ne $true -or $null -ne $health.activeRunId) { throw 'Worker busy; refuse main cutover.' }
$backendArgs=& docker exec $BackendContainer ps -p $BackendContainerProcessId -o args=
if($LASTEXITCODE -ne 0 -or $backendArgs -notmatch '^java -jar /workspace/backend/(target|releases)/' -or $backendArgs -match '--spring.profiles.active=test|SERVER_PORT=8082|--server.port=8082') { throw 'Backend PID is not the persistent main release.' }
$relative=[IO.Path]::GetRelativePath($repo,$jar).Replace('\','/')
$containerJar=$BackendContainerJarPrefix.TrimEnd('/')+'/'+$relative
$timestamp=[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,6)
$logRoot=Join-Path $repo 'backend/releases'
$backendLog=$BackendContainerJarPrefix.TrimEnd('/')+'/backend/releases/main-'+$timestamp+'.log'
$workerLog=Join-Path $logRoot ('worker-main-'+$timestamp+'.log')
$workerError=Join-Path $logRoot ('worker-main-'+$timestamp+'.stderr.log')
# All path, ownership, backup and idle checks happen before either service stops.
& docker exec $BackendContainer kill -TERM $BackendContainerProcessId
if($LASTEXITCODE -ne 0) { throw 'Backend stop failed.' }
$timer=[Diagnostics.Stopwatch]::StartNew()
do {
    $alive=& docker exec $BackendContainer sh -c 'if test -e "/proc/$1"; then printf alive; fi' sh $BackendContainerProcessId
    if($LASTEXITCODE -ne 0) { throw 'Cannot confirm backend exit; Worker remains untouched.' }
    if(-not $alive) { break }
    if($timer.Elapsed.TotalSeconds -gt 45) { throw 'Backend did not stop gracefully. No forced kill performed.' }
    Start-Sleep -Milliseconds 300
} while($true)
Stop-Process -Id $WorkerProcessId -ErrorAction Stop
Wait-Process -Id $WorkerProcessId -Timeout 15 -ErrorAction SilentlyContinue
$workerArguments=@(('"'+$dll+'"'),'--Urls=http://127.0.0.1:5150',('--Worker:StorageRoot="'+$storage+'"'),('--Worker:EclrunPath="'+$launcher+'"'))
$launched=Start-Process -FilePath (Get-Command dotnet -CommandType Application).Source -ArgumentList $workerArguments -WorkingDirectory $repo -WindowStyle Hidden -RedirectStandardOutput $workerLog -RedirectStandardError $workerError -PassThru
& docker exec -d -e 'SERVER_PORT=8080' -e 'SOFTWARE_INTEGRATION_STORAGE_ROOT=/data' -e 'SOFTWARE_INTEGRATION_WORKER_BASE_URL=http://host.docker.internal:5150' -e "ORIGINAL_PLATFORM_BASE_URL=$OriginalPlatformBase" -e "GRDP_TEMPLATE_SESSION_URL=$TemplateSessionUrl" $BackendContainer sh -c 'exec java -jar "$1" > "$2" 2>&1' sh $containerJar $backendLog
if($LASTEXITCODE -ne 0) { throw 'Backend launch failed; restore the preserved release before accepting work.' }
$timer.Restart()
$ready=$false
do {
    try {
        $api=Invoke-RestMethod -Uri 'http://127.0.0.1:8080/actuator/health' -TimeoutSec 3
        $newHealth=Invoke-RestMethod -Uri 'http://127.0.0.1:5150/api/health' -TimeoutSec 3
        $apiStatus=if($null -ne $api.data){$api.data.status}else{$api.status}
        if($apiStatus -ceq 'UP' -and $newHealth.status -ceq 'UP' -and $newHealth.generationId -cne $health.generationId) { $ready=$true;break }
    } catch { }
    if($launched.HasExited) { throw 'New Worker exited; read the release logs.' }
    Start-Sleep -Milliseconds 500
} while($timer.Elapsed.TotalSeconds -lt 90)
if(-not $ready) { throw 'Unified release health timeout; not accepted.' }
$report=@{frontend='http://127.0.0.1:5173';api='http://127.0.0.1:8080';worker='http://127.0.0.1:5150';workerProcessId=$launched.Id;generationId=$newHealth.generationId;backendJar=$relative;backendJarSha256=(Get-FileHash -LiteralPath $jar).Hash;workerDllSha256=(Get-FileHash -LiteralPath $dll).Hash;database=$certificate.database;storageRoot=$storage;backupDirectory=$backup;healthVerified=$true;nativeCalculationVerified=$false}
[IO.File]::WriteAllText((Join-Path $backup ('deployment-'+$timestamp+'.json')),($report|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
$report|ConvertTo-Json -Depth 8
