#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ApiBase,
    [Parameter(Mandatory)][string]$StorageRoot,
    [Parameter(Mandatory)][string]$EvidenceDirectory
)
$ErrorActionPreference='Stop'
$uri=[uri]$ApiBase
if (-not $uri.IsLoopback -or $uri.UserInfo -or $uri.Query) { throw 'Only local validation APIs can be archived.' }
$output=[IO.Path]::GetFullPath($EvidenceDirectory)
$source=(Resolve-Path -LiteralPath $StorageRoot).Path
if (Test-Path -LiteralPath $output) { throw 'Use a new archive directory.' }
if ($output.StartsWith($source.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Archive must not be in storage.' }
$null=[IO.Directory]::CreateDirectory($output)
function Save-Json([string]$Name,$Value) { [IO.File]::WriteAllText((Join-Path $output $Name),($Value|ConvertTo-Json -Depth 100),[Text.UTF8Encoding]::new($false)) }
function Read-Api([string]$Path) {
    $value=Invoke-RestMethod -Uri ($ApiBase.TrimEnd('/')+$Path) -TimeoutSec 30
    if ($value.code -ne 200) { throw 'Archive API rejected request.' }
    return $value.data
}
$projects=@(Read-Api '/projects')
Save-Json 'projects.json' $projects
Save-Json 'recycle-bin.json' (Read-Api '/recycle-bin/projects')
$runCount=0
foreach($project in $projects) {
    $detail=Read-Api ('/projects/'+$project.id)
    Save-Json ('project-'+$project.id+'.json') $detail
    foreach($model in $detail.models) {
        foreach($version in $model.versions) {
            $history=@(Read-Api ('/model-versions/'+$version.id+'/runs'))
            Save-Json ('version-'+$version.id+'-runs.json') $history
            foreach($run in $history) {
                if($run.status -notin @('SUCCEEDED','PARTIAL_SUCCEEDED','FAILED','CANCELLED','TIMED_OUT','WORKER_LOST')) { throw 'Validation environment still has active work.' }
                Save-Json ('run-'+$run.id+'.json') (Read-Api ('/runs/'+$run.id))
                $runCount++
            }
        }
    }
}
$items=@(Get-ChildItem -LiteralPath $source -Recurse -Force)
if (@($items|Where-Object {$_.Attributes -band [IO.FileAttributes]::ReparsePoint}).Count) { throw 'No links permitted in validation storage.' }
$target=Join-Path $output 'storage'
$null=[IO.Directory]::CreateDirectory($target)
foreach($item in (Get-ChildItem -LiteralPath $source -Force)) { Copy-Item -LiteralPath $item.FullName -Destination $target -Recurse }
$manifest=@($items|Where-Object {-not $_.PSIsContainer}|ForEach-Object {
    $relative=[IO.Path]::GetRelativePath($source,$_.FullName)
    $sha=(Get-FileHash -LiteralPath $_.FullName).Hash
    if((Get-FileHash -LiteralPath (Join-Path $target $relative)).Hash -cne $sha) { throw 'Archive file differs.' }
    @{path=$relative;sha256=$sha;sizeBytes=$_.Length}
})
Save-Json 'storage-manifest.json' $manifest
Save-Json 'archive-certified.json' @{apiBase=$ApiBase;projectCount=$projects.Count;runCount=$runCount;storageFileCount=$manifest.Count;verified=$true;databaseExportType='API project/version/run/event snapshot, not an SQL database dump'}
Write-Output "Archived $($projects.Count) projects, $runCount runs, $($manifest.Count) files: $output"
