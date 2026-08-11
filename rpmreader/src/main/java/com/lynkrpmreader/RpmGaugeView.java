package com.lynkrpmreader;

import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Tech-forward 0–8000 RPM tachometer with a continuously animated physical needle. */
final class RpmGaugeView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private float displayedRpm;
    private ValueAnimator animator;

    RpmGaugeView(Context context) {
        super(context);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    void setRpm(int value) {
        int target = RpmGaugeModel.clamp(value);
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(displayedRpm, target);
        animator.setDuration(140L);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            displayedRpm = (float) animation.getAnimatedValue();
            invalidate(); // A new frame is drawn for every intermediate RPM value.
        });
        animator.start();
    }

    void runStartupSweep(Runnable completion) {
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(0f, 6500f, 0f);
        animator.setDuration(760L);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            displayedRpm = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (!cancelled) completion.run();
            }
        });
        animator.start();
    }

    void cancelAnimation() {
        if (animator != null) animator.cancel();
        animator = null;
    }

    @Override protected void onDetachedFromWindow() {
        cancelAnimation();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float density = getResources().getDisplayMetrics().density;
        float cx = w * 0.5f;
        float cy = h * 0.53f;
        float radius = Math.min(w * 0.405f, h * 0.43f);
        arc.set(cx - radius, cy - radius, cx + radius, cy + radius);

        drawTechnicalGrid(canvas, w, h, density);
        drawDhtSignature(canvas, w, density);
        drawRings(canvas, cx, cy, radius, density);
        drawTicks(canvas, cx, cy, radius, density);
        drawNeedle(canvas, cx, cy, radius, density);
        drawReadout(canvas, cx, cy, radius, density);
    }

    private void drawDhtSignature(Canvas canvas, float w, float density) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        float left = 13f * density;
        float top = 18f * density;
        float bladeW = 31f * density;
        float bladeH = 8f * density;
        int[] colors = {Color.rgb(0, 226, 255), Color.rgb(86, 113, 255), Color.rgb(245, 61, 185)};
        for (int i = 0; i < 3; i++) {
            float x = left + i * 22f * density;
            Path blade = new Path();
            blade.moveTo(x + 7f * density, top);
            blade.lineTo(x + bladeW, top);
            blade.lineTo(x + bladeW - 7f * density, top + bladeH);
            blade.lineTo(x, top + bladeH);
            blade.close();
            paint.setColor(colors[i]);
            canvas.drawPath(blade, paint);
        }
        paint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(19f * density);
        paint.setColor(Color.rgb(137, 178, 192));
        canvas.drawText("3DHT EVO / TELEMETRY BUS", left, top + 30f * density, paint);

        // Exact animated sample lives independently in the upper-right telemetry position.
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        paint.setTextSize(38f * density);
        paint.setColor(brightZoneColor(RpmGaugeModel.zoneForRpm(displayedRpm)));
        canvas.drawText(RpmGaugeModel.formatExact(displayedRpm), w - 15f * density,
                top + 15f * density, paint);
        paint.setTextSize(18f * density);
        paint.setColor(Color.rgb(103, 147, 160));
        canvas.drawText("RAW ENGINE SPEED", w - 15f * density,
                top + 38f * density, paint);
    }

    private void drawTechnicalGrid(Canvas canvas, float w, float h, float density) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(Color.argb(28, 42, 180, 202));
        float gap = 34f * density;
        for (float x = 0; x < w; x += gap) canvas.drawLine(x, 0, x, h, paint);
        for (float y = 0; y < h; y += gap) canvas.drawLine(0, y, w, y, paint);

        paint.setColor(Color.argb(95, 0, 225, 255));
        paint.setStrokeWidth(2f * density);
        float corner = 24f * density;
        float inset = 9f * density;
        canvas.drawLine(inset, inset, inset + corner, inset, paint);
        canvas.drawLine(inset, inset, inset, inset + corner, paint);
        canvas.drawLine(w - inset, h - inset, w - inset - corner, h - inset, paint);
        canvas.drawLine(w - inset, h - inset, w - inset, h - inset - corner, paint);
    }

    private void drawRings(Canvas canvas, float cx, float cy, float radius, float density) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeWidth(16f * density);
        paint.setColor(Color.rgb(15, 41, 53));
        canvas.drawArc(arc, RpmGaugeModel.START_ANGLE, RpmGaugeModel.SWEEP_ANGLE, false, paint);

        // Deliberate segmented telemetry band rather than a generic continuous progress ring.
        float segmentSweep = RpmGaugeModel.SWEEP_ANGLE / 64f;
        float active = RpmGaugeModel.sweepForRpm(displayedRpm);
        paint.setStrokeWidth(11f * density);
        for (int i = 0; i < 64; i++) {
            float start = RpmGaugeModel.START_ANGLE + i * segmentSweep + 0.7f;
            float midpoint = (i + 0.5f) * segmentSweep;
            float midpointRpm = (i + 0.5f) * RpmGaugeModel.MAX_RPM / 64f;
            int zoneColor = brightZoneColor(RpmGaugeModel.zoneForRpm(midpointRpm));
            if (midpoint <= active) {
                paint.setColor(zoneColor);
            } else {
                paint.setColor(dimZoneColor(RpmGaugeModel.zoneForRpm(midpointRpm)));
            }
            canvas.drawArc(arc, start, segmentSweep - 1.4f, false, paint);
        }

        RectF inner = new RectF(arc);
        inner.inset(27f * density, 27f * density);
        paint.setStrokeWidth(1f * density);
        paint.setColor(Color.argb(115, 40, 154, 178));
        canvas.drawArc(inner, RpmGaugeModel.START_ANGLE, RpmGaugeModel.SWEEP_ANGLE, false, paint);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    private void drawTicks(Canvas canvas, float cx, float cy, float radius, float density) {
        paint.setShader(null);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        for (int i = 0; i <= 40; i++) {
            boolean major = i % 5 == 0;
            int rpm = i * 200;
            float angle = RpmGaugeModel.angleForRpm(rpm);
            double rad = Math.toRadians(angle);
            float outer = radius - 25f * density;
            float inner = outer - (major ? 20f : 9f) * density;
            RpmGaugeModel.Zone zone = RpmGaugeModel.zoneForRpm(rpm);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth((major ? 2.2f : 1f) * density);
            paint.setColor(major ? brightZoneColor(zone) : dimZoneColor(zone));
            canvas.drawLine(cx + (float) Math.cos(rad) * inner,
                    cy + (float) Math.sin(rad) * inner,
                    cx + (float) Math.cos(rad) * outer,
                    cy + (float) Math.sin(rad) * outer, paint);
            if (major) {
                float labelRadius = inner - 30f * density;
                paint.setStyle(Paint.Style.FILL);
                paint.setTextSize(25f * density);
                canvas.drawText(String.valueOf(rpm / 1000),
                        cx + (float) Math.cos(rad) * labelRadius,
                        cy + (float) Math.sin(rad) * labelRadius + 8f * density, paint);
            }
        }

        float peakAngle = RpmGaugeModel.angleForRpm(RpmGaugeModel.POWER_PEAK_RPM);
        double peakRad = Math.toRadians(peakAngle);
        paint.setTextSize(18f * density);
        paint.setColor(Color.rgb(255, 112, 133));
        canvas.drawText("POWER 5500",
                cx + (float) Math.cos(peakRad) * (radius + 18f * density),
                cy + (float) Math.sin(peakRad) * (radius + 18f * density), paint);
    }

    private static int brightZoneColor(RpmGaugeModel.Zone zone) {
        switch (zone) {
            case LOW: return Color.rgb(48, 137, 255);
            case TORQUE: return Color.rgb(0, 226, 255);
            case POWER: return Color.rgb(255, 185, 69);
            default: return Color.rgb(255, 70, 101);
        }
    }

    private static int dimZoneColor(RpmGaugeModel.Zone zone) {
        switch (zone) {
            case LOW: return Color.rgb(24, 57, 94);
            case TORQUE: return Color.rgb(24, 70, 79);
            case POWER: return Color.rgb(81, 64, 32);
            default: return Color.rgb(91, 36, 52);
        }
    }

    private void drawNeedle(Canvas canvas, float cx, float cy, float radius, float density) {
        float angle = RpmGaugeModel.angleForRpm(displayedRpm);
        double rad = Math.toRadians(angle);
        float dx = (float) Math.cos(rad);
        float dy = (float) Math.sin(rad);
        float nx = -dy;
        float ny = dx;
        float tip = radius - 52f * density;
        float tail = 22f * density;
        Path needle = new Path();
        needle.moveTo(cx + nx * 4f * density - dx * tail, cy + ny * 4f * density - dy * tail);
        needle.lineTo(cx + dx * tip, cy + dy * tip);
        needle.lineTo(cx - nx * 4f * density - dx * tail, cy - ny * 4f * density - dy * tail);
        needle.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(cx - dx * tail, cy - dy * tail,
                cx + dx * tip, cy + dy * tip,
                Color.rgb(90, 245, 255), Color.WHITE, Shader.TileMode.CLAMP));
        canvas.drawPath(needle, paint);
        paint.setShader(null);
        paint.setColor(Color.rgb(0, 226, 255));
        canvas.drawCircle(cx, cy, 13f * density, paint);
        paint.setColor(Color.rgb(7, 19, 27));
        canvas.drawCircle(cx, cy, 7f * density, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f * density);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(cx, cy, 10f * density, paint);
    }

    private void drawReadout(Canvas canvas, float cx, float cy, float radius, float density) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        paint.setTextSize(Math.min(112f * density, radius * 0.38f));
        paint.setColor(Color.WHITE);
        canvas.drawText(RpmGaugeModel.formatThousands(displayedRpm),
                cx, cy + radius * 0.51f, paint);
        paint.setTextSize(20f * density);
        paint.setColor(brightZoneColor(RpmGaugeModel.zoneForRpm(displayedRpm)));
        canvas.drawText("× 1000  RPM", cx, cy + radius * 0.63f, paint);

        // One sharp asymmetric underline adds youthful tension without enclosing the value.
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeWidth(3f * density);
        float y = cy + radius * 0.69f;
        paint.setColor(Color.rgb(0, 226, 255));
        canvas.drawLine(cx - radius * 0.31f, y, cx + radius * 0.12f, y, paint);
        paint.setColor(Color.rgb(245, 61, 185));
        canvas.drawLine(cx + radius * 0.14f, y, cx + radius * 0.31f, y, paint);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }
}
