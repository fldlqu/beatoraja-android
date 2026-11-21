package bms.player.beatoraja.settings;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

import bms.player.beatoraja.AppPaths;
import bms.player.beatoraja.Config;
import bms.player.beatoraja.Defaults;
import bms.player.beatoraja.PlayerConfig;

/**
 * Reads and writes the game configuration from the start screen without initialising libGDX.
 *
 * <p>The game's own config classes are plain Java and are reused so the on-disk schema stays
 * identical; only paths and the player directory need to be prepared first.</p>
 */
final class SettingsStore {

    private static final Logger LOG = Logger.getGlobal();

    private final Context context;
    private final Path dataRoot;

    SettingsStore(Context context) {
        this.context = context.getApplicationContext();
        final File external = context.getExternalFilesDir(null);
        final File base = external != null ? external : context.getFilesDir();
        this.dataRoot = new File(base, "beatoraja").toPath();
    }

    Path dataRoot() {
        return dataRoot;
    }

    static void prepare(Context context) {
        final File external = context.getExternalFilesDir(null);
        final File base = external != null ? external : context.getFilesDir();
        AppPaths.setRoot(new File(base, "beatoraja").toPath());
    }

    /** Loads the system config, creating Android-tuned defaults on the very first start. */
    Config loadConfig() {
        prepare(context);
        final boolean firstStart = !Files.isRegularFile(dataRoot.resolve("config_sys.json"));
        final Config config = Config.read();
        if (firstStart) {
            Defaults.apply(config, true);
            Config.write(config);
        }
        return config;
    }

    void saveConfig(Config config) {
        prepare(context);
        Config.write(config);
    }

    PlayerConfig loadPlayer(Config config) {
        prepare(context);
        PlayerConfig player = PlayerConfig.readPlayerConfig(config.getPlayerpath(), config.getPlayername());
        if (player == null) {
            PlayerConfig.create(config.getPlayerpath(), config.getPlayername());
            player = PlayerConfig.readPlayerConfig(config.getPlayerpath(), config.getPlayername());
        }
        return player;
    }

    void savePlayer(Config config, PlayerConfig player) {
        prepare(context);
        PlayerConfig.write(config.getPlayerpath(), player);
    }

    String[] playerIds(Config config) {
        prepare(context);
        return PlayerConfig.readAllPlayerID(config.getPlayerpath());
    }

    int songCount() {
        final Path database = dataRoot.resolve("songdata.db");
        if (!Files.isRegularFile(database)) return 0;
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(database.toString(), null,
                SQLiteDatabase.OPEN_READONLY)) {
            try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM song", null)) {
                return cursor.moveToFirst() ? cursor.getInt(0) : 0;
            }
        } catch (RuntimeException e) {
            LOG.warning("song database cannot be read: " + e.getMessage());
            return 0;
        }
    }

    /** Deletes the song database so the next start scans every configured root from scratch. */
    void rebuildSongDatabase() {
        prepare(context);
        for (String name : new String[] {"songdata.db", "songdata.db-journal", "songdata.db-wal"}) {
            try {
                Files.deleteIfExists(dataRoot.resolve(name));
            } catch (Exception ignored) {
            }
        }
    }

    /** IR plugins discovered during the last game run (written by IRConnectionManager). */
    List<IrPlugin> irPlugins() {
        prepare(context);
        final List<IrPlugin> plugins = new ArrayList<>();
        final Path file = dataRoot.resolve("ir/plugins.json");
        if (Files.isRegularFile(file)) {
            try {
                final JSONObject root = new JSONObject(new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8));
                final JSONArray array = root.optJSONArray("plugins");
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        final JSONObject entry = array.optJSONObject(i);
                        if (entry == null) continue;
                        plugins.add(new IrPlugin(entry.optString("name"), entry.optString("home"),
                                entry.optString("file")));
                    }
                }
            } catch (Exception e) {
                LOG.warning("IR plugin list cannot be read: " + e.getMessage());
            }
        }
        return plugins;
    }

    void installBundledIrPlugins() {
        prepare(context);
        try {
            final Path target = dataRoot.resolve("ir");
            Files.createDirectories(target);
            final String[] entries = context.getAssets().list("ir");
            if (entries == null) return;
            for (String entry : entries) {
                if (!entry.endsWith(".jar")) continue;
                final Path file = target.resolve(entry);
                // Existing files are kept read-only; replace when the packaged plugin changed.
                try (var in = context.getAssets().open("ir/" + entry)) {
                    Files.deleteIfExists(file);
                    Files.copy(in, file);
                }
                try {
                    Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("r--r--r--"));
                } catch (Exception ignored) {
                    file.toFile().setReadOnly();
                }
            }
        } catch (Exception e) {
            LOG.warning("IR plugin installation failed: " + e.getMessage());
        }
    }

    record IrPlugin(String name, String home, String file) {
    }

    /** Copy a text value to the system clipboard. */
    void copyToClipboard(String label, String value) {
        final android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, value));
    }

    /** Forces the IR plugin metadata to be refreshed on the next game start. */
    void invalidateIrPluginCache() {
        try {
            Files.deleteIfExists(dataRoot.resolve("ir/plugins.json"));
        } catch (Exception ignored) {
        }
    }
}
