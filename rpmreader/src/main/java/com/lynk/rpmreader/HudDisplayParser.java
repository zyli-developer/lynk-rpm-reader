package com.lynk.rpmreader;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure parser for the Flyme Auto private HUD entry in {@code dumpsys display}. */
final class HudDisplayParser {
    private static final Pattern HUD_DISPLAY_PATTERN = Pattern.compile(
            "DisplayInfo\\{\\\"Ex Share Display[^\\\"]*\\\", displayId (\\d+),"
                    + "[^\\r\\n]*real 520 x 280");

    private HudDisplayParser() {}

    static int findHudDisplayId(String dumpsysDisplay) {
        if (dumpsysDisplay == null || dumpsysDisplay.isEmpty()) return -1;
        Matcher matcher = HUD_DISPLAY_PATTERN.matcher(dumpsysDisplay);
        int candidate = -1;
        while (matcher.find()) {
            candidate = Integer.parseInt(matcher.group(1));
        }
        return candidate;
    }
}
