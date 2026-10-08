param(
    [string]$Config = (Join-Path $PSScriptRoot '..\config\database.local.env'),
    [string]$Container = 'mysql'
)

$ErrorActionPreference = 'Stop'

function Read-EnvFile([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { throw "Database config not found: $Path" }
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#') -or -not $trimmed.Contains('=')) { continue }
        $separator = $trimmed.IndexOf('=')
        $key = $trimmed.Substring(0, $separator).Trim()
        $values[$key] = $trimmed.Substring($separator + 1)
    }
    return $values
}

function Invoke-Docker([string[]]$Arguments, [switch]$Capture) {
    if ($Capture) {
        $result = & docker @Arguments
        if ($LASTEXITCODE -ne 0) { throw "Docker command failed (exit $LASTEXITCODE)." }
        return @($result)
    }
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker command failed (exit $LASTEXITCODE)." }
}

$configPath = (Resolve-Path -LiteralPath $Config).Path
$settings = Read-EnvFile $configPath
$url = $settings['NOVELFORGE_DB_URL']
$username = $settings['NOVELFORGE_DB_USERNAME']
$password = $settings['NOVELFORGE_DB_PASSWORD']
if (-not $url -or -not $username -or $null -eq $password) {
    throw 'Database URL, username and password are required.'
}
if ($url -notmatch '^jdbc:mysql://[^/]+/(?<database>[A-Za-z0-9_]+)(?:\?|$)') {
    throw 'Only a MySQL JDBC URL with a simple database name is supported.'
}
$sourceDatabase = $Matches['database']
if ($sourceDatabase -notmatch '^novelforge(?:_|$)') {
    throw "Refusing to back up a database outside the NovelForge namespace: $sourceDatabase"
}
if ($username -notmatch '^[A-Za-z0-9_.@-]+$') { throw 'Database username contains unsupported characters.' }
if ($Container -notmatch '^[A-Za-z0-9_.-]+$') { throw 'Container name contains unsupported characters.' }

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$restoreDatabase = "${sourceDatabase}_restore_verify_$stamp"
$containerBackup = "/tmp/${sourceDatabase}-$stamp.sql"
$backupDirectory = Join-Path $PSScriptRoot '..\backups'
New-Item -ItemType Directory -Force -Path $backupDirectory | Out-Null
$hostBackup = Join-Path $backupDirectory "${sourceDatabase}-$stamp.sql"

$oldPassword = $env:MYSQL_PWD
$oldUser = $env:NOVELFORGE_BACKUP_USER
$oldSource = $env:NOVELFORGE_SOURCE_DB
$oldRestore = $env:NOVELFORGE_RESTORE_DB
$oldBackupFile = $env:NOVELFORGE_BACKUP_FILE
$restoreCreated = $false
try {
    $env:MYSQL_PWD = $password
    $env:NOVELFORGE_BACKUP_USER = $username
    $env:NOVELFORGE_SOURCE_DB = $sourceDatabase
    $env:NOVELFORGE_RESTORE_DB = $restoreDatabase
    $env:NOVELFORGE_BACKUP_FILE = $containerBackup

    Invoke-Docker @('exec','-e','MYSQL_PWD','-e','NOVELFORGE_BACKUP_USER','-e','NOVELFORGE_SOURCE_DB',
        '-e','NOVELFORGE_BACKUP_FILE',$Container,'sh','-c',
        'umask 077; mysqldump --user="$NOVELFORGE_BACKUP_USER" --single-transaction --quick --routines --events --triggers --default-character-set=utf8mb4 "$NOVELFORGE_SOURCE_DB" > "$NOVELFORGE_BACKUP_FILE"')
    Invoke-Docker @('cp',"${Container}:${containerBackup}",$hostBackup)

    $createSql = "CREATE DATABASE ``$restoreDatabase`` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
    Invoke-Docker @('exec','-e','MYSQL_PWD',$Container,'mysql',"--user=$username",'--execute',$createSql)
    $restoreCreated = $true
    Invoke-Docker @('exec','-e','MYSQL_PWD','-e','NOVELFORGE_BACKUP_USER','-e','NOVELFORGE_RESTORE_DB',
        '-e','NOVELFORGE_BACKUP_FILE',$Container,'sh','-c',
        'mysql --user="$NOVELFORGE_BACKUP_USER" "$NOVELFORGE_RESTORE_DB" < "$NOVELFORGE_BACKUP_FILE"')

    $tableSql = "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='$sourceDatabase' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME;"
    $tables = Invoke-Docker @('exec','-e','MYSQL_PWD',$Container,'mysql',"--user=$username",'--batch','--skip-column-names','--execute',$tableSql) -Capture
    $tables = @($tables | Where-Object { $_ -match '^[A-Za-z0-9_]+$' })
    if ($tables.Count -eq 0) { throw 'The source database contains no base tables.' }

    foreach ($table in $tables) {
        $countSql = "SELECT (SELECT COUNT(*) FROM ``$sourceDatabase``.``$table``), (SELECT COUNT(*) FROM ``$restoreDatabase``.``$table``);"
        $counts = (Invoke-Docker @('exec','-e','MYSQL_PWD',$Container,'mysql',"--user=$username",'--batch','--skip-column-names','--execute',$countSql) -Capture | Select-Object -First 1) -split "`t"
        if ($counts.Count -ne 2 -or $counts[0] -ne $counts[1]) {
            throw "Row-count mismatch after restore: $table"
        }
        $checksumSql = "CHECKSUM TABLE ``$sourceDatabase``.``$table``, ``$restoreDatabase``.``$table`` EXTENDED;"
        $checksumRows = Invoke-Docker @('exec','-e','MYSQL_PWD',$Container,'mysql',"--user=$username",'--batch','--skip-column-names','--execute',$checksumSql) -Capture
        $checksums = @($checksumRows | ForEach-Object { ($_ -split "`t")[-1] })
        if ($checksums.Count -ne 2 -or $checksums[0] -ne $checksums[1]) {
            throw "Checksum mismatch after restore: $table"
        }
    }

    $backup = Get-Item -LiteralPath $hostBackup
    Write-Host "Backup/restore verification passed: $($tables.Count) tables, $($backup.Length) bytes."
    Write-Host "Backup retained at: $($backup.FullName)"
} finally {
    if ($restoreCreated -and $restoreDatabase -match '^novelforge(?:_[A-Za-z0-9]+)*_restore_verify_[0-9]{8}-[0-9]{6}$') {
        $dropSql = "DROP DATABASE IF EXISTS ``$restoreDatabase``;"
        try { Invoke-Docker @('exec','-e','MYSQL_PWD',$Container,'mysql',"--user=$username",'--execute',$dropSql) } catch { Write-Warning 'Temporary restore database cleanup failed.' }
    }
    try { Invoke-Docker @('exec','-e','NOVELFORGE_BACKUP_FILE',$Container,'sh','-c','rm -f -- "$NOVELFORGE_BACKUP_FILE"') } catch { Write-Warning 'Container temporary backup cleanup failed.' }
    $env:MYSQL_PWD = $oldPassword
    $env:NOVELFORGE_BACKUP_USER = $oldUser
    $env:NOVELFORGE_SOURCE_DB = $oldSource
    $env:NOVELFORGE_RESTORE_DB = $oldRestore
    $env:NOVELFORGE_BACKUP_FILE = $oldBackupFile
}
