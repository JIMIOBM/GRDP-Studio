param([string]$ContainerName = "grdp-flowbalance-dev")

$ErrorActionPreference = "Stop"
docker rm -f $ContainerName
if ($LASTEXITCODE -ne 0) { throw "Could not stop FlowBalance container '$ContainerName'." }
