# ASCII-only: Windows PowerShell 5.1 must parse this without a BOM.
function Assert-NovelForgeStartupAvailable {
    param(
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [Parameter(Mandatory = $true)][int]$Port
    )
    if ($Port -lt 1 -or $Port -gt 65535) { throw 'NOVELFORGE_PORT must be between 1 and 65535.' }

    $databasePath = Join-Path $ProjectRoot 'data/novelforge.mv.db'
    if (Test-Path -LiteralPath $databasePath) {
        $databaseProbe = $null
        try {
            # Read-only, no creation/truncation, and immediately released after checking.
            $databaseProbe = [IO.File]::Open($databasePath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::None)
        } catch [UnauthorizedAccessException] {
            throw "Cannot access database file: $databasePath. Check file permissions; do not delete the database."
        } catch [IO.IOException] {
            throw "Database is already in use or locked: $databasePath. Stop the earlier NovelForge instance or database viewer first. Changing ports will not release this lock. Do not delete the database."
        } finally {
            if ($null -ne $databaseProbe) { $databaseProbe.Dispose() }
        }
    }

    $portProbe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
    try {
        $portProbe.Server.ExclusiveAddressUse = $true
        $portProbe.Start()
    } catch [Net.Sockets.SocketException] {
        throw "Port $Port is already in use or unavailable. Stop the earlier instance before restarting. No build or server launch was attempted."
    } finally {
        $portProbe.Stop()
    }
}
