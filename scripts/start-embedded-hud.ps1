param(
    [string]$Adb = 'adb'
)

$ErrorActionPreference = 'Stop'
$packageName = 'com.lynk.rpmreader'
$apkLine = & $Adb shell pm path $packageName
if ($LASTEXITCODE -ne 0 -or -not $apkLine) {
    throw "APK is not installed: $packageName"
}
$apkPath = ($apkLine | Where-Object { $_ -match '^package:' } | Select-Object -First 1) -replace '^package:', ''
if (-not $apkPath -or $apkPath -notmatch '^/data/app/[A-Za-z0-9_./=+~-]+\.apk$') {
    throw "Unexpected APK path returned by pm: $apkPath"
}
$remoteCommand = "CLASSPATH=$apkPath /system/bin/app_process /system/bin com.lynk.rpmreader.EmbeddedHudShellMain serve >/dev/null 2>&1 &"
& $Adb shell $remoteCommand
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to start embedded HUD helper through ADB shell.'
}
Write-Host 'Embedded HUD helper requested. Open the app and select HUD to verify.'
