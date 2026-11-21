package bms.player.beatoraja.database;

/**
 * SQLite connection options used by the game database layer.
 *
 * @param sharedCache whether the connection asks for shared-cache locking
 * @param synchronousOff whether to run with {@code PRAGMA synchronous = OFF} instead of
 *            {@code NORMAL}
 * @param busyTimeoutMillis busy timeout requested by the caller; the connection layer applies its
 *            own lower bound
 * @param immediateTransactions whether write transactions take the write lock immediately
 */
public record SqliteOptions(boolean sharedCache, boolean synchronousOff, int busyTimeoutMillis,
		boolean immediateTransactions) {

	/** Standard score database profile: shared cache, synchronous off, deferred write locks. */
	public static final SqliteOptions DEFAULT = new SqliteOptions(true, true, 0, false);
}
