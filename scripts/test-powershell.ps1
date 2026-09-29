# Keep all PowerShell scripts ASCII-safe for Windows PowerShell 5.1 without a UTF-8 BOM.
# External commands are mocked: this test does not start a server or invoke a model.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$sourceRoot = $projectRoot
$originalLocation = Get-Location
$originalMode = $env:NOVELFORGE_MODEL_MODE
$originalPort = $env:NOVELFORGE_PORT
$originalExitCode = $global:LASTEXITCODE

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

foreach ($relativePath in @('start.ps1', 'verify.ps1', 'scripts/startup-checks.ps1', 'scripts/test-powershell.ps1')) {
    $scriptPath = Join-Path $projectRoot $relativePath
    $bytes = [IO.File]::ReadAllBytes($scriptPath)
    Assert-True (@($bytes | Where-Object { $_ -gt 127 }).Count -eq 0) "Non-ASCII byte found in $relativePath."
    $tokens = $null
    $parseErrors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($scriptPath, [ref]$tokens, [ref]$parseErrors) | Out-Null
    Assert-True ($parseErrors.Count -eq 0) "Parsing failed for $relativePath."
}

# Isolated fixture: never inspect/lock the user's live database or compete with their server port.
$fixtureParent = Join-Path $sourceRoot 'backend/target'
$projectRoot = Join-Path $fixtureParent ('shell-test-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory((Join-Path $projectRoot 'scripts')) | Out-Null
[IO.Directory]::CreateDirectory((Join-Path $projectRoot 'backend/target')) | Out-Null
[IO.Directory]::CreateDirectory((Join-Path $projectRoot 'data')) | Out-Null
[IO.Directory]::CreateDirectory((Join-Path $projectRoot 'config')) | Out-Null
foreach ($relativePath in @('start.ps1', 'verify.ps1', 'scripts/startup-checks.ps1')) {
    Copy-Item -LiteralPath (Join-Path $sourceRoot $relativePath) -Destination (Join-Path $projectRoot $relativePath)
}
[IO.File]::WriteAllBytes((Join-Path $projectRoot 'backend/target/novelforge-0.1.0.jar'), [byte[]]@())
[IO.File]::WriteAllLines((Join-Path $projectRoot 'config/model.local.env'), @(
    'NOVELFORGE_MODEL_MODE=http',
    'NOVELFORGE_MODEL_BASE_URL=https://model.example/v1',
    'NOVELFORGE_MODEL_API_KEY=test-only-secret',
    'NOVELFORGE_MODEL_NAME=test-model',
    'NOVELFORGE_MODEL_TIMEOUT=17',
    'NOVELFORGE_MAX_OUTPUT_TOKENS=3456',
    'NOVELFORGE_MAX_CONTEXT_CHARS=7890',
    'NOVELFORGE_TOKEN_LIMIT_FIELD=max_tokens',
    'NOVELFORGE_RESPONSE_FORMAT=none'
))
$testListener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$testListener.Start()
$env:NOVELFORGE_PORT = [string]$testListener.LocalEndpoint.Port
$testListener.Stop()
$lockedDatabase = $null

$previousTestState = Get-Variable -Name NovelForgeShellTestState -Scope Global -ErrorAction SilentlyContinue
$testState = [pscustomobject]@{ MavenCalls = 0; JavaCalls = 0; MavenExitCode = 0; JavaArguments = @(); JavaEnvironment = $null }
$global:NovelForgeShellTestState = $testState
function mvn {
    $global:NovelForgeShellTestState.MavenCalls++
    $global:LASTEXITCODE = $global:NovelForgeShellTestState.MavenExitCode
}
function java {
    $global:NovelForgeShellTestState.JavaCalls++
    $global:NovelForgeShellTestState.JavaArguments = @($args)
    $global:NovelForgeShellTestState.JavaEnvironment = [pscustomobject]@{
        Mode = $env:NOVELFORGE_MODEL_MODE; BaseUrl = $env:NOVELFORGE_MODEL_BASE_URL
        ApiKey = $env:NOVELFORGE_MODEL_API_KEY; Name = $env:NOVELFORGE_MODEL_NAME
        Timeout = $env:NOVELFORGE_MODEL_TIMEOUT; OutputTokens = $env:NOVELFORGE_MAX_OUTPUT_TOKENS
    }
    Assert-True ((Get-Location).Path -eq $projectRoot) 'Server launched from an unexpected directory.'
    $global:LASTEXITCODE = 0
}

try {
    # Deliberately invoke from another directory to verify that data paths remain project-relative.
    Set-Location $PSScriptRoot
    & (Join-Path $projectRoot 'start.ps1') -Mode http
    Assert-True ($testState.MavenCalls -eq 1 -and $testState.JavaCalls -eq 1) 'Build/start path did not invoke the expected commands.'
    Assert-True ($testState.JavaEnvironment.Mode -eq 'http') 'HTTP mode was not selected.'
    Assert-True ($testState.JavaEnvironment.BaseUrl -eq 'https://model.example/v1') 'Model base URL was not loaded from the private config.'
    Assert-True ($testState.JavaEnvironment.ApiKey -eq 'test-only-secret' -and $testState.JavaEnvironment.Name -eq 'test-model') 'Model identity was not loaded from the private config.'
    Assert-True ($testState.JavaEnvironment.Timeout -eq '17' -and $testState.JavaEnvironment.OutputTokens -eq '3456') 'Model limits were not loaded from the private config.'
    Assert-True ($env:NOVELFORGE_MODEL_MODE -eq $originalMode) 'Model environment was not restored after Java exited.'
    Assert-True ($testState.JavaArguments.Count -eq 2 -and $testState.JavaArguments[0] -eq '-jar') 'Unexpected Java command arguments.'
    Assert-True ((Get-Location).Path -eq $PSScriptRoot) 'Working directory was not restored.'

    & (Join-Path $projectRoot 'start.ps1') -Mode http -SkipBuild
    Assert-True ($testState.MavenCalls -eq 1 -and $testState.JavaCalls -eq 2) 'SkipBuild did not skip Maven.'

    $testListener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, [int]$env:NOVELFORGE_PORT)
    $testListener.Start()
    $portRejected = $false
    try { & (Join-Path $projectRoot 'start.ps1') -Mode http }
    catch { $portRejected = $_.Exception.Message -like 'Port * is already in use or unavailable.*' }
    finally { $testListener.Stop() }
    Assert-True $portRejected 'Occupied port was not rejected before building.'
    Assert-True ($testState.MavenCalls -eq 1 -and $testState.JavaCalls -eq 2) 'A busy port still triggered a build or launch.'

    $fixtureDatabase = Join-Path $projectRoot 'data/novelforge.mv.db'
    [IO.File]::WriteAllBytes($fixtureDatabase, [byte[]]@(1, 2, 3))
    $lockedDatabase = [IO.File]::Open($fixtureDatabase, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    $databaseRejected = $false
    try { & (Join-Path $projectRoot 'start.ps1') -Mode http }
    catch { $databaseRejected = $_.Exception.Message -like 'Database is already in use or locked:*' }
    finally { $lockedDatabase.Dispose(); $lockedDatabase = $null }
    Assert-True $databaseRejected 'Locked database was not rejected before building.'
    Assert-True ($testState.MavenCalls -eq 1 -and $testState.JavaCalls -eq 2) 'A locked database still triggered a build or launch.'
    Assert-True ([BitConverter]::ToString([IO.File]::ReadAllBytes($fixtureDatabase)) -eq '01-02-03') 'The database probe changed file contents.'

    $testState.MavenExitCode = 1
    $buildFailed = $false
    try { & (Join-Path $projectRoot 'start.ps1') -Mode http }
    catch { $buildFailed = $_.Exception.Message -eq 'Build failed. The server was not started.' }
    Assert-True $buildFailed 'Build failure was not reported correctly.'
    Assert-True ($testState.JavaCalls -eq 2) 'Java was started after a failed build.'
    Assert-True ((Get-Location).Path -eq $PSScriptRoot) 'Working directory was not restored after failure.'

    $verificationFailed = $false
    try { & (Join-Path $projectRoot 'verify.ps1') }
    catch { $verificationFailed = $_.Exception.Message -eq 'Java tests failed.' }
    Assert-True $verificationFailed 'Verification did not stop after failed Java tests.'
    Write-Host "PASS: PowerShell $($PSVersionTable.PSVersion), parsing, HTTP mode, SkipBuild, busy-port/locked-database checks, failure handling, working-directory restoration."
} finally {
    if ($null -ne $lockedDatabase) { $lockedDatabase.Dispose() }
    $testListener.Stop()
    Set-Location $originalLocation
    $env:NOVELFORGE_MODEL_MODE = $originalMode
    $env:NOVELFORGE_PORT = $originalPort
    $global:LASTEXITCODE = $originalExitCode
    if ($null -ne $previousTestState) { $global:NovelForgeShellTestState = $previousTestState.Value }
    else { Remove-Variable -Name NovelForgeShellTestState -Scope Global }
    $resolvedFixture = (Resolve-Path -LiteralPath $projectRoot).Path
    $allowedPrefix = [IO.Path]::GetFullPath($fixtureParent) + [IO.Path]::DirectorySeparatorChar + 'shell-test-'
    if (-not $resolvedFixture.StartsWith($allowedPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Refusing cleanup outside the test fixture directory.' }
    Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
}
