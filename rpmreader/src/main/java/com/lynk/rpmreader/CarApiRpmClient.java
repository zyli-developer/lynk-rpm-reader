package com.lynk.rpmreader;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reads a runtime-validated engine RPM property through CarPropertyManager. */
final class CarApiRpmClient implements AutoCloseable {
    private static final String TAG = "RpmReader";
    static final int ENGINE_RPM = 0x11600305;
    static final int VENDOR_ENGINE_RPM = 0x21408066;
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
            Object currentCar = carClass.getMethod("createCar", Context.class).invoke(null, context);
            if (currentCar == null) throw new IllegalStateException("Car.createCar 返回空");
            car = currentCar;

            Object propertyManager = carClass.getMethod("getCarManager", String.class)
                    .invoke(currentCar, "property");
            if (propertyManager == null) throw new IllegalStateException("CarPropertyManager 不可用");

            Method getProperty = propertyManager.getClass().getMethod(
                    "getProperty", Class.class, int.class, int.class);
            Class<?> valueClass = Class.forName("android.car.hardware.CarPropertyValue");
            Method getValue = valueClass.getMethod("getValue");
            Method getStatus = valueClass.getMethod("getStatus");
            PropertyTarget target = resolveProperty(propertyManager);

            listener.onLog("Android Car API 已连接");
            listener.onLog(target.label + "=" + target.propertyId + " (0x"
                    + Integer.toHexString(target.propertyId) + "), area=0, valueClass="
                    + target.valueClass.getSimpleName());
            listener.onStatus("正在读取发动机转速", false);
            Log.i(TAG, "CarPropertyManager connected; reading " + target.label + " 0x"
                    + Integer.toHexString(target.propertyId));

            int lastLoggedRpm = Integer.MIN_VALUE;
            int readsWithoutAvailableValue = 0;
            while (!closed.get()) {
                Object propertyValue = getProperty.invoke(propertyManager,
                        target.valueClass, target.propertyId, GLOBAL_AREA);
                if (propertyValue != null) {
                    int status = ((Number) getStatus.invoke(propertyValue)).intValue();
                    if (status == 0) {
                        int rpm = CarRpmValue.toDisplayRpm(getValue.invoke(propertyValue));
                        listener.onRpm(rpm, status);
                        readsWithoutAvailableValue = 0;
                        if (rpm != lastLoggedRpm) {
                            Log.i(TAG, target.label + "=" + rpm + " status=" + status);
                            lastLoggedRpm = rpm;
                        }
                    } else {
                        readsWithoutAvailableValue++;
                    }
                } else {
                    readsWithoutAvailableValue++;
                }
                if (readsWithoutAvailableValue >= 30) {
                    throw new IOException(target.label
                            + " produced no available value within 3 seconds");
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

    /**
     * Prefer standard AAOS ENGINE_RPM. When available, the property catalog validates
     * both support and the Java value type before the first read.
     */
    private PropertyTarget resolveProperty(Object propertyManager) throws Exception {
        Method getPropertyList;
        try {
            getPropertyList = propertyManager.getClass().getMethod("getPropertyList");
        } catch (NoSuchMethodException unavailable) {
            listener.onLog("当前 Car API 未公开属性目录，将直接探测标准 ENGINE_RPM");
            return new PropertyTarget(ENGINE_RPM, Float.class, "ENGINE_RPM");
        }

        Object result;
        try {
            result = getPropertyList.invoke(propertyManager);
        } catch (InvocationTargetException denied) {
            listener.onLog("读取 Car API 属性目录被拒绝，将直接探测标准 ENGINE_RPM");
            return new PropertyTarget(ENGINE_RPM, Float.class, "ENGINE_RPM");
        }
        if (!(result instanceof List)) {
            listener.onLog("Car API 属性目录格式未知，将直接探测标准 ENGINE_RPM");
            return new PropertyTarget(ENGINE_RPM, Float.class, "ENGINE_RPM");
        }

        PropertyTarget standard = null;
        PropertyTarget vendor = null;
        for (Object config : (List<?>) result) {
            if (config == null) continue;
            int propertyId = ((Number) config.getClass().getMethod("getPropertyId")
                    .invoke(config)).intValue();
            if (propertyId != ENGINE_RPM && propertyId != VENDOR_ENGINE_RPM) continue;
            Class<?> propertyType = readPropertyType(config,
                    propertyId == ENGINE_RPM ? Float.class : Integer.class);
            PropertyTarget candidate = new PropertyTarget(propertyId, propertyType,
                    propertyId == ENGINE_RPM ? "ENGINE_RPM" : "VENDOR_ENGINE_RPM");
            if (propertyId == ENGINE_RPM) standard = candidate;
            if (propertyId == VENDOR_ENGINE_RPM) vendor = candidate;
        }
        if (standard != null) return standard;
        if (vendor != null) return vendor;
        throw new IOException("CarPropertyManager exposes no supported engine RPM property");
    }

    private static Class<?> readPropertyType(Object config, Class<?> fallback) {
        try {
            Object value = config.getClass().getMethod("getPropertyType").invoke(config);
            return value instanceof Class ? (Class<?>) value : fallback;
        } catch (Throwable unavailable) {
            return fallback;
        }
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof InvocationTargetException
                && ((InvocationTargetException) error).getCause() != null) {
            return ((InvocationTargetException) error).getCause();
        }
        return error;
    }

    @Override public void close() {
        closed.set(true);
        Thread currentWorker = worker;
        worker = null;
        if (currentWorker != null) currentWorker.interrupt();
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

    private static final class PropertyTarget {
        final int propertyId;
        final Class<?> valueClass;
        final String label;

        PropertyTarget(int propertyId, Class<?> valueClass, String label) {
            this.propertyId = propertyId;
            this.valueClass = valueClass;
            this.label = label;
        }
    }
}
