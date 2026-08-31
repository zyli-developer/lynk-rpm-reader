package com.lynk.rpmreader;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import rikka.shizuku.Shizuku;

/** Coordinates permission and the narrow Shizuku UserService used for HUD launch. */
final class ShizukuHudController implements AutoCloseable {
    private static final int REQUEST_PERMISSION = 4107;
    private static final int MIN_SHIZUKU_API = 12;

    interface Callback {
        void onStarted(int displayId, int privilegeUid, String commandOutput);
        void onError(String message, Throwable error);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Shizuku.UserServiceArgs userServiceArgs;
    private final boolean permissionRequestsAllowed;
    private Callback pendingCallback;
    private IHudShellService service;
    private boolean binding;
    private boolean operationRunning;
    private boolean closed;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> {
        if (pendingCallback != null) continueStart();
    };
    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> {
        service = null;
        binding = false;
        failPending("Shizuku 服务已停止，请检查开机启动器", null);
    };
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
            (requestCode, grantResult) -> {
                if (requestCode != REQUEST_PERMISSION || pendingCallback == null) return;
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    bindAndRun();
                } else {
                    failPending("未授予 Shizuku 权限，HUD 无法启动", null);
                }
            };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            binding = false;
            service = IHudShellService.Stub.asInterface(binder);
            runPendingOperation();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            binding = false;
            service = null;
        }
    };

    ShizukuHudController(Context context) {
        this(context, true);
    }

    ShizukuHudController(Context context, boolean permissionRequestsAllowed) {
        this.permissionRequestsAllowed = permissionRequestsAllowed;
        userServiceArgs = new Shizuku.UserServiceArgs(
                new ComponentName(context, HudShellUserService.class))
                .daemon(false)
                .tag("lynk-rpm-hud-shell")
                .processNameSuffix("hud_shell")
                .debuggable(false)
                .version(1);
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);
    }

    void startHud(Callback callback) {
        if (closed) {
            callback.onError("HUD 权限控制器已经关闭", null);
            return;
        }
        if (pendingCallback != null) {
            callback.onError("HUD 启动操作正在进行中", null);
            return;
        }
        pendingCallback = callback;
        continueStart();
    }

    private void continueStart() {
        try {
            if (!Shizuku.pingBinder()) {
                failPending("Shizuku 服务未运行，请先启动或安装开机启动器", null);
                return;
            }
            if (Shizuku.getVersion() < MIN_SHIZUKU_API) {
                failPending("Shizuku 版本过旧，需要 API 12 或更高版本", null);
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bindAndRun();
                return;
            }
            if (!permissionRequestsAllowed) {
                failPending("Shizuku 权限尚未授予，请打开应用完成一次授权", null);
                return;
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                failPending("Shizuku 权限已被拒绝，请在 Shizuku 中重新授权", null);
                return;
            }
            Shizuku.requestPermission(REQUEST_PERMISSION);
        } catch (Throwable error) {
            failPending("无法连接 Shizuku：" + safeMessage(error), error);
        }
    }

    private void bindAndRun() {
        if (service != null && service.asBinder().pingBinder()) {
            runPendingOperation();
            return;
        }
        if (binding) return;
        binding = true;
        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection);
        } catch (Throwable error) {
            binding = false;
            failPending("无法启动 HUD shell 服务：" + safeMessage(error), error);
        }
    }

    private void runPendingOperation() {
        final Callback callback = pendingCallback;
        final IHudShellService activeService = service;
        if (callback == null || activeService == null || operationRunning) return;
        operationRunning = true;

        new Thread(() -> {
            try {
                int displayId = activeService.findHudDisplay();
                if (displayId < 0) {
                    throw new IllegalStateException(
                            "未找到 Flyme Auto 的 520×280 Ex Share Display HUD");
                }
                String output = activeService.startHudActivity(
                        displayId, android.os.Process.myUid() / 100000);
                int privilegeUid = Shizuku.getUid();
                mainHandler.post(() -> complete(callback, displayId,
                        privilegeUid, output));
            } catch (RemoteException | RuntimeException error) {
                mainHandler.post(() -> fail(callback,
                        "HUD 启动失败：" + safeMessage(error), error));
            }
        }, "RPM-hud-launch").start();
    }

    private void complete(
            Callback callback, int displayId, int privilegeUid, String commandOutput) {
        if (pendingCallback != callback) return;
        operationRunning = false;
        pendingCallback = null;
        callback.onStarted(displayId, privilegeUid, commandOutput);
    }

    private void failPending(String message, Throwable error) {
        Callback callback = pendingCallback;
        if (callback == null) return;
        fail(callback, message, error);
    }

    private void fail(Callback callback, String message, Throwable error) {
        operationRunning = false;
        if (pendingCallback == callback) pendingCallback = null;
        callback.onError(message, error);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message.trim();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        operationRunning = false;
        pendingCallback = null;
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        if (binding || service != null) {
            try {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, false);
            } catch (Throwable ignored) {
                // Shizuku may have stopped before the Activity was destroyed.
            }
        }
        binding = false;
        service = null;
    }
}
