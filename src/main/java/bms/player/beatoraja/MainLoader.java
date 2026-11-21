package bms.player.beatoraja;

/**
 * Compatibility entry point for prebuilt IR plugins that reference the launcher diagnostics API.
 *
 * <p>Plugins are distributed as compiled archives; this small façade keeps the referenced diagnostic
 * methods available without restoring the removed launcher.</p>
 */
public final class MainLoader {

    private MainLoader() {
    }

    public static int getIllegalSongCount() {
        return GameContext.getIllegalSongCount();
    }
}
