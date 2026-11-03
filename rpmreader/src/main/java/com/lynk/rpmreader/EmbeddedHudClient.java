package com.lynk.rpmreader;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Connects only to the fixed-function helper packaged in this APK. */
final class EmbeddedHudClient {
    interface Callback {
        void onStarted(int displayId, int privilegeUid);
        void onUnavailable(String reason);
        void onError(String reason);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    void startHud(Callback callback) {
        new Thread(() -> {
            try (LocalSocket socket = new LocalSocket()) {
                socket.connect(new LocalSocketAddress(EmbeddedHudShellMain.SOCKET_NAME,
                        LocalSocketAddress.Namespace.ABSTRACT));
                socket.setSoTimeout(18_000);
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                output.writeInt(Process.myUid() / 100000);
                output.flush();
                String reply = new DataInputStream(socket.getInputStream()).readUTF();
                String[] parts = reply.split("\\|", 3);
                if (parts.length == 3 && "OK".equals(parts[0])) {
                    int displayId = Integer.parseInt(parts[1]);
                    int privilegeUid = Integer.parseInt(parts[2]);
                    mainHandler.post(() -> callback.onStarted(displayId, privilegeUid));
                } else {
                    mainHandler.post(() -> callback.onError(reply));
                }
            } catch (IOException | RuntimeException error) {
                mainHandler.post(() -> callback.onUnavailable(error.getMessage()));
            }
        }, "RPM-embedded-HUD").start();
    }
}
