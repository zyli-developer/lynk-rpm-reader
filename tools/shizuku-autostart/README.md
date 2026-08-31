# Shizuku boot helper for the validated Flyme Auto head unit

This optional helper is for the validated development head unit where the root/system
partition is already writable and the built-in `su` command is available to the ADB shell. It is
not installed by the RPM APK.

The installer writes exactly two files:

- `/system/etc/init/lynk-rpm-shizuku.rc`
- `/data/local/lynk-rpm-shizuku-start.sh`

At `sys.boot_completed=1`, Android init runs the script as the shell user. The script
discovers the currently installed Shizuku APK and executes Shizuku's own extracted
`libshizuku.so` starter. The native starter forks the server and exits, so the script
stays alive as a lightweight supervisor while `shizuku_server` is running; this keeps
Android init from cleaning up the forked server with the service process group. It does
not embed or redistribute Shizuku.

Run the installer only against an owned and explicitly authorized test vehicle:

```powershell
.\install.ps1 -Adb '<adb-path>' -Serial '<device-serial>'
```

The installer does not reboot the head unit. A full Android reboot is required before
init loads the new rc file. Remove both files with:

```powershell
.\uninstall.ps1 -Adb '<adb-path>' -Serial '<device-serial>'
```
