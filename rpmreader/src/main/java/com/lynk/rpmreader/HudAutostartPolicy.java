package com.lynk.rpmreader;

/** Pure policy that prevents HUD restore before the privileged boot path was verified. */
final class HudAutostartPolicy {
    private HudAutostartPolicy() {}

    static boolean shouldSchedule(
            RpmDisplayLocation location, boolean privilegedBootPathVerified) {
        return location == RpmDisplayLocation.HUD_LEFT && privilegedBootPathVerified;
    }

    static boolean shouldRearmAfterSurfaceDestroyed(
            RpmDisplayLocation renderedLocation,
            boolean changingConfigurations,
            boolean restoreStillConfigured) {
        return renderedLocation == RpmDisplayLocation.HUD_LEFT
                && !changingConfigurations
                && restoreStillConfigured;
    }
}
