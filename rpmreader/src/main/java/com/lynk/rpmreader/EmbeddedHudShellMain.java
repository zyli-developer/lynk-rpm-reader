package com.lynk.rpmreader;

import android.content.Context;
import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Process;

import java.io.DataInputStream;
import java.io.DataOutputStream;

/**
 * APK-packaged, narrowly scoped HUD helper. Start with app_process as ADB shell or root.
 * A normal application cannot grant itself shell privileges or start this process.
 */
public final class EmbeddedHudShellMain {
    static final String SOCKET_NAME = "com.lynk.rpmreader.hud.v1";
    private static final String PACKAGE_NAME = "com.lynk.rpmreader";

    private EmbeddedHudShellMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !"serve".equals(args[0])) {
            throw new IllegalArgumentException("Expected: serve");
        }
        int helperUid = Process.myUid();
        if (helperUid != 0 && helperUid != 2000) {
            throw new SecurityException("HUD helper requires root or ADB shell UID");
        }
        Class<?> threadClass = Class.forName("android.app.ActivityThread");
        Object thread = threadClass.getMethod("systemMain").invoke(null);
        Context context = (Context) threadClass.getMethod("getSystemContext").invoke(thread);
        int appUid = context.getPackageManager().getPackageUid(PACKAGE_NAME, 0);
        try (LocalServerSocket server = new LocalServerSocket(SOCKET_NAME)) {
            System.out.println("HUD_HELPER_READY|" + helperUid);
            System.out.flush();
            while (true) {
                try (LocalSocket client = server.accept()) {
                    client.setSoTimeout(15_000);
                    Credentials peer = client.getPeerCredentials();
                    // PackageManager resolves user 0 here; Android keeps the appId
                    // (the low five digits) stable across Android users.
                    if (peer == null || peer.getUid() % 100000 != appUid % 100000) {
                        continue;
                    }
                    DataInputStream input = new DataInputStream(client.getInputStream());
                    DataOutputStream output = new DataOutputStream(client.getOutputStream());
                    int userId = input.readInt();
                    if (userId < 0 || userId != peer.getUid() / 100000) {
                        output.writeUTF("ERROR|Invalid Android user");
                        output.flush();
                        continue;
                    }
                    try {
                        HudShellUserService service = new HudShellUserService();
                        int displayId = service.findHudDisplay();
                        if (displayId < 0) {
                            output.writeUTF("ERROR|HUD display unavailable");
                        } else {
                            service.startHudActivity(displayId, userId);
                            output.writeUTF("OK|" + displayId + "|" + helperUid);
                        }
                    } catch (Exception error) {
                        output.writeUTF("ERROR|" + String.valueOf(error.getMessage()));
                    }
                    output.flush();
                } catch (Exception error) {
                    System.err.println("HUD_HELPER_CLIENT_ERROR|" + error);
                }
            }
        }
    }
}
