#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ArchiveRoot,
    [Parameter(Mandatory)][int[]]$FrontendProcessIds,
    [Parameter(Mandatory)][int[]]$BackendProcessIds,
    [Parameter(Mandatory)][int[]]$WorkerProcessIds
)
$ErrorActionPreference='Stop'
$archive=(Resolve-Path -LiteralPath $ArchiveRoot).Path
foreach($name in @('CC05-SA1','CC06-MW1','CC06-OUT1','CC06-EDIT1-A')) {
    $cert=Get-Content -LiteralPath (Join-Path $archive "$name/archive-certified.json") -Raw|ConvertFrom-Json
    if($cert.verified -ne $true) { throw 'Validation archive incomplete; do not stop its in-memory database.' }
}
if($FrontendProcessIds.Count -ne 4 -or $BackendProcessIds.Count -ne 4 -or $WorkerProcessIds.Count -ne 4) { throw 'Only the four archived validation stacks may be retired.' }
$groups=@(
    @{ids=$FrontendProcessIds;ports=@(5177,5178,5179,5180);pattern='createServer';required='8087|8088|8089|8090'},
    @{ids=$BackendProcessIds;ports=@(8087,8088,8089,8090);pattern='GrdpBackendApplication';required='--spring.profiles.active=test'},
    @{ids=$WorkerProcessIds;ports=@(5151,5153,5154,5155);pattern='Grdp.SoftwareIntegration.Worker.dll';required='CC05-SA1|CC06-MW1|CC06-OUT1|CC06-EDIT1-A'}
)
$targets=@()
foreach($group in $groups) {
    foreach($processId in $group.ids) {
        $process=Get-CimInstance Win32_Process -Filter "ProcessId=$processId"
        if($null -eq $process -or $process.CommandLine -notmatch [regex]::Escape($group.pattern) -or $process.CommandLine -notmatch $group.required) { throw 'Retirement PID ownership mismatch.' }
        $ports=@(Get-NetTCPConnection -State Listen -ErrorAction Stop|Where-Object { $_.OwningProcess -eq $processId -and $_.LocalPort -in $group.ports }|Select-Object -ExpandProperty LocalPort -Unique)
        if($ports.Count -ne 1) { throw 'Retirement PID/port mismatch.' }
        if($group.pattern -eq 'Grdp.SoftwareIntegration.Worker.dll') {
            $health=Invoke-RestMethod -Uri ('http://127.0.0.1:'+$ports[0]+'/api/capabilities') -TimeoutSec 20
            if($health.idle -ne $true -or $null -ne $health.activeRunId) { throw 'Validation Worker busy.' }
        }
        $targets+=@{id=$processId;port=$ports[0];started=$process.CreationDate;command=$process.CommandLine}
    }
}
# Validate the entire list before ending any process. The main ports never appear in the allowlist.
foreach($target in $targets) {
    $current=Get-CimInstance Win32_Process -Filter ("ProcessId="+$target.id)
    if($current.CreationDate -ne $target.started -or $current.CommandLine -cne $target.command) { throw 'PID reused; stop list invalidated.' }
    Stop-Process -Id $target.id -ErrorAction Stop
}
foreach($target in $targets) { Wait-Process -Id $target.id -Timeout 15 -ErrorAction SilentlyContinue }
$remaining=@(Get-NetTCPConnection -State Listen -ErrorAction Stop|Where-Object LocalPort -in ($groups.ports|ForEach-Object {$_}))
if($remaining.Count) { throw 'A validation listener remains; do not report retirement complete.' }
$report=@{retired=$true;ports=@($targets.port|Sort-Object);archivedEvidence=$archive;originalFilesDeleted=$false;mainEntry='http://127.0.0.1:5173'}
[IO.File]::WriteAllText((Join-Path $archive 'retired.json'),($report|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
$report|ConvertTo-Json -Depth 6
