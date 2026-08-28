param(
    [string]$Adb = 'adb',
    [Parameter(Mandatory = $true)]
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
$toolDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$launcher = Join-Path $toolDir 'lynk-rpm-shizuku-start.sh'
$service = Join-Path $toolDir 'lynk-rpm-shizuku.rc'
$temporaryLauncher = '/data/local/tmp/lynk-rpm-shizuku-start.sh'
$temporaryService = '/data/local/tmp/lynk-rpm-shizuku.rc'
$targetLauncher = '/data/local/lynk-rpm-shizuku-start.sh'
$targetService = '/system/etc/init/lynk-rpm-shizuku.rc'

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
if ($existing.Count -gt 0) {
    throw "Autostart files already exist; inspect or uninstall them before reinstalling: $($existing -join ', ')"
}

Invoke-Adb push $launcher $temporaryLauncher
Invoke-Adb push $service $temporaryService
Invoke-Adb shell su 0 cp $temporaryLauncher $targetLauncher
Invoke-Adb shell su 0 dd "if=$temporaryService" "of=$targetService"
Invoke-Adb shell su 0 chown 0.2000 $targetLauncher
Invoke-Adb shell su 0 chmod 0750 $targetLauncher
Invoke-Adb shell su 0 chown 0.0 $targetService
Invoke-Adb shell su 0 chmod 0644 $targetService
Invoke-Adb shell su 0 restorecon $targetLauncher $targetService
Invoke-Adb shell su 0 sync
Invoke-Adb shell su 0 ls -lZ $targetLauncher $targetService
Invoke-Adb shell rm -f $temporaryLauncher $temporaryService

Write-Host 'Installed. The new init rc is loaded on the next full Android boot.'
Write-Host 'No reboot was issued by this script.'
