package bms.player.beatoraja;

import android.content.Context;
import android.view.Display;
import android.view.WindowManager;

import java.util.logging.Logger;

/**
 * Display properties of the current device.
 */
public final class DisplayInfo {

    private static volatile Display display;

    private DisplayInfo() {
    }

    static void attach(Context context) {
        if (context == null) return;
        final WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        display = manager != null ? manager.getDefaultDisplay() : null;
    }

    /** Display refresh rate in Hz, or 60 when it cannot be queried. */
    public static int refreshRate() {
        final Display current = display;
        if (current == null) return 60;
        try {
            final float rate = current.getRefreshRate();
            if (rate > 1f) return Math.round(rate);
        } catch (RuntimeException e) {
            Logger.getGlobal().warning("display refresh rate is unavailable: " + e.getMessage());
        }
        return 60;
    }
}
