package bms.player.beatoraja.database;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Returns every row as a bean list (possibly empty).
 *
 * <p>Drop-in replacement for {@code org.apache.commons.dbutils.handlers.BeanListHandler}.</p>
 */
public class BeanListHandler<T> implements ResultSetHandler<List<T>> {

	private final Class<T> type;

	public BeanListHandler(Class<T> type) {
		this.type = type;
	}

	@Override
	public List<T> handle(ResultSet rs) throws SQLException {
		List<T> result = new ArrayList<>();
		while (rs.next()) {
			result.add(PropertyMapper.mapRow(rs, type));
		}
		return result;
	}
}
