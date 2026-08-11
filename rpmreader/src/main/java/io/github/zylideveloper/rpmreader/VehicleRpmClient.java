package io.github.zylideveloper.rpmreader;

import android.content.Context;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/** Uses the vehicle's APVP signal first, with standard AAOS paths as compatibility fallbacks. */
final class VehicleRpmClient implements AutoCloseable {
    private static final String TAG = "RpmReader";
    interface Listener {
        void onStatus(String status, boolean error);
        void onLog(String message);
        void onRpm(int rpm, int status);
    }

    private final Context context;
    private final Listener listener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lock = new Object();
    private AutoCloseable delegate;

    VehicleRpmClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void start() {
        closed.set(false);
        listener.onLog("读取顺序：车辆 APVP → Android Car API → Root Car API");
        listener.onLog("优先使用兼容车机的 APVP readSignal，标准 ENGINE_RPM 作为后备");
        Log.i(TAG, "Starting APVP gRPC RPM path");
        ApvpGrpcRpmClient apvpClient = new ApvpGrpcRpmClient(listener);
        synchronized (lock) {
            delegate = apvpClient;
        }
        apvpClient.start(this::fallbackToCar);
    }

    private void fallbackToCar(Throwable apvpError) {
        if (closed.get()) return;
        listener.onLog("车辆 APVP 不可用：" + apvpError.getClass().getSimpleName()
                + ": " + String.valueOf(apvpError.getMessage()));
        Log.w(TAG, "Falling back to Android Car API", apvpError);
        CarApiRpmClient carClient = new CarApiRpmClient(context, listener);
        synchronized (lock) {
            if (closed.get()) {
                carClient.close();
                return;
            }
            closeQuietly(delegate);
            delegate = carClient;
        }
        carClient.start(this::fallbackToRoot);
    }

    private void fallbackToRoot(Throwable carError) {
        if (closed.get()) {
            return;
        }
        listener.onLog("Android Car API 不可用：" + carError.getClass().getSimpleName()
                + ": " + String.valueOf(carError.getMessage()));
        listener.onLog("正在尝试用户授权的 Root Car API 通道");
        Log.w(TAG, "Falling back to Root Car API", carError);

        RootRpmClient rootClient = new RootRpmClient(context, listener);
        synchronized (lock) {
            if (closed.get()) {
                rootClient.close();
                return;
            }
            closeQuietly(delegate);
            delegate = rootClient;
        }
        rootClient.start(this::reportFailure);
    }

    private void reportFailure(Throwable rootError) {
        if (closed.get()) return;
        listener.onLog("Root Car API 不可用：" + rootError.getClass().getSimpleName()
                + ": " + String.valueOf(rootError.getMessage()));
        listener.onStatus("当前车机没有可用的发动机转速数据通道", true);
        Log.e(TAG, "All RPM paths failed", rootError);
    }

    @Override
    public void close() {
        closed.set(true);
        synchronized (lock) {
            closeQuietly(delegate);
            delegate = null;
        }
    }

    private static void closeQuietly(AutoCloseable value) {
        if (value != null) {
            try {
                value.close();
            } catch (Exception ignored) {
            }
        }
    }
}
