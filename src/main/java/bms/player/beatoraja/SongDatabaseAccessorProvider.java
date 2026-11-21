package bms.player.beatoraja;

import java.io.File;

import bms.player.beatoraja.song.SQLiteSongDatabaseAccessor;
import bms.player.beatoraja.song.SongDatabaseAccessor;

/**
 * Lazily creates (and re-creates when the relevant config changes) the song database accessor.
 */
public class SongDatabaseAccessorProvider {

    // Batched updates after comparing songdata.db update performance on target environments.
    private static final SQLiteSongDatabaseAccessor.SongUpdaterType SONG_UPDATER_TYPE =
            SQLiteSongDatabaseAccessor.SongUpdaterType.BATCHED;

    private SongDatabaseAccessor songdb;

    private String songpath;

    private String reviewpath;

    private boolean scanSongArchives;

    private Config.SongArchiveExtractMode songArchiveExtractMode;

    public synchronized SongDatabaseAccessor get(Config config) {
        return get(config, config.getPlayername());
    }

    public synchronized SongDatabaseAccessor get(Config config, String playername) {
        String nextSongpath = AppPaths.path(config.getSongpath()).toString();
        String nextReviewpath = getSongReviewPath(config, playername);
        if (songdb == null || !nextSongpath.equals(songpath) || !nextReviewpath.equals(reviewpath)
                || config.isScanSongArchives() != scanSongArchives
                || config.getSongArchiveExtractMode() != songArchiveExtractMode) {
            songdb = new SQLiteSongDatabaseAccessor(config.getSongpath(), config.getBmsroot(), nextReviewpath,
                    SONG_UPDATER_TYPE, config.isScanSongArchives(), config.getSongArchiveExtractMode());
            songpath = nextSongpath;
            reviewpath = nextReviewpath;
            scanSongArchives = config.isScanSongArchives();
            songArchiveExtractMode = config.getSongArchiveExtractMode();
        }
        return songdb;
    }

    private String getSongReviewPath(Config config, String playername) {
        return AppPaths.path(config.getPlayerpath()).toString() + File.separatorChar + playername
                + File.separatorChar + "songreview.db";
    }
}
