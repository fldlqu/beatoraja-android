package bms.player.beatoraja.database;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Returns every row as a column-label to value map, in column order.
 *
 * <p>Drop-in replacement for {@code org.apache.commons.dbutils.handlers.MapListHandler}; values are
 * the raw {@link ResultSet#getObject(int)} results so callers that cast to Integer/Long keep the
 * database driver's behaviour.</p>
 */
public class MapListHandler implements ResultSetHandler<List<Map<String, Object>>> {

	@Override
	public List<Map<String, Object>> handle(ResultSet rs) throws SQLException {
		ResultSetMetaData metaData = rs.getMetaData();
		int columnCount = metaData.getColumnCount();
		List<Map<String, Object>> rows = new ArrayList<>();
		while (rs.next()) {
			Map<String, Object> row = new LinkedHashMap<>(columnCount * 2);
			for (int i = 1; i <= columnCount; i++) {
				String label = metaData.getColumnLabel(i);
				if (label == null || label.isEmpty()) {
					label = metaData.getColumnName(i);
				}
				row.put(label, rs.getObject(i));
			}
			rows.add(row);
		}
		return rows;
	}
}
