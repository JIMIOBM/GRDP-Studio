param([string]$OutputDirectory = "$env:LOCALAPPDATA\GRDP\IamRestHost")
$ErrorActionPreference = 'Stop'
$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path -LiteralPath $compiler)) { throw '.NET Framework x64 compiler not found.' }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$executable = Join-Path $OutputDirectory 'PipesimAgentProbe.exe'
$source = Join-Path $PSScriptRoot 'PipesimAgentProbe.cs'
$extensions = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\System.Web.Extensions.dll'
& $compiler /nologo /platform:x64 /target:exe "/reference:$extensions" "/out:$executable" $source
if ($LASTEXITCODE -ne 0) { throw 'PIPESIM Agent Manager probe build failed.' }
Write-Output $executable
