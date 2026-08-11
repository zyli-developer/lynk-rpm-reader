package com.lynk.rpmreader;

import java.util.Locale;

/** Pure tachometer geometry shared by rendering and zero-dependency tests. */
final class RpmGaugeModel {
    static final int MAX_RPM = 8000;
    static final int TORQUE_BAND_START_RPM = 2500;
    static final int TORQUE_BAND_END_RPM = 4000;
    static final int POWER_PEAK_RPM = 5500;
    static final float START_ANGLE = 150f;
    static final float SWEEP_ANGLE = 240f;

    private RpmGaugeModel() {}

    static int clamp(int rpm) {
        return Math.max(0, Math.min(MAX_RPM, rpm));
    }

    static float angleForRpm(float rpm) {
        float safe = Math.max(0f, Math.min(MAX_RPM, rpm));
        return START_ANGLE + SWEEP_ANGLE * safe / MAX_RPM;
    }

    static float sweepForRpm(float rpm) {
        return angleForRpm(rpm) - START_ANGLE;
    }

    static String formatThousands(float rpm) {
        return String.format(Locale.ROOT, "%.1f", clamp(Math.round(rpm)) / 1000f);
    }

    static String formatExact(float rpm) {
        return String.format(Locale.ROOT, "%d RPM", clamp(Math.round(rpm)));
    }

    enum Zone { LOW, TORQUE, POWER, ABOVE_PUBLISHED_PEAK }

    static Zone zoneForRpm(float rpm) {
        if (rpm < TORQUE_BAND_START_RPM) return Zone.LOW;
        if (rpm <= TORQUE_BAND_END_RPM) return Zone.TORQUE;
        if (rpm <= POWER_PEAK_RPM) return Zone.POWER;
        return Zone.ABOVE_PUBLISHED_PEAK;
    }
}
