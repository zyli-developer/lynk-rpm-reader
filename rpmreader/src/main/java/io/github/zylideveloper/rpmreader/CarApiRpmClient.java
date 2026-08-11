package io.github.zylideveloper.rpmreader;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reads the standard AAOS ENGINE_RPM property through CarPropertyManager. */
final class CarApiRpmClient implements AutoCloseable {
    private static final String TAG = "RpmReader";
    static final int ENGINE_RPM = 0x11600305;
    static final int GLOBAL_AREA = 0;

    interface FailureCallback {
        void onFailure(Throwable error);
    }

    private final Context context;
    private final VehicleRpmClient.Listener listener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Object car;
    private volatile Thread worker;

    CarApiRpmClient(Context context, VehicleRpmClient.Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void start(FailureCallback failureCallback) {
        closed.set(false);
        Thread thread = new Thread(() -> readLoop(failureCallback), "CarAPI-RPM");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    private void readLoop(FailureCallback failureCallback) {
        try {
            listener.onStatus("正在连接 Android Car 服务", false);
            Class<?> carClass = Class.forName("android.car.Car");
            Object currentCar = carClass.getMethod("createCar", Context.class)
                    .invoke(null, context);
            if (currentCar == null) {
                throw new IllegalStateException("Car.createCar 返回空");
            }
            car = currentCar;

            Object propertyManager = carClass.getMethod("getCarManager", String.class)
                    .invoke(currentCar, "property");
            if (propertyManager == null) {
                throw new IllegalStateException("CarPropertyManager 不可用");
            }

            Method getProperty = propertyManager.getClass().getMethod(
                    "getProperty", Class.class, int.class, int.class);
            Class<?> valueClass = Class.forName("android.car.hardware.CarPropertyValue");
            Method getValue = valueClass.getMethod("getValue");
            Method getStatus = valueClass.getMethod("getStatus");

            listener.onLog("Android Car API 已连接");
            listener.onLog("ENGINE_RPM=291504901 (0x11600305), area=0, Float, 10Hz");
            listener.onStatus("正在读取发动机转速", false);
            Log.i(TAG, "CarPropertyManager connected; reading ENGINE_RPM 0x11600305");

            int lastLoggedRpm = Integer.MIN_VALUE;
            while (!closed.get()) {
                Object propertyValue = getProperty.invoke(
                        propertyManager, Float.class, ENGINE_RPM, GLOBAL_AREA);
                if (propertyValue != null) {
                    int rpm = CarRpmValue.toDisplayRpm(getValue.invoke(propertyValue));
                    int status = ((Number) getStatus.invoke(propertyValue)).intValue();
                    listener.onRpm(rpm, status);
                    if (rpm != lastLoggedRpm) {
                        Log.i(TAG, "ENGINE_RPM=" + rpm + " status=" + status);
                        lastLoggedRpm = rpm;
                    }
                }
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (Throwable error) {
            Throwable cause = unwrap(error);
            if (!closed.get()) {
                Log.e(TAG, "Android Car API RPM read failed", cause);
                failureCallback.onFailure(cause);
            }
        }
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof InvocationTargetException
                && ((InvocationTargetException) error).getCause() != null) {
            return ((InvocationTargetException) error).getCause();
        }
        return error;
    }

    @Override
    public void close() {
        closed.set(true);
        Thread currentWorker = worker;
        worker = null;
        if (currentWorker != null) {
            currentWorker.interrupt();
        }
        Object currentCar = car;
        car = null;
        if (currentCar != null) {
            try {
                currentCar.getClass().getMethod("disconnect").invoke(currentCar);
            } catch (Throwable ignored) {
                // Process teardown also releases the binder connection.
            }
        }
    }
}
