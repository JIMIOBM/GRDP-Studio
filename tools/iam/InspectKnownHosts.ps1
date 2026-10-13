param(
  [Parameter(Mandatory=$true)][string]$IamInstall,
  [Parameter(Mandatory=$true)][string]$KnownHostsFile,
  [string]$SetPipesimFolder,
  [string]$OutputFile
)
$ErrorActionPreference = 'Stop'
$resolver = [ResolveEventHandler]{
  param($sender, $args)
  $path = Join-Path $IamInstall ((New-Object Reflection.AssemblyName($args.Name)).Name + '.dll')
  if (Test-Path -LiteralPath $path) { return [Reflection.Assembly]::LoadFrom($path) }
  return $null
}
$null = [AppDomain]::CurrentDomain.add_AssemblyResolve($resolver)
Get-ChildItem -LiteralPath $IamInstall -Filter 'Schlumberger.Avocet.Workflows.dll' -Recurse |
  ForEach-Object { [Reflection.Assembly]::LoadFrom($_.FullName) | Out-Null }
$formatter = New-Object Runtime.Serialization.Formatters.Binary.BinaryFormatter
$stream = [IO.File]::OpenRead($KnownHostsFile)
try { $hosts = $formatter.Deserialize($stream) } finally { $stream.Dispose() }
if ($SetPipesimFolder) {
  if (-not $OutputFile) { throw 'OutputFile is required when setting a PIPESIM folder.' }
  foreach ($hostObject in $hosts) {
    foreach ($field in $hostObject.GetType().GetFields([Reflection.BindingFlags]'Instance,Public,NonPublic')) {
      $value = $field.GetValue($hostObject)
      if ($value -is [Collections.IDictionary]) {
        foreach ($key in @('PIPESIM_STINGRAY_FOLDER','PIPESIM_FOLDER')) {
          foreach ($entryKey in @($value.Keys)) {
            if ($entryKey.ToString() -eq $key) {
              $entry = $value[$entryKey]
              $pathField = $entry.GetType().GetField('_path', [Reflection.BindingFlags]'Instance,Public,NonPublic')
              if (-not $pathField) { $pathField = $entry.GetType().GetField('path', [Reflection.BindingFlags]'Instance,Public,NonPublic') }
              if (-not $pathField) { throw "Could not locate path field for $key" }
              $pathField.SetValue($entry, $SetPipesimFolder)
              break
            }
          }
        }
      }
    }
  }
  $outputStream = [IO.File]::Create($OutputFile)
  try { $formatter.Serialize($outputStream, $hosts) } finally { $outputStream.Dispose() }
  $KnownHostsFile = $OutputFile
  $stream = [IO.File]::OpenRead($KnownHostsFile)
  try { $hosts = $formatter.Deserialize($stream) } finally { $stream.Dispose() }
}
"container=$($hosts.GetType().FullName) count=$($hosts.Count)"
foreach ($hostObject in $hosts) {
  "hostType=$($hostObject.GetType().FullName) name=$($hostObject.Name)"
  foreach ($field in $hostObject.GetType().GetFields([Reflection.BindingFlags]'Instance,Public,NonPublic')) {
    try {
      $value = $field.GetValue($hostObject)
      if ($value -is [Collections.IDictionary]) {
        foreach ($key in $value.Keys) {
          $entry = $value[$key]
          $fields = @($entry.GetType().GetFields([Reflection.BindingFlags]'Instance,Public,NonPublic') | ForEach-Object { "$($_.Name)=$($_.GetValue($entry))" })
          "  $($field.Name)[$key]=$($fields -join ',')"
        }
      } elseif ($null -ne $value -and $value -isnot [string] -and $value -isnot [ValueType]) {
        "  $($field.Name)=$($value.GetType().FullName)"
      }
    } catch {}
  }
  foreach ($property in $hostObject.GetType().GetProperties()) {
    try {
      $value = $property.GetValue($hostObject, $null)
      if ($value -is [Collections.IDictionary]) {
        foreach ($key in $value.Keys) { "  $($property.Name)[$key]=$($value[$key])" }
      } elseif ($null -ne $value -and $value -isnot [string] -and $value -isnot [ValueType]) {
        "  $($property.Name)=$($value.GetType().FullName)"
      }
    } catch {}
  }
}
