param(
    [ValidateSet('demo','http')][string]$Mode,
    [string]$ModelConfig = 'config/model.local.env',
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
$originalEnvironment = @{}
function Set-TrackedEnvironment([string]$Name, [string]$Value) {
    if (-not $originalEnvironment.ContainsKey($Name)) {
        $existing = Get-Item -LiteralPath "Env:$Name" -ErrorAction SilentlyContinue
        $originalEnvironment[$Name] = if ($null -eq $existing) { $null } else { $existing.Value }
    }
    Set-Item -LiteralPath "Env:$Name" -Value $Value
}
try {
    $allowedModelSettings = @(
        'NOVELFORGE_MODEL_MODE', 'NOVELFORGE_MODEL_BASE_URL', 'NOVELFORGE_MODEL_API_KEY',
        'NOVELFORGE_MODEL_NAME', 'NOVELFORGE_MODEL_TIMEOUT', 'NOVELFORGE_MAX_OUTPUT_TOKENS',
        'NOVELFORGE_MAX_CONTEXT_CHARS', 'NOVELFORGE_TOKEN_LIMIT_FIELD', 'NOVELFORGE_RESPONSE_FORMAT',
        'NOVELFORGE_CONTINUITY_SHADOW_ENABLED', 'NOVELFORGE_PLOT_FORESHADOW_SHADOW_ENABLED'
    )
    $modelConfigPath = if ([IO.Path]::IsPathRooted($ModelConfig)) { $ModelConfig } else { Join-Path $PSScriptRoot $ModelConfig }
    if (Test-Path -LiteralPath $modelConfigPath) {
        $lineNumber = 0
        foreach ($rawLine in Get-Content -LiteralPath $modelConfigPath -Encoding UTF8) {
            $lineNumber++
            $line = $rawLine.Trim()
            if ($lineNumber -eq 1) { $line = $line.TrimStart([char]0xFEFF) }
            if (-not $line -or $line.StartsWith('#')) { continue }
            $separator = $line.IndexOf('=')
            if ($separator -lt 1) { throw "Invalid model config line $lineNumber. Expected NAME=value." }
            $name = $line.Substring(0, $separator).Trim()
            if ($allowedModelSettings -notcontains $name) { throw "Unsupported model config key at line ${lineNumber}: $name" }
            $value = $line.Substring($separator + 1).Trim()
            if ($value.Length -ge 2 -and (($value[0] -eq '"' -and $value[$value.Length-1] -eq '"') -or ($value[0] -eq "'" -and $value[$value.Length-1] -eq "'"))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            Set-TrackedEnvironment -Name $name -Value $value
        }
    }
    if ($PSBoundParameters.ContainsKey('Mode')) { Set-TrackedEnvironment -Name 'NOVELFORGE_MODEL_MODE' -Value $Mode }
    elseif (-not $env:NOVELFORGE_MODEL_MODE) { Set-TrackedEnvironment -Name 'NOVELFORGE_MODEL_MODE' -Value 'demo' }
    $selectedMode = $env:NOVELFORGE_MODEL_MODE
    if ($selectedMode -notin @('demo','http')) { throw 'NOVELFORGE_MODEL_MODE must be demo or http.' }
    . (Join-Path $PSScriptRoot 'scripts/startup-checks.ps1')
    $listenPort = 8080
    if ($env:NOVELFORGE_PORT -and -not [int]::TryParse($env:NOVELFORGE_PORT, [ref]$listenPort)) {
        throw 'NOVELFORGE_PORT must be an integer between 1 and 65535.'
    }
    Assert-NovelForgeStartupAvailable -ProjectRoot $PSScriptRoot -Port $listenPort
    $mavenCache = Join-Path $PSScriptRoot '.m2'
    if (-not $SkipBuild) {
        & mvn -B -ntp -f backend/pom.xml "-Dmaven.repo.local=$mavenCache" -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'Build failed. The server was not started.' }
    }
    if (-not (Test-Path -LiteralPath 'backend/target/novelforge-0.1.0.jar')) { throw 'Application JAR not found. Run again without -SkipBuild.' }
    Write-Host "Starting NovelForge mode=$selectedMode. After the 'Started NovelForgeApplication' message, open http://127.0.0.1:$listenPort/"
    & java -jar backend/target/novelforge-0.1.0.jar
    if ($LASTEXITCODE -ne 0) { throw 'Server exited with an error. Check the output above.' }
} finally {
    foreach ($name in $originalEnvironment.Keys) {
        if ($null -eq $originalEnvironment[$name]) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
        else { Set-Item -LiteralPath "Env:$name" -Value $originalEnvironment[$name] }
    }
    Pop-Location
}
