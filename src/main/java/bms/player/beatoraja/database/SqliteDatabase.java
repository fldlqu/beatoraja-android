package bms.player.beatoraja.database;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Locale;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteProgram;
import android.database.sqlite.SQLiteStatement;


/**
 * Minimal {@code java.sql} implementation over the native SQLite API.
 *
 * <p>The runtime ships the {@code java.sql} interfaces but no usable SQLite JDBC driver (the
 * xerial driver needs a JNI library that is not available), so the game database layer talks to
 * the native SQLite database through these proxies. Only the JDBC surface exercised by the
 * beatoraja database layer is implemented; unhandled interface methods return a safe default
 * (0/false/null) so that harmless capability queries do not crash.</p>
 *
 * <p>Design notes:</p>
 * <ul>
 *   <li>Each {@code java.sql.Connection} is one native SQLite handle opened on demand by
 *       {@link #open(String, SqliteOptions)}, matching the one-connection-per-query behaviour of
 *       the database accessors.</li>
 *   <li>{@code setAutoCommit(false)} groups the following statements into a native SQLite
 *       transaction ({@code beginTransactionNonExclusive()} = BEGIN IMMEDIATE when the
 *       {@code immediateTransactions} option is set, otherwise {@code beginTransaction()});
 *       {@code commit()}/{@code rollback()}/{@code close()} finish it. Exceptions become
 *       {@link SQLException}.</li>
 *   <li>Prepared-statement updates bind through {@link SQLiteStatement}; prepared-statement queries
 *       use {@link SQLiteDatabase#rawQuery} (the native cursor API can only bind string selection
 *       args).</li>
 *   <li>{@code addBatch()}/{@code executeBatch()} compile the statement once and re-bind per row,
 *       which is what {@code SQLiteStatement} is documented to be reusable for.</li>
 *   <li>The transaction state lives in the thread's {@code SQLiteSession}, so a
 *       {@code java.sql.Connection} must be used from one thread at a time (the database accessors
 *       keep their updater connections thread-confined).</li>
 *   <li>{@link Connection#getMetaData()}, {@link ResultSet#getMetaData()} and
 *       {@link PreparedStatement#getParameterMetaData()} are lightweight stubs; the accessor code
 *       only needs column labels/counts.</li>
 * </ul>
 */
public final class SqliteDatabase {

	/** Minimum busy timeout applied to opened connections (ms); see {@link #applyOptions}. */
	private static final int MIN_BUSY_TIMEOUT_MILLIS = 2_000;

	private SqliteDatabase() {
	}

	/** Opens a native SQLite database with the default options. */
	public static Connection open(String path) throws SQLException {
		return open(path, SqliteOptions.DEFAULT);
	}

