package bms.player.beatoraja.database;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Transforms a {@link ResultSet} into a result object.
 *
 * <p>Drop-in replacement for {@code org.apache.commons.dbutils.ResultSetHandler}.</p>
 *
 * @param <T> the result type
 */
@FunctionalInterface
public interface ResultSetHandler<T> {

	/**
	 * Turns the (positioned before the first row) result set into a result object.
	 */
	T handle(ResultSet rs) throws SQLException;
}
