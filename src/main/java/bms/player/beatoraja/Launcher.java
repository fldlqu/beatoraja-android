package bms.player.beatoraja;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import android.os.Bundle;
import android.util.Log;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;

import bms.player.beatoraja.input.PollingInput;
import bms.player.beatoraja.song.SongDatabaseAccessor;

/**
 * Application entry point: prepares the game data directory, extracts the bundled game data on first
 * run and boots the game core straight into the music-select state.
 */
public class Launcher extends AndroidApplication {

    public static final String TAG = "beatoraja";

    /** Set by the start screen: incrementally refresh the song database after creating the game. */
    public static final String EXTRA_SCAN = "scan";
    /** Set by the start screen: delete songdata.db before opening it, then scan all roots. */
    public static final String EXTRA_REBUILD = "rebuild";

    private MainController main;
    private PollingInput pollingInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Path dataRoot = resolveDataRoot();
        AppPaths.setRoot(dataRoot);
        Logging.install(dataRoot.resolve("logs"));
        DisplayInfo.attach(this);
        Log.i(TAG, "Launcher.onCreate root=" + dataRoot);
        AssetExtractor.extractIfNeeded(this, dataRoot);
        DebugBridge.install(this, dataRoot);

        final boolean rebuild = getIntent() != null && getIntent().getBooleanExtra(EXTRA_REBUILD, false);
        final boolean scan = getIntent() != null && getIntent().getBooleanExtra(EXTRA_SCAN, false);
        Logger.getGlobal().info("launcher request: scan=" + scan + ", rebuild=" + rebuild);
        if (rebuild) {
            for (String name : new String[] {"songdata.db", "songdata.db-journal", "songdata.db-wal"}) {
                try {
                    Files.deleteIfExists(dataRoot.resolve(name));
                } catch (Exception e) {
                    Logger.getGlobal().warning("cannot remove song database: " + e.getMessage());
                }
            }
        }
        final boolean firstRun = !Files.isRegularFile(dataRoot.resolve("config_sys.json"));
        final boolean songDatabaseMissing = !Files.isRegularFile(dataRoot.resolve("songdata.db"));
        final Config config = GameBootstrap.loadConfig();
        Defaults.apply(config, firstRun);
        final PlayerConfig player = GameBootstrap.loadPlayerConfig(config);
        if (firstRun) {
            // Persist defaults immediately: the game only writes config.json on a clean exit.
            Config.write(config);
            PlayerConfig.write(config.getPlayerpath(), player);
        }
        final SongDatabaseAccessor songdb;
        try {
            songdb = GameBootstrap.openSongDatabase(config, player);
        } catch (RuntimeException e) {
            Logger.getGlobal().severe("song database startup failed: " + e);
            throw e;
        }
        // Optional direct-play launch: adb shell am start -n pkg/.Launcher --es bms /path/to/song.bms --ez autoplay true
        Path bmsFile = null;
        BMSPlayerMode auto = BMSPlayerMode.PLAY;
        String requestedBms = getIntent() == null ? null : getIntent().getStringExtra("bms");
        if (requestedBms != null && !requestedBms.isBlank()) {
            bmsFile = Path.of(requestedBms);
            auto = getIntent().getBooleanExtra("autoplay", false) ? BMSPlayerMode.AUTOPLAY : BMSPlayerMode.PLAY;
        }
        main = GameBootstrap.createMainController(bmsFile, bmsFile == null ? BMSPlayerMode.PLAY : auto, config, player,
                false, songdb);
        final MainController controller = main;
        DebugBridge.setMainController(controller);
        final boolean scanSongs = firstRun || songDatabaseMissing || scan || rebuild;
        final Runnable scanAction = () -> startSongScan(controller, config);
        DebugBridge.setScanSongsAction(scanAction);
        final ApplicationListener game = GameBootstrap.createGameListener(main);

        AndroidApplicationConfiguration cfg = new AndroidApplicationConfiguration();
        cfg.useImmersiveMode = true;
        cfg.useAccelerometer = false;
        cfg.useCompass = false;
        cfg.useGyroscope = false;
        cfg.numSamples = 0;
        cfg.useGL30 = false;

        initialize(new ApplicationListener() {
            @Override
            public void create() {
                // Gdx.input exists now but MainController.create() is about to run; install first so the
                // input processor sees every event from the first frame.
                pollingInput = PollingInput.install(Launcher.this);
                game.create();
                if (scanSongs) scanAction.run();
            }

            @Override
            public void resize(int width, int height) {
                game.resize(width, height);
            }

            @Override
            public void render() {
                DebugBridge.process();
                game.render();
            }

            @Override
            public void pause() {
                game.pause();
            }

            @Override
            public void resume() {
                game.resume();
            }

            @Override
            public void dispose() {
                game.dispose();
            }
        }, cfg);
    }

    /** Scans the configured BMS roots on a worker thread and refreshes the music-select bar list. */
    private static void startSongScan(MainController controller, Config config) {
        new Thread(() -> {
            try {
                Logger.getGlobal().info("scanning song database: " + java.util.Arrays.toString(config.getBmsroot()));
                controller.getSongDatabase().updateSongDatas(null, config.getBmsroot(), false,
                        controller.getInfoDatabase());
                if (controller.getMusicSelector() != null) {
                    controller.getMusicSelector().getBarManager().updateBar();
                }
                Logger.getGlobal().info("song database scan finished");
            } catch (RuntimeException e) {
                Logger.getGlobal().severe("song scan failed: " + e);
            }
        }, "song-scan").start();
    }

    private Path resolveDataRoot() {
        File external = getExternalFilesDir(null);
        File base = external != null ? external : getFilesDir();
        return new File(base, "beatoraja").toPath();
    }

    @Override
    protected void onPause() {
        if (pollingInput != null) pollingInput.setWindowActive(false);
        if (main != null) {
            try {
                main.saveConfig();
            } catch (RuntimeException e) {
                Logger.getGlobal().warning("config save on pause failed: " + e);
            }
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pollingInput != null) pollingInput.setWindowActive(true);
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "Launcher.onDestroy");
        super.onDestroy();
    }
}
