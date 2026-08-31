package com.lynk.rpmreader;

import android.os.RemoteException;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Shizuku UserService running as ADB shell (UID 2000) or root (UID 0).
 *
 * <p>The Binder API is deliberately narrow. It does not expose an arbitrary shell command to
 * the application; it can only identify the Flyme Auto 520x280 HUD and launch this package's
 * numeric RPM activity on that display.</p>
 */
public final class HudShellUserService extends IHudShellService.Stub {
    private static final String TAG = "LynkRpmHudShell";
    private static final String HUD_COMPONENT =
            "com.lynk.rpmreader/.SecondaryRpmActivity";

    /** Required by Shizuku when creating the UserService process. */
    public HudShellUserService() {
        Log.i(TAG, "UserService created");
    }

    @Override public int findHudDisplay() throws RemoteException {
        CommandResult result = runCommand("/system/bin/dumpsys", "display");
        if (result.exitCode != 0) {
            throw new RemoteException("dumpsys display failed: " + result.output);
        }

        int candidate = HudDisplayParser.findHudDisplayId(result.output);
        Log.i(TAG, "Detected HUD displayId=" + candidate);
        return candidate;
    }

    @Override public String startHudActivity(int displayId, int userId)
            throws RemoteException {
        if (displayId < 0 || userId < 0) {
            throw new RemoteException("Invalid display/user: " + displayId + "/" + userId);
        }

        CommandResult result = runCommand(
                "/system/bin/am", "start",
                "--user", Integer.toString(userId),
                "--display", Integer.toString(displayId),
                "-n", HUD_COMPONENT,
                "--es", SecondaryRpmActivity.EXTRA_DISPLAY_LOCATION,
                RpmDisplayLocation.HUD_LEFT.persistedValue(),
                "--ei", SecondaryRpmActivity.EXTRA_PROJECTION_DISPLAY_ID,
                Integer.toString(displayId));
        if (result.exitCode != 0
                || result.output.contains("Error:")
                || result.output.contains("SecurityException")
                || result.output.contains("Permission Denial")) {
            throw new RemoteException("HUD Activity launch failed: " + result.output);
        }
        Log.i(TAG, "HUD Activity launched on display " + displayId + ": " + result.output);
        return result.output;
    }

    @Override public void destroy() {
        Log.i(TAG, "UserService destroyed");
        System.exit(0);
    }

    private static CommandResult runCommand(String... command) throws RemoteException {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) lines.add(line);
            }
            if (!process.waitFor(12, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new RemoteException("Command timed out: " + command[0]);
            }
            return new CommandResult(process.exitValue(), joinLines(lines));
        } catch (IOException error) {
            throw remoteException("Cannot execute " + command[0], error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw remoteException("Interrupted while executing " + command[0], error);
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static RemoteException remoteException(String message, Throwable cause) {
        RemoteException error = new RemoteException(message + ": " + cause.getMessage());
        error.initCause(cause);
        return error;
    }

    private static String joinLines(List<String> lines) {
        StringBuilder output = new StringBuilder();
        for (String line : lines) {
            if (output.length() > 0) output.append('\n');
            output.append(line);
        }
        return output.toString().trim();
    }

    private static final class CommandResult {
        final int exitCode;
        final String output;

        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
