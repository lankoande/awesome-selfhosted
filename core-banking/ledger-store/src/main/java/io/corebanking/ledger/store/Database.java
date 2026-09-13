package io.corebanking.ledger.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Function;
import javax.sql.DataSource;

/**
 * Acces a la base et gestion des transactions.
 *
 * <p>Le ledger n'utilise pas de mapping objet-relationnel. Le cache de premier niveau, le flush
 * implicite et le chargement paresseux d'un ORM rendent imprevisible le moment exact ou une
 * instruction atteint la base — sur le chemin de comptabilisation, cette imprevisibilite se paie
 * en interblocages et en verrous tenus trop longtemps. Les instructions sont donc ecrites a la
 * main et leur ordre est explicite.
 */
public final class Database implements AutoCloseable {

    private final HikariDataSource dataSource;

    public Database(String jdbcUrl, String user, String password, int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setAutoCommit(false);
        config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        config.setPoolName("ledger");
        this.dataSource = new HikariDataSource(config);
    }

    public DataSource dataSource() {
        return dataSource;
    }

    /** Execute une unite de travail dans une transaction : tout ou rien. */
    public <T> T inTransaction(Function<Connection, T> work) {
        try (Connection connection = dataSource.getConnection()) {
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (RuntimeException | Error e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Echec de la transaction", e);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
