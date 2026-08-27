package com.lynk.rpmreader;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.Display;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends Activity implements VehicleRpmClient.Listener {
    private static final String PREFS = "rpm_secondary_display";
    private static final String PREF_DISPLAY_ID = "display_id";
    private static final String PREF_DISPLAY_LOCATION = "display_location";
    private static final String PREF_ACTIVE_DISPLAY_LOCATION = "active_display_location";
    private static final String EXTRA_START_SECONDARY = "start_secondary";
    private static final int INK = Color.rgb(4, 12, 18);
    private static final int PANEL = Color.rgb(9, 29, 39);
    private static final int MUTED = Color.rgb(126, 151, 164);
    private static final int ICE = Color.rgb(0, 226, 255);
    private static final int AMBER = Color.rgb(255, 181, 71);
    private static final int CORAL = Color.rgb(255, 94, 91);

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
    private RpmGaugeView gaugeView;
    private TextView statusView;
    private TextView rawView;
    private TextView logView;
    private View statusDot;
    private VehicleRpmClient client;
    private StartupOverlayView startupOverlay;
    private RadioGroup displayLocationGroup;
    private RadioButton displayOffButton;
    private RadioButton displayLeftButton;
    private RadioButton displayRightButton;
    private EcarxProjectionClient projectionOperation;
    private boolean suppressLocationCallback;
    private boolean firstLaunch = true;
    private boolean activityStarted;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setNavigationBarColor(INK);
        buildUi();
        appendLog("目标信号：EngNSafeEngN / 0x12600596 / 0x12600597");
        appendLog("等待连接车辆 APVP 数据服务");
        if (getIntent().getBooleanExtra(EXTRA_START_SECONDARY, false)) {
            displayLocationGroup.post(() -> {
                RpmDisplayLocation location = selectedDisplayLocation();
                if (location.isEnabled()) startSecondaryDisplay(location);
            });
        }
    }

    @Override protected void onResume() {
        super.onResume();
        refreshDisplayLocationControls();
        displayLocationGroup.post(() -> {
            RpmDisplayLocation location = selectedDisplayLocation();
            if (projectionOperation == null
                    && location.isEnabled()
                    && activeSecondaryDisplayId() < 0) {
                startSecondaryDisplay(location);
            }
        });
    }

    @Override protected void onStart() {
        super.onStart();
        activityStarted = true;
        if (firstLaunch) {
            firstLaunch = false;
            playStartupAnimation();
        } else {
            connect();
        }
    }

    @Override protected void onStop() {
        activityStarted = false;
        if (startupOverlay != null) {
            startupOverlay.cancel();
            startupOverlay.animate().cancel();
            startupOverlay.setVisibility(View.GONE);
        }
        if (gaugeView != null) gaugeView.cancelAnimation();
        disconnect();
        super.onStop();
    }

    private void connect() {
        disconnect();
        setConnectionState("正在连接车辆", false, AMBER);
        client = new VehicleRpmClient(this, this);
        client.start();
    }

    private void disconnect() {
        if (client != null) {
            client.close();
            client = null;
        }
    }

    private void buildUi() {
        FrameLayout shell = new FrameLayout(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(dp(24), dp(20), dp(24), dp(18));
        root.setBackgroundColor(INK);

        LinearLayout gaugeColumn = new LinearLayout(this);
        gaugeColumn.setOrientation(LinearLayout.VERTICAL);
        gaugeColumn.setPadding(dp(10), 0, dp(22), 0);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("VEHICLE RPM  //  ENGINE TELEMETRY", 24, Color.rgb(208, 240, 244), Typeface.BOLD);
        title.setLetterSpacing(0.12f);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, dp(62), 1f));
        TextView live = label("●  APVP LIVE", 20, ICE, Typeface.BOLD);
        live.setLetterSpacing(0.12f);
        titleRow.addView(live);
        gaugeColumn.addView(titleRow);

        gaugeView = new RpmGaugeView(this);
        gaugeColumn.addView(gaugeView, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(gaugeColumn, new LinearLayout.LayoutParams(0, -1, 2.15f));

        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(29, 56, 68));
        root.addView(divider, new LinearLayout.LayoutParams(dp(1), -1));

        ScrollView panelScroll = new ScrollView(this);
        panelScroll.setFillViewport(true);
        panelScroll.setVerticalScrollBarEnabled(false);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(28), dp(10), 0, dp(6));
        TextView eyebrow = label("VEHICLE SIGNAL", 20, MUTED, Typeface.BOLD);
        eyebrow.setLetterSpacing(0.22f);
        panel.addView(eyebrow);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, dp(18), 0, dp(16));
        statusDot = new View(this);
        setDotColor(AMBER);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(14), dp(14));
        dotParams.rightMargin = dp(14);
        statusRow.addView(statusDot, dotParams);
        statusView = label("正在连接车辆", 25, Color.WHITE, Typeface.BOLD);
        statusRow.addView(statusView, new LinearLayout.LayoutParams(0, -2, 1f));
        panel.addView(statusRow);
        addDisplayLocationControls(panel);

        panel.addView(infoBlock("车辆链路", "APVP / VDDM / 10 HZ"));
        panel.addView(infoBlock("动力总成", "BHE15-BFZ · 3DHT EVO"));
        panel.addView(zoneLegend());
        rawView = infoBlock("当前帧", "等待数据");
        panel.addView(rawView);

        Button reconnect = new Button(this);
        reconnect.setAllCaps(false);
        reconnect.setText("重新连接");
        reconnect.setTextSize(22);
        reconnect.setTextColor(INK);
        reconnect.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        reconnect.setBackground(roundRect(ICE, 12));
        reconnect.setOnClickListener(v -> connect());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, dp(64));
        buttonParams.topMargin = dp(12);
        panel.addView(reconnect, buttonParams);

        TextView logTitle = label("诊断记录", 20, MUTED, Typeface.BOLD);
        logTitle.setLetterSpacing(0.14f);
        LinearLayout.LayoutParams logTitleParams = new LinearLayout.LayoutParams(-1, -2);
        logTitleParams.topMargin = dp(20);
        panel.addView(logTitle, logTitleParams);

        logView = label("", 20, Color.rgb(172, 197, 207), Typeface.NORMAL);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setLineSpacing(dp(4), 1f);
        logView.setMovementMethod(new ScrollingMovementMethod());
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(logView);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(180)));
        panelScroll.addView(panel, new ScrollView.LayoutParams(-1, -2));
        root.addView(panelScroll, new LinearLayout.LayoutParams(0, -1, 1f));
        shell.addView(root, new FrameLayout.LayoutParams(-1, -1));
        startupOverlay = new StartupOverlayView(this);
        shell.addView(startupOverlay, new FrameLayout.LayoutParams(-1, -1));
        setContentView(shell);
    }

    private void addDisplayLocationControls(LinearLayout panel) {
        TextView locationTitle = label("仪表转速显示位置", 18, MUTED, Typeface.BOLD);
        locationTitle.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams locationTitleParams = new LinearLayout.LayoutParams(-1, -2);
        locationTitleParams.bottomMargin = dp(6);
        panel.addView(locationTitle, locationTitleParams);

        displayLocationGroup = new RadioGroup(this);
        displayLocationGroup.setOrientation(RadioGroup.HORIZONTAL);
        displayLocationGroup.setGravity(Gravity.CENTER_VERTICAL);
        displayLocationGroup.setPadding(dp(4), 0, dp(4), 0);
        GradientDrawable locationBackground = roundRect(PANEL, 12);
        locationBackground.setStroke(dp(1), ICE);
        displayLocationGroup.setBackground(locationBackground);

        displayOffButton = locationButton("关闭");
        displayLeftButton = locationButton("左侧速度区");
        displayRightButton = locationButton("右侧转速卡片");
        displayLocationGroup.addView(displayOffButton,
                new RadioGroup.LayoutParams(0, dp(54), 0.75f));
        displayLocationGroup.addView(displayLeftButton,
                new RadioGroup.LayoutParams(0, dp(54), 1.2f));
        displayLocationGroup.addView(displayRightButton,
                new RadioGroup.LayoutParams(0, dp(54), 1.45f));
        displayLocationGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (suppressLocationCallback) return;
            selectDisplayLocation(locationForCheckedId(checkedId));
        });
        LinearLayout.LayoutParams locationParams = new LinearLayout.LayoutParams(-1, dp(54));
        locationParams.bottomMargin = dp(12);
        panel.addView(displayLocationGroup, locationParams);
        refreshDisplayLocationControls();
    }

    private void playStartupAnimation() {
        startupOverlay.post(() -> startupOverlay.start(() -> {
            startupOverlay.animate().alpha(0f).setDuration(140L).withEndAction(() -> {
                startupOverlay.setVisibility(View.GONE);
                gaugeView.runStartupSweep(() -> {
                    if (activityStarted) connect();
                });
            }).start();
        }));
    }

    private TextView infoBlock(String title, String value) {
        TextView view = label(title.toUpperCase(Locale.ROOT) + "\n" + value,
                20, Color.rgb(220, 234, 239), Typeface.NORMAL);
        view.setLineSpacing(dp(5), 1f);
        view.setPadding(dp(18), dp(13), dp(14), dp(13));
        GradientDrawable tile = roundRect(PANEL, 6);
        tile.setStroke(dp(1), Color.rgb(25, 78, 92));
        view.setBackground(tile);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(10);
        view.setLayoutParams(params);
        return view;
    }

    private TextView zoneLegend() {
        String content = "转速特性\n■ 低转 0–2500   ■ 峰值扭矩 2500–4000\n■ 高功率 4000–5500   ■ 超出公开功率点 >5500";
        TextView view = label(content, 19, Color.rgb(190, 215, 223), Typeface.NORMAL);
        SpannableString colored = new SpannableString(content);
        int[] colors = {Color.rgb(48, 137, 255), Color.rgb(0, 226, 255),
                Color.rgb(255, 185, 69), Color.rgb(255, 70, 101)};
        int from = 0;
        for (int color : colors) {
            int marker = content.indexOf('■', from);
            if (marker < 0) break;
            colored.setSpan(new ForegroundColorSpan(color), marker, marker + 1,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            from = marker + 1;
        }
        view.setText(colored);
        view.setLineSpacing(dp(7), 1f);
        view.setPadding(dp(18), dp(13), dp(12), dp(13));
        GradientDrawable tile = roundRect(Color.rgb(8, 24, 33), 6);
        tile.setStroke(dp(1), Color.rgb(25, 78, 92));
        view.setBackground(tile);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(10);
        view.setLayoutParams(params);
        return view;
    }

    private TextView label(String value, int sp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", style));
        return view;
    }

    private RadioButton locationButton(String text) {
        RadioButton button = new RadioButton(this);
        button.setId(View.generateViewId());
        button.setText(text);
        button.setTextSize(16);
        int[][] states = {
                new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_enabled},
                new int[]{}
        };
        button.setTextColor(new ColorStateList(states, new int[]{
                INK, Color.rgb(92, 113, 124), Color.rgb(208, 240, 244)
        }));
        button.setGravity(Gravity.CENTER);
        button.setButtonDrawable(null);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_checked}, roundRect(ICE, 9));
        background.addState(new int[]{}, roundRect(Color.TRANSPARENT, 9));
        button.setBackground(background);
        button.setPadding(dp(6), 0, dp(6), 0);
        return button;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private void setDotColor(int color) {
        if (statusDot != null) statusDot.setBackground(roundRect(color, 9));
    }

    private void setConnectionState(String text, boolean error, int color) {
        if (statusView != null) {
            statusView.setText(text);
            statusView.setTextColor(error ? CORAL : Color.WHITE);
        }
        setDotColor(error ? CORAL : color);
    }

    private void selectDisplayLocation(RpmDisplayLocation location) {
        if (projectionOperation != null) {
            refreshDisplayLocationControls();
            return;
        }

        RpmDisplayLocation previous = activeDisplayLocation();
        persistSelectedDisplayLocation(location);
        setDisplayLocationControlsEnabled(false);

        int activeId = activeSecondaryDisplayId();
        if (activeId >= 0 && previous == location) {
            refreshDisplayLocationControls();
            return;
        }

        Runnable activateSelection = () -> {
            if (location.isEnabled()) {
                startSecondaryDisplay(location);
            } else {
                appendLog("仪表转速显示已关闭");
                refreshDisplayLocationControls();
            }
        };

        if (activeId >= 0) {
            appendLog("正在从“" + previous.displayName() + "”切换到“"
                    + location.displayName() + "”");
            stopSecondaryDisplay(activateSelection, previous);
        } else {
            activateSelection.run();
        }
    }

    private void startSecondaryDisplay(RpmDisplayLocation location) {
        if (projectionOperation != null) return;
        if (!location.isEnabled()) {
            refreshDisplayLocationControls();
            return;
        }
        int activeId = activeSecondaryDisplayId();
        if (activeId >= 0) {
            launchSecondaryActivity(activeId, location);
            return;
        }

        setDisplayLocationControlsEnabled(false);
        appendLog("正在为“" + location.displayName() + "”请求 Flyme Auto 副屏通道");
        projectionOperation = EcarxProjectionClient.createDisplay(this,
                new EcarxProjectionClient.Callback() {
                    @Override public void onComplete(int displayId) {
                        projectionOperation = null;
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putInt(PREF_DISPLAY_ID, displayId)
                                .putString(PREF_ACTIVE_DISPLAY_LOCATION,
                                        location.persistedValue())
                                .apply();
                        waitForProjectionDisplay(displayId, location, 20);
                    }

                    @Override public void onError(String message, Throwable error) {
                        projectionOperation = null;
                        persistSelectedDisplayLocation(RpmDisplayLocation.OFF);
                        clearSecondaryDisplayRuntime();
                        refreshDisplayLocationControls();
                        appendLog(message + (error == null ? "" : "：" + error.getMessage()));
                    }
                });
    }

    private void waitForProjectionDisplay(
            int displayId, RpmDisplayLocation location, int remainingAttempts) {
        DisplayManager manager = getSystemService(DisplayManager.class);
        Display display = manager == null ? null : manager.getDisplay(displayId);
        if (display != null) {
            launchSecondaryActivity(displayId, location);
            return;
        }
        if (remainingAttempts <= 0) {
            persistSelectedDisplayLocation(RpmDisplayLocation.OFF);
            clearSecondaryDisplayRuntime();
            refreshDisplayLocationControls();
            appendLog("副屏已创建，但 Android 尚未注册显示 ID " + displayId);
            return;
        }
        displayLocationGroup.postDelayed(
                () -> waitForProjectionDisplay(displayId, location, remainingAttempts - 1),
                100L);
    }

    private void launchSecondaryActivity(int displayId, RpmDisplayLocation location) {
        try {
            Intent intent = new Intent(this, SecondaryRpmActivity.class);
            intent.putExtra(SecondaryRpmActivity.EXTRA_PROJECTION_DISPLAY_ID, displayId);
            intent.putExtra(SecondaryRpmActivity.EXTRA_DISPLAY_LOCATION,
                    location.persistedValue());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(displayId);
            startActivity(intent, options.toBundle());
            appendLog("转速已选择“" + location.displayName()
                    + "”，displayId=" + displayId);
            refreshDisplayLocationControls();
        } catch (RuntimeException error) {
            persistSelectedDisplayLocation(RpmDisplayLocation.OFF);
            refreshDisplayLocationControls();
            appendLog("副屏 Activity 启动失败：" + error.getMessage());
        }
    }

    private void stopSecondaryDisplay(
            Runnable afterStopped, RpmDisplayLocation locationToRestoreOnError) {
        if (projectionOperation != null) return;
        int displayId = activeSecondaryDisplayId();
        if (displayId < 0) {
            clearSecondaryDisplayRuntime();
            afterStopped.run();
            return;
        }
        setDisplayLocationControlsEnabled(false);
        projectionOperation = EcarxProjectionClient.stopDisplay(this, displayId,
                new EcarxProjectionClient.Callback() {
                    @Override public void onComplete(int ignored) {
                        projectionOperation = null;
                        clearSecondaryDisplayRuntime();
                        afterStopped.run();
                    }

                    @Override public void onError(String message, Throwable error) {
                        projectionOperation = null;
                        persistSelectedDisplayLocation(locationToRestoreOnError);
                        refreshDisplayLocationControls();
                        appendLog(message + (error == null ? "" : "：" + error.getMessage()));
                    }
                });
    }

    private int activeSecondaryDisplayId() {
        int displayId = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt(PREF_DISPLAY_ID, -1);
        if (displayId < 0) return -1;
        DisplayManager manager = getSystemService(DisplayManager.class);
        if (manager != null && manager.getDisplay(displayId) != null) return displayId;
        clearSecondaryDisplayRuntime();
        return -1;
    }

    private RpmDisplayLocation selectedDisplayLocation() {
        String value = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_DISPLAY_LOCATION, RpmDisplayLocation.OFF.persistedValue());
        return RpmDisplayLocation.fromPersistedValue(value);
    }

    private RpmDisplayLocation activeDisplayLocation() {
        String value = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_ACTIVE_DISPLAY_LOCATION,
                        RpmDisplayLocation.OFF.persistedValue());
        return RpmDisplayLocation.fromPersistedValue(value);
    }

    private void persistSelectedDisplayLocation(RpmDisplayLocation location) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_DISPLAY_LOCATION, location.persistedValue()).apply();
    }

    private void clearSecondaryDisplayRuntime() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .remove(PREF_DISPLAY_ID)
                .remove(PREF_ACTIVE_DISPLAY_LOCATION)
                .apply();
    }

    private RpmDisplayLocation locationForCheckedId(int checkedId) {
        if (displayLeftButton != null && checkedId == displayLeftButton.getId()) {
            return RpmDisplayLocation.LEFT_SPEED;
        }
        if (displayRightButton != null && checkedId == displayRightButton.getId()) {
            return RpmDisplayLocation.RIGHT_CARD;
        }
        return RpmDisplayLocation.OFF;
    }

    private int checkedIdForLocation(RpmDisplayLocation location) {
        if (location == RpmDisplayLocation.LEFT_SPEED) return displayLeftButton.getId();
        if (location == RpmDisplayLocation.RIGHT_CARD) return displayRightButton.getId();
        return displayOffButton.getId();
    }

    private void setDisplayLocationControlsEnabled(boolean enabled) {
        if (displayOffButton != null) displayOffButton.setEnabled(enabled);
        if (displayLeftButton != null) displayLeftButton.setEnabled(enabled);
        if (displayRightButton != null) displayRightButton.setEnabled(enabled);
    }

    private void refreshDisplayLocationControls() {
        if (displayLocationGroup == null) return;
        RpmDisplayLocation location = selectedDisplayLocation();
        suppressLocationCallback = true;
        displayLocationGroup.check(checkedIdForLocation(location));
        suppressLocationCallback = false;
        setDisplayLocationControlsEnabled(projectionOperation == null);
    }

    @Override public void onStatus(String status, boolean error) {
        runOnUiThread(() -> setConnectionState(status, error, error ? CORAL : ICE));
    }

    @Override public void onLog(String message) {
        runOnUiThread(() -> appendLog(message));
    }

    @Override public void onRpm(int rpm, int status) {
        runOnUiThread(() -> {
            gaugeView.setRpm(rpm);
            rawView.setText("当前帧\n" + rpm + " RPM  ·  MODE " + status);
        });
    }

    private void appendLog(String message) {
        if (logView == null) return;
        String line = timeFormat.format(new Date()) + "  " + message;
        CharSequence old = logView.getText();
        String combined = old.length() == 0 ? line : old + "\n" + line;
        if (combined.length() > 8000) combined = combined.substring(combined.length() - 6500);
        logView.setText(combined);
        logView.post(() -> {
            int offset = logView.getLineCount() * logView.getLineHeight() - logView.getHeight();
            logView.scrollTo(0, Math.max(offset, 0));
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
