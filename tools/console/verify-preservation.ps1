#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$BackupDirectory,
    [string]$MysqlContainer='grdp-mysql',
    [string]$FrontendBase='http://127.0.0.1:5173'
)
$ErrorActionPreference='Stop'
$backup=(Resolve-Path -LiteralPath $BackupDirectory).Path
$certificate=Get-Content -LiteralPath (Join-Path $backup 'backup-certified.json') -Raw|ConvertFrom-Json
$baseline=Get-Content -LiteralPath (Join-Path $backup 'database-before.json') -Raw|ConvertFrom-Json -AsHashtable
$database=[string]$certificate.database
if($database -notmatch '^[a-zA-Z0-9_]+$' -or $MysqlContainer -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Unsafe database/container.' }
function Read-Sql([string]$Query) {
    $value=& docker exec $MysqlContainer sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names "$@"' sh "--database=$database" "--execute=$Query"
    if($LASTEXITCODE -ne 0) { throw 'Preservation SQL failed.' }
    return $value
}
$tableReports=@()
foreach($table in ($baseline.Keys|Sort-Object)) {
    if($table -notmatch '^[a-zA-Z0-9_]+$') { throw 'Unsafe table.' }
    $keys=@(Read-Sql "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$database' AND TABLE_NAME='$table' AND COLUMN_KEY='PRI' ORDER BY ORDINAL_POSITION")
    $fields=($baseline[$table].columns|ForEach-Object {'`'+$_+'`'}) -join ','
    $keyFields=($keys|ForEach-Object {'`'+$_+'`'}) -join ','
    if(-not $keys.Count) { throw 'Primary key disappeared.' }
    $rows=@(Read-Sql ('SELECT CAST(JSON_ARRAY('+$keyFields+') AS CHAR),SHA2(CAST(JSON_ARRAY('+$fields+') AS CHAR),256) FROM `'+$table+'` ORDER BY '+$keyFields))
    $current=@{}
    foreach($row in $rows) { $parts=$row.Split("`t",2);if($parts.Count -ne 2 -or $current.ContainsKey($parts[0])){throw 'Invalid/duplicate persisted identity.'};$current[$parts[0]]=$parts[1] }
    foreach($row in $baseline[$table].rowFingerprints) {
        $parts=$row.Split("`t",2)
        if(-not $current.ContainsKey($parts[0]) -or $current[$parts[0]] -cne $parts[1]) { throw "Original row changed or missing: $table / $($parts[0])" }
    }
    $tableReports+=@{table=$table;preservedRows=$baseline[$table].rowCount;currentRows=$rows.Count}
}
$files=Get-Content -LiteralPath (Join-Path $backup 'storage-before.json') -Raw|ConvertFrom-Json
foreach($file in $files) {
    $path=Join-Path $certificate.storageRoot $file.path
    if(-not (Test-Path -LiteralPath $path -PathType Leaf) -or (Get-Item -LiteralPath $path).Length -ne $file.sizeBytes -or (Get-FileHash -LiteralPath $path).Hash -cne $file.sha256) { throw "Original stored file changed/missing: $($file.path)" }
}
$projectsBefore=(Get-Content -LiteralPath (Join-Path $backup 'projects-before.json') -Raw|ConvertFrom-Json).data
$response=Invoke-RestMethod -Uri ($FrontendBase.TrimEnd('/')+'/api/software-integration/projects') -TimeoutSec 20
if($response.code -ne 200) { throw 'Main projects API unavailable.' }
foreach($project in $projectsBefore) {
    $match=@($response.data|Where-Object id -eq $project.id)
    if($match.Count -ne 1 -or $match[0].name -cne $project.name) { throw 'Original project absent from the main page API.' }
}
$report=@{preservationVerified=$true;frontend=$FrontendBase;database=$database;originalProjectCount=@($projectsBefore).Count;originalFileCount=@($files).Count;tables=$tableReports;verifiedAtUtc=[DateTime]::UtcNow.ToString('o')}
$name='preservation-'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')+'.json'
[IO.File]::WriteAllText((Join-Path $backup $name),($report|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
$report|ConvertTo-Json -Depth 8
