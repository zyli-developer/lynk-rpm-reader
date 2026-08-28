package com.lynk.rpmreader;

/** Mutually exclusive instrument location selected for the RPM readout. */
enum RpmDisplayLocation {
    OFF("off", "关闭"),
    LEFT_SPEED("left_speed", "左侧速度区"),
    RIGHT_CARD("right_card", "右侧转速卡片"),
    HUD_LEFT("hud_left", "HUD 左下角（D/N 挡）");

    private final String persistedValue;
    private final String displayName;

    RpmDisplayLocation(String persistedValue, String displayName) {
        this.persistedValue = persistedValue;
        this.displayName = displayName;
    }

    String persistedValue() {
        return persistedValue;
    }

    String displayName() {
        return displayName;
    }

    boolean isEnabled() {
        return this != OFF;
    }

    static RpmDisplayLocation fromPersistedValue(String value) {
        if (value != null) {
            for (RpmDisplayLocation location : values()) {
                if (location.persistedValue.equals(value)) return location;
            }
        }
        return OFF;
    }
}
