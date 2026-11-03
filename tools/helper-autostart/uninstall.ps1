param(
    [string]$Adb = 'adb',
    [Parameter(Mandatory = $true)]
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
$targetLauncher = '/data/local/lynk-rpm-hud-helper-start.sh'
$targetService = '/system/etc/init/lynk-rpm-hud-helper.rc'

function Invoke-Adb {
    & $Adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed with exit code $LASTEXITCODE"
    }
}

$deviceLine = & $Adb devices -l | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+device\b" }
if (-not $deviceLine) {
    throw "Target $Serial is not connected in device state"
}

# Stop the currently loaded init service (ignored if it is not loaded).
& $Adb -s $Serial shell su 0 setprop ctl.stop lynk_rpm_hud_helper 2>$null | Out-Null

Invoke-Adb shell su 0 rm -f $targetLauncher $targetService
Invoke-Adb shell su 0 sync
Invoke-Adb shell su 0 ls -lZ $targetLauncher $targetService

Write-Host 'Removed. The service disappears from init after the next full reboot.'
Write-Host 'If the helper process is still running, stop it with: adb shell su 0 pkill -f EmbeddedHudShellMain'
