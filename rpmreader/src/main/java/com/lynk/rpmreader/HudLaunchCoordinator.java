package com.lynk.rpmreader;

import android.content.Context;

/** Prefer the APK's authorized shell helper; retain Shizuku for existing installs. */
final class HudLaunchCoordinator implements AutoCloseable {
    interface Callback {
        void onStarted(int displayId, int privilegeUid, String source);
        void onError(String message, Throwable error);
    }

    private final Context context;
    private final boolean permissionRequestsAllowed;
    private ShizukuHudController shizuku;
    private boolean closed;

    HudLaunchCoordinator(Context context, boolean permissionRequestsAllowed) {
        this.context = context.getApplicationContext();
        this.permissionRequestsAllowed = permissionRequestsAllowed;
    }

    void startHud(Callback callback) {
        new EmbeddedHudClient().startHud(new EmbeddedHudClient.Callback() {
            @Override public void onStarted(int displayId, int privilegeUid) {
                if (!closed) callback.onStarted(displayId, privilegeUid, "内置 shell 服务");
            }

            @Override public void onUnavailable(String reason) {
                if (closed) return;
                shizuku = new ShizukuHudController(context, permissionRequestsAllowed);
                shizuku.startHud(new ShizukuHudController.Callback() {
                    @Override public void onStarted(int displayId, int privilegeUid,
                            String output) {
                        if (!closed) callback.onStarted(displayId, privilegeUid, "Shizuku");
                    }

                    @Override public void onError(String message, Throwable error) {
                        if (!closed) callback.onError("内置服务不可用（" + reason
                                + "）；" + message, error);
                    }
                });
            }

            @Override public void onError(String reason) {
                if (!closed) callback.onError("内置 HUD 服务：" + reason, null);
            }
        });
    }

    @Override public void close() {
        closed = true;
        if (shizuku != null) shizuku.close();
        shizuku = null;
    }
}
