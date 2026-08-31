package com.lynk.rpmreader;

import java.util.Locale;

/** Formatting shared by both instrument placement options. */
final class RpmDisplayText {
    private RpmDisplayText() {}

    static String available(int rpm) {
        int safeRpm = Math.max(0, Math.min(rpm, RpmGaugeModel.MAX_RPM));
        return String.format(Locale.ROOT, "%.1f × 1000", safeRpm / 1000f);
    }

    static String unavailable() {
        return "—.- × 1000";
    }

    static String hudAvailable(int rpm) {
        int safeRpm = Math.max(0, Math.min(rpm, RpmGaugeModel.MAX_RPM));
        return String.format(Locale.ROOT, "%04d", safeRpm);
    }

    static String hudUnavailable() {
        return "----";
    }
}
