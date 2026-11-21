package bms.player.beatoraja.database;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Returns the first row as a bean, or {@code null} when the result set is empty.
 *
 * <p>Drop-in replacement for {@code org.apache.commons.dbutils.handlers.BeanHandler}.</p>
 */
public class BeanHandler<T> implements ResultSetHandler<T> {

	private final Class<T> type;

	public BeanHandler(Class<T> type) {
		this.type = type;
	}

	@Override
	public T handle(ResultSet rs) throws SQLException {
		return rs.next() ? PropertyMapper.mapRow(rs, type) : null;
	}
}
