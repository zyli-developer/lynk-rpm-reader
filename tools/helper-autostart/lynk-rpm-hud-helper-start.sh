#!/system/bin/sh

TAG=lynk_rpm_hud_helper_boot
PACKAGE=com.lynk.rpmreader

# PackageManager is up once sys.boot_completed=1, but retry briefly anyway.
attempt=0
apk_path=""
while [ "$attempt" -lt 15 ]; do
    apk_path="$(/system/bin/pm path "$PACKAGE" 2>/dev/null \
        | /system/bin/sed -n 's/^package://p' \
        | /system/bin/head -n 1)"
    [ -n "$apk_path" ] && break
    attempt=$((attempt + 1))
    /system/bin/sleep 1
done

if [ -z "$apk_path" ]; then
    /system/bin/log -t "$TAG" "APK path not found for $PACKAGE"
    exit 10
fi

/system/bin/log -t "$TAG" "Starting embedded HUD helper from $apk_path"
export ANDROID_ROOT=/system
export ANDROID_DATA=/data
export CLASSPATH="$apk_path"
exec /system/bin/app_process /system/bin com.lynk.rpmreader.EmbeddedHudShellMain serve
