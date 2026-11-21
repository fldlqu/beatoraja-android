package bms.player.beatoraja.external;

import bms.player.beatoraja.AppPaths;
import bms.player.beatoraja.ScoreData;
import bms.player.beatoraja.ScoreDatabaseAccessor;
import bms.player.beatoraja.database.MapListHandler;
import bms.player.beatoraja.database.QueryRunner;
import bms.player.beatoraja.database.SqliteDatabase;
import bms.player.beatoraja.song.SongData;
import bms.player.beatoraja.song.SongDatabaseAccessor;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class ScoreDataImporter {

    private ScoreDatabaseAccessor scoredb;

    public ScoreDataImporter(ScoreDatabaseAccessor scoredb) {
        this.scoredb = scoredb;
    }

    public void importFromLR2ScoreDatabase(String path, SongDatabaseAccessor songdb) {
        final int[] clears = { 0, 1, 4, 5, 6, 8, 9 };
        scoredb.createTable();

        try (Connection con = openImportConnection(path)) {
            QueryRunner qr = new QueryRunner();
            MapListHandler rh = new MapListHandler();
            List<Map<String, Object>> scores = qr.query(con, "SELECT * FROM score", rh);

            List<ScoreData> result = new ArrayList<ScoreData>();
            for (Map<String, Object> score : scores) {
                final String md5 = (String) score.get("hash");
                SongData[] song = songdb.getSongDatas(new String[] { md5 });
                if (song.length > 0) {
                    ScoreData sd = new ScoreData();
                    sd.setEpg((int) score.get("perfect"));
                    sd.setEgr((int) score.get("great"));
                    sd.setEgd((int) score.get("good"));
                    sd.setEbd((int) score.get("bad"));
                    sd.setEpr((int) score.get("poor"));
                    sd.setMinbp((int) score.get("minbp"));
                    sd.setClear(clears[(int) score.get("clear")]);
                    sd.setPlaycount((int) score.get("playcount"));
                    sd.setClearcount((int) score.get("clearcount"));
                    sd.setSha256(song[0].getSha256());
                    sd.setNotes(song[0].getNotes());
                    result.add(sd);
                }
            }
            
            this.importScores(result.toArray(new ScoreData[result.size()]), "LR2");
        } catch (Exception e) {
            Logger.getGlobal().severe("スコア移行時の例外:" + e.getMessage());
        }
    }

    /**
     * Opens the foreign (LR2) score database through the shared SQLite layer.
     */
    private static Connection openImportConnection(String path) throws SQLException {
        return SqliteDatabase.open(AppPaths.path(path).toString());
    }

    public void importScores(ScoreData[] scores, String scorehash) {
        List<ScoreData> result = new ArrayList<ScoreData>();

        for(ScoreData score : scores) {
            ScoreData oldsd = scoredb.getScoreData(score.getSha256(), score.getMode());
            if(oldsd == null) {
                oldsd = new ScoreData();
                oldsd.setPlaycount(score.getPlaycount());
                oldsd.setClearcount(score.getClearcount());
                oldsd.setSha256(score.getSha256());
                oldsd.setMode(score.getMode());
                oldsd.setNotes(score.getNotes());
            }
            oldsd.setScorehash(scorehash);
            if (oldsd.update(score)) {
                result.add(oldsd);
            }
        }
        
        scoredb.setScoreData(result.toArray(new ScoreData[result.size()]));
		Logger.getGlobal().info("スコアインポート完了 - インポート数 : " + result.size());
    }
}
