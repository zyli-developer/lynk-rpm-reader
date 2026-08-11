package com.lynk.rpmreader;

/** Value conversion shared by the Android Car API path and its JVM tests. */
final class CarRpmValue {
    private CarRpmValue() {
    }

    static int toDisplayRpm(Object value) {
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException("ENGINE_RPM is not numeric");
        }
        float rpm = ((Number) value).floatValue();
        if (Float.isNaN(rpm) || Float.isInfinite(rpm)) {
            throw new IllegalArgumentException("ENGINE_RPM is not finite");
        }
        return Math.max(0, Math.round(rpm));
    }
}
