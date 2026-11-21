package bms.player.beatoraja;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Logger;

import com.badlogic.gdx.ApplicationListener;


import bms.player.beatoraja.ir.IRConnection;
import bms.player.beatoraja.ir.IRConnectionManager;
import bms.player.beatoraja.ir.IRResponse;
import bms.player.beatoraja.song.SongData;
import bms.player.beatoraja.song.SongDatabaseAccessor;
import bms.player.beatoraja.song.SongUtils;

/**
 * Game bootstrap shared by the application entry point.
 */
public final class GameBootstrap {

    private GameBootstrap() {
    }

    public static Config loadConfig() {
        return Config.read();
    }

    public static PlayerConfig loadPlayerConfig(Config config) {
        return PlayerConfig.readPlayerConfig(config.getPlayerpath(), config.getPlayername());
    }

    /**
     * Opens the song database and records illegal songs detected through IR. Returns null on failure.
     */
    public static SongDatabaseAccessor openSongDatabase(Config config, PlayerConfig player) {
        try {
            SongDatabaseAccessor songdb = new SongDatabaseAccessorProvider().get(config, player.getId());
            try (var timing = StartupDiagnostics.measure(StartupDiagnostics.Step.IR_CHART_CHECK)) {
                timing.outcome(detectIllegalSongs(songdb, player));
            }
            return songdb;
        } catch (RuntimeException e) {
            Logger.getGlobal().severe("楽曲データベース初期化中の例外:" + e.getMessage());
            return null;
        }
    }

    public static MainController createMainController(Path bmsFile, BMSPlayerMode auto, Config config,
            PlayerConfig player, boolean songUpdated, SongDatabaseAccessor songdb) {
        return new MainController(bmsFile, config, player, auto, songUpdated, songdb);
    }

    /** Adapts the game controller to the libGDX application lifecycle. */
    public static ApplicationListener createGameListener(MainController main) {
        return new ApplicationListener() {
            @Override
            public void create() {
                main.create();
            }

            @Override
            public void resize(int width, int height) {
                main.resize(width, height);
            }

            @Override
            public void render() {
                main.render();
            }

            @Override
            public void pause() {
                main.pause();
            }

            @Override
            public void resume() {
                main.resume();
            }

            @Override
            public void dispose() {
                main.dispose();
            }
        };
    }

    public static StartupDiagnostics.Outcome detectIllegalSongs(SongDatabaseAccessor songdb, PlayerConfig player) {
        GameContext.clearIllegalSongs();
        boolean failed = false;

        Set<String> hashes = new LinkedHashSet<>();
        addIllegalSongHashes(hashes, SongUtils.illegalsongs);
        for (bms.player.beatoraja.IRConfig irconfig : player.getIrconfig()) {
            if (irconfig == null || irconfig.getIrname() == null || irconfig.getIrname().length() == 0) {
                continue;
            }
            IRConnection ir = IRConnectionManager.getIRConnection(irconfig.getIrname());
            if (ir == null) {
                continue;
            }
            try {
                IRResponse<String[]> response = ir.getIllegalSongs();
                if (response.isSucceeded()) {
                    addIllegalSongHashes(hashes, response.getData());
                } else if (response.getMessage() != null && response.getMessage().length() > 0
                        && !"Not supported".equals(response.getMessage())) {
                    failed = true;
                    Logger.getGlobal().warning("IR illegal song list取得失敗 - " + irconfig.getIrname() + " : "
                            + response.getMessage());
                }
            } finally {
                closeIRConnection(ir);
            }
        }

        if (hashes.isEmpty()) {
            return failed ? StartupDiagnostics.Outcome.FAILED : StartupDiagnostics.Outcome.OK;
        }
        SongData[] matches;
        try (var timing = StartupDiagnostics.measure(StartupDiagnostics.Step.IR_CHART_DB_LOOKUP)) {
            matches = songdb.getSongDatas(hashes.toArray(String[]::new));
            timing.outcome(StartupDiagnostics.Outcome.OK);
        }
        for (SongData song : matches) {
            if (song != null && song.getSha256() != null && song.getSha256().length() > 0) {
                GameContext.putIllegalSong(song.getSha256());
            }
        }
        return failed ? StartupDiagnostics.Outcome.FAILED : StartupDiagnostics.Outcome.OK;
    }

    private static void addIllegalSongHashes(Set<String> hashes, String[] candidates) {
        if (candidates == null) return;
        for (String candidate : candidates) {
            if (candidate != null && candidate.length() == 64) hashes.add(candidate);
        }
    }

    private static void closeIRConnection(IRConnection ir) {
        if (ir instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }
}
