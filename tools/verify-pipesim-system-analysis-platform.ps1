<#
CC05-SA1 native collection, strict comparison and actual platform evidence.
Comparison-only/native evidence is NOT a platform, browser or coupled-solve pass.
All writes are new evidence/copies outside the official installation. No credentials are logged.
#>
[CmdletBinding(DefaultParameterSetName = 'Native')]
param(
    [Parameter(Mandatory)][string]$PipesimHome,
    [Parameter(Mandatory)][string]$PythonPath,
    [Parameter(Mandatory)][string]$EvidenceDirectory,
    [string]$PtkPath,
    [string]$WorkerBase = 'http://127.0.0.1:5150',
    [Parameter(Mandatory, ParameterSetName = 'Native')][switch]$NativeOnly,
    [Parameter(Mandatory, ParameterSetName = 'Platform')][switch]$Platform,
    [Parameter(Mandatory, ParameterSetName = 'Compare')][switch]$CompareOnly,
    [Parameter(Mandatory, ParameterSetName = 'Rejections')][switch]$NativeRejections,
    [Parameter(Mandatory, ParameterSetName = 'Platform')]
    [Parameter(Mandatory, ParameterSetName = 'Rejections')]
    [Parameter(Mandatory, ParameterSetName = 'Compare')][string]$NativeEvidenceDirectory,
    [string]$ReferenceManifestPath = (Join-Path $PSScriptRoot '../docs/software-integration/execution/CC05-SA1/native-reference-manifest.json'),
    [Parameter(Mandatory, ParameterSetName = 'Platform')][string]$ApiBase,
    [Parameter(Mandatory, ParameterSetName = 'Platform')][string]$FrontendBase,
    [Parameter(Mandatory, ParameterSetName = 'Platform')][int]$BackendProcessId,
    [Parameter(Mandatory, ParameterSetName = 'Platform')][string]$BackendClassesDirectory,
    [Parameter(Mandatory, ParameterSetName = 'Platform')][int]$WorkerProcessId,
    [Parameter(Mandatory, ParameterSetName = 'Platform')][string]$WorkerDllPath,
    [Parameter(Mandatory, ParameterSetName = 'Platform')][string]$StorageRoot,
    [string]$TokenEnvironmentVariable = 'GRDP_SA_ACCEPTANCE_TOKEN',
    [ValidateRange(30, 3600)][int]$TimeoutSeconds = 600
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$installation = (Resolve-Path -LiteralPath $PipesimHome).Path
$python = (Resolve-Path -LiteralPath $PythonPath).Path
if (-not $PtkPath) { $PtkPath = Join-Path $installation 'Developer Tools\Python Toolkit\Modules\PythonToolkitModules.zip' }
$toolkit = (Resolve-Path -LiteralPath $PtkPath).Path
$output = [System.IO.Path]::GetFullPath($EvidenceDirectory)
if (Test-Path -LiteralPath $output) { throw 'Evidence directory already exists; use a new directory, do not overwrite an earlier attempt.' }
foreach ($root in @($installation, $repoRoot)) {
    if ($output.Equals($root, [StringComparison]::OrdinalIgnoreCase) -or
        $output.StartsWith($root.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Evidence must be outside the installation and repository.'
    }
}
function Assert-Evidence([bool]$Condition, [string]$Code) {
    if (-not $Condition) { throw $Code }
}
function Read-EvidenceJson([string]$Path) {
    return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -AsHashtable
}
function Write-EvidenceJson([string]$Name, $Value) {
    $path = Join-Path $output $Name
    $bytes = [Text.Encoding]::UTF8.GetBytes(($Value | ConvertTo-Json -Depth 100))
    $file = [IO.File]::Open($path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
    try { $file.Write($bytes, 0, $bytes.Length) } finally { $file.Dispose() }
}
function Assert-Hash([string]$Path, [string]$Expected) {
    Assert-Evidence ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -ceq $Expected.ToUpperInvariant()) 'EVIDENCE_SHA_MISMATCH'
}
function Test-JsonNumber($Value) {
    return $Value -is [byte] -or $Value -is [int16] -or $Value -is [int32] -or $Value -is [int64] -or
        $Value -is [single] -or $Value -is [double] -or $Value -is [decimal]
}
function Compare-SaValue($Left, $Right, $Limits, [string]$Path, $Stats) {
    if ($null -eq $Left -or $null -eq $Right) {
        Assert-Evidence ($null -eq $Left -and $null -eq $Right) ('COMPARE_NULL:' + $Path)
        return
    }
    if ($Left -is [bool] -or $Right -is [bool]) {
        Assert-Evidence ($Left -is [bool] -and $Right -is [bool] -and $Left -eq $Right) ('COMPARE_BOOLEAN:' + $Path)
        return
    }
    if ((Test-JsonNumber $Left) -or (Test-JsonNumber $Right)) {
        Assert-Evidence ((Test-JsonNumber $Left) -and (Test-JsonNumber $Right)) ('COMPARE_NUMBER_TYPE:' + $Path)
        Assert-Evidence ([double]::IsFinite([double]$Left) -and [double]::IsFinite([double]$Right)) ('COMPARE_NONFINITE:' + $Path)
        Assert-Evidence ($Limits.Keys -ccontains $Path) ('COMPARE_NO_FROZEN_LIMIT:' + $Path)
        $limit = [double]$Limits[$Path]
        Assert-Evidence ([double]::IsFinite($limit) -and $limit -ge 0) ('COMPARE_BAD_LIMIT:' + $Path)
        $delta = [Math]::Abs([double]$Left - [double]$Right)
        Assert-Evidence ($delta -le $limit) ('COMPARE_VALUE:' + $Path)
        $Stats.numericPaths++
        $Stats.maximumAbsoluteDelta = [Math]::Max($Stats.maximumAbsoluteDelta, $delta)
        return
    }
    if ($Left -is [System.Collections.IDictionary] -or $Right -is [System.Collections.IDictionary]) {
        Assert-Evidence ($Left -is [System.Collections.IDictionary] -and $Right -is [System.Collections.IDictionary] -and $Left.Count -eq $Right.Count) ('COMPARE_FIELDS:' + $Path)
        foreach ($key in $Left.Keys) {
            Assert-Evidence ($Right.Keys -ccontains $key) ('COMPARE_FIELD_NAME:' + $Path)
            Compare-SaValue $Left[$key] $Right[$key] $Limits ($Path + '/' + $key) $Stats
        }
        return
    }
    if ($Left -is [array] -or $Right -is [array]) {
        Assert-Evidence ($Left -is [array] -and $Right -is [array] -and $Left.Count -eq $Right.Count) ('COMPARE_LENGTH:' + $Path)
        for ($i = 0; $i -lt $Left.Count; $i++) { Compare-SaValue $Left[$i] $Right[$i] $Limits ($Path + '/' + $i) $Stats }
        return
    }
    Assert-Evidence ($Left -is [string] -and $Right -is [string] -and $Left -ceq $Right) ('COMPARE_TEXT_UNIT:' + $Path)
}
function Compare-SaResult($Expected, $Actual, $Limits) {
    $stats = @{ numericPaths = 0; maximumAbsoluteDelta = 0.0 }
    Compare-SaValue $Expected $Actual $Limits '' $stats
    Assert-Evidence ($stats.numericPaths -eq $Limits.Count) 'COMPARE_NUMERIC_COVERAGE'
    return $stats
}
function Get-FrozenReferences {
    $manifest = Read-EvidenceJson $ReferenceManifestPath
    Assert-Evidence ($manifest.schemaVersion -ceq 'cc05-sa1-native-reference-manifest/1' -and $manifest.frozenBeforePlatform -eq $true -and $manifest.models.Count -eq 2) 'INVALID_REFERENCE_MANIFEST'
    Assert-Hash (Join-Path $repoRoot 'worker/ptk_run.py') $manifest.adapterSha256
    Assert-Hash $toolkit $manifest.toolkitSha256
    $native = (Resolve-Path -LiteralPath $NativeEvidenceDirectory).Path
    $summary = Read-EvidenceJson (Join-Path $native 'native-summary.json')
    Assert-Evidence ($summary.nativeReferencesVerified -eq $true -and $summary.adapterSha256.ToUpperInvariant() -ceq $manifest.adapterSha256) 'NATIVE_REFERENCE_NOT_VERIFIED'
    $references = @()
    foreach ($entry in $manifest.models) {
        Assert-Evidence ($entry.model -cin @('N301','N312')) 'UNEXPECTED_REFERENCE_MODEL'
        $source = Join-Path $installation $entry.relativeSource
        $referencePath = Join-Path $native ($entry.model + '-reference-0.json')
        $repeatPath = Join-Path $native ($entry.model + '-repeatability.json')
        Assert-Hash $source $entry.sourceSha256
        Assert-Hash $referencePath $entry.referenceSha256
        Assert-Hash $repeatPath $entry.repeatabilitySha256
        $audit = @($summary.models | Where-Object { $_.model -ceq $entry.model })
        Assert-Evidence ($audit.Count -eq 1 -and $audit[0].sourceUnchanged -eq $true -and $audit[0].closed.Count -eq 2 -and $audit[0].sourceSha256.ToUpperInvariant() -ceq $entry.sourceSha256) 'NATIVE_SOURCE_OR_CLOSE_UNVERIFIED'
        $repeat = Read-EvidenceJson $repeatPath
        Assert-Evidence ($repeat.frozenBeforePlatform -eq $true) 'UNFROZEN_NATIVE_TOLERANCE'
        $reference = Read-EvidenceJson $referencePath
        $self = Compare-SaResult $reference $reference $repeat.absoluteTolerances
        $references += @{ model=$entry.model; source=$source; sourceSha256=$entry.sourceSha256; reference=$reference;
            limits=$repeat.absoluteTolerances; request=$audit[0].request; study=$audit[0].study; selfComparison=$self }
    }
    Assert-Evidence (@($references.model | Select-Object -Unique).Count -eq 2) 'DUPLICATE_REFERENCE_MODEL'
    return $references
}
function Test-Comparator($References) {
    $tests = @()
    foreach ($item in $References) {
        foreach ($fault in @('unit','missing-case','numeric-delta','study')) {
            $changed = ($item.reference | ConvertTo-Json -Depth 100) | ConvertFrom-Json -AsHashtable
            switch ($fault) {
                'unit' { $changed.cases[0].system[0].unit = 'unsupported-unit' }
                'missing-case' { $changed.cases = @($changed.cases | Select-Object -Skip 1) }
                'numeric-delta' { $changed.cases[0].system[0].value += 1 + 10 * $item.limits['/cases/0/system/0/value'] }
                'study' { $changed.study = 'wrong-study' }
            }
            $rejected = $false
            $rejectionCode = $null
            try { $null = Compare-SaResult $item.reference $changed $item.limits }
            catch { if ($_.Exception.Message.StartsWith('COMPARE_')) { $rejected=$true; $rejectionCode=$_.Exception.Message } else { throw } }
            Assert-Evidence $rejected ('COMPARATOR_FAILED_TO_REJECT:' + $fault)
            $tests += @{ model=$item.model; fault=$fault; rejected=$true; rejectionCode=$rejectionCode }
            Write-Host ($item.model+' deliberate '+$fault+' RED: '+$rejectionCode)
        }
        $null = Compare-SaResult $item.reference $item.reference $item.limits
        Write-Host ($item.model+' restored reference GREEN')
    }
    return $tests
}
function Assert-LocalUri([string]$Value) {
    $uri = [uri]$Value
    Assert-Evidence ($uri.IsAbsoluteUri -and $uri.IsLoopback -and $uri.Scheme -cin @('http','https') -and -not $uri.UserInfo -and -not $uri.Query) 'NONLOCAL_OR_SECRET_SERVICE_URL'
    return $uri
}
function Get-LiveDeployment([string]$Base, [int]$ExpectedProcessId, [string]$RequiredCommandPart) {
    $uri = Assert-LocalUri $Base
    $listener = @(Get-NetTCPConnection -State Listen -LocalPort $uri.Port -ErrorAction Stop | Where-Object OwningProcess -eq $ExpectedProcessId)
    Assert-Evidence ($listener.Count -gt 0) 'DEPLOYMENT_PROCESS_PORT_MISMATCH'
    $process = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $ExpectedProcessId)
    $commandText = $process.CommandLine
    $argumentFiles = @()
    foreach($match in [regex]::Matches($process.CommandLine, '@(?:"([^"]+)"|([^\s]+))')) {
        $argumentFile = if($match.Groups[1].Success){$match.Groups[1].Value}else{$match.Groups[2].Value}
        $argumentInfo = Get-Item -LiteralPath $argumentFile
        Assert-Evidence ($argumentInfo.Length -lt 16MB -and $argumentInfo.LastWriteTimeUtc -le $process.CreationDate.ToUniversalTime()) 'DEPLOYMENT_ARGUMENT_FILE_CHANGED'
        $commandText += "`n" + (Get-Content -LiteralPath $argumentFile -Raw)
        $argumentFiles += @{path=$argumentFile;sha256=(Get-FileHash -LiteralPath $argumentFile).Hash}
    }
    # Maven launches Java through an @argfile whose classpath doubles backslashes.
    $command = ($commandText -replace '[\\/]+','/').ToLowerInvariant()
    $required = ($RequiredCommandPart -replace '[\\/]+','/').ToLowerInvariant()
    Assert-Evidence ($command.Contains($required)) 'DEPLOYMENT_COMMAND_MISMATCH'
    $commandHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($process.CommandLine)))
    return @{ processId=$ExpectedProcessId; executable=$process.ExecutablePath; createdAt=$process.CreationDate; commandLineSha256=$commandHash; argumentFiles=$argumentFiles; port=$uri.Port }
}
function Invoke-SaHttp([string]$Method, [string]$Url, $Body=$null, $Content=$null, [int]$ExpectedStatus=200, [switch]$Binary) {
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), $Url)
    if ($null -ne $Content) { $request.Content=$Content }
    elseif ($null -ne $Body) { $request.Content=[Net.Http.StringContent]::new(($Body | ConvertTo-Json -Depth 100), [Text.Encoding]::UTF8, 'application/json') }
    $response=$null
    try {
        $response=$script:saHttp.SendAsync($request).GetAwaiter().GetResult()
        $bytes=$response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        if ($Binary) {
            Assert-Evidence ([int]$response.StatusCode -eq $ExpectedStatus) ('HTTP_ARTIFACT_STATUS:' + [int]$response.StatusCode)
            return ,$bytes
        }
        $json=[Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json -AsHashtable
        Assert-Evidence ([int]$response.StatusCode -eq $ExpectedStatus -and $json.code -eq $ExpectedStatus) ('HTTP_API_STATUS:' + [int]$response.StatusCode + ':' + $json.code)
        return $json
    } finally { if($null -ne $response) {$response.Dispose()}; $request.Dispose() }
}
function Wait-SaRun([long]$RunId) {
    $clock=[Diagnostics.Stopwatch]::StartNew()
    do {
        $run=(Invoke-SaHttp GET ($ApiBase.TrimEnd('/') + '/runs/' + $RunId)).data
        if ($run.status -cin @('SUCCEEDED','FAILED','PARTIAL_SUCCEEDED','CANCELLED','TIMED_OUT')) { return $run }
        Assert-Evidence ($clock.Elapsed.TotalSeconds -lt $TimeoutSeconds) ('RUN_OBSERVATION_TIMEOUT:' + $RunId)
        Start-Sleep -Milliseconds 1000
    } while ($true)
}
function Assert-SaRun($Run, $Item, [long]$VersionId) {
    Assert-Evidence ($Run.status -ceq 'SUCCEEDED' -and $Run.resultContract -ceq 'VALID_FULL' -and $null -eq $Run.error -and -not $Run.resultExpired) ('RUN_NOT_FULL:' + $Run.id + ':' + $Run.status)
    Assert-Evidence ($Run.modelVersionId -eq $VersionId -and $Run.study -ceq $Item.study -and $Run.runType -ceq 'system-analysis') 'RUN_IDENTITY_MISMATCH'
    Assert-Evidence ($Run.parameters.Count -eq $Item.request.Count) 'RUN_REQUEST_FIELDS'
    foreach($key in $Item.request.Keys) {
        Assert-Evidence ($Run.parameters.Keys -ccontains $key) 'RUN_REQUEST_FIELD_NAME'
        if ($key -ceq 'values') {
            Assert-Evidence ($Run.parameters.values.Count -eq $Item.request.values.Count) 'RUN_REQUEST_AXIS_COUNT'
            for($i=0;$i -lt $Item.request.values.Count;$i++) { Assert-Evidence ($Run.parameters.values[$i] -eq $Item.request.values[$i]) 'RUN_REQUEST_AXIS_VALUE' }
        } else { Assert-Evidence ($Run.parameters[$key] -ceq $Item.request[$key]) 'RUN_REQUEST_VALUE' }
    }
    Assert-Evidence ($Run.cleanup.processTreeExitConfirmed -eq $true -and $Run.cleanup.inputDeleted -eq $true -and $Run.cleanup.workDirectoryDeleted -eq $true) 'RUN_CLEANUP_UNCONFIRMED'
    return Compare-SaResult $Item.reference $Run.result $Item.limits
}
function Download-SaArtifacts($Run, $Item) {
    Assert-Evidence ($Run.artifacts.Count -gt 0) 'RUN_HAS_NO_ARTIFACTS'
    $downloads=@()
    $directory=Join-Path $output ('run-' + $Run.id + '-downloads')
    $null=[IO.Directory]::CreateDirectory($directory)
    foreach($artifact in $Run.artifacts) {
        Assert-Evidence ([IO.Path]::GetFileName($artifact.name) -ceq $artifact.name) 'UNSAFE_ARTIFACT_NAME'
        $path=Join-Path $directory $artifact.name
        Assert-Evidence (-not (Test-Path -LiteralPath $path)) 'DUPLICATE_ARTIFACT_NAME'
        $bytes=Invoke-SaHttp GET ($ApiBase.TrimEnd('/') + '/runs/' + $Run.id + '/artifacts/' + $artifact.id + '/download') -Binary
        Assert-Evidence ($bytes.Length -eq $artifact.sizeBytes) 'ARTIFACT_SIZE_MISMATCH'
        $file=[IO.File]::Open($path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write)
        try {$file.Write($bytes,0,$bytes.Length)} finally {$file.Dispose()}
        Assert-Hash $path $artifact.sha256
        $downloads+=@{id=$artifact.id;name=$artifact.name;sizeBytes=$bytes.Length;sha256=$artifact.sha256}
    }
    Assert-Evidence ($downloads.name -ccontains 'request.json' -and $downloads.name -ccontains 'normalized-result.json' -and $downloads.name -ccontains 'raw-response.json') 'REPLAY_ARTIFACT_MISSING'
    $snapshot=Read-EvidenceJson (Join-Path $directory 'request.json')
    Assert-Evidence ($snapshot.runId -eq $Run.id -and $snapshot.study -ceq $Run.study -and $snapshot.runTask -ceq $Run.runType -and $snapshot.expectedModelSha256.ToUpperInvariant() -ceq $Item.sourceSha256) 'SNAPSHOT_IDENTITY_MISMATCH'
    $storage=(Resolve-Path -LiteralPath $StorageRoot).Path
    Assert-Evidence (-not [IO.Path]::IsPathRooted($snapshot.modelStorageKey)) 'ABSOLUTE_MODEL_STORAGE_KEY'
    $uploaded=[IO.Path]::GetFullPath((Join-Path $storage $snapshot.modelStorageKey))
    Assert-Evidence ($uploaded.StartsWith($storage.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase)) 'MODEL_STORAGE_KEY_ESCAPE'
    Assert-Hash $uploaded $Item.sourceSha256
    $normalized=Read-EvidenceJson (Join-Path $directory 'normalized-result.json')
    $null=Compare-SaResult $Item.reference $normalized $Item.limits
    return @{artifacts=$downloads; snapshot=$snapshot; uploadedSourceSha256=(Get-FileHash -LiteralPath $uploaded).Hash}
}
function Invoke-SaPlatform($References) {
    $null=Assert-LocalUri $ApiBase
    $null=Assert-LocalUri $FrontendBase
    $null=Assert-LocalUri $WorkerBase
    Assert-Evidence ($ApiBase.TrimEnd('/').EndsWith('/software-integration')) 'API_PREFIX_MISMATCH'
    $classes=(Resolve-Path -LiteralPath $BackendClassesDirectory).Path
    $workerDll=(Resolve-Path -LiteralPath $WorkerDllPath).Path
    $backend=Get-LiveDeployment $ApiBase $BackendProcessId $classes
    $worker=Get-LiveDeployment $WorkerBase $WorkerProcessId $workerDll
    $deployedScript=Join-Path (Split-Path -Parent $workerDll) 'ptk_run.py'
    Assert-Hash $deployedScript (Get-FileHash -LiteralPath (Join-Path $repoRoot 'worker/ptk_run.py')).Hash
    $deployedValidationScript=Join-Path (Split-Path -Parent $workerDll) 'ptk_validate.py'
    Assert-Hash $deployedValidationScript (Get-FileHash -LiteralPath (Join-Path $repoRoot 'worker/ptk_validate.py')).Hash
    $health=Invoke-RestMethod -Uri ($WorkerBase.TrimEnd('/') + '/api/health') -TimeoutSec 10
    Assert-Evidence ($health.status -ceq 'UP' -and $health.idle -eq $true -and $null -eq $health.activeRunId) 'WORKER_NOT_IDLE'
    $report=@{platformApiVerified=$false; platformVerified=$false; browserAndNativeRejectionVerified=$false; databaseScope='runtime configuration; not a production persistence/restart pass'; projectId=$null; models=@(); workerGeneration=$health.generationId; stages=@()}
    $script:saStage='preflight'
    $script:saKnownRunIds=@()
    $script:saHttp=[Net.Http.HttpClient]::new()
    $script:saHttp.Timeout=[TimeSpan]::FromSeconds(45)
    $token=[Environment]::GetEnvironmentVariable($TokenEnvironmentVariable)
    if($token){$script:saHttp.DefaultRequestHeaders.Add('token',$token)}
    try {
        $capability=(Invoke-SaHttp GET ($ApiBase.TrimEnd('/')+'/capabilities')).data
        Assert-Evidence ($capability.worker.status -ceq 'AVAILABLE' -and $capability.pipesimNetwork.runTasks -ccontains 'system-analysis') 'SYSTEM_ANALYSIS_CAPABILITY_UNAVAILABLE'
        $null=Invoke-SaHttp GET ($ApiBase.TrimEnd('/')+'/projects')
        $sourceFiles=@('vue/src/views/SoftwareIntegration/systemAnalysisParameters.js','worker/ptk_run.py','worker/ptk_validate.py','worker/Execution/PtkRunService.cs','backend/src/main/java/com/grdp/studio/softwareintegration/support/SystemAnalysisParameters.java','backend/src/main/java/com/grdp/studio/softwareintegration/execution/PipesimSystemAnalysisResultValidator.java')
        $sourceHashes=@($sourceFiles | ForEach-Object {@{file=$_;sha256=(Get-FileHash -LiteralPath (Join-Path $repoRoot $_)).Hash}})
        $classHashes=@(Get-ChildItem -LiteralPath (Join-Path $classes 'com/grdp/studio/softwareintegration') -Filter '*.class' -Recurse | ForEach-Object {@{file=$_.FullName.Substring($classes.Length);sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}})
        Assert-Evidence ($classHashes.Count -gt 0) 'BACKEND_CLASSES_MISSING'
        $helper=Invoke-WebRequest -Uri ($FrontendBase.TrimEnd('/')+'/src/views/SoftwareIntegration/systemAnalysisParameters.js') -TimeoutSec 15
        Assert-Evidence ($helper.StatusCode -eq 200 -and $helper.Content.Contains('JSON.parse(JSON.stringify(result))')) 'FRONTEND_FIX_NOT_SERVED'
        Write-EvidenceJson 'deployment.json' @{backend=$backend;worker=$worker;workerDllSha256=(Get-FileHash -LiteralPath $workerDll).Hash;deployedScriptSha256=(Get-FileHash -LiteralPath $deployedScript).Hash;deployedValidationScriptSha256=(Get-FileHash -LiteralPath $deployedValidationScript).Hash;sourceHashes=$sourceHashes;classHashes=$classHashes;capability=$capability;health=$health;frontendHelperSha256=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($helper.Content)));tokenProvided=[bool]$token}
        $script:saStage='create-project'
        $project=(Invoke-SaHttp POST ($ApiBase.TrimEnd('/')+'/projects') @{name=('CC05-SA1-'+[IO.Path]::GetFileName($output));description='Dedicated real API/SDK acceptance; isolated test database.'}).data
        $report.projectId=$project.id
        Write-EvidenceJson 'project-created.json' $project
        foreach($item in $References) {
            $script:saStage=$item.model+':upload'
            $form=[Net.Http.MultipartFormDataContent]::new()
            $content=[Net.Http.StreamContent]::new([IO.File]::OpenRead($item.source))
            $form.Add($content,'file',[IO.Path]::GetFileName($item.source))
            $uploaded=(Invoke-SaHttp POST ($ApiBase.TrimEnd('/')+'/projects/'+$project.id+'/models') -Content $form).data
            Write-EvidenceJson ($item.model+'-uploaded.json') $uploaded
            $clock=[Diagnostics.Stopwatch]::StartNew()
            do {
                $detail=(Invoke-SaHttp GET ($ApiBase.TrimEnd('/')+'/projects/'+$project.id)).data
                $versions=@($detail.models.versions | Where-Object {$_.originalName -ceq [IO.Path]::GetFileName($item.source)})
                Assert-Evidence ($versions.Count -eq 1) 'UPLOADED_VERSION_IDENTITY_AMBIGUOUS'
                $version=$versions[0]
                if($version.status -ceq 'READY'){break}
                Assert-Evidence ($version.status -cnotin @('INVALID','FAILED') -and $clock.Elapsed.TotalSeconds -lt $TimeoutSeconds) 'UPLOADED_VERSION_NOT_READY'
                Start-Sleep -Milliseconds 1000
            } while($true)
            $networkInspection=$version.inspection
            if($version.modelKind -cne 'network') {
                Assert-Evidence ($version.inspection.networkCapability.schemaVersion -ceq 'pipesim-network-capability/1' -and $version.inspection.networkCapability.studies -ccontains $item.study) 'MIXED_MODEL_NETWORK_STUDY_UNVERIFIED'
                $networkInspection=$version.inspection.networkCapability.networkInspection
            }
            Assert-Evidence ($version.sha256.ToUpperInvariant() -ceq $item.sourceSha256 -and $version.studies -ccontains $item.study -and $networkInspection.wellContexts -ccontains $item.request.producer -and $networkInspection.flowlineContexts -ccontains $item.request.branchTerminator -and $networkInspection.studies.study -ccontains $item.study) 'INSPECTION_SOURCE_CONTEXT_MISMATCH'
            Write-EvidenceJson ($item.model+'-ready-version.json') $version
            $modelReport=@{model=$item.model;versionId=$version.id;runIds=@();comparisons=@();downloads=@();sourceUnchanged=$false}
            $report.models+=,$modelReport
            $parameters=$item.request
            for($replay=0;$replay -lt 2;$replay++) {
                $script:saStage=$item.model+':submit-'+$replay
                $accepted=(Invoke-SaHttp POST ($ApiBase.TrimEnd('/')+'/model-versions/'+$version.id+'/runs') @{study=$item.study;runType='system-analysis';parameters=$parameters} -ExpectedStatus 201).data
                $script:saKnownRunIds+=,$accepted.id
                $modelReport.runIds+=,$accepted.id
                Write-EvidenceJson ('run-'+$accepted.id+'-accepted.json') $accepted
                $script:saStage=$item.model+':observe-'+$accepted.id
                $run=Wait-SaRun $accepted.id
                Write-EvidenceJson ('run-'+$run.id+'-detail.json') $run
                $modelReport.comparisons+=,(Assert-SaRun $run $item $version.id)
                $history=(Invoke-SaHttp GET ($ApiBase.TrimEnd('/')+'/model-versions/'+$version.id+'/runs')).data
                Assert-Evidence (@($history | Where-Object {$_.id -eq $run.id}).Count -eq 1) 'RUN_MISSING_FROM_HISTORY'
                $refreshed=(Invoke-SaHttp GET ($ApiBase.TrimEnd('/')+'/runs/'+$run.id)).data
                $null=Assert-SaRun $refreshed $item $version.id
                Write-EvidenceJson ('run-'+$run.id+'-history-refreshed.json') $refreshed
                $frontend=(Invoke-SaHttp GET ($FrontendBase.TrimEnd('/')+'/api/software-integration/runs/'+$run.id)).data
                $null=Assert-SaRun $frontend $item $version.id
                Write-EvidenceJson ('run-'+$run.id+'-frontend-proxy.json') $frontend
                $script:saStage=$item.model+':download-'+$run.id
                $download=Download-SaArtifacts $run $item
                $modelReport.downloads+=,@{runId=$run.id;artifacts=$download.artifacts;uploadedSourceSha256=$download.uploadedSourceSha256}
                $parameters=$download.snapshot.parameters
                Assert-Hash $item.source $item.sourceSha256
                $modelReport.sourceUnchanged=$true
                Write-Output ($item.model+' Run '+$run.id+' native comparison/history/download verified; replay='+$replay)
            }
        }
        $finalHealth=Invoke-RestMethod -Uri ($WorkerBase.TrimEnd('/')+'/api/health') -TimeoutSec 10
        Assert-Evidence ($finalHealth.idle -eq $true -and $null -eq $finalHealth.activeRunId -and $finalHealth.generationId -ceq $health.generationId) 'WORKER_FINAL_EXIT_OR_GENERATION_UNVERIFIED'
        $report.platformApiVerified=$true
        $report.workerFinalHealth=$finalHealth
        Write-EvidenceJson 'platform-summary.json' $report
        Write-Output ('Platform API evidence verified: '+$output+'; commercial browser/release acceptance remains separate.')
    } catch {
        Write-EvidenceJson 'platform-failure.json' @{platformVerified=$false;stage=$script:saStage;knownRunIds=$script:saKnownRunIds;report=$report;failureCode=$_.Exception.Message}
        throw
    } finally {$script:saHttp.Dispose()}
}
if ($NativeRejections) {
    $references=Get-FrozenReferences
    $health=Invoke-RestMethod -Uri ($WorkerBase.TrimEnd('/')+'/api/health') -TimeoutSec 10
    Assert-Evidence ($health.status -ceq 'UP' -and $health.idle -eq $true -and $null -eq $health.activeRunId) 'WORKER_NOT_IDLE'
    $mutex=[Threading.Mutex]::new($false,'Global\GRDP-Pipesim-Golden-Capture')
    $owned=$false
    try {
        try {$owned=$mutex.WaitOne(0)} catch [Threading.AbandonedMutexException] {$owned=$true}
        Assert-Evidence $owned 'NATIVE_LEASE_BUSY'
        $probe=@'
import copy, hashlib, json, os, pathlib, shutil, sys
repo, home, toolkit, output, generation = sys.argv[1:]
sys.path.insert(0, toolkit)
sys.path.insert(0, os.path.join(repo, "worker"))
import ptk_run as adapter
from sixgill.pipesim import Model
from sixgill.definitions import Units

root = pathlib.Path(output)
root.mkdir(parents=True, exist_ok=False)
source = pathlib.Path(home) / "Case Studies" / "Network Models" / "CSN_312_Gas Lift Optimization.pips"
def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
def save(name, value):
    with open(root / name, "x", encoding="utf-8") as target:
        json.dump(value, target, ensure_ascii=False, indent=2, allow_nan=False)
source_hash = digest(source)
baseline = {"schemaVersion": "pipesim-system-analysis-parameters/2", "producer": "Well_1",
            "branchTerminator": "Flowline_1", "outletPressurePsi": 600,
            "scanVariable": "liquidFlowRate", "values": [2400, 3000, 3600]}
trials = [("missing-producer", Units.FIELD, "Study 1", {"producer": "CC05_SA1_Missing_Well"}, "SYSTEM_ANALYSIS_PRODUCER_NOT_FOUND"),
          ("wrong-study", Units.FIELD, "CC05_SA1_Missing_Study", {}, "STUDY_NOT_FOUND"),
          ("unreachable-branch", Units.FIELD, "Study 1", {"branchTerminator": "Flowline_2"}, "SYSTEM_ANALYSIS_BRANCH_UNREACHABLE"),
          ("unsupported-metric-view", Units.METRIC, "Study 1", {}, "SYSTEM_ANALYSIS_UNIT_UNSUPPORTED"),
          ("restored-field", Units.FIELD, "Study 1", {}, None)]
audits = []
try:
    for name, units, study, changes, expected in trials:
        folder = root / name
        folder.mkdir()
        isolated = folder / source.name
        shutil.copy2(source, isolated)
        calls = {key: 0 for key in ("set_conditions", "get_conditions", "validate", "run", "close")}
        codes, inventory, close_exceptions = {}, {}, []
        def factory(path):
            # The public SDK unit option is the only variation; no fake Model/task.
            model = Model.open(path, units=units, in_memory_results=False)
            try:
                inventory.update(unitSystem=str(model.about.unit_system), wells=list(model.find(component="Well")),
                                 flowlines=list(model.find(component="Flowline")), connections=list(model.connections()),
                                 studies=adapter._study_names(model))
                task = model.tasks.systemanalysissimulation
                for key in calls:
                    method = getattr(model if key == "close" else task, key)
                    code = getattr(getattr(method, "__func__", method), "__code__", None)
                    if code is None or code in codes: raise RuntimeError("SDK_CALL_OBSERVATION_UNAVAILABLE")
                    codes[code] = key
                if name == "missing-producer" and changes["producer"] in inventory["wells"]:
                    raise RuntimeError("NEGATIVE_PRODUCER_EXISTS")
                if name == "wrong-study" and study in inventory["studies"]:
                    raise RuntimeError("NEGATIVE_STUDY_EXISTS")
                if name == "unreachable-branch":
                    pending, visited = [baseline["producer"]], set()
                    while pending:
                        node = pending.pop()
                        if node in visited: continue
                        visited.add(node)
                        pending.extend(edge["Destination"] for edge in inventory["connections"] if edge["Source"] == node)
                    if changes["branchTerminator"] not in inventory["flowlines"] or changes["branchTerminator"] in visited:
                        raise RuntimeError("NEGATIVE_BRANCH_NOT_EXISTING_UNREACHABLE")
                    inventory["producerReachableContexts"] = sorted(visited)
                return model
            except Exception:
                model.close()
                raise
        def observe(frame, event, arg):
            key = codes.get(frame.f_code)
            if key is not None:
                if event == "call": calls[key] += 1
                if event == "exception" and key == "close": close_exceptions.append(type(arg[1]).__name__)
            return observe
        sys.settrace(observe)
        try:
            envelope = adapter.execute_request({"modelPath": str(isolated), "study": study,
                "runTask": "system-analysis", "parameters": dict(copy.deepcopy(baseline), **changes)},
                model_factory=factory, emit_event=lambda *args: None)
        finally:
            sys.settrace(None)
        audit = {"test": name, "expectedError": expected, "actualError": (envelope.get("error") or {}).get("code"),
                 "status": envelope.get("status"), "calls": calls, "nativeInventory": inventory,
                 "closeExceptions": close_exceptions, "sourceSha256": source_hash,
                 "sourceUnchanged": digest(source) == source_hash, "workerGenerationObserved": generation,
                 "platformVerified": False}
        audits.append(audit)
        save(name + "-envelope.json", envelope)
        save(name + "-audit.json", audit)
        if not audit["sourceUnchanged"] or calls["close"] != 1 or close_exceptions:
            raise RuntimeError("SOURCE_OR_CLOSE_UNVERIFIED")
        if expected is not None:
            if envelope.get("status") != "error" or envelope.get("result") is not None or audit["actualError"] != expected:
                raise RuntimeError("NATIVE_REJECTION_MISMATCH")
            if any(calls[key] for key in ("set_conditions", "get_conditions", "validate", "run")):
                raise RuntimeError("REJECTED_INPUT_MUTATED_OR_SOLVED")
            print(name + " RED: " + expected + "; solver calls=0", flush=True)
        else:
            if envelope.get("status") != "ok" or calls["run"] != 3 or any(calls[key] < 3 for key in ("set_conditions", "get_conditions", "validate")):
                raise RuntimeError("RESTORED_POSITIVE_NOT_COMPLETED")
            save("N312-restored-result.json", envelope["result"])
            print("restored-field GREEN: 3 real solver calls; numeric comparison pending", flush=True)
    save("native-rejections.json", {"nativeRejectionsVerified": True, "platformVerified": False, "models": audits,
         "unitCaseScope": "Official METRIC engineering view; not an unknown stored unit mutation."})
except Exception as exc:
    save("native-rejections-failure.json", {"nativeRejectionsVerified": False, "platformVerified": False,
         "code": type(exc).__name__, "message": adapter._safe_exception_diagnostic(exc), "models": audits})
    print("Native rejection evidence FAILED: " + type(exc).__name__, flush=True)
    sys.exit(2)
'@
        & $python -c $probe $repoRoot $installation $toolkit $output $health.generationId
        Assert-Evidence ($LASTEXITCODE -eq 0) 'NATIVE_REJECTIONS_FAILED'
        $item=@($references | Where-Object model -ceq 'N312')[0]
        $stats=@{numericPaths=0;maximumAbsoluteDelta=0.0}
        Compare-SaValue $item.reference (Read-EvidenceJson (Join-Path $output 'N312-restored-result.json')) $item.limits '' $stats
        Assert-Evidence ($stats.numericPaths -eq $item.limits.Count) 'RESTORED_NUMERIC_COVERAGE_MISMATCH'
        Write-EvidenceJson 'restored-comparison.json' @{nativeRejectionsVerified=$true;platformVerified=$false;comparison=$stats}
        Write-Output ('Native rejection/restored comparison verified: '+$output)
    } finally {if($owned){$mutex.ReleaseMutex()};$mutex.Dispose()}
    return
}
if ($Platform -or $CompareOnly) {
    $null=[IO.Directory]::CreateDirectory($output)
    $references=Get-FrozenReferences
    $comparatorTests=@(Test-Comparator $references)
    Write-EvidenceJson 'comparator-self-test.json' @{comparisonSelfTestVerified=$true;platformVerified=$false;rejectionTests=$comparatorTests;manifestSha256=(Get-FileHash -LiteralPath $ReferenceManifestPath).Hash}
    if($CompareOnly){Write-Output ('Comparator: '+$comparatorTests.Count+' faults rejected; both references restored green. No platform/SDK run.');return}
    $script:saStage='deployment-preflight'
    $script:saKnownRunIds=@()
    try { Invoke-SaPlatform $references }
    catch {
        if(-not(Test-Path -LiteralPath (Join-Path $output 'platform-failure.json'))) {
            Write-EvidenceJson 'platform-failure.json' @{platformVerified=$false;stage=$script:saStage;knownRunIds=$script:saKnownRunIds;failureCode=$_.Exception.Message}
        }
        throw
    }
    return
}
$health = Invoke-RestMethod -Uri ($WorkerBase.TrimEnd('/') + '/api/health') -TimeoutSec 5
if ($health.status -ne 'UP' -or -not $health.idle -or $null -ne $health.activeRunId) { throw 'Worker is not confirmed idle; no native calculation was started.' }
$mutex = [Threading.Mutex]::new($false, 'Global\GRDP-Pipesim-Golden-Capture')
$owned = $false
try {
    try { $owned = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $owned = $true }
    if (-not $owned) { throw 'PIPESIM machine lease is busy; no native calculation was started.' }
    $probe = @'
import copy, hashlib, json, math, numbers, os, pathlib, shutil, sys, time, types

repo, home, toolkit, output, generation = sys.argv[1:]
sys.path.insert(0, toolkit)
sys.path.insert(0, os.path.join(repo, "worker"))
import ptk_run as adapter
from sixgill.pipesim import Model
from sixgill.definitions import Constants, Parameters, ProfileVariables, SystemVariables, Units

root = pathlib.Path(output)
root.mkdir(parents=True, exist_ok=False)
def digest(path):
    h = hashlib.sha256()
    with open(path, "rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""): h.update(block)
    return h.hexdigest()

def serializable(value):
    if value is None or isinstance(value, (str, bool, int)): return value
    if isinstance(value, numbers.Real):
        number = float(value)
        return number if math.isfinite(number) else {"$nonFinite": repr(number)}
    if isinstance(value, dict): return {str(k): serializable(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)): return [serializable(v) for v in value]
    if hasattr(value, "tolist"): return serializable(value.tolist())
    raise TypeError("Unserializable native result type: " + type(value).__name__)

def save(name, value):
    with open(root / name, "x", encoding="utf-8") as target:
        json.dump(serializable(value), target, indent=2, ensure_ascii=False, allow_nan=False)

system_variables = [SystemVariables.PRESSURE, SystemVariables.TEMPERATURE,
                    SystemVariables.VOLUME_FLOWRATE_LIQUID_STOCKTANK, SystemVariables.BOTTOM_HOLE_PRESSURE]
profile_variables = [ProfileVariables.TEMPERATURE, ProfileVariables.PRESSURE, ProfileVariables.TOTAL_DISTANCE]
models = [
    ("N301", "CSN_301_Small Network.pips", "Well", "FL-2"),
    ("N312", "CSN_312_Gas Lift Optimization.pips", "Well_1", "Flowline_1"),
]
audits = []

def task_parameters(request, flow):
    return {
        Parameters.SystemAnalysisSimulation.BRANCHTERMINATOR: request["branchTerminator"],
        Parameters.SystemAnalysisSimulation.OUTLETPRESSURE: request["outletPressurePsi"],
        Parameters.SystemAnalysisSimulation.LIQUIDFLOWRATE: flow,
        Parameters.SystemAnalysisSimulation.FLOWRATETYPE: Constants.FlowRateType.LIQUIDFLOWRATE,
        Parameters.SystemAnalysisSimulation.CALCULATEDVARIABLE: Constants.CalculatedVariable.INLETPRESSURE,
    }

def direct_case(simulation, flow):
    # Independent extraction from SDK dictionaries; no adapter normalizer is used here.
    if str(simulation.state) != "Completed": raise ValueError("Native task did not complete")
    names = list(simulation.profile)
    if len(names) != 1 or set(simulation.node) != set(names): raise ValueError("Native case identity is ambiguous")
    name = names[0]
    system = [{"variable": key, "unit": value.get("Unit"), "value": value[name]}
              for key, value in simulation.system.items() if isinstance(value, dict) and name in value]
    nodes = {}
    for variable, payload in simulation.node[name].items():
        for node, value in payload.items():
            if node != "Unit": nodes.setdefault(node, []).append({"variable": variable, "unit": payload.get("Unit"), "value": value})
    profile = [{"variable": key, "unit": simulation.profile_units.get(key), "values": list(values)}
               for key, values in simulation.profile[name].items()]
    counts = {len(value["values"]) for value in profile}
    if len(counts) != 1 or not counts or next(iter(counts)) <= 0: raise ValueError("Native profile lengths do not align")
    return {"caseName": name, "scanValue": flow, "system": system,
            "node": [{"node": key, "variables": values} for key, values in nodes.items()],
            "profile": {"pointCount": next(iter(counts)), "variables": profile}}

def compare(left, right, path="", deltas=None):
    deltas = {} if deltas is None else deltas
    if isinstance(left, bool) or isinstance(right, bool):
        if type(left) != type(right) or left != right: raise ValueError("Boolean identity differs: " + path)
    elif isinstance(left, numbers.Real) and isinstance(right, numbers.Real):
        if not math.isfinite(float(left)) or not math.isfinite(float(right)): raise ValueError("Unavailable native number: " + path)
        deltas[path] = {"reference": float(left), "observed": float(right), "absoluteDelta": abs(float(left) - float(right))}
    elif isinstance(left, dict) and isinstance(right, dict):
        if set(left) != set(right): raise ValueError("Result fields differ: " + path)
        for key in left: compare(left[key], right[key], path + "/" + key, deltas)
    elif isinstance(left, list) and isinstance(right, list):
        if len(left) != len(right): raise ValueError("Result lengths differ: " + path)
        for i, (a, b) in enumerate(zip(left, right)): compare(a, b, path + "/" + str(i), deltas)
    elif left != right: raise ValueError("Native text/units differ: " + path)
    return deltas

try:
    for short, filename, producer, branch in models:
        source = pathlib.Path(home) / "Case Studies" / "Network Models" / filename
        original_hash = digest(source)
        request = {"schemaVersion": "pipesim-system-analysis-parameters/2", "producer": producer,
                   "branchTerminator": branch, "outletPressurePsi": 600,
                   "scanVariable": "liquidFlowRate", "values": [2400, 3000, 3600]}
        study = "Study 1"
        audit = {"model": short, "relativeSource": "Case Studies/Network Models/" + filename,
                 "sourceSha256": original_hash, "request": request, "study": study,
                 "sourceUnchanged": False, "closed": [], "conditions": [], "nativeOnly": True,
                 "platformVerified": False, "workerGenerationObserved": generation}
        audits.append(audit)
        results = []
        for repeat in range(2):
            folder = root / (short + "-direct-" + str(repeat))
            folder.mkdir()
            isolated = folder / filename
            shutil.copy2(source, isolated)
            model = None
            cases, raw_cases = [], []
            try:
                model = Model.open(str(isolated))
                if model.about.unit_system != Units.FIELD: raise ValueError("Native input unit system is not FIELD")
                if study not in adapter._study_names(model): raise ValueError("Native Study is absent")
                adapter._preflight_system_analysis_context(model, request)
                audit["unitSystem"] = str(model.about.unit_system)
                for flow in request["values"]:
                    print(short + " native repeat " + str(repeat + 1) + " flow " + str(flow), flush=True)
                    task = model.tasks.systemanalysissimulation
                    params = task_parameters(request, flow)
                    adapter._prepare_system_analysis_case(task, producer, study, params)
                    audit["conditions"].append({"repeat": repeat, "flow": flow,
                                               "readback": task.get_conditions(producer=producer, study=study)})
                    simulation = task.run(producer=producer, study=study, parameters=params,
                                          system_variables=system_variables, profile_variables=profile_variables)
                    case = direct_case(simulation, flow)
                    adapter._validate_system_analysis_case_units(case)
                    cases.append(case)
                    raw_cases.append({"flow": flow, "state": str(simulation.state), "system": simulation.system,
                                      "node": simulation.node, "profile": simulation.profile,
                                      "profile_units": simulation.profile_units})
                result = {"schemaVersion": "pipesim-system-analysis-result/2", "model_kind": "network",
                          "runTask": "system-analysis", "resultContract": "VALID_FULL", "study": study,
                          **{key: request[key] for key in ("producer", "branchTerminator", "outletPressurePsi", "scanVariable")},
                          "cases": cases}
                results.append(result)
                save(short + "-raw-" + str(repeat) + ".json", raw_cases)
                save(short + "-reference-" + str(repeat) + ".json", result)
            finally:
                if model is not None:
                    model.close()
                    audit["closed"].append("direct-" + str(repeat))
                audit["sourceUnchanged"] = digest(source) == original_hash
                if not audit["sourceUnchanged"]: raise ValueError("Official source SHA changed")
                save(short + "-audit-" + str(repeat) + ".json", audit)
        deltas = compare(results[0], results[1])
        # Freeze before platform execution, based on actual repeatability plus numeric rounding.
        tolerances = {key: max(value["absoluteDelta"] * 2, abs(value["reference"]) * 1e-9, 1e-8)
                      for key, value in deltas.items()}
        save(short + "-repeatability.json", {"deltas": deltas, "absoluteTolerances": tolerances,
              "basis": "twice same SDK conditions; 2x observed repeat delta, 1e-9 relative rounding, 1e-8 absolute floor",
              "frozenBeforePlatform": True, "platformVerified": False})
        print(short + " native reference saved; platform not yet verified", flush=True)
    save("native-summary.json", {"nativeReferencesVerified": True, "platformVerified": False, "models": audits,
                                 "adapterSha256": digest(pathlib.Path(repo) / "worker" / "ptk_run.py")})
except Exception as exc:
    save("native-failure.json", {"nativeReferencesVerified": False, "platformVerified": False,
         "category": getattr(exc, "category", "NATIVE"), "code": getattr(exc, "code", type(exc).__name__),
         "message": adapter._safe_exception_diagnostic(exc), "models": audits})
    print("Native evidence FAILED: " + getattr(exc, "code", type(exc).__name__), flush=True)
    sys.exit(2)
'@
    & $python -c $probe $repoRoot $installation $toolkit $output $health.generationId
    if ($LASTEXITCODE -ne 0) { throw ('Native evidence failed with exit ' + $LASTEXITCODE + '; inspect native-failure.json. No platform pass claimed.') }
    Write-Output ('Native evidence saved: ' + $output + '. Platform verification remains pending.')
} finally {
    if ($owned) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
