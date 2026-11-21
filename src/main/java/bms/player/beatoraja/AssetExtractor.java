package bms.player.beatoraja;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

/**
 * Extracts the game data bundled in {@code assets/game} into the writable game data root.
 *
 * <p>The game core uses {@code java.nio.file} APIs, while packaged assets are only readable through
 * the asset manager, so first run copies the bundled skin/font/sounds to real files. A version marker
 * avoids copying on every launch.</p>
 */
public final class AssetExtractor {

    private static final String TAG = "beatoraja";
    private static final String ASSET_ROOT = "game";
    private static final String MARKER = ".assets-version";
    /** Bump when the packaged game data changes to force re-extraction on devices. */
    private static final String VERSION = "1";

    private AssetExtractor() {
    }

    public static void extractIfNeeded(Context context, Path root) {
        syncIrPlugins(context, root);
        final Path marker = root.resolve(MARKER);
        try {
            ensureCompatibilityFiles(root);
            if (Files.isRegularFile(marker) && VERSION.equals(new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim())) {
                return;
            }
            Files.createDirectories(root);
            long started = System.nanoTime();
            copyDirectory(context.getAssets(), ASSET_ROOT, root.toFile());
            ensureCompatibilityFiles(root);
            Files.write(marker, VERSION.getBytes(StandardCharsets.UTF_8));
            Log.i(TAG, "game data extracted in " + ((System.nanoTime() - started) / 1_000_000) + " ms to " + root);
        } catch (IOException | RuntimeException e) {
            Logger.getGlobal().severe("game data extraction failed: " + e);
            Log.e(TAG, "game data extraction failed for root " + root, e);
        }
    }

    /**
     * Copies the DEXed IR plugin archives into the data directory on every start.
     *
     * <p>The set is small and may change between builds, so it is replaced instead of versioned.</p>
     */
    private static void syncIrPlugins(Context context, Path root) {
        final Path target = root.resolve("ir");
        try {
            Files.createDirectories(target);
            try (var existing = Files.list(target)) {
                existing.filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException ignored) {
                            }
                        });
            }
            final String[] entries = context.getAssets().list("ir");
            if (entries == null) return;
            for (String entry : entries) {
                if (!entry.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) continue;
                final File plugin = new File(target.toFile(), entry);
                copyFile(context.getAssets(), "ir/" + entry, plugin);
                makeReadOnly(plugin);
            }
            if (entries.length > 0) {
                Log.i(TAG, "IR plugins installed: " + entries.length);
            }
        } catch (IOException | RuntimeException e) {
            Logger.getGlobal().warning("IR plugin extraction failed: " + e);
        }
    }

    /** The default skin references its font relative to {@code skin/default}. */
    private static void ensureCompatibilityFiles(Path root) {
        final Path source = root.resolve("font/VL-Gothic-Regular.ttf");
        final Path skinTarget = root.resolve("skin/default/VL-Gothic-Regular.ttf");
        try {
            if (Files.isRegularFile(source) && !Files.isRegularFile(skinTarget)) {
                Files.createDirectories(skinTarget.getParent());
                Files.copy(source, skinTarget);
                Log.i(TAG, "restored default skin font: " + skinTarget);
            }
        } catch (IOException e) {
            Log.w(TAG, "could not restore default skin font", e);
        }
    }

    /** DEX archives must not be writable when they are loaded. */
    private static void makeReadOnly(File file) {
        try {
            java.nio.file.Files.setPosixFilePermissions(file.toPath(),
                    java.nio.file.attribute.PosixFilePermissions.fromString("r--r--r--"));
        } catch (IOException | UnsupportedOperationException e) {
            file.setReadOnly();
        }
    }

    private static void copyDirectory(AssetManager assets, String assetPath, File target) throws IOException {
        String[] children = assets.list(assetPath);
        if (children == null || children.length == 0) {
            copyFile(assets, assetPath, target);
            return;
        }
        if (!target.isDirectory() && !target.mkdirs() && !target.isDirectory()) {
            throw new IOException("cannot create directory " + target);
        }
        for (String child : children) {
            copyDirectory(assets, assetPath + "/" + child, new File(target, child));
        }
    }

    private static void copyFile(AssetManager assets, String assetPath, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create directory " + parent);
        }
        try (InputStream in = assets.open(assetPath); FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }
}