	/**
	 * Opens a native SQLite database and returns a JDBC connection proxy for it.
	 */
	public static Connection open(String path, SqliteOptions options) throws SQLException {
		if (path == null || path.isEmpty()) {
			throw new SQLException("SQLite database path is empty");
		}
		final SqliteOptions effective = options != null ? options : SqliteOptions.DEFAULT;
		// The native open call does not create parent directories, but the game relies on the
		// player/stat directories existing before the first write.
		final java.io.File parent = new java.io.File(path).getParentFile();
		if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
			throw new SQLException("Cannot create SQLite directory: " + parent);
		}
		final SQLiteDatabase database;
		try {
			database = SQLiteDatabase.openDatabase(path, null,
					SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.CREATE_IF_NECESSARY
							| SQLiteDatabase.NO_LOCALIZED_COLLATORS);
		} catch (RuntimeException e) {
			throw sqlException("Cannot open SQLite database: " + path, e);
		}
		try {
			applyOptions(database, effective);
			return ConnectionHandler.create(database, effective);
		} catch (RuntimeException e) {
			try {
				database.close();
			} catch (RuntimeException ignored) {
				// best effort
			}
			throw sqlException("Cannot configure SQLite database: " + path, e);
		}
	}

	/**
	 * Applies the pragma/transaction profile. Pragma failures that only concern optional
	 * features (shared cache, read-uncommitted) are ignored so that a reduced SQLite build still
	 * opens; synchronous/busy-timeout failures are propagated because they change durability or
	 * locking behaviour.
	 */
	private static void applyOptions(SQLiteDatabase database, SqliteOptions options) {
		if (options.sharedCache()) {
			execPragmaQuietly(database, "shared_cache = ON");
		}
		try {
			execPragma(database, "PRAGMA synchronous = " + (options.synchronousOff() ? "OFF" : "NORMAL"));
		} catch (RuntimeException e) {
			// Durability tuning only; keep the connection usable on restricted SQLite builds.
		}
		// Rollback-journal readers fail fast with SQLITE_BUSY otherwise; shared-cache locking is
		// not available here.
		execPragmaQuietly(database, "PRAGMA busy_timeout = "
				+ Math.max(options.busyTimeoutMillis(), MIN_BUSY_TIMEOUT_MILLIS));
		if (options.immediateTransactions()) {
			execPragmaQuietly(database, "read_uncommitted = ON");
		}
	}

	/** PRAGMA statements return rows, so they must run through rawQuery (execSQL rejects them). */
	private static void execPragma(SQLiteDatabase database, String pragma) {
		final Cursor cursor = database.rawQuery(pragma, null);
		try {
			cursor.moveToFirst();
		} finally {
			cursor.close();
		}
	}

	private static void execPragmaQuietly(SQLiteDatabase database, String pragma) {
		try {
			execPragma(database, pragma);
		} catch (RuntimeException ignored) {
			// optional pragma unsupported by this SQLite build
		}
	}

	static SQLException sqlException(String message, Throwable cause) {
		return new SQLException(message + (cause != null && cause.getMessage() != null
				? " (" + cause.getMessage() + ")" : ""), cause);
	}

	static SQLException closedException() {
		return new SQLException("Connection is closed");
	}

	/** Default value for an unimplemented JDBC method. */
	static Object defaultValue(Class<?> type) {
		if (type == void.class) {
			return null;
		}
		if (type == boolean.class) {
			return Boolean.FALSE;
		}
		if (type == byte.class) {
			return Byte.valueOf((byte) 0);
		}
		if (type == short.class) {
			return Short.valueOf((short) 0);
		}
		if (type == int.class) {
			return Integer.valueOf(0);
		}
		if (type == long.class) {
			return Long.valueOf(0L);
		}
		if (type == float.class) {
			return Float.valueOf(0f);
		}
		if (type == double.class) {
			return Double.valueOf(0d);
		}
		if (type == char.class) {
			return Character.valueOf('\0');
		}
		return null;
	}

	static boolean isObjectMethod(Method method) {
		return method.getDeclaringClass() == Object.class;
	}

	private static Object newProxy(Class<?> type, InvocationHandler handler) {
		return Proxy.newProxyInstance(SqliteDatabase.class.getClassLoader(),
				new Class<?>[] { type }, handler);
	}

	static ResultSet wrapCursor(Cursor cursor, Object statementProxy) {
		return (ResultSet) newProxy(ResultSet.class, new CursorResultSetHandler(cursor, statementProxy));
	}

	// ---------------------------------------------------------------- binding

	private static void bind(SQLiteProgram program, Object[] params) throws SQLException {
		if (params == null) {
			return;
		}
		for (int i = 0; i < params.length; i++) {
			bindValue(program, i + 1, params[i]);
		}
	}

	@SuppressWarnings("deprecation")
	private static void bindValue(SQLiteProgram program, int index, Object value) {
		if (value == null) {
			program.bindNull(index);
		} else if (value instanceof String) {
			program.bindString(index, (String) value);
		} else if (value instanceof Integer || value instanceof Long || value instanceof Short
				|| value instanceof Byte) {
			program.bindLong(index, ((Number) value).longValue());
		} else if (value instanceof Boolean) {
			program.bindLong(index, ((Boolean) value).booleanValue() ? 1L : 0L);
		} else if (value instanceof Float || value instanceof Double) {
			program.bindDouble(index, ((Number) value).doubleValue());
		} else if (value instanceof Number) {
			program.bindDouble(index, ((Number) value).doubleValue());
		} else if (value instanceof byte[]) {
			program.bindBlob(index, (byte[]) value);
		} else if (value instanceof java.util.Date) {
			program.bindLong(index, ((java.util.Date) value).getTime());
		} else {
			program.bindString(index, value.toString());
		}
	}

	private static String[] rawQueryArgs(Object[] params) throws SQLException {
		if (params == null || params.length == 0) {
			return null;
		}
		String[] args = new String[params.length];
		for (int i = 0; i < params.length; i++) {
			Object value = params[i];
			if (value == null) {
				args[i] = null;
			} else if (value instanceof byte[]) {
				throw new SQLFeatureNotSupportedException("BLOB query parameters are not supported");
			} else if (value instanceof Boolean) {
				args[i] = ((Boolean) value).booleanValue() ? "1" : "0";
			} else {
				args[i] = value.toString();
			}
		}
		return args;
	}

	// ------------------------------------------------------------- connection

	private static final class ConnectionHandler implements InvocationHandler {

		private final SQLiteDatabase database;
		private final SqliteOptions options;
		private final Object lock = new Object();

		private Connection proxy;
		private boolean closed;
		private boolean autoCommit = true;
		private boolean transactionActive;
		private boolean readOnly;

		private ConnectionHandler(SQLiteDatabase database, SqliteOptions options) {
			this.database = database;
			this.options = options;
		}

		static Connection create(SQLiteDatabase database, SqliteOptions options) {
			ConnectionHandler handler = new ConnectionHandler(database, options);
			Connection connection = (Connection) newProxy(Connection.class, handler);
			handler.proxy = connection;
			return connection;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			if (isObjectMethod(method)) {
				return objectMethod(proxy, method, args);
			}
			final String name = method.getName();
			if ("createStatement".equals(name)) {
				checkOpen();
				return newStatement(null, false);
			}
			if ("prepareStatement".equals(name)) {
				checkOpen();
				String sql = (String) args[0];
				boolean generatedKeys = args.length == 2 && StatementHandler.isGeneratedKeysFlag(args[1]);
				return newStatement(sql, generatedKeys);
			}
			if ("prepareCall".equals(name)) {
				throw unsupported(name);
			}
			if ("nativeSQL".equals(name)) {
				return args[0];
			}
			if ("setAutoCommit".equals(name)) {
				setAutoCommit(((Boolean) args[0]).booleanValue());
				return null;
			}
			if ("getAutoCommit".equals(name)) {
				synchronized (lock) {
					checkOpen();
					return Boolean.valueOf(autoCommit);
				}
			}
			if ("commit".equals(name)) {
				commit();
				return null;
			}
			if ("rollback".equals(name)) {
				if (args != null && args.length > 0) {
					throw unsupported(name);
				}
				rollback();
				return null;
			}
			if ("close".equals(name)) {
				close();
				return null;
			}
			if ("isClosed".equals(name)) {
				synchronized (lock) {
					return Boolean.valueOf(closed);
				}
			}
			if ("isValid".equals(name)) {
				synchronized (lock) {
					return Boolean.valueOf(!closed);
				}
			}
			if ("getMetaData".equals(name)) {
				return databaseMetaData();
			}
			if ("unwrap".equals(name)) {
				Class<?> type = (Class<?>) args[0];
				if (type.isInstance(proxy)) {
					return proxy;
				}
				throw new SQLException("Not a wrapper for " + type.getName());
			}
			if ("isWrapperFor".equals(name)) {
				return Boolean.valueOf(((Class<?>) args[0]).isInstance(proxy));
			}
			if ("setReadOnly".equals(name)) {
				readOnly = ((Boolean) args[0]).booleanValue();
				return null;
			}
			if ("isReadOnly".equals(name)) {
				return Boolean.valueOf(readOnly);
			}
			if ("setTransactionIsolation".equals(name) || "setHoldability".equals(name)
					|| "clearWarnings".equals(name) || "setTypeMap".equals(name)
					|| "setCatalog".equals(name) || "setSchema".equals(name)
					|| "setClientInfo".equals(name) || "setNetworkTimeout".equals(name)
					|| "abort".equals(name) || "beginRequest".equals(name) || "endRequest".equals(name)) {
				return null;
			}
			if ("getTransactionIsolation".equals(name)) {
				return Integer.valueOf(Connection.TRANSACTION_SERIALIZABLE);
			}
			if ("getWarnings".equals(name) || "getCatalog".equals(name) || "getSchema".equals(name)) {
				return null;
			}
			if ("getTypeMap".equals(name)) {
				return new HashMap<String, Class<?>>();
			}
			if ("getHoldability".equals(name)) {
				return Integer.valueOf(ResultSet.HOLD_CURSORS_OVER_COMMIT);
			}
			if ("getClientInfo".equals(name)) {
				return args == null || args.length == 0 ? new Properties() : null;
			}
			if ("getNetworkTimeout".equals(name)) {
				return Integer.valueOf(0);
			}
			if ("createClob".equals(name) || "createBlob".equals(name) || "createNClob".equals(name)
					|| "createSQLXML".equals(name) || "createArrayOf".equals(name)
					|| "createStruct".equals(name) || "setSavepoint".equals(name)
					|| "releaseSavepoint".equals(name)) {
				throw unsupported(name);
			}
			return defaultValue(method.getReturnType());
		}

		private Object objectMethod(Object proxy, Method method, Object[] args) {
			if ("toString".equals(method.getName())) {
				return "SQLiteConnection[" + database.getPath() + "]";
			}
			if ("hashCode".equals(method.getName())) {
				return Integer.valueOf(System.identityHashCode(proxy));
			}
			return Boolean.valueOf(proxy == (args != null && args.length > 0 ? args[0] : null));
		}

		private Object newStatement(String sql, boolean generatedKeys) {
			StatementHandler handler = new StatementHandler(this, sql, generatedKeys);
			Class<?> type = sql == null ? Statement.class : PreparedStatement.class;
			return newProxy(type, handler);
		}

		Object proxy() {
			return proxy;
		}

		Object lock() {
			return lock;
		}

		void checkOpen() throws SQLException {
			if (closed) {
				throw closedException();
			}
		}

		private void setAutoCommit(boolean value) throws SQLException {
			synchronized (lock) {
				checkOpen();
				if (autoCommit == value) {
					return;
				}
				if (value) {
					commitInternal();
				}
				autoCommit = value;
			}
		}

		private void commit() throws SQLException {
			synchronized (lock) {
				checkOpen();
				if (autoCommit) {
					throw new SQLException("Cannot commit while autoCommit is true");
				}
				commitInternal();
			}
		}

		private void rollback() throws SQLException {
			synchronized (lock) {
				checkOpen();
				if (autoCommit) {
					throw new SQLException("Cannot rollback while autoCommit is true");
				}
				rollbackInternal();
			}
		}

		private void close() throws SQLException {
			synchronized (lock) {
				if (closed) {
					return;
				}
				try {
					if (transactionActive) {
						rollbackInternal();
					}
				} finally {
					closed = true;
					database.close();
				}
			}
		}

		private void commitInternal() {
			if (transactionActive) {
				try {
					database.setTransactionSuccessful();
				} finally {
					database.endTransaction();
					transactionActive = false;
				}
			}
		}

		private void rollbackInternal() {
			if (transactionActive) {
				database.endTransaction();
				transactionActive = false;
			}
		}

		private void ensureTransaction() {
			if (!autoCommit && !transactionActive) {
				// BEGIN IMMEDIATE lets concurrent readers keep working while the writer holds its
				// reserved lock; EXCLUSIVE (beginTransaction()) would reject them outright.
				database.beginTransactionNonExclusive();
				transactionActive = true;
			}
		}

		int executeUpdate(String sql, Object[] params, long[] generatedKeyOut) throws SQLException {
			synchronized (lock) {
				checkOpen();
				ensureTransaction();
				try {
					SQLiteStatement statement = database.compileStatement(sql);
					try {
						bind(statement, params);
						int count = statement.executeUpdateDelete();
						if (generatedKeyOut != null) {
							generatedKeyOut[0] = lastInsertRowid();
						}
						return count;
					} finally {
						statement.close();
					}
				} catch (RuntimeException e) {
					throw sqlException("Failed to execute update: " + sql, e);
				}
			}
		}

		int[] executeBatch(String sql, List<Object[]> batch) throws SQLException {
			synchronized (lock) {
				checkOpen();
				if (batch.isEmpty()) {
					return new int[0];
				}
				ensureTransaction();
				try {
					SQLiteStatement statement = database.compileStatement(sql);
					try {
						int[] counts = new int[batch.size()];
						for (int i = 0; i < batch.size(); i++) {
							statement.clearBindings();
							bind(statement, batch.get(i));
							counts[i] = statement.executeUpdateDelete();
						}
						return counts;
					} finally {
						statement.close();
					}
				} catch (RuntimeException e) {
					throw sqlException("Failed to execute batch: " + sql, e);
				}
			}
		}

		Cursor executeQuery(String sql, Object[] params) throws SQLException {
			synchronized (lock) {
				checkOpen();
				ensureTransaction();
				try {
					return database.rawQuery(sql, rawQueryArgs(params));
				} catch (RuntimeException e) {
					throw sqlException("Failed to execute query: " + sql, e);
				}
			}
		}

		void executeStatement(String sql) throws SQLException {
			synchronized (lock) {
				checkOpen();
				ensureTransaction();
				try {
					database.execSQL(sql);
				} catch (RuntimeException e) {
					throw sqlException("Failed to execute statement: " + sql, e);
				}
			}
		}

		private long lastInsertRowid() throws SQLException {
			Cursor cursor = database.rawQuery("SELECT last_insert_rowid()", null);
			try {
				return cursor.moveToFirst() ? cursor.getLong(0) : -1L;
			} finally {
				cursor.close();
			}
		}

		private java.sql.DatabaseMetaData databaseMetaData() {
			return (java.sql.DatabaseMetaData) newProxy(java.sql.DatabaseMetaData.class,
					new DatabaseMetaDataHandler(proxy, database.getPath()));
		}

		private static SQLFeatureNotSupportedException unsupported(String name) {
			return new SQLFeatureNotSupportedException(name + " is not supported by SqliteDatabase");
		}
	}

	// -------------------------------------------------------------- statement

	private static final class StatementHandler implements InvocationHandler {

		private final ConnectionHandler connection;
		private final String sql;
		private final boolean returnGeneratedKeys;

		private Object[] params = new Object[0];
		private final List<Object[]> batchParams = new ArrayList<>();
		private final List<String> batchSql = new ArrayList<>();
		private int updateCount = -1;
		private boolean hasGeneratedKeys;
		private long generatedKey = -1L;
		private boolean closed;

		private StatementHandler(ConnectionHandler connection, String sql, boolean returnGeneratedKeys) {
			this.connection = connection;
			this.sql = sql;
			this.returnGeneratedKeys = returnGeneratedKeys;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			if (isObjectMethod(method)) {
				if ("toString".equals(method.getName())) {
					return "SQLiteStatement[" + sql + "]";
				}
				if ("hashCode".equals(method.getName())) {
					return Integer.valueOf(System.identityHashCode(proxy));
				}
				return Boolean.valueOf(proxy == (args != null && args.length > 0 ? args[0] : null));
			}
			final String name = method.getName();
			if ("close".equals(name)) {
				closed = true;
				batchParams.clear();
				batchSql.clear();
				return null;
			}
			if ("isClosed".equals(name)) {
				return Boolean.valueOf(closed);
			}
			if ("getConnection".equals(name)) {
				return connection.proxy();
			}
			if ("executeQuery".equals(name)) {
				checkOpen();
				Cursor cursor;
				if (args == null || args.length == 0) {
					cursor = connection.executeQuery(requirePrepared(), params);
				} else {
					cursor = connection.executeQuery((String) args[0], null);
				}
				updateCount = -1;
				return wrapCursor(cursor, proxy);
			}
			if ("executeUpdate".equals(name) || "executeLargeUpdate".equals(name)) {
				checkOpen();
				updateCount = executeUpdate(args);
				return method.getReturnType() == long.class ? (Object) Long.valueOf(updateCount)
						: (Object) Integer.valueOf(updateCount);
			}
			if ("execute".equals(name)) {
				checkOpen();
				if (args == null || args.length == 0) {
					updateCount = executeUpdate(args);
					return Boolean.FALSE;
				}
				connection.executeStatement((String) args[0]);
				updateCount = -1;
				return Boolean.FALSE;
			}
			if ("addBatch".equals(name)) {
				checkOpen();
				if (args == null || args.length == 0) {
					requirePrepared();
					batchParams.add(Arrays.copyOf(params, params.length));
				} else {
					batchSql.add((String) args[0]);
				}
				return null;
			}
			if ("clearBatch".equals(name)) {
				batchParams.clear();
				batchSql.clear();
				return null;
			}
			if ("executeBatch".equals(name) || "executeLargeBatch".equals(name)) {
				checkOpen();
				int[] counts = executeBatch();
				updateCount = -1;
				if ("executeLargeBatch".equals(name)) {
					long[] large = new long[counts.length];
					for (int i = 0; i < counts.length; i++) {
						large[i] = counts[i];
					}
					return large;
				}
				return counts;
			}
			if ("getGeneratedKeys".equals(name)) {
				String[] columns = { "last_insert_rowid" };
				MatrixCursor cursor = new MatrixCursor(columns, 1);
				if (hasGeneratedKeys) {
					cursor.addRow(new Object[] { Long.valueOf(generatedKey) });
				}
				return wrapCursor(cursor, proxy);
			}
			if ("getUpdateCount".equals(name)) {
				return Integer.valueOf(updateCount);
			}
			if ("getMoreResults".equals(name) || "getResultSet".equals(name)) {
				return "getResultSet".equals(name) ? null : Boolean.FALSE;
			}
			if ("getResultSetType".equals(name)) {
				return Integer.valueOf(ResultSet.TYPE_FORWARD_ONLY);
			}
			if ("getResultSetConcurrency".equals(name)) {
				return Integer.valueOf(ResultSet.CONCUR_READ_ONLY);
			}
			if ("getResultSetHoldability".equals(name)) {
				return Integer.valueOf(ResultSet.HOLD_CURSORS_OVER_COMMIT);
			}
			if ("getFetchDirection".equals(name)) {
				return Integer.valueOf(ResultSet.FETCH_FORWARD);
			}
			if ("getFetchSize".equals(name) || "getMaxRows".equals(name)
					|| "getQueryTimeout".equals(name) || "getMaxFieldSize".equals(name)) {
				return Integer.valueOf(0);
			}
			if ("getWarnings".equals(name)) {
				return null;
			}
			if ("isPoolable".equals(name) || "isCloseOnCompletion".equals(name)) {
				return Boolean.FALSE;
			}
			if ("getParameterMetaData".equals(name)) {
				return Proxy.newProxyInstance(SqliteDatabase.class.getClassLoader(),
						new Class<?>[] { ParameterMetaData.class },
						(proxy12, method12, args12) -> defaultValue(method12.getReturnType()));
			}
			if ("setString".equals(name) || "setInt".equals(name) || "setLong".equals(name)
					|| "setFloat".equals(name) || "setDouble".equals(name) || "setBoolean".equals(name)
					|| "setShort".equals(name) || "setByte".equals(name) || "setBytes".equals(name)
					|| "setNull".equals(name) || "setObject".equals(name) || "setDate".equals(name)
					|| "setTime".equals(name) || "setTimestamp".equals(name)
					|| "setBigDecimal".equals(name)) {
				requirePrepared();
				setParameter(name, args);
				return null;
			}
			if ("clearParameters".equals(name)) {
				requirePrepared();
				params = new Object[0];
				return null;
			}
			if ("setFetchSize".equals(name) || "setFetchDirection".equals(name)
					|| "setMaxRows".equals(name) || "setQueryTimeout".equals(name)
					|| "setEscapeProcessing".equals(name) || "setCursorName".equals(name)
					|| "setPoolable".equals(name) || "closeOnCompletion".equals(name)
					|| "cancel".equals(name) || "clearWarnings".equals(name)) {
				return null;
			}
			return defaultValue(method.getReturnType());
		}

		private void checkOpen() throws SQLException {
			if (closed) {
				throw closedException();
			}
		}

		private String requirePrepared() throws SQLException {
			if (sql == null) {
				throw new SQLException("Not a prepared statement");
			}
			return sql;
		}

		private static boolean isGeneratedKeysFlag(Object flag) {
			if (flag instanceof Integer) {
				return ((Integer) flag).intValue() == Statement.RETURN_GENERATED_KEYS;
			}
			return flag instanceof int[] || flag instanceof String[];
		}

		private int executeUpdate(Object[] args) throws SQLException {
			if (args == null || args.length == 0) {
				long[] key = returnGeneratedKeys ? new long[1] : null;
				int count = connection.executeUpdate(requirePrepared(), params, key);
				if (key != null) {
					hasGeneratedKeys = true;
					generatedKey = key[0];
				}
				return count;
			}
			String plainSql = (String) args[0];
			boolean generatedKeys = args.length > 1 && isGeneratedKeysFlag(args[1]);
			long[] key = generatedKeys ? new long[1] : null;
			int count = connection.executeUpdate(plainSql, null, key);
			if (key != null) {
				hasGeneratedKeys = true;
				generatedKey = key[0];
			}
			return count;
		}

		private int[] executeBatch() throws SQLException {
			if (sql != null) {
				int[] counts = connection.executeBatch(sql, batchParams);
				batchParams.clear();
				return counts;
			}
			int[] counts = new int[batchSql.size()];
			for (int i = 0; i < counts.length; i++) {
				counts[i] = connection.executeUpdate(batchSql.get(i), null, null);
			}
			batchSql.clear();
			return counts;
		}

		private void setParameter(String name, Object[] args) {
			int index = ((Number) args[0]).intValue();
			Object value;
			if ("setNull".equals(name)) {
				value = null;
			} else if ("setObject".equals(name) || "setString".equals(name) || "setInt".equals(name)
					|| "setLong".equals(name) || "setFloat".equals(name) || "setDouble".equals(name)
					|| "setBoolean".equals(name) || "setShort".equals(name) || "setByte".equals(name)
					|| "setBytes".equals(name) || "setBigDecimal".equals(name)) {
				value = args[1];
			} else {
				// java.sql.Date/Time/Timestamp
				value = args[1] instanceof java.util.Date
						? Long.valueOf(((java.util.Date) args[1]).getTime()) : args[1];
			}
			if (index > params.length) {
				params = Arrays.copyOf(params, index);
			}
			params[index - 1] = value;
		}
	}

	// -------------------------------------------------------------- result set

	private static final class CursorResultSetHandler implements InvocationHandler {

		private final Cursor cursor;
		private final Object statementProxy;
		private boolean closed;
		private boolean wasNull;

		private CursorResultSetHandler(Cursor cursor, Object statementProxy) {
			this.cursor = cursor;
			this.statementProxy = statementProxy;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			if (isObjectMethod(method)) {
				if ("toString".equals(method.getName())) {
					return "SQLiteResultSet";
				}
				if ("hashCode".equals(method.getName())) {
					return Integer.valueOf(System.identityHashCode(proxy));
				}
				return Boolean.valueOf(proxy == (args != null && args.length > 0 ? args[0] : null));
			}
			final String name = method.getName();
			if ("close".equals(name)) {
				if (!closed) {
					closed = true;
					cursor.close();
				}
				return null;
			}
			if ("isClosed".equals(name)) {
				return Boolean.valueOf(closed);
			}
			if ("next".equals(name)) {
				return Boolean.valueOf(cursor.moveToNext());
			}
			if ("wasNull".equals(name)) {
				return Boolean.valueOf(wasNull);
			}
			if ("findColumn".equals(name)) {
				int index = cursor.getColumnIndex((String) args[0]);
				if (index < 0) {
					throw new SQLException("Unknown column: " + args[0]);
				}
				return Integer.valueOf(index + 1);
			}
			if ("getMetaData".equals(name)) {
				return Proxy.newProxyInstance(SqliteDatabase.class.getClassLoader(),
						new Class<?>[] { ResultSetMetaData.class },
						new ResultSetMetaDataHandler(cursor.getColumnNames()));
			}
			if ("getStatement".equals(name)) {
				return statementProxy;
			}
			if ("getType".equals(name)) {
				return Integer.valueOf(ResultSet.TYPE_FORWARD_ONLY);
			}
			if ("getConcurrency".equals(name)) {
				return Integer.valueOf(ResultSet.CONCUR_READ_ONLY);
			}
			if ("getHoldability".equals(name)) {
				return Integer.valueOf(ResultSet.HOLD_CURSORS_OVER_COMMIT);
			}
			if ("getFetchDirection".equals(name)) {
				return Integer.valueOf(ResultSet.FETCH_FORWARD);
			}
			if ("getFetchSize".equals(name)) {
				return Integer.valueOf(0);
			}
			if ("isBeforeFirst".equals(name)) {
				return Boolean.valueOf(cursor.getPosition() < 0 && cursor.getCount() > 0);
			}
			if ("isAfterLast".equals(name)) {
				return Boolean.valueOf(cursor.isAfterLast() && cursor.getCount() > 0);
			}
			if ("isFirst".equals(name)) {
				return Boolean.valueOf(cursor.getPosition() == 0);
			}
			if ("isLast".equals(name)) {
				return Boolean.valueOf(cursor.getPosition() == cursor.getCount() - 1);
			}
			if ("getRow".equals(name)) {
				return Integer.valueOf(cursor.getPosition() + 1);
			}
			if ("getWarnings".equals(name) || "getCursorName".equals(name)) {
				return null;
			}
			if ("clearWarnings".equals(name) || "setFetchDirection".equals(name)
					|| "setFetchSize".equals(name)) {
				return null;
			}
			if (name.startsWith("get")) {
				Class<?>[] parameterTypes = method.getParameterTypes();
				if (parameterTypes.length == 1
						&& (parameterTypes[0] == int.class || parameterTypes[0] == String.class)) {
					int index = columnIndex(args[0], parameterTypes[0]);
					return readColumn(name, index);
				}
			}
			return defaultValue(method.getReturnType());
		}

		private int columnIndex(Object arg, Class<?> type) throws SQLException {
			int index;
			if (type == String.class) {
				index = cursor.getColumnIndex((String) arg);
				if (index < 0) {
					throw new SQLException("Unknown column: " + arg);
				}
			} else {
				index = ((Number) arg).intValue() - 1;
				if (index < 0 || index >= cursor.getColumnCount()) {
					throw new SQLException("Column index out of range: " + (((Number) arg).intValue()));
				}
			}
			return index;
		}

		private Object readColumn(String name, int index) throws SQLException {
			try {
				final boolean isNull = cursor.isNull(index);
				wasNull = isNull;
				if ("getString".equals(name) || "getNString".equals(name)) {
					return isNull ? null : cursor.getString(index);
				}
				if ("getInt".equals(name)) {
					return Integer.valueOf(isNull ? 0 : cursor.getInt(index));
				}
				if ("getLong".equals(name)) {
					return Long.valueOf(isNull ? 0L : cursor.getLong(index));
				}
				if ("getShort".equals(name)) {
					return Short.valueOf(isNull ? (short) 0 : (short) cursor.getInt(index));
				}
				if ("getByte".equals(name)) {
					return Byte.valueOf(isNull ? (byte) 0 : (byte) cursor.getInt(index));
				}
				if ("getFloat".equals(name)) {
					return Float.valueOf(isNull ? 0f : cursor.getFloat(index));
				}
				if ("getDouble".equals(name)) {
					return Double.valueOf(isNull ? 0d : cursor.getDouble(index));
				}
				if ("getBoolean".equals(name)) {
					return Boolean.valueOf(!isNull && cursor.getInt(index) != 0);
				}
				if ("getBytes".equals(name)) {
					return isNull ? null : cursor.getBlob(index);
				}
				if ("getObject".equals(name)) {
					return isNull ? null : objectValue(index);
				}
				if ("getBigDecimal".equals(name)) {
					return isNull ? null : new java.math.BigDecimal(cursor.getString(index));
				}
				return isNull ? null : cursor.getString(index);
			} catch (RuntimeException e) {
				throw sqlException("Failed to read column " + (index + 1), e);
			}
		}

		private Object objectValue(int index) {
			switch (cursor.getType(index)) {
			case Cursor.FIELD_TYPE_INTEGER:
				long value = cursor.getLong(index);
				if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
					return Integer.valueOf((int) value);
				}
				return Long.valueOf(value);
			case Cursor.FIELD_TYPE_FLOAT:
				return Double.valueOf(cursor.getDouble(index));
			case Cursor.FIELD_TYPE_BLOB:
				return cursor.getBlob(index);
			case Cursor.FIELD_TYPE_NULL:
				return null;
			default:
				return cursor.getString(index);
			}
		}
	}

	// ----------------------------------------------------------- metadata stubs

	private static final class ResultSetMetaDataHandler implements InvocationHandler {

		private final String[] columnNames;

		private ResultSetMetaDataHandler(String[] columnNames) {
			this.columnNames = columnNames != null ? columnNames : new String[0];
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			if (isObjectMethod(method)) {
				return "toString".equals(method.getName()) ? "SQLiteResultSetMetaData"
						: defaultValue(method.getReturnType());
			}
			final String name = method.getName();
			if ("getColumnCount".equals(name)) {
				return Integer.valueOf(columnNames.length);
			}
			if ("getColumnName".equals(name) || "getColumnLabel".equals(name)) {
				int index = ((Number) args[0]).intValue() - 1;
				return index >= 0 && index < columnNames.length ? columnNames[index] : "";
			}
			if ("getColumnTypeName".equals(name) || "getColumnClassName".equals(name)
					|| "getSchemaName".equals(name) || "getTableName".equals(name)
					|| "getCatalogName".equals(name)) {
				return "";
			}
			if ("getColumnType".equals(name)) {
				return Integer.valueOf(Types.OTHER);
			}
			if ("getColumnDisplaySize".equals(name) || "getPrecision".equals(name)
					|| "getScale".equals(name)) {
				return Integer.valueOf(0);
			}
			if ("isNullable".equals(name)) {
				return Integer.valueOf(ResultSetMetaData.columnNullableUnknown);
			}
			if ("isAutoIncrement".equals(name) || "isCurrency".equals(name) || "isReadOnly".equals(name)
					|| "isWritable".equals(name) || "isDefinitelyWritable".equals(name)) {
				return Boolean.FALSE;
			}
			if ("isCaseSensitive".equals(name) || "isSearchable".equals(name) || "isSigned".equals(name)) {
				return Boolean.TRUE;
			}
			return defaultValue(method.getReturnType());
		}
	}

	private static final class DatabaseMetaDataHandler implements InvocationHandler {

		private final Object connectionProxy;
		private final String path;

		private DatabaseMetaDataHandler(Object connectionProxy, String path) {
			this.connectionProxy = connectionProxy;
			this.path = path;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			if (isObjectMethod(method)) {
				return "toString".equals(method.getName()) ? "SqliteDatabaseMetaData[" + path + "]"
						: defaultValue(method.getReturnType());
			}
			final String name = method.getName();
			if ("getConnection".equals(name)) {
				return connectionProxy;
			}
			if ("getURL".equals(name)) {
				return "jdbc:sqlite:" + path;
			}
			if ("getDatabaseProductName".equals(name)) {
				return "SQLite";
			}
			if ("getDatabaseProductVersion".equals(name)) {
				return "3";
			}
			if ("getDriverName".equals(name)) {
				return "SqliteDatabase";
			}
			if ("getDriverVersion".equals(name)) {
				return "1.0";
			}
			if ("getDatabaseMajorVersion".equals(name) || "getJDBCMajorVersion".equals(name)) {
				return Integer.valueOf(3);
			}
			if ("getDatabaseMinorVersion".equals(name) || "getJDBCMinorVersion".equals(name)) {
				return Integer.valueOf(0);
			}
			if ("getDefaultTransactionIsolation".equals(name)) {
				return Integer.valueOf(Connection.TRANSACTION_SERIALIZABLE);
			}
			if ("supportsTransactions".equals(name) || "supportsBatchUpdates".equals(name)
					|| "supportsPreparedStatementMetadata".equals(name)) {
				return Boolean.TRUE;
			}
			if ("getIdentifierQuoteString".equals(name)) {
				return "\"";
			}
			return defaultValue(method.getReturnType());
		}
	}
}
