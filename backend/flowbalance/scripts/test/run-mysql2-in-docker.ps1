param(
  [string]$MariaDbContainer = "mariadb",
  [string]$ImageName = "flowbalance-mysql2-integration:local"
)

$ErrorActionPreference = "Stop"

function Get-ContainerEnvMap {
  param([string]$ContainerName)

  $envLines = docker inspect $ContainerName --format '{{range .Config.Env}}{{println .}}{{end}}'
  $map = @{}
  foreach ($line in $envLines) {
    $separator = $line.IndexOf("=")
    if ($separator -le 0) { continue }
    $name = $line.Substring(0, $separator)
    $value = $line.Substring($separator + 1)
    $map[$name] = $value
  }
  return $map
}

function Get-FirstDockerNetwork {
  param([string]$ContainerName)

  $networkJson = docker inspect $ContainerName --format '{{json .NetworkSettings.Networks}}'
  $networks = $networkJson | ConvertFrom-Json
  $names = @($networks.PSObject.Properties.Name)
  if (-not $names -or $names.Count -eq 0) {
    throw "Container '$ContainerName' is not attached to a Docker network."
  }
  return $names[0]
}

function Invoke-Native {
  param(
    [string]$FilePath,
    [string[]]$Arguments,
    [string]$DisplayName = $FilePath
  )

  & $FilePath @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw "'$DisplayName' failed with exit code $LASTEXITCODE."
  }
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$flowbalanceDir = Resolve-Path (Join-Path $scriptDir "../..")

$dbEnv = Get-ContainerEnvMap -ContainerName $MariaDbContainer
$network = Get-FirstDockerNetwork -ContainerName $MariaDbContainer

$dbName = $dbEnv["MYSQL_DATABASE"]
if (-not $dbName) { $dbName = "database" }

$dbUser = $dbEnv["MYSQL_USER"]
$dbPassword = $dbEnv["MYSQL_PASSWORD"]
if (-not $dbUser) {
  $dbUser = "root"
  $dbPassword = $dbEnv["MYSQL_ROOT_PASSWORD"]
}
if (-not $dbPassword) {
  throw "Could not read a database password from '$MariaDbContainer' environment."
}

Write-Host "Building FlowBalance mysql2 integration image..."
Invoke-Native "docker" @("build", "-f", (Join-Path $flowbalanceDir "Dockerfile.integration"), "-t", $ImageName, $flowbalanceDir) "docker build integration image"

Write-Host "Running mysql2 integration test on Docker network '$network' with DB_HOST='$MariaDbContainer' and DB_PORT='3306'."
Invoke-Native "docker" @(
  "run", "--rm",
  "--network", $network,
  "-e", "FLOWBALANCE_DB_HOST=$MariaDbContainer",
  "-e", "FLOWBALANCE_DB_PORT=3306",
  "-e", "FLOWBALANCE_DB_USER=$dbUser",
  "-e", "FLOWBALANCE_DB_PASSWORD=$dbPassword",
  "-e", "FLOWBALANCE_DB_NAME=$dbName",
  $ImageName
) "docker run mysql2 integration test"
