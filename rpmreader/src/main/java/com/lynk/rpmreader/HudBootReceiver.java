package com.lynk.rpmreader;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Schedules a delayed HUD restore after boot or an in-place APK update. */
public final class HudBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        if (HudBootJobService.shouldRestore(context)) {
            HudBootJobService.schedule(context);
        }
    }
}
