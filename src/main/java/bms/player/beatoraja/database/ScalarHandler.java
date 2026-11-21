package bms.player.beatoraja.database;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Returns the first column of the first row, or {@code null} when the result set is empty.
 *
 * <p>Drop-in replacement for {@code org.apache.commons.dbutils.handlers.ScalarHandler}. The value is
 * the raw {@link ResultSet#getObject(int)} result, so {@code SELECT count(*)} yields whatever
 * numeric type the driver reports (xerial returns Integer/Long, callers use {@link Number}).</p>
 */
public class ScalarHandler<T> implements ResultSetHandler<T> {

	private final int columnIndex;
	private final String columnName;

	public ScalarHandler() {
		this(1, null);
	}

	public ScalarHandler(int columnIndex) {
		this(columnIndex, null);
	}

	public ScalarHandler(String columnName) {
		this(1, columnName);
	}

	private ScalarHandler(int columnIndex, String columnName) {
		this.columnIndex = columnIndex;
		this.columnName = columnName;
	}

	@Override
	@SuppressWarnings("unchecked")
	public T handle(ResultSet rs) throws SQLException {
		if (!rs.next()) {
			return null;
		}
		return (T) (columnName == null ? rs.getObject(columnIndex) : rs.getObject(columnName));
	}
}
