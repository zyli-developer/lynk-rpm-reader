param(
    [string]$Adb = 'adb',
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$toolDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$launcher = Join-Path $toolDir 'lynk-rpm-hud-helper-start.sh'
$service = Join-Path $toolDir 'lynk-rpm-hud-helper.rc'
$temporaryLauncher = '/data/local/tmp/lynk-rpm-hud-helper-start.sh'
$temporaryService = '/data/local/tmp/lynk-rpm-hud-helper.rc'
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

$deviceName = (& $Adb -s $Serial shell getprop ro.product.device).Trim()
$modelName = (& $Adb -s $Serial shell getprop ro.product.model).Trim()
$identity = "$deviceName`n$modelName"
if ($identity -notmatch 'se1000_dx11_slave') {
    throw "Refusing an unexpected target: $identity"
}

$appPath = (& $Adb -s $Serial shell pm path com.lynk.rpmreader).Trim()
if (-not $appPath) {
    throw 'com.lynk.rpmreader is not installed'
}

$mountLine = (& $Adb -s $Serial shell mount |
        Select-String -SimpleMatch ' on / ' |
        Select-Object -First 1).Line
if ($mountLine -notmatch '\(rw[,)]') {
    throw 'The root/system partition is not already writable; this installer will not remount it'
}

$existing = @()
& $Adb -s $Serial shell su 0 test -e $targetLauncher
if ($LASTEXITCODE -eq 0) { $existing += $targetLauncher }
& $Adb -s $Serial shell su 0 test -e $targetService
if ($LASTEXITCODE -eq 0) { $existing += $targetService }
if ($existing.Count -gt 0 -and -not $Force) {
    throw "Autostart files already exist; rerun with -Force or uninstall first: $($existing -join ', ')"
}

Invoke-Adb push $launcher $temporaryLauncher
Invoke-Adb push $service $temporaryService
Invoke-Adb shell su 0 cp $temporaryLauncher $targetLauncher
Invoke-Adb shell su 0 cp $temporaryService $targetService
Invoke-Adb shell su 0 chown 0.2000 $targetLauncher
Invoke-Adb shell su 0 chmod 0750 $targetLauncher
Invoke-Adb shell su 0 chown 0.0 $targetService
Invoke-Adb shell su 0 chmod 0644 $targetService
Invoke-Adb shell su 0 restorecon $targetLauncher $targetService
Invoke-Adb shell su 0 sync
Invoke-Adb shell su 0 ls -lZ $targetLauncher $targetService
& $Adb -s $Serial shell rm -f $temporaryLauncher $temporaryService | Out-Null

Write-Host 'Installed. The init service is parsed and started on the next full Android boot.'
Write-Host 'Verify after reboot with:'
Write-Host '  adb shell ps -A -o PID,PPID,USER,NAME,ARGS | grep EmbeddedHudShellMain'
Write-Host '  adb logcat -d -s lynk_rpm_hud_helper_boot'
