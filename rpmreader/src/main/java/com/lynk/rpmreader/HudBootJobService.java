package com.lynk.rpmreader;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** Restores the selected HUD surface after Android has finished booting. */
public final class HudBootJobService extends JobService {
    private static final String TAG = "LynkRpmHudBoot";
    private static final int JOB_ID = 0x4c52504d;
    private static final long BOOT_DELAY_MS = 30_000L;
    private static final long BOOT_DEADLINE_MS = 90_000L;
    private static final long HUD_RECREATE_DELAY_MS = 1_000L;
    private static final long HUD_RECREATE_DEADLINE_MS = 10_000L;
    private static final long RETRY_BACKOFF_MS = 30_000L;
    private static final long HUD_DISCOVERY_RETRY_MS = 5_000L;

    static final String PREFS = "rpm_secondary_display";
    static final String PREF_DISPLAY_ID = "display_id";
    static final String PREF_DISPLAY_LOCATION = "display_location";
    static final String PREF_ACTIVE_DISPLAY_LOCATION = "active_display_location";
    static final String PREF_HUD_AUTOSTART_READY = "hud_autostart_ready";

    private final Handler retryHandler = new Handler(Looper.getMainLooper());
    private volatile ShizukuHudController controller;
    private JobParameters activeParams;

    private final Runnable retryRunnable = () -> {
        JobParameters params = activeParams;
        if (params != null) attemptRestore(params);
    };

    static boolean shouldRestore(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        RpmDisplayLocation location = RpmDisplayLocation.fromPersistedValue(
                preferences.getString(PREF_DISPLAY_LOCATION,
                        RpmDisplayLocation.OFF.persistedValue()));
        return HudAutostartPolicy.shouldSchedule(location,
                preferences.getBoolean(PREF_HUD_AUTOSTART_READY, false));
    }

    static void markAutostartReady(Context context) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_HUD_AUTOSTART_READY, true)
                .apply();
    }

    static void markHudSurfaceInactive(Context context) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .remove(PREF_DISPLAY_ID)
                .remove(PREF_ACTIVE_DISPLAY_LOCATION)
                .apply();
    }

    static void schedule(Context context) {
        schedule(context, BOOT_DELAY_MS, BOOT_DEADLINE_MS, "boot");
    }

    static void scheduleAfterHudDisplayLoss(Context context) {
        schedule(context, HUD_RECREATE_DELAY_MS, HUD_RECREATE_DEADLINE_MS,
                "HUD display loss");
    }

    private static void schedule(
            Context context, long minimumLatencyMs, long deadlineMs, String reason) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        if (scheduler.getPendingJob(JOB_ID) != null) {
            Log.i(TAG, "HUD restore already scheduled; trigger=" + reason);
            return;
        }
        JobInfo job = new JobInfo.Builder(JOB_ID,
                new ComponentName(context, HudBootJobService.class))
                .setMinimumLatency(minimumLatencyMs)
                .setOverrideDeadline(deadlineMs)
                .setBackoffCriteria(RETRY_BACKOFF_MS, JobInfo.BACKOFF_POLICY_LINEAR)
                .build();
        int result = scheduler.schedule(job);
        Log.i(TAG, "HUD restore scheduled: result=" + result + ", trigger=" + reason);
    }

    @Override public boolean onStartJob(JobParameters params) {
        if (!shouldRestore(this)) return false;
        activeParams = params;
        attemptRestore(params);
        return true;
    }

    private void attemptRestore(JobParameters params) {
        if (activeParams != params) return;
        if (!shouldRestore(this)) {
            finish(params, false);
            return;
        }

        closeController();
        // A background boot job must never present a permission prompt. The foreground
        // HUD launch marks this path ready only after Shizuku permission already exists.
        controller = new ShizukuHudController(this, false);
        controller.startHud(new ShizukuHudController.Callback() {
            @Override public void onStarted(
                    int displayId, int privilegeUid, String commandOutput) {
                if (activeParams != params) return;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putInt(PREF_DISPLAY_ID, displayId)
                        .putString(PREF_ACTIVE_DISPLAY_LOCATION,
                                RpmDisplayLocation.HUD_LEFT.persistedValue())
                        .apply();
                Log.i(TAG, "HUD restored after boot on display " + displayId
                        + " with Shizuku UID " + privilegeUid);
                finish(params);
            }

            @Override public void onError(String message, Throwable error) {
                if (activeParams != params) return;
                boolean retry = shouldRestore(HudBootJobService.this);
                Log.e(TAG, "HUD boot restore failed; retry=" + retry
                        + ", reason=" + message, error);
                closeController();
                if (retry) {
                    // Flyme Auto creates its private 520x280 HUD display only after the
                    // vehicle enters D/N. Keep this job alive and poll at a fixed cadence;
                    // JobScheduler's linear backoff otherwise grows to several minutes
                    // while the vehicle is still in P.
                    retryHandler.removeCallbacks(retryRunnable);
                    retryHandler.postDelayed(retryRunnable, HUD_DISCOVERY_RETRY_MS);
                    Log.i(TAG, "HUD discovery retry scheduled in "
                            + HUD_DISCOVERY_RETRY_MS + " ms");
                } else {
                    finish(params, false);
                }
            }
        });
    }

    private void finish(JobParameters params) {
        finish(params, false);
    }

    private void finish(JobParameters params, boolean reschedule) {
        if (activeParams != params) return;
        activeParams = null;
        retryHandler.removeCallbacks(retryRunnable);
        closeController();
        jobFinished(params, reschedule);
    }

    @Override public boolean onStopJob(JobParameters params) {
        activeParams = null;
        retryHandler.removeCallbacks(retryRunnable);
        closeController();
        return shouldRestore(this);
    }

    private void closeController() {
        ShizukuHudController active = controller;
        controller = null;
        if (active != null) active.close();
    }
}
