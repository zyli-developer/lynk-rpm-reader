param(
    [string]$Adb = 'adb',
    [Parameter(Mandatory = $true)]
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
$targetLauncher = '/data/local/lynk-rpm-shizuku-start.sh'
$targetService = '/system/etc/init/lynk-rpm-shizuku.rc'

$deviceLine = & $Adb devices -l | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+device\b" }
if (-not $deviceLine) {
    throw "Target $Serial is not connected in device state"
}

& $Adb -s $Serial shell su 0 rm -f $targetLauncher $targetService
if ($LASTEXITCODE -ne 0) {
    throw "Unable to remove the two autostart files (exit $LASTEXITCODE)"
}

& $Adb -s $Serial shell su 0 sync

Write-Host 'Removed the Lynk RPM Shizuku autostart files.'
Write-Host 'The already-running Shizuku server was not stopped.'
