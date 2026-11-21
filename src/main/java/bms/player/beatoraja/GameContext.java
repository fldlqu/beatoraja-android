package bms.player.beatoraja;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide state shared between the launcher and the running game.
 */
public final class GameContext {

    private static final Set<String> illegalSongs = ConcurrentHashMap.newKeySet();

    private GameContext() {
    }

    public static void putIllegalSong(String hash) {
        if (hash != null && !hash.isBlank()) illegalSongs.add(hash);
    }

    public static void clearIllegalSongs() {
        illegalSongs.clear();
    }

    public static String[] getIllegalSongs() {
        return illegalSongs.toArray(new String[0]);
    }

    public static int getIllegalSongCount() {
        return illegalSongs.size();
    }
}
