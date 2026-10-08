param(
    [string]$ConfigPath = 'config/database.local.env',
    [switch]$RecreateEmpty
)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$resolvedConfig = if ([IO.Path]::IsPathRooted($ConfigPath)) { (Resolve-Path -LiteralPath $ConfigPath).Path } else { (Resolve-Path -LiteralPath (Join-Path $projectRoot $ConfigPath)).Path }
$connectorRoot=Join-Path $projectRoot '.m2\repository\com\mysql\mysql-connector-j'
$connector=Get-ChildItem -LiteralPath $connectorRoot -Recurse -File -Filter 'mysql-connector-j-*.jar' -ErrorAction SilentlyContinue |
    Where-Object Name -NotLike '*sources*' | Sort-Object FullName -Descending | Select-Object -First 1
if($null -eq $connector){throw 'MySQL JDBC driver was not found. Run a Maven build once, then retry.'}
$arguments=@('-cp',$connector.FullName,(Join-Path $PSScriptRoot 'storage\ProvisionMySql.java'),$resolvedConfig)
if($RecreateEmpty){$arguments+='--recreate-empty'}
& java @arguments
if($LASTEXITCODE -ne 0){throw 'MySQL database provisioning failed.'}
Write-Host 'NovelForge MySQL database is ready. The local configuration file was not modified.'
