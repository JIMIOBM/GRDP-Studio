<# CC06-OUT1 offline real-artifact comparison, not a simulator or UI acceptance. PowerShell 7/.NET 10. #>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$WorkerAssembly,
    [Parameter(Mandatory)][string]$Specification,
    [Parameter(Mandatory)][string]$Summary,
    [Parameter(Mandatory)][string]$ReferenceRun,
    [Parameter(Mandatory)][string]$EvidenceFile
)
$ErrorActionPreference='Stop'
$output=[IO.Path]::GetFullPath($EvidenceFile)
if (Test-Path -LiteralPath $output) { throw 'Use a new evidence file; never rewrite a failure.' }
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ($output.StartsWith($repo.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Evidence must be outside the repository.' }
$paths=@($WorkerAssembly,$Specification,$Summary,$ReferenceRun) | ForEach-Object { (Resolve-Path -LiteralPath $_).Path }
if (@($paths | Where-Object { $_ -ieq $output }).Count) { throw 'Evidence cannot overwrite input.' }
$before=@($paths | ForEach-Object { @{ path=$_;sha256=(Get-FileHash -LiteralPath $_).Hash } })
$report=@{schemaVersion='cc06-out1-offline-evidence/1';offlineVerified=$false;manualUiVerified=$false;coupledSolveVerified=$false;errors=@();maximumCanonicalDelta=0;maximumRawAbsoluteDelta=0;rawDifferentValues=0;maximumRawFloatUlps=0;comparison='Official E16.8 REAL G8 then float, exact';inputs=$before}
try {
    $null=[Reflection.Assembly]::LoadFrom($paths[0])
    $series=[Grdp.SoftwareIntegration.Worker.Execution.EclipseFormattedSummaryParser]::Parse($paths[1],$paths[2])
    $dataHeaders=[regex]::Matches((Get-Content -LiteralPath $paths[2] -Raw),"(?m)^\s*'PARAMS  '\s+\d+\s+'(?<type>\w+)'\s*$")
    if ($dataHeaders.Count -eq 0 -or @($dataHeaders | Where-Object { $_.Groups['type'].Value -cne 'REAL' }).Count) { throw 'This precision comparison is frozen only for all-REAL samples; DOUB needs its own contract.' }
    $validator=[Grdp.SoftwareIntegration.Worker.Execution.EclipseParsers].GetMethod('IsPublishableSummary',[Reflection.BindingFlags]'Static,NonPublic')
    if (-not $validator.Invoke($null,[object[]]@(,$series))) { throw 'Formatted Summary contract invalid.' }
    $reference=Get-Content -LiteralPath $paths[3] -Raw | ConvertFrom-Json
    if ($reference.result) {
        if ($reference.status -cne 'SUCCEEDED' -or $reference.resultContract -cne 'VALID_FULL') { throw 'Reference Run is not verified full.' }
        $expected=@($reference.result.summary.series)
    } else {
        if ($reference.resultContract -cne 'VALID_FULL') { throw 'Reference normalized result is not verified full.' }
        $expected=@($reference.summary.series)
    }
    if ($series.Count -ne $expected.Count -or $series.Count -eq 0) { throw 'Series count mismatch.' }
    $numeric=0
    for ($i=0;$i -lt $series.Count;$i++) {
        $actual=$series[$i];$left=$expected[$i]
        if ($actual.Keyword -cne $left.keyword -or $actual.ObjectName -cne $left.objectName -or $actual.Unit -cne $left.unit -or $actual.Points.Count -ne $left.points.Count) { throw "Series identity/unit/count mismatch at $i." }
        for ($j=0;$j -lt $actual.Points.Count;$j++) {
            foreach ($pair in @(@($actual.Points[$j].TimeDays,$left.points[$j].timeDays),@($actual.Points[$j].Value,$left.points[$j].value))) {
                $raw=[double]$pair[1]
                $canonical=[double][float]::Parse($raw.ToString('G8',[Globalization.CultureInfo]::InvariantCulture),[Globalization.CultureInfo]::InvariantCulture)
                if ($pair[0] -ne $canonical) { throw "Official REAL representation mismatch at $i/$j." }
                $report.maximumRawAbsoluteDelta=[Math]::Max([double]$report.maximumRawAbsoluteDelta,[Math]::Abs([double]$pair[0]-$raw))
                if ($pair[0] -ne $raw) { $report.rawDifferentValues++ }
                $ulps=[Math]::Abs([long][BitConverter]::SingleToInt32Bits([float]$pair[0])-[long][BitConverter]::SingleToInt32Bits([float]$raw))
                $report.maximumRawFloatUlps=[Math]::Max($report.maximumRawFloatUlps,$ulps)
                $numeric++
            }
        }
    }
    $report.seriesCount=$series.Count
    $report.numericPaths=$numeric
    $report.offlineVerified=$true
} catch { $report.errors+=$_.Exception.Message }
finally {
    $report.sourceUnchanged=$true
    foreach ($item in $before) { if ((Get-FileHash -LiteralPath $item.path).Hash -cne $item.sha256) { $report.sourceUnchanged=$false;$report.offlineVerified=$false } }
    $bytes=[Text.Encoding]::UTF8.GetBytes(($report | ConvertTo-Json -Depth 8))
    $stream=[IO.File]::Open($output,[IO.FileMode]::CreateNew)
    try { $stream.Write($bytes,0,$bytes.Length) } finally { $stream.Dispose() }
}
$report | Select-Object offlineVerified,seriesCount,numericPaths,maximumCanonicalDelta,rawDifferentValues,maximumRawAbsoluteDelta,maximumRawFloatUlps,sourceUnchanged,errors | ConvertTo-Json -Depth 3
if (-not $report.offlineVerified) { exit 2 }
