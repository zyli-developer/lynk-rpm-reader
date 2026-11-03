# 内置 HUD helper 开机自启动

通过 Android init 在 `sys.boot_completed=1` 之后以 ADB shell（UID 2000）身份启动 APK 内置的
`com.lynk.rpmreader.EmbeddedHudShellMain serve`，使 HUD 在车机重启后自动恢复，无需再手动执行
adb 命令。

## 文件

- `lynk-rpm-hud-helper.rc`：init 服务定义，部署到 `/system/etc/init/lynk-rpm-hud-helper.rc`
- `lynk-rpm-hud-helper-start.sh`：解析当前 APK 路径并 exec helper，部署到
  `/data/local/lynk-rpm-hud-helper-start.sh`
- `install.ps1` / `uninstall.ps1`：部署与移除脚本

## 安装

需要 root（`su 0`）且 `/system` 已可写；安装脚本会拒绝非 `se1000_dx11_slave` 设备：

```powershell
.\install.ps1 -Adb <adb.exe 路径> -Serial <设备序列号>
```

安装后需要完整重启一次，init 才会解析新的 `.rc` 文件。

## 验证

```powershell
adb shell getprop init.svc.lynk_rpm_hud_helper   # 应为 running
adb shell ps -A -o PID,PPID,USER,ARGS | Select-String EmbeddedHudShellMain
adb logcat -d -s lynk_rpm_hud_helper_boot
```

helper 只在 APK 已安装时启动。APK 更新后建议重启一次，让启动脚本解析新的 APK 路径。
卸载使用 `uninstall.ps1`；服务定义会在下次完整重启后彻底消失。

## 安全边界

helper 以 shell 身份运行，但只接受本应用 UID 的连接，只能查找 Flyme Auto 的 520x280 HUD
并启动本应用的 `SecondaryRpmActivity`，不提供任意命令执行。
