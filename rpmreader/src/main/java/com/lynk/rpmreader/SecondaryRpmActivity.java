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

/** Numeric-only RPM surface. Exactly one instrument placement is rendered per instance. */
public final class SecondaryRpmActivity extends Activity
        implements VehicleRpmClient.Listener {
    static final String EXTRA_PROJECTION_DISPLAY_ID = "projection_display_id";
    static final String EXTRA_DISPLAY_LOCATION = "display_location";

    private static final String TAG = "LynkRpmSecondary";
    private static final int DAY_TEXT = Color.rgb(35, 42, 50);
    private static final int ERROR_TEXT = Color.rgb(125, 130, 136);

    private FrameLayout root;
    private TextView rpmView;
    private RpmDisplayLocation displayLocation;
    private VehicleRpmClient client;

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
        Log.i(TAG, "Numeric RPM surface created on display "
                + getDisplay().getDisplayId() + " for " + displayLocation.persistedValue());
    }

    @Override protected void onStart() {
        super.onStart();
        if (displayLocation != null && displayLocation.isEnabled()) connect();
    }

    @Override protected void onStop() {
        disconnect();
        super.onStop();
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);

        rpmView = new TextView(this);
        rpmView.setText(RpmDisplayText.unavailable());
        rpmView.setTextSize(displayLocation == RpmDisplayLocation.LEFT_SPEED ? 23 : 31);
        rpmView.setTextColor(DAY_TEXT);
        rpmView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        rpmView.setGravity(Gravity.CENTER);
        rpmView.setSingleLine(true);
        rpmView.setBackgroundColor(Color.TRANSPARENT);
        root.addView(rpmView, new FrameLayout.LayoutParams(1, 1));
        setContentView(root);

        root.post(this::applyPrototypePlacement);
    }

    private void applyPrototypePlacement() {
        int width = root.getWidth();
        int height = root.getHeight();
        if (width <= 0 || height <= 0) return;

        FrameLayout.LayoutParams params;
        if (displayLocation == RpmDisplayLocation.LEFT_SPEED) {
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
    }

    private void disconnect() {
        if (client == null) return;
        client.close();
        client = null;
    }

    @Override public void onStatus(String status, boolean error) {
        if (!error) return;
        runOnUiThread(() -> {
            rpmView.setText(RpmDisplayText.unavailable());
            rpmView.setTextColor(ERROR_TEXT);
        });
    }

    @Override public void onLog(String message) {
        Log.i(TAG, message);
    }

    @Override public void onRpm(int rpm, int status) {
        runOnUiThread(() -> {
            rpmView.setText(RpmDisplayText.available(rpm));
            rpmView.setTextColor(DAY_TEXT);
        });
    }
}
