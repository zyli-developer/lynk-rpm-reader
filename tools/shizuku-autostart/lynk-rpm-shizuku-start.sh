#!/system/bin/sh

TAG=lynk_rpm_shizuku_boot
PACKAGE=moe.shizuku.privileged.api

/system/bin/sleep 5

apk_path="$(/system/bin/pm path "$PACKAGE" 2>/dev/null \
    | /system/bin/sed -n 's/^package://p' \
    | /system/bin/head -n 1)"
if [ -z "$apk_path" ]; then
    /system/bin/log -t "$TAG" "Shizuku package is not installed"
    exit 10
fi

base_dir="${apk_path%/base.apk}"
starter=""
for candidate in "$base_dir"/lib/*/libshizuku.so; do
    if [ -x "$candidate" ]; then
        starter="$candidate"
        break
    fi
done

if [ -z "$starter" ]; then
    /system/bin/log -t "$TAG" "libshizuku.so starter was not extracted"
    exit 11
fi

/system/bin/log -t "$TAG" "Starting Shizuku with the installed official starter"
/system/bin/logwrapper "$starter" --apk="$apk_path"
starter_status=$?
if [ "$starter_status" -ne 0 ]; then
    /system/bin/log -t "$TAG" "Shizuku starter failed with status $starter_status"
    exit "$starter_status"
fi

# The native starter forks shizuku_server and then exits. Android init kills a
# service's remaining process group when its main process exits, so keep this
# shell alive for as long as the forked server is alive.
server_pid=""
attempt=0
while [ "$attempt" -lt 20 ]; do
    server_pid="$(/system/bin/pidof shizuku_server 2>/dev/null)"
    [ -n "$server_pid" ] && break
    attempt=$((attempt + 1))
    /system/bin/sleep 1
done

if [ -z "$server_pid" ]; then
    /system/bin/log -t "$TAG" "Shizuku server did not stay alive after startup"
    exit 12
fi

/system/bin/log -t "$TAG" "Shizuku server is running as PID $server_pid"
while /system/bin/pidof shizuku_server >/dev/null 2>&1; do
    /system/bin/sleep 30
done

/system/bin/log -t "$TAG" "Shizuku server stopped"
exit 13
