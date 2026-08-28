package com.lynk.rpmreader;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reads the standard AAOS CURRENT_GEAR property and reports changes. */
final class GearStateClient implements AutoCloseable {
    private static final String TAG = "LynkRpmGear";
    static final int CURRENT_GEAR = 0x11400401;
    static final int GLOBAL_AREA = 0;
    static final int GEAR_NEUTRAL = 1;
    static final int GEAR_DRIVE = 8;

    interface Listener {
        void onGearChanged(int gear);
        void onGearReadFailed(Throwable error);
    }

    private final Context context;
    private final Listener listener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Object car;
    private volatile Thread worker;

    GearStateClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void start() {
        closed.set(false);
        Thread thread = new Thread(this::readLoop, "CarAPI-current-gear");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    private void readLoop() {
        try {
            Class<?> carClass = Class.forName("android.car.Car");
            Object currentCar = carClass.getMethod("createCar", Context.class)
                    .invoke(null, context);
            if (currentCar == null) throw new IllegalStateException("Car.createCar returned null");
            car = currentCar;

            Object propertyManager = carClass.getMethod("getCarManager", String.class)
                    .invoke(currentCar, "property");
            if (propertyManager == null) {
                throw new IllegalStateException("CarPropertyManager unavailable");
            }

            Method getProperty = propertyManager.getClass().getMethod(
                    "getProperty", Class.class, int.class, int.class);
            Class<?> valueClass = Class.forName("android.car.hardware.CarPropertyValue");
            Method getValue = valueClass.getMethod("getValue");
            Method getStatus = valueClass.getMethod("getStatus");

            int lastGear = Integer.MIN_VALUE;
            while (!closed.get()) {
                Object propertyValue = getProperty.invoke(
                        propertyManager, Integer.class, CURRENT_GEAR, GLOBAL_AREA);
                if (propertyValue != null) {
                    int status = ((Number) getStatus.invoke(propertyValue)).intValue();
                    Object raw = getValue.invoke(propertyValue);
                    if (status == 0 && raw instanceof Number) {
                        int gear = ((Number) raw).intValue();
                        if (gear != lastGear) {
                            lastGear = gear;
                            Log.i(TAG, "CURRENT_GEAR=" + gear);
                            listener.onGearChanged(gear);
                        }
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
                Log.e(TAG, "CURRENT_GEAR read failed", cause);
                listener.onGearReadFailed(cause);
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
}
