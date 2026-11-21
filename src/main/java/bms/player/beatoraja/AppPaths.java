package bms.player.beatoraja;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

/**
 * Resolution of game data paths.
 *
 * <p>All relative paths are resolved against the game data directory, which is configured once during
 * startup. The process working directory is not used because it is not writable on the target device.</p>
 */
public final class AppPaths {

    private static volatile Path root;

    private AppPaths() {
    }

    /** Configures the game data directory. Must be called before any other method. */
    public static void setRoot(Path path) {
        root = path.toAbsolutePath().normalize();
    }

    /** Absolute, normalized game data root. */
    public static Path root() {
        final Path current = root;
        if (current == null) {
            throw new IllegalStateException("game data directory is not configured");
        }
        return current;
    }

    /** Absolute, normalized game data root (no "." segments); used for folder CRC prefix stripping. */
    public static Path normalizedRoot() {
        return path(".").toAbsolutePath().normalize();
    }

    public static Path path(String first, String... more) {
        Path result = root().resolve(first);
        if (more != null) {
            for (String part : more) {
                result = result.resolve(part);
            }
        }
        return result.toAbsolutePath().normalize();
    }

    public static Path path(URI uri) {
        return new File(uri).toPath();
    }

    public static Path path(Path path) {
        return path == null ? null : path.isAbsolute() ? path : root().resolve(path);
    }

    public static File file(String first, String... more) {
        return path(first, more).toFile();
    }

    /** Opens a packaged asset (shaders and other files bundled in the APK). */
    public static FileHandle resource(String path) {
        return Gdx.files.internal(path);
    }

    /** Opens a game data file as an absolute handle. */
    public static FileHandle handle(String first, String... more) {
        return Gdx.files.absolute(path(first, more).toString());
    }
}
