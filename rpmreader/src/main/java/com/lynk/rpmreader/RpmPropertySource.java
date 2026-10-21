package com.lynk.rpmreader;

import java.lang.reflect.Method;

/** Vehicle property candidates observed in EVCC; availability is checked on the car. */
final class RpmPropertySource {
    static final int STANDARD_ENGINE_RPM = 0x11600305; // Float, area 0
    static final int LYNK_E245_ENGINE_RPM = 0x21408066; // Int32, area 0

    final int id;
    final Class<?> valueType;
    final String name;

    private RpmPropertySource(int id, Class<?> valueType, String name) {
        this.id = id;
        this.valueType = valueType;
        this.name = name;
    }

    static RpmPropertySource findReadable(Object manager, Method getProperty,
            Method getValue, Method getStatus) throws Exception {
        Exception lastError = null;
        for (RpmPropertySource source : new RpmPropertySource[] {
                new RpmPropertySource(STANDARD_ENGINE_RPM, Float.class, "ENGINE_RPM"),
                new RpmPropertySource(LYNK_E245_ENGINE_RPM, Integer.class,
                        "Lynk E245 vendor RPM")}) {
            try {
                Object property = getProperty.invoke(manager, source.valueType, source.id, 0);
                if (property != null
                        && ((Number) getStatus.invoke(property)).intValue() == 0
                        && getValue.invoke(property) instanceof Number) {
                    return source;
                }
            } catch (Exception error) {
                lastError = error;
            }
        }
        if (lastError != null) throw lastError;
        throw new IllegalStateException("No readable ENGINE_RPM vehicle property");
    }
}
