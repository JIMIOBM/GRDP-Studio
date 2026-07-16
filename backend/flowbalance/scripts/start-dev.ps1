param(
  [string]$MariaDbContainer = "mariadb",
  [string]$ContainerName = "grdp-flowbalance-dev",
  [string]$ImageName = "grdp-flowbalance-dev:local",
  [int]$HostPort = 8891
)

$ErrorActionPreference = "Stop"

function Get-ContainerEnvMap {
  param([string]$Name)
  $map = @{}
  docker inspect $Name --format '{{range .Config.Env}}{{println .}}{{end}}' | ForEach-Object {
    $separator = $_.IndexOf("=")
    if ($separator -gt 0) { $map[$_.Substring(0, $separator)] = $_.Substring($separator + 1) }
  }
  if ($LASTEXITCODE -ne 0) { throw "Cannot inspect Docker container '$Name'." }
  return $map
}

function Get-FirstDockerNetwork {
  param([string]$Name)
  $networks = (docker inspect $Name --format '{{json .NetworkSettings.Networks}}' | ConvertFrom-Json)
  if ($LASTEXITCODE -ne 0) { throw "Cannot inspect Docker network for '$Name'." }
  $names = @($networks.PSObject.Properties.Name)
  if ($names.Count -eq 0) { throw "Container '$Name' has no Docker network." }
  return $names[0]
}

$flowbalanceDir = Resolve-Path (Join-Path $PSScriptRoot "..")
$dbEnv = Get-ContainerEnvMap $MariaDbContainer
$network = Get-FirstDockerNetwork $MariaDbContainer
$dbName = if ($dbEnv["MYSQL_DATABASE"]) { $dbEnv["MYSQL_DATABASE"] } else { "database" }
$dbUser = $dbEnv["MYSQL_USER"]
$dbPassword = $dbEnv["MYSQL_PASSWORD"]
if (-not $dbUser) {
  $dbUser = "root"
  $dbPassword = $dbEnv["MYSQL_ROOT_PASSWORD"]
}
if (-not $dbPassword) { throw "No MariaDB password was found in '$MariaDbContainer' environment." }

docker build -t $ImageName $flowbalanceDir
if ($LASTEXITCODE -ne 0) { throw "FlowBalance image build failed." }

$existingContainer = docker ps -aq --filter "name=^/$ContainerName$"
if ($existingContainer) { docker rm -f $ContainerName | Out-Null }
docker run -d --name $ContainerName --network $network -p "${HostPort}:8891" `
  -e "FLOWBALANCE_DB_HOST=$MariaDbContainer" `
  -e "FLOWBALANCE_DB_PORT=3306" `
  -e "FLOWBALANCE_DB_USER=$dbUser" `
  -e "FLOWBALANCE_DB_PASSWORD=$dbPassword" `
  -e "FLOWBALANCE_DB_NAME=$dbName" `
  -e "FLOWBALANCE_ALLOWED_ORIGIN=http://localhost:5174" `
  $ImageName | Out-Null
if ($LASTEXITCODE -ne 0) { throw "FlowBalance container start failed." }

Write-Host "FlowBalance started: http://127.0.0.1:$HostPort/flowbalance/health"
Write-Host "Container '$ContainerName' joined Docker network '$network'."
