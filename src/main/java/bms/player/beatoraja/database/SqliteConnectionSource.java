package bms.player.beatoraja.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

import bms.player.beatoraja.AppPaths;

/**
 * Opens SQLite connections through {@link SqliteDatabase}.
 *
 * <p>Every {@link #getConnection()} call opens a fresh connection, which matches the historical
 * one-connection-per-query semantics of the database accessors. Relative paths are resolved against
 * the game data root through {@link AppPaths}, so callers may keep passing the portable paths
 * stored in the game configuration.</p>
 */
public final class SqliteConnectionSource implements ConnectionSource {

	private final String path;
	private final SqliteOptions options;

	public SqliteConnectionSource(String path) {
		this(path, SqliteOptions.DEFAULT);
	}

	public SqliteConnectionSource(String path, SqliteOptions options) {
		this.path = Objects.requireNonNull(path, "path");
		this.options = options != null ? options : SqliteOptions.DEFAULT;
	}

	public String getPath() {
		return path;
	}

	public SqliteOptions getOptions() {
		return options;
	}

	@Override
	public Connection getConnection() throws SQLException {
		return SqliteDatabase.open(AppPaths.path(path).toString(), options);
	}

	@Override
	public String toString() {
		return "SqliteConnectionSource[" + path + "]";
	}
}
