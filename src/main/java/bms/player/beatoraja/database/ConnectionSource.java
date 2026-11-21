package bms.player.beatoraja.database;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Supplies JDBC connections to a {@link QueryRunner}.
 *
 * <p>Minimal replacement for {@code javax.sql.DataSource}: the runtime ships no JDBC DataSource, so
 * the database layer only depends on {@code java.sql} plus this small supplier. Implementations are
 * expected to return a new connection for every call.</p>
 */
@FunctionalInterface
public interface ConnectionSource {

	/**
	 * Opens a new connection. The caller is responsible for closing it.
	 */
	Connection getConnection() throws SQLException;
}
