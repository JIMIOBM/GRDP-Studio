param(
    [Parameter(Mandatory = $true)]
    [string]$ActualResult,
    [Parameter(Mandatory = $true)]
    [string]$GoldenResult,
    [string]$SchemaPath = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
if ([string]::IsNullOrWhiteSpace($SchemaPath)) {
    $SchemaPath = Join-Path $repositoryRoot "docs\software-integration\contracts\pipesim-well-result-v1.schema.json"
}

foreach ($path in @($ActualResult, $GoldenResult, $SchemaPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required comparison input is missing: $([IO.Path]::GetFileName($path))"
    }
}

if (-not (Test-Json -LiteralPath $ActualResult -SchemaFile $SchemaPath -ErrorAction Stop)) {
    throw "Actual result does not satisfy pipesim-well-result/1 Schema."
}
if (-not (Test-Json -LiteralPath $GoldenResult -SchemaFile $SchemaPath -ErrorAction Stop)) {
    throw "Golden result does not satisfy pipesim-well-result/1 Schema."
}

$actual = [IO.File]::ReadAllText($ActualResult) | ConvertFrom-Json
$golden = [IO.File]::ReadAllText($GoldenResult) | ConvertFrom-Json

function Assert-Equal($Actual, $Expected, [string]$Location) {
    if ($Actual -ne $Expected) {
        throw "$Location differs."
    }
}

foreach ($name in @("schemaVersion", "model_kind", "runTask", "resultContract")) {
    Assert-Equal $actual.$name $golden.$name $name
}

foreach ($quantity in @("flow", "pressure", "depth", "temperature")) {
    Assert-Equal $actual.units.$quantity.displayUnit $golden.units.$quantity.displayUnit "units.$quantity.displayUnit"
    Assert-Equal $actual.units.$quantity.semantics $golden.units.$quantity.semantics "units.$quantity.semantics"
}

foreach ($arrayName in @("ipr", "vlp", "profile")) {
    $actualArray = @($actual.$arrayName)
    $goldenArray = @($golden.$arrayName)
    Assert-Equal $actualArray.Count $goldenArray.Count "$arrayName length"
    $properties = if ($arrayName -eq "profile") {
        @("depth", "pressure", "temperature")
    }
    else {
        @("flow", "pressure")
    }
    for ($index = 0; $index -lt $goldenArray.Count; $index++) {
        foreach ($property in $properties) {
            $actualNumber = [double]$actualArray[$index].$property
            $goldenNumber = [double]$goldenArray[$index].$property
            if (-not $actualNumber.Equals($goldenNumber)) {
                throw "$arrayName[$index].$property differs."
            }
        }
    }
}

Write-Output "Actual PIPESIM result exactly matches the Golden Schema, categories, units, array order, lengths, and double values."
