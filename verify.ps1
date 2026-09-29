$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    $mavenCache = Join-Path $PSScriptRoot '.m2'
    $testTemp = Join-Path $PSScriptRoot '.test-tmp'
    New-Item -ItemType Directory -Force -Path $testTemp | Out-Null
    & mvn -B -ntp -f backend/pom.xml "-Dmaven.repo.local=$mavenCache" "-DargLine=-Djava.io.tmpdir=$testTemp" test
    if ($LASTEXITCODE -ne 0) { throw 'Java tests failed.' }
    if (Get-Command node -ErrorAction SilentlyContinue) {
        & node --check frontend/app.js
        if ($LASTEXITCODE -ne 0) { throw 'Frontend JavaScript syntax check failed.' }
    }
    Write-Host 'Verification passed. For live API verification: node scripts/smoke.mjs'
} finally { Pop-Location }
