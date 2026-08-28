package com.lynk.rpmreader;

/** Pure gear-state rule for the HUD numeric RPM layer. */
final class HudGearVisibility {
    private static final int GEAR_NEUTRAL = 1;
    private static final int GEAR_DRIVE = 8;

    private HudGearVisibility() {}

    static boolean isVisible(int gear) {
        return gear == GEAR_DRIVE || gear == GEAR_NEUTRAL;
    }
}
