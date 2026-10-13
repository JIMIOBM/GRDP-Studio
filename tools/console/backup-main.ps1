#requires -Version 7.0
<#! Backup is read/copy only for the live database and storage. Restore checks use a NEW database. !#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$EvidenceDirectory,
    [Parameter(Mandatory)][string]$StorageRoot,
    [string]$MysqlContainer = 'grdp-mysql',
    [string]$Database = 'grdp_studio',
    [string]$ApiBase = 'http://127.0.0.1:5173/api/software-integration',
    [string]$WorkerBase = 'http://127.0.0.1:5150',
    [string[]]$ExternalJunctionPaths = @()
)
$ErrorActionPreference = 'Stop'
foreach ($identifier in @($Database, $MysqlContainer)) {
    if ($identifier -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Unsafe database/container identifier.' }
}
$output = [IO.Path]::GetFullPath($EvidenceDirectory)
$source = (Resolve-Path -LiteralPath $StorageRoot).Path.TrimEnd([IO.Path]::DirectorySeparatorChar)
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..')).TrimEnd([IO.Path]::DirectorySeparatorChar)
foreach ($root in @($source,$repo)) {
    if ($output.Equals($root,[StringComparison]::OrdinalIgnoreCase) -or
        $output.StartsWith($root+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Backup must be outside source/repository.' }
}
if (Test-Path -LiteralPath $output) { throw 'Use a NEW backup directory.' }
function Invoke-ConsoleDocker([string[]]$Arguments) {
    $value = & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'Docker operation failed; original data has not been restored or removed.' }
    return $value
}
function Sql([string]$Name,[string]$Query) {
    # Password stays in the container environment; never appears in arguments or reports.
    Invoke-ConsoleDocker -Arguments @('exec',$MysqlContainer,'sh','-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names "$@"',
        'sh',"--database=$Name","--execute=$Query")
}
function Save-Json([string]$Name,$Value) {
    [IO.File]::WriteAllText((Join-Path $output $Name),($Value|ConvertTo-Json -Depth 100),[Text.UTF8Encoding]::new($false))
}
function Fingerprints([string]$Name) {
    $result = [ordered]@{}
    foreach ($table in @(Sql $Name 'SHOW TABLES')) {
        if ($table -notmatch '^[a-zA-Z0-9_]+$') { throw 'Unsafe table name.' }
        $columns = @(Sql $Name "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$Name' AND TABLE_NAME='$table' ORDER BY ORDINAL_POSITION")
        $keys = @(Sql $Name "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$Name' AND TABLE_NAME='$table' AND COLUMN_KEY='PRI' ORDER BY ORDINAL_POSITION")
        if (-not $keys.Count) { throw "No stable primary identity for table $table" }
        foreach ($column in $columns) { if ($column -notmatch '^[a-zA-Z0-9_]+$') { throw 'Unsafe column.' } }
        $fields = ($columns | ForEach-Object { '`'+$_+'`' }) -join ','
        $keyFields = ($keys | ForEach-Object { '`'+$_+'`' }) -join ','
        $query = 'SELECT CAST(JSON_ARRAY('+ $keyFields +') AS CHAR),SHA2(CAST(JSON_ARRAY('+ $fields +') AS CHAR),256) FROM `'+$table+'` ORDER BY '+$keyFields
        $rows = @(Sql $Name $query)
        $result[$table] = @{columns=$columns;rowCount=$rows.Count;rowFingerprints=$rows}
    }
    return $result
}
$health = Invoke-RestMethod -Uri ($WorkerBase+'/api/health') -TimeoutSec 15
if ($health.idle -ne $true -or $null -ne $health.activeRunId) { throw 'Worker busy; cannot freeze a coherent backup.' }
$active = @(Sql $Database "SELECT id FROM software_integration_run WHERE status NOT IN ('SUCCEEDED','PARTIAL_SUCCEEDED','FAILED','CANCELLED','TIMED_OUT','WORKER_LOST')")
if ($active.Count) { throw 'Live run/queue exists; finish or explicitly cancel it before deployment.' }
$null = [IO.Directory]::CreateDirectory($output)
Save-Json 'worker-before.json' $health
Save-Json 'projects-before.json' (Invoke-RestMethod -Uri ($ApiBase+'/projects') -TimeoutSec 30)
$before = Fingerprints $Database
Save-Json 'database-before.json' $before
$backupToken = 'grdp_unified_'+[Guid]::NewGuid().ToString('N')
$dumpContainer = '/tmp/'+$backupToken+'.sql'
$null = Invoke-ConsoleDocker -Arguments @('exec',$MysqlContainer,'sh','-c',
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --routines --triggers --hex-blob --no-tablespaces --set-gtid-purged=OFF "$@"',
    'sh',"--result-file=$dumpContainer",$Database)
$dump = Join-Path $output 'database.sql'
$null = Invoke-ConsoleDocker -Arguments @('cp',($MysqlContainer+':'+$dumpContainer),$dump)
if ((Get-Item -LiteralPath $dump).Length -eq 0) { throw 'Empty database backup.' }
$afterDump = Fingerprints $Database
if (($before|ConvertTo-Json -Depth 100 -Compress) -cne ($afterDump|ConvertTo-Json -Depth 100 -Compress)) { throw 'Database changed during dump; backup is not certified.' }
$restoreName = 'grdp_restore_unified_'+[DateTime]::UtcNow.ToString('yyyyMMddHHmmss')
$null = Sql $Database ("CREATE DATABASE $restoreName CHARACTER SET utf8mb4")
$null = Invoke-ConsoleDocker -Arguments @('exec',$MysqlContainer,'sh','-c',
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --database="$1" < "$2"',
    'sh',$restoreName,$dumpContainer)
$restored = Fingerprints $restoreName
Save-Json 'restore-database.json' $restored
if (($before|ConvertTo-Json -Depth 100 -Compress) -cne ($restored|ConvertTo-Json -Depth 100 -Compress)) { throw 'Restore contents differ; DO NOT DEPLOY.' }
$copiedRoot = Join-Path $output 'storage'
$null = [IO.Directory]::CreateDirectory($copiedRoot)
$entries = @(Get-ChildItem -LiteralPath $source -Recurse -Force)
$links=@($entries | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint })
$linkManifest=@($links | ForEach-Object {
    if(-not $_.PSIsContainer -or $_.LinkType -ne 'Junction' -or $_.FullName -notin $ExternalJunctionPaths -or -not (Test-Path -LiteralPath $_.Target -PathType Container)) { throw 'Unapproved storage link; refuse to traverse it.' }
    @{path=[IO.Path]::GetRelativePath($source,$_.FullName);linkType=$_.LinkType;target=$_.Target}
})
Save-Json 'external-junctions.json' $linkManifest
$manifest = @($entries | Where-Object {-not $_.PSIsContainer} | ForEach-Object {
    @{path=[IO.Path]::GetRelativePath($source,$_.FullName);sizeBytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
})
foreach($item in $manifest) {
    foreach($link in $linkManifest) {
        if($item.path.StartsWith($link.path+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Backup enumeration followed an external junction.' }
    }
    $copied=Join-Path $copiedRoot $item.path
    $null=[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($copied))
    Copy-Item -LiteralPath (Join-Path $source $item.path) -Destination $copied
}
foreach ($item in $manifest) {
    $copied = Join-Path $copiedRoot $item.path
    if ((Get-Item -LiteralPath $copied).Length -ne $item.sizeBytes -or (Get-FileHash -LiteralPath $copied).Hash -cne $item.sha256) { throw 'Storage backup verification failed.' }
}
Save-Json 'storage-before.json' $manifest
Save-Json 'backup-certified.json' @{
    schemaVersion='grdp-unified-backup/1';createdAtUtc=[DateTime]::UtcNow.ToString('o');database=$Database;
    storageRoot=$source;restoreDatabase=$restoreName;restoreVerified=$true;storageVerified=$true;
    sqlSha256=(Get-FileHash -LiteralPath $dump).Hash;storageFileCount=$manifest.Count;externalJunctionCount=$linkManifest.Count;
    storageSizeBytes=($manifest|Measure-Object sizeBytes -Sum).Sum
}
Write-Output "Certified database restore and $($manifest.Count) storage files: $output"
