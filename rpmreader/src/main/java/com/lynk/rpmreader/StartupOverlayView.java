package com.lynk.rpmreader;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** One-shot branded launch overlay using the generated tachometer artwork. */
final class StartupOverlayView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Bitmap icon;
    private float progress;
    private ValueAnimator animator;

    StartupOverlayView(Context context) {
        super(context);
        icon = BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher_foreground_art);
        setBackgroundColor(Color.rgb(4, 12, 18));
    }

    void start(Runnable completion) {
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(820L);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(value -> {
            progress = (float) value.getAnimatedValue();
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

    void cancel() {
        if (animator != null) animator.cancel();
        animator = null;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float alpha = StartupMotion.contentAlpha(progress);
        float cx = getWidth() * 0.5f;
        float cy = getHeight() * 0.43f;
        float size = Math.min(210f * density, getHeight() * 0.48f) * StartupMotion.iconScale(progress);

        paint.setAlpha(Math.round(alpha * 255f));
        RectF target = new RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f);
        canvas.drawBitmap(icon, null, target, paint);
        paint.setAlpha(255);

        float bladeY = cy + size * 0.52f;
        int[] colors = {Color.rgb(0, 226, 255), Color.rgb(86, 113, 255), Color.rgb(245, 61, 185)};
        for (int i = 0; i < 3; i++) {
            float blade = StartupMotion.bladeProgress(progress, i);
            float width = 66f * density * blade;
            float x = cx - 106f * density + i * 73f * density;
            Path path = new Path();
            path.moveTo(x + 8f * density, bladeY);
            path.lineTo(x + width, bladeY);
            path.lineTo(x + width - 8f * density, bladeY + 7f * density);
            path.lineTo(x, bladeY + 7f * density);
            path.close();
            paint.setColor(colors[i]);
            paint.setAlpha(Math.round(alpha * 255f));
            canvas.drawPath(path, paint);
        }

        paint.setAlpha(Math.round(alpha * 255f));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        paint.setTextSize(30f * density);
        paint.setColor(Color.WHITE);
        canvas.drawText("ENGINE TELEMETRY", cx, bladeY + 48f * density, paint);
        paint.setTextSize(15f * density);
        paint.setLetterSpacing(0.22f);
        paint.setColor(Color.rgb(91, 204, 219));
        canvas.drawText("APVP  /  3DHT EVO  /  SYSTEM READY", cx, bladeY + 74f * density, paint);
        paint.setLetterSpacing(0f);
        paint.setAlpha(255);

        float scanX = getWidth() * progress;
        paint.setColor(Color.argb(Math.round(alpha * 150f), 0, 226, 255));
        paint.setStrokeWidth(1f * density);
        canvas.drawLine(scanX, 0, scanX, getHeight(), paint);
    }
}
