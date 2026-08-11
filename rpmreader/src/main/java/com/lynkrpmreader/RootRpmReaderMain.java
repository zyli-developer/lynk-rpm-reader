package com.lynkrpmreader;

import android.content.Context;
import android.os.Looper;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

/** Root app_process entry point used on vehicles that protect ENGINE_RPM. */
public final class RootRpmReaderMain {
    public static final int SERVER_PORT = 38605;
    private RootRpmReaderMain() {
    }

    public static void main(String[] args) {
        Object car = null;
        HandlerThread callbackThread = null;
        try {
            if (Looper.myLooper() == null) {
                Looper.prepareMainLooper();
            }
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = activityThreadClass.getMethod("systemMain").invoke(null);
            Context systemContext = (Context) activityThreadClass.getMethod("getSystemContext")
                    .invoke(activityThread);
            Context context = systemContext.createPackageContext(
                    "com.lynkrpmreader", Context.CONTEXT_IGNORE_SECURITY);

            Class<?> carClass = Class.forName("android.car.Car");
            callbackThread = new HandlerThread("Root-RPM-events");
            callbackThread.start();
            car = createCarFromBinder(context, carClass,
                    new Handler(callbackThread.getLooper()));
            Object propertyManager = carClass.getMethod("getCarManager", String.class)
                    .invoke(car, "property");
            Method getProperty = propertyManager.getClass().getMethod(
                    "getProperty", Class.class, int.class, int.class);
            Class<?> valueClass = Class.forName("android.car.hardware.CarPropertyValue");
            Method getValue = valueClass.getMethod("getValue");
            Method getStatus = valueClass.getMethod("getStatus");
            if (args.length > 0 && "serve".equals(args[0])) {
                AtomicReference<RpmSample> callbackSample = new AtomicReference<>();
                Object callback = registerRpmCallback(propertyManager, valueClass,
                        getValue, getStatus, callbackSample);
                serve(propertyManager, getProperty, getValue, getStatus,
                        callbackSample, callback);
                return;
            }
            boolean stream = args.length > 0 && "stream".equals(args[0]);
            do {
                Object propertyValue = getProperty.invoke(propertyManager, Float.class,
                        CarApiRpmClient.ENGINE_RPM, CarApiRpmClient.GLOBAL_AREA);
                Object value = getValue.invoke(propertyValue);
                int status = ((Number) getStatus.invoke(propertyValue)).intValue();
                System.out.println("RPM|" + ((Number) value).floatValue() + "|" + status);
                System.out.flush();
                if (stream) {
                    Thread.sleep(100L);
                }
            } while (stream);
        } catch (Throwable error) {
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            System.err.println("ERROR|" + cause.getClass().getName() + "|"
                    + String.valueOf(cause.getMessage()));
            cause.printStackTrace(System.err);
            System.exit(1);
        } finally {
            if (car != null) {
                try {
                    car.getClass().getMethod("disconnect").invoke(car);
                } catch (Throwable ignored) {
                }
            }
            if (callbackThread != null) {
                callbackThread.quitSafely();
            }
        }
        System.exit(0);
    }

    private static void serve(Object propertyManager, Method getProperty,
            Method getValue, Method getStatus, AtomicReference<RpmSample> callbackSample,
            Object callbackToKeepAlive) throws Exception {
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), SERVER_PORT));
        while (true) {
            try (Socket client = server.accept();
                 BufferedWriter writer = new BufferedWriter(
                         new OutputStreamWriter(client.getOutputStream()))) {
                while (!client.isClosed()) {
                    RpmSample sample = callbackSample.get();
                    if (sample == null) {
                        Object propertyValue = getProperty.invoke(propertyManager, Float.class,
                                CarApiRpmClient.ENGINE_RPM, CarApiRpmClient.GLOBAL_AREA);
                        Object value = getValue.invoke(propertyValue);
                        int status = ((Number) getStatus.invoke(propertyValue)).intValue();
                        sample = new RpmSample(((Number) value).floatValue(), status);
                    }
                    writer.write("RPM|" + sample.value + "|" + sample.status);
                    writer.newLine();
                    writer.flush();
                    Thread.sleep(100L);
                }
            } catch (java.io.IOException ignored) {
                // A closed client is expected; wait for the next app connection.
            }
        }
    }

    private static Object registerRpmCallback(Object propertyManager, Class<?> valueClass,
            Method getValue, Method getStatus, AtomicReference<RpmSample> sample)
            throws Exception {
        Class<?> callbackClass = Class.forName(
                "android.car.hardware.property.CarPropertyManager$CarPropertyEventCallback");
        Method getPropertyId = valueClass.getMethod("getPropertyId");
        Object callback = Proxy.newProxyInstance(callbackClass.getClassLoader(),
                new Class<?>[] {callbackClass}, (proxy, method, args) -> {
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return args != null && args.length == 1 && proxy == args[0];
                    }
                    if ("toString".equals(method.getName())) {
                        return "RpmReaderCallback";
                    }
                    if ("onChangeEvent".equals(method.getName()) && args != null
                            && args.length > 0 && args[0] != null) {
                        int propertyId = ((Number) getPropertyId.invoke(args[0])).intValue();
                        if (propertyId == CarApiRpmClient.ENGINE_RPM) {
                            Object value = getValue.invoke(args[0]);
                            int status = ((Number) getStatus.invoke(args[0])).intValue();
                            sample.set(new RpmSample(((Number) value).floatValue(), status));
                        }
                    }
                    return null;
                });
        Method register = propertyManager.getClass().getMethod("registerCallback",
                callbackClass, int.class, float.class);
        Object result = register.invoke(propertyManager, callback,
                CarApiRpmClient.ENGINE_RPM, 10.0f);
        if (result instanceof Boolean && !((Boolean) result)) {
            throw new IllegalStateException("ENGINE_RPM callback registration rejected");
        }
        return callback;
    }

    private static Object createCarFromBinder(Context context, Class<?> carClass,
            Handler handler)
            throws Exception {
        Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) serviceManagerClass.getMethod("getService", String.class)
                .invoke(null, "car_service");
        if (binder == null) {
            throw new IllegalStateException("car_service binder not found");
        }
        Class<?> iCarClass = Class.forName("android.car.ICar");
        Object iCar = Class.forName("android.car.ICar$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        for (Constructor<?> constructor : carClass.getDeclaredConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length == 3 && Context.class.isAssignableFrom(types[0])
                    && types[1] == iCarClass && types[2] == Handler.class) {
                constructor.setAccessible(true);
                return constructor.newInstance(context, iCar, handler);
            }
        }
        throw new NoSuchMethodException("Car(Context, ICar, Handler)");
    }

    private static final class RpmSample {
        final float value;
        final int status;

        RpmSample(float value, int status) {
            this.value = value;
            this.status = status;
        }
    }
}
