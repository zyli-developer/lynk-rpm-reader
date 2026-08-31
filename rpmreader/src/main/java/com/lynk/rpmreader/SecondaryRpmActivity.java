package com.lynk.rpmreader;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/** Numeric-only RPM surface. Exactly one instrument placement is rendered per instance. */
public final class SecondaryRpmActivity extends Activity
        implements VehicleRpmClient.Listener, GearStateClient.Listener {
    static final String EXTRA_PROJECTION_DISPLAY_ID = "projection_display_id";
    static final String EXTRA_DISPLAY_LOCATION = "display_location";

    private static final String TAG = "LynkRpmSecondary";
    private static final int DAY_TEXT = Color.rgb(35, 42, 50);
    private static final int HUD_TEXT = Color.rgb(235, 252, 255);
    private static final int ERROR_TEXT = Color.rgb(125, 130, 136);
    private static WeakReference<SecondaryRpmActivity> activeInstance =
            new WeakReference<>(null);

    private FrameLayout root;
    private TextView rpmView;
    private RpmDisplayLocation displayLocation;
    private VehicleRpmClient client;
    private GearStateClient gearClient;
    private int currentGear = Integer.MIN_VALUE;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        displayLocation = RpmDisplayLocation.fromPersistedValue(
                getIntent().getStringExtra(EXTRA_DISPLAY_LOCATION));
        if (!displayLocation.isEnabled()) {
            Log.w(TAG, "Refusing to render without one selected instrument location");
            finish();
            return;
        }

        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        buildUi();
        activeInstance = new WeakReference<>(this);
        Log.i(TAG, "Numeric RPM surface created on display "
                + currentDisplayId() + " for " + displayLocation.persistedValue()
                + ", bounds=" + getResources().getDisplayMetrics().widthPixels
                + "x" + getResources().getDisplayMetrics().heightPixels);
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        RpmDisplayLocation requested = RpmDisplayLocation.fromPersistedValue(
                intent.getStringExtra(EXTRA_DISPLAY_LOCATION));
        if (!requested.isEnabled()) {
            finish();
            return;
        }
        displayLocation = requested;
        if (rpmView != null) {
            applyTextAppearance();
            applyGearVisibility();
        }
        if (root != null) root.post(this::applyPrototypePlacement);
        Log.i(TAG, "Numeric RPM placement changed to " + displayLocation.persistedValue());
    }

    @Override protected void onStart() {
        super.onStart();
        if (displayLocation != null && displayLocation.isEnabled()) connect();
    }

    @Override protected void onStop() {
        disconnect();
        super.onStop();
    }

    @Override protected void onDestroy() {
        SecondaryRpmActivity active = activeInstance.get();
        if (active == this) activeInstance.clear();
        boolean rearmHudRestore = HudAutostartPolicy.shouldRearmAfterSurfaceDestroyed(
                displayLocation, isChangingConfigurations(),
                HudBootJobService.shouldRestore(this));
        super.onDestroy();
        if (rearmHudRestore) {
            Log.i(TAG, "HUD RPM surface destroyed; scheduling display reacquisition");
            HudBootJobService.markHudSurfaceInactive(getApplicationContext());
            HudBootJobService.scheduleAfterHudDisplayLoss(getApplicationContext());
        }
    }

    static boolean isActiveOnDisplay(int displayId, RpmDisplayLocation location) {
        SecondaryRpmActivity active = activeInstance.get();
        return active != null && !active.isFinishing()
                && active.currentDisplayId() == displayId
                && active.displayLocation == location;
    }

    static void finishActiveInstance() {
        SecondaryRpmActivity active = activeInstance.get();
        if (active == null || active.isFinishing()) return;
        active.runOnUiThread(active::finishAndRemoveTask);
    }

    private int currentDisplayId() {
        android.view.Display display = getWindowManager().getDefaultDisplay();
        return display == null ? -1 : display.getDisplayId();
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);

        rpmView = new TextView(this);
        rpmView.setText(unavailableText());
        rpmView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        rpmView.setSingleLine(true);
        rpmView.setBackgroundColor(Color.TRANSPARENT);
        applyTextAppearance();
        applyGearVisibility();
        root.addView(rpmView, new FrameLayout.LayoutParams(1, 1));
        setContentView(root);

        root.post(this::applyPrototypePlacement);
    }

    private void applyPrototypePlacement() {
        int width = root.getWidth();
        int height = root.getHeight();
        if (width <= 0 || height <= 0) return;

        FrameLayout.LayoutParams params;
        if (displayLocation == RpmDisplayLocation.HUD_LEFT) {
            // Keep the OEM D indicator, map and central navigation arrow unobstructed.
            params = new FrameLayout.LayoutParams(
                    Math.round(width * 0.42f), Math.round(height * 0.18f));
            params.leftMargin = Math.round(width * 0.06f);
            params.topMargin = Math.round(height * 0.75f);
        } else if (displayLocation == RpmDisplayLocation.LEFT_SPEED) {
            // Bottom row under the unchanged speed/power stack.
            params = new FrameLayout.LayoutParams(
                    Math.round(width * 0.25f), Math.round(height * 0.11f));
            params.leftMargin = Math.round(width * 0.08f);
            params.topMargin = Math.round(height * 0.68f);
        } else {
            // Content area of the selected RPM card, away from the vertical card selector.
            params = new FrameLayout.LayoutParams(
                    Math.round(width * 0.21f), Math.round(height * 0.22f));
            params.leftMargin = Math.round(width * 0.72f);
            params.topMargin = Math.round(height * 0.32f);
        }
        rpmView.setLayoutParams(params);
    }

    private void connect() {
        disconnect();
        client = new VehicleRpmClient(this, this);
        client.start();
        if (displayLocation == RpmDisplayLocation.HUD_LEFT) {
            gearClient = new GearStateClient(this, this);
            gearClient.start();
        }
    }

    private void disconnect() {
        if (client != null) {
            client.close();
            client = null;
        }
        if (gearClient != null) {
            gearClient.close();
            gearClient = null;
        }
        currentGear = Integer.MIN_VALUE;
        applyGearVisibility();
    }

    private void applyTextAppearance() {
        if (rpmView == null) return;
        if (displayLocation == RpmDisplayLocation.HUD_LEFT) {
            rpmView.setTextSize(27);
            rpmView.setTextColor(HUD_TEXT);
            rpmView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            rpmView.setShadowLayer(4f, 0f, 2f, Color.BLACK);
        } else {
            rpmView.setTextSize(displayLocation == RpmDisplayLocation.LEFT_SPEED ? 23 : 31);
            rpmView.setTextColor(DAY_TEXT);
            rpmView.setGravity(Gravity.CENTER);
            rpmView.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
        }
    }

    private void applyGearVisibility() {
        if (rpmView == null) return;
        boolean visible = displayLocation != RpmDisplayLocation.HUD_LEFT
                || HudGearVisibility.isVisible(currentGear);
        rpmView.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }

    @Override public void onStatus(String status, boolean error) {
        if (!error) return;
        runOnUiThread(() -> {
            rpmView.setText(unavailableText());
            rpmView.setTextColor(ERROR_TEXT);
        });
    }

    @Override public void onLog(String message) {
        Log.i(TAG, message);
    }

    @Override public void onRpm(int rpm, int status) {
        runOnUiThread(() -> {
            rpmView.setText(availableText(rpm));
            rpmView.setTextColor(displayLocation == RpmDisplayLocation.HUD_LEFT
                    ? HUD_TEXT : DAY_TEXT);
        });
    }

    @Override public void onGearChanged(int gear) {
        runOnUiThread(() -> {
            currentGear = gear;
            applyGearVisibility();
            Log.i(TAG, "HUD RPM visibility=" + HudGearVisibility.isVisible(gear)
                    + " for CURRENT_GEAR=" + gear);
        });
    }

    private String availableText(int rpm) {
        return displayLocation == RpmDisplayLocation.HUD_LEFT
                ? RpmDisplayText.hudAvailable(rpm)
                : RpmDisplayText.available(rpm);
    }

    private String unavailableText() {
        return displayLocation == RpmDisplayLocation.HUD_LEFT
                ? RpmDisplayText.hudUnavailable()
                : RpmDisplayText.unavailable();
    }

    @Override public void onGearReadFailed(Throwable error) {
        runOnUiThread(() -> {
            currentGear = Integer.MIN_VALUE;
            applyGearVisibility();
        });
    }
}
