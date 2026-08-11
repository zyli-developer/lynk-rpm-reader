package io.github.zylideveloper.rpmreader;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends Activity implements VehicleRpmClient.Listener {
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
    private boolean firstLaunch = true;
    private boolean activityStarted;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setNavigationBarColor(INK);
        buildUi();
        appendLog("目标信号：EngNSafeEngN / 0x12600596");
        appendLog("等待连接车辆 APVP 数据服务");
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
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(panel, new LinearLayout.LayoutParams(0, -1, 1f));
        shell.addView(root, new FrameLayout.LayoutParams(-1, -1));
        startupOverlay = new StartupOverlayView(this);
        shell.addView(startupOverlay, new FrameLayout.LayoutParams(-1, -1));
        setContentView(shell);
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
