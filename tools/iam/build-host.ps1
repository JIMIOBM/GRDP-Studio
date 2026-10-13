param([string]$OutputDirectory = "$env:LOCALAPPDATA\GRDP\IamRestHost")
$ErrorActionPreference = 'Stop'
$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path -LiteralPath $compiler)) { throw '.NET Framework x64 compiler not found.' }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$executable = Join-Path $OutputDirectory 'IamRestHost.exe'
& $compiler /nologo /platform:x64 /target:exe "/out:$executable" (Join-Path $PSScriptRoot 'IamRestHost.cs')
if ($LASTEXITCODE -ne 0) { throw 'IAM host build failed.' }
Write-Output $executable
