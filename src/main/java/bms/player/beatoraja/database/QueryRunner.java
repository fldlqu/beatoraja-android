package bms.player.beatoraja.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Minimal drop-in replacement for {@code org.apache.commons.dbutils.QueryRunner}.
 *
 * <p>Only the surface used by beatoraja is implemented: {@code query} (with or without an explicit
 * connection), {@code update} (with or without an explicit connection) and {@link #getDataSource()}
 * for code that opens its own connection. Results are produced by {@link ResultSetHandler}
 * implementations in this package.</p>
 *
 * <p>Connection management matches DbUtils: a runner created with a {@link ConnectionSource} opens
 * (and closes) a fresh connection per call, while methods that receive a {@link Connection} use it
 * as-is and never close it. The runner itself holds only the immutable source, so it is safe to
 * share between threads.</p>
 */
public class QueryRunner {

	private final ConnectionSource connectionSource;

	/** Creates a runner without a connection source; only the explicit-connection methods work. */
	public QueryRunner() {
		this(null);
	}

	/** Creates a runner that opens a new connection from {@code connectionSource} for each call. */
	public QueryRunner(ConnectionSource connectionSource) {
		this.connectionSource = connectionSource;
	}

	/**
	 * The source used by the connection-less methods, or {@code null} for a runner created with the
	 * no-argument constructor.
	 */
	public ConnectionSource getDataSource() {
		return connectionSource;
	}

	// ------------------------------------------------------------------ query

	public <T> T query(String sql, ResultSetHandler<T> rsh) throws SQLException {
		return query(sql, rsh, (Object[]) null);
	}

	public <T> T query(String sql, ResultSetHandler<T> rsh, Object... params) throws SQLException {
		Connection connection = prepareConnection();
		try {
			return query(connection, sql, rsh, params);
		} finally {
			closeQuietly(connection);
		}
	}

	public <T> T query(Connection connection, String sql, ResultSetHandler<T> rsh) throws SQLException {
		return query(connection, sql, rsh, (Object[]) null);
	}

	public <T> T query(Connection connection, String sql, ResultSetHandler<T> rsh, Object... params)
			throws SQLException {
		requireConnection(connection);
		if (rsh == null) {
			throw new SQLException("Null ResultSetHandler");
		}
		PreparedStatement statement = null;
		ResultSet resultSet = null;
		try {
			statement = connection.prepareStatement(sql);
			fillStatement(statement, params);
			resultSet = statement.executeQuery();
			return rsh.handle(resultSet);
		} finally {
			closeQuietly(resultSet);
			closeQuietly(statement);
		}
	}

	// ----------------------------------------------------------------- update

	public int update(String sql) throws SQLException {
		return update(sql, (Object[]) null);
	}

	public int update(String sql, Object... params) throws SQLException {
		Connection connection = prepareConnection();
		try {
			return update(connection, sql, params);
		} finally {
			closeQuietly(connection);
		}
	}

	public int update(Connection connection, String sql) throws SQLException {
		return update(connection, sql, (Object[]) null);
	}

	public int update(Connection connection, String sql, Object... params) throws SQLException {
		requireConnection(connection);
		PreparedStatement statement = null;
		try {
			statement = connection.prepareStatement(sql);
			fillStatement(statement, params);
			return statement.executeUpdate();
		} finally {
			closeQuietly(statement);
		}
	}

	// ---------------------------------------------------------------- helpers

	private Connection prepareConnection() throws SQLException {
		if (connectionSource == null) {
			throw new SQLException("No ConnectionSource configured for this QueryRunner");
		}
		Connection connection = connectionSource.getConnection();
		if (connection == null) {
			throw new SQLException("ConnectionSource returned null");
		}
		return connection;
	}

	private static void requireConnection(Connection connection) throws SQLException {
		if (connection == null) {
			throw new SQLException("Null connection");
		}
	}

	/**
	 * Binds statement parameters. Unlike DbUtils this does not inspect
	 * {@code ParameterMetaData} (the SQLite JDBC shim does not implement it), so NULL is
	 * bound with {@link Types#VARCHAR}; SQLite has no typed NULLs so the equivalent is safe.
	 */
	private static void fillStatement(PreparedStatement statement, Object... params) throws SQLException {
		if (params == null) {
			return;
		}
		for (int i = 0; i < params.length; i++) {
			Object param = params[i];
			if (param != null) {
				statement.setObject(i + 1, param);
			} else {
				statement.setNull(i + 1, Types.VARCHAR);
			}
		}
	}

	private static void closeQuietly(AutoCloseable closeable) {
		if (closeable != null) {
			try {
				closeable.close();
			} catch (Exception ignored) {
				// DbUtils closes quietly as well; a close failure must not mask the query result.
			}
		}
	}
}
