package com.lynk.rpmreader;

/** Narrow shell bridge: it can only locate the OEM HUD and launch this app's HUD activity. */
interface IHudShellService {
    void destroy() = 16777114;

    int findHudDisplay() = 1;

    String startHudActivity(int displayId, int userId) = 2;
}
