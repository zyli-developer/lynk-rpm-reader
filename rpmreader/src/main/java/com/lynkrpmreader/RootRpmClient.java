package com.lynkrpmreader;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/** Connects to an optional, user-authorized helper that exposes only ENGINE_RPM. */
final class RootRpmClient implements AutoCloseable {
    private static final String TAG = "RpmReader";

    interface FailureCallback {
        void onFailure(Throwable error);
    }

    private final VehicleRpmClient.Listener listener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Socket socket;
    private volatile Thread worker;
    private volatile Integer lastLoggedRpm;

    RootRpmClient(Context context, VehicleRpmClient.Listener listener) {
        this.listener = listener;
    }

    void start(FailureCallback failureCallback) {
        closed.set(false);
        Thread thread = new Thread(() -> runHelper(failureCallback), "Root-RPM");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    private void runHelper(FailureCallback failureCallback) {
        try {
            listener.onStatus("正在启动车辆 Root 读取服务", false);
            Socket current = new Socket();
            current.connect(new InetSocketAddress("127.0.0.1",
                    RootRpmReaderMain.SERVER_PORT), 1500);
            current.setSoTimeout(5000);
            socket = current;

            boolean received = false;
            int diagnosticLines = 0;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(current.getInputStream()))) {
                String line;
                while (!closed.get() && (line = reader.readLine()) != null) {
                    if (line.startsWith("RPM|")) {
                        String[] parts = line.split("\\|", -1);
                        if (parts.length >= 3) {
                            int rpm = CarRpmValue.toDisplayRpm(Float.parseFloat(parts[1]));
                            int status = Integer.parseInt(parts[2]);
                            if (!received) {
                                received = true;
                                listener.onLog("Root Car API 已连接，ENGINE_RPM 数据有效");
                                listener.onStatus("正在读取发动机转速（Root Car API）", false);
                                Log.i(TAG, "Root Car API connected");
                            }
                            if (lastLoggedRpm == null || lastLoggedRpm != rpm) {
                                lastLoggedRpm = rpm;
                                Log.i(TAG, "ROOT_ENGINE_RPM=" + rpm + ", status=" + status);
                            }
                            listener.onRpm(rpm, status);
                        }
                    } else if (line.startsWith("ERROR|")) {
                        throw new IllegalStateException(line);
                    } else if (diagnosticLines++ < 3) {
                        Log.d(TAG, "root-helper: " + line);
                    }
                }
            }

            if (!closed.get()) {
                throw new IllegalStateException("Root RPM 服务连接已关闭");
            }
        } catch (Throwable error) {
            if (!closed.get()) {
                Log.e(TAG, "Root RPM path failed", error);
                failureCallback.onFailure(error);
            }
        }
    }

    @Override
    public void close() {
        closed.set(true);
        Socket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.close();
            } catch (Exception ignored) {
            }
        }
        Thread currentWorker = worker;
        worker = null;
        if (currentWorker != null) {
            currentWorker.interrupt();
        }
    }
}
