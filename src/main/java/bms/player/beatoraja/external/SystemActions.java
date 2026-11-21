package bms.player.beatoraja.external;

import java.net.URI;
import java.nio.file.Path;
import java.util.logging.Logger;

import com.badlogic.gdx.Gdx;

/**
 * Clipboard, browser and file actions triggered by the game UI.
 *
 * <p>Text clipboard and browser launching use the libGDX application services. Opening files or
 * folders and copying images are not available without a content provider based intent.</p>
 */
public final class SystemActions {

    private SystemActions() {
    }

    /** Copies plain text to the system clipboard. Must run on the game thread. */
    public static boolean copyText(String text) {
        return attempt("Clipboard text copy", () -> {
            if (Gdx.app == null || text == null) throw new IllegalStateException("Clipboard unavailable");
            Gdx.app.getClipboard().setContents(text);
        });
    }

    /** Opens an HTTP(S) URL in the system browser. */
    public static boolean openUri(String uri) {
        return attempt("Browser launch", () -> {
            if (uri == null) throw new IllegalArgumentException("Missing URL");
            URI target = URI.create(uri);
            String scheme = target.getScheme();
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("Only HTTP(S) browser URLs are supported");
            }
            if (Gdx.net == null || !Gdx.net.openURI(target.toString())) {
                throw new IllegalStateException("Browser unavailable or URI rejected");
            }
        });
    }

    /** Opens a file or folder with the system file manager. */
    public static boolean openPath(Path path) {
        // TODO(unimplemented): opening files/folders requires a content-provider based intent; not implemented.
        return false;
    }

    /** Copies a saved PNG to the system clipboard. Failure does not invalidate the saved screenshot. */
    public static boolean copyImage(Path path) {
        // TODO(unimplemented): opening files/folders requires a content-provider based intent; not implemented.
        return false;
    }

    private static boolean attempt(String action, CheckedAction task) {
        try {
            task.run();
            return true;
        } catch (Exception e) {
            Logger.getGlobal().warning(action + " failed: " + e);
        }
        return false;
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }
}
