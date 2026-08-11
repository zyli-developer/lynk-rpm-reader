package io.github.zylideveloper.rpmreader;

/** Pure startup-animation timing functions used by rendering and tests. */
final class StartupMotion {
    private StartupMotion() {}

    static float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }

    static float iconScale(float progress) {
        float p = clamp(progress / 0.55f);
        float overshoot = 1.70158f;
        p -= 1f;
        return 0.72f + 0.28f * (p * p * ((overshoot + 1f) * p + overshoot) + 1f);
    }

    static float contentAlpha(float progress) {
        return clamp(progress / 0.20f) * clamp((1f - progress) / 0.18f);
    }

    static float bladeProgress(float progress, int index) {
        return clamp((progress - 0.18f - index * 0.08f) / 0.28f);
    }
}
