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

    /**
     * Transaction en cours sur le fil d'execution.
     *
     * <p>Sans elle, chaque service ouvrirait sa propre transaction et un traitement composite —
     * un TFJ, une operation qui enchaine plusieurs services — ne serait atomique dans aucune de
     * ses parties. Elle rend aussi possible le TFJ a blanc : le traitement complet s'execute dans
     * une transaction annulee a la fin, donc par le meme chemin de code que le TFJ reel.
     */
    private static final ThreadLocal<Connection> CURRENT = new ThreadLocal<>();

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

    /**
     * Execute une unite de travail dans une transaction : tout ou rien.
     *
     * <p>Si une transaction est deja ouverte sur ce fil, l'unite <b>y participe</b> au lieu d'en
     * ouvrir une seconde : elle ne valide ni n'annule, et son sort est celui de la transaction
     * englobante. C'est ce qui rend un traitement composite reellement atomique.
     */
    public <T> T inTransaction(Function<Connection, T> work) {
        Connection existing = CURRENT.get();
        if (existing != null) {
            return work.apply(existing);
        }
        return inNewTransaction(work);
    }

    /**
     * Execute une unite de travail dans une transaction <b>independante</b>, meme si une
     * transaction est deja ouverte sur ce fil.
     *
     * <p>Reserve a ce qui doit survivre a l'annulation de l'englobante : la piste d'audit des
     * habilitations en est le cas type. Un refus survient avant l'operation et la transaction
     * metier est annulee ; si la trace la partageait, le systeme n'aurait aucune memoire des
     * tentatives refusees.
     */
    public <T> T inNewTransaction(Function<Connection, T> work) {
        Connection previous = CURRENT.get();
        try (Connection connection = dataSource.getConnection()) {
            CURRENT.set(connection);
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
        } finally {
            restore(previous);
        }
    }

    /**
     * Execute une unite de travail puis <b>annule systematiquement</b>, succes compris.
     *
     * <p>Support du TFJ a blanc. Le traitement emprunte exactement le chemin du TFJ reel —
     * memes controles, memes calculs, memes ecritures, memes contraintes de base — et ne laisse
     * rien. Un mode simulation qui court-circuiterait la comptabilisation ne prouverait rien :
     * il ne testerait pas ce qui casse en production.
     *
     * <p>Contrepartie a connaitre : l'ensemble tient dans une seule transaction, donc dans un seul
     * jeu de verrous. Sur un portefeuille entier, un TFJ a blanc est long et bloquant ; il se
     * lance sur un echantillon, une entite reduite, ou hors des heures de service.
     */
    public <T> T inRolledBackTransaction(Function<Connection, T> work) {
        Connection previous = CURRENT.get();
        try (Connection connection = dataSource.getConnection()) {
            CURRENT.set(connection);
            try {
                return work.apply(connection);
            } finally {
                connection.rollback();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Echec de la transaction simulee", e);
        } finally {
            restore(previous);
        }
    }

    private static void restore(Connection previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    /** Vrai si une transaction est ouverte sur ce fil d'execution. */
    public boolean inTransaction() {
        return CURRENT.get() != null;
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
