package com.lynk.rpmreader;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;

/**
 * Minimal Binder client for the exported ECARX display projection service.
 *
 * <p>Flyme Auto 2.5 exposes this service from the system package
 * {@code com.ecarx.dfe.service}. The public AIDL method used here creates a
 * presentation-capable virtual display whose surface is routed to the vehicle's
 * secondary-display pipeline. Keeping the Binder protocol local avoids bundling
 * private vendor framework classes into the APK.</p>
 */
final class EcarxProjectionClient {
    private static final String TAG = "LynkRpmProjection";
    private static final String SERVICE_PACKAGE = "com.ecarx.dfe.service";
    private static final String SERVICE_CLASS =
            "com.ecarx.dfe.service.ScreenProjectionService";
    private static final String SERVICE_ACTION =
            "com.ecarx.dfe.service.ScreenProjectionService";
    private static final String DESCRIPTOR =
            "com.ecarx.dfe.IScreenProjectionManager";

    // Values recovered from the Flyme Auto 2.5 system service AIDL stub.
    private static final int TRANSACTION_STOP_DISPLAY = 3;
    private static final int TRANSACTION_CREATE_PRESENTATION_DISPLAY = 9;

    interface Callback {
        void onComplete(int displayId);
        void onError(String message, Throwable error);
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Callback callback;
    private final int displayToStop;
    private boolean bound;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            new Thread(() -> transact(service), "RPM-projection-binder").start();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            if (bound) finishError("车机副屏服务连接已断开", null);
        }
    };

    private EcarxProjectionClient(Context context, int displayToStop, Callback callback) {
        this.context = context.getApplicationContext();
        this.displayToStop = displayToStop;
        this.callback = callback;
    }

    static EcarxProjectionClient createDisplay(Context context, Callback callback) {
        EcarxProjectionClient client =
                new EcarxProjectionClient(context, -1, callback);
        client.bind();
        return client;
    }

    static EcarxProjectionClient stopDisplay(
            Context context, int displayId, Callback callback) {
        EcarxProjectionClient client =
                new EcarxProjectionClient(context, displayId, callback);
        client.bind();
        return client;
    }

    private void bind() {
        Intent intent = new Intent(SERVICE_ACTION);
        intent.setComponent(new ComponentName(SERVICE_PACKAGE, SERVICE_CLASS));
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bound) finishError("车机未提供可用的副屏投影服务", null);
        } catch (RuntimeException error) {
            finishError("无法连接车机副屏投影服务", error);
        }
    }

    private void transact(IBinder service) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            final int transaction;
            if (displayToStop >= 0) {
                transaction = TRANSACTION_STOP_DISPLAY;
                data.writeInt(displayToStop);
            } else {
                transaction = TRANSACTION_CREATE_PRESENTATION_DISPLAY;
                data.writeString(context.getPackageName());
            }

            if (!service.transact(transaction, data, reply, 0)) {
                throw new IllegalStateException("Projection Binder rejected transaction "
                        + transaction);
            }
            reply.readException();
            int result = displayToStop >= 0 ? displayToStop : reply.readInt();
            if (displayToStop < 0 && result < 0) {
                throw new IllegalStateException("Projection service returned display " + result);
            }
            Log.i(TAG, displayToStop >= 0
                    ? "Stopped projection display " + result
                    : "Created projection display " + result);
            finishSuccess(result);
        } catch (Throwable error) {
            finishError(displayToStop >= 0
                    ? "关闭副屏失败"
                    : "创建副屏显示失败", error);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void finishSuccess(int displayId) {
        mainHandler.post(() -> {
            unbind();
            callback.onComplete(displayId);
        });
    }

    private void finishError(String message, Throwable error) {
        Log.e(TAG, message, error);
        mainHandler.post(() -> {
            unbind();
            callback.onError(message, error);
        });
    }

    private void unbind() {
        if (!bound) return;
        bound = false;
        try {
            context.unbindService(connection);
        } catch (RuntimeException ignored) {
            // The service may have disconnected before the one-shot call completed.
        }
    }
}
