package io.corebanking.ledger.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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

    /**
     * Entite juridique dans laquelle le fil d'execution travaille, et celle que la transaction
     * courante a effectivement posee en base ({@code app.entity_id}).
     *
     * <p>La Row Level Security ne voit que ce que la transaction lui dit : sans entite posee, le
     * role applicatif ne voit aucune ligne. La portee est posee par la couche qui connait
     * l'appelant — l'API pour chaque requete, le moteur de fin de journee pour chaque
     * traitement — et jamais devinee ici. Une transaction ne change pas d'entite en cours de
     * route : la portee se pose avant de l'ouvrir, et une unite de travail qui rejoint une
     * transaction posee dans une autre entite est une erreur de programmation, refusee.
     */
    private static final ThreadLocal<UUID> ENTITY = new ThreadLocal<>();
    private static final ThreadLocal<UUID> APPLIED = new ThreadLocal<>();

    private final HikariDataSource dataSource;

    public Database(String jdbcUrl, String user, String password, int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setAutoCommit(false);
        config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        config.setPoolName("ledger-" + user);
        this.dataSource = new HikariDataSource(config);
    }

    public DataSource dataSource() {
        return dataSource;
    }

    // ------------------------------------------------------------------ portee d'entite

    /** Portee d'entite ouverte sur le fil d'execution ; sa fermeture restaure la precedente. */
    @FunctionalInterface
    public interface EntityScope extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Pose l'entite juridique dans laquelle travaillent les transactions ouvertes ensuite sur ce
     * fil d'execution, jusqu'a la fermeture de la portee.
     *
     * <p>Refuse de changer d'entite sous une transaction ouverte : la base a deja recu l'entite
     * de cette transaction, et une unite de travail qui croirait en voir une autre lirait ou
     * ecrirait a cote de ce qu'elle attend.
     */
    public static EntityScope enterEntity(UUID legalEntityId) {
        Objects.requireNonNull(legalEntityId, "legalEntityId");
        if (CURRENT.get() != null && !legalEntityId.equals(APPLIED.get())) {
            throw new IllegalStateException(
                "Changement d'entite sous une transaction ouverte : la transaction travaille "
                + describeApplied() + ", la portee demandee est " + legalEntityId
                + ". Une transaction ne change pas d'entite : la portee se pose avant de l'ouvrir.");
        }
        UUID previous = ENTITY.get();
        ENTITY.set(legalEntityId);
        return () -> {
            if (previous == null) {
                ENTITY.remove();
            } else {
                ENTITY.set(previous);
            }
        };
    }

    /** Entite posee sur ce fil d'execution, s'il y en a une. */
    public static Optional<UUID> currentEntity() {
        return Optional.ofNullable(ENTITY.get());
    }

    /** {@link #inTransaction(Function)} dans la portee d'une entite, posee pour la duree de l'appel. */
    public <T> T inEntity(UUID legalEntityId, Function<Connection, T> work) {
        try (EntityScope scope = enterEntity(legalEntityId)) {
            return inTransaction(work);
        }
    }

    // ------------------------------------------------------------------ transactions

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
            requireSameEntity();
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
        UUID previouslyApplied = APPLIED.get();
        try (Connection connection = dataSource.getConnection()) {
            CURRENT.set(connection);
            applyEntity(connection);
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
            restore(previous, previouslyApplied);
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
        UUID previouslyApplied = APPLIED.get();
        try (Connection connection = dataSource.getConnection()) {
            CURRENT.set(connection);
            applyEntity(connection);
            try {
                return work.apply(connection);
            } finally {
                connection.rollback();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Echec de la transaction simulee", e);
        } finally {
            restore(previous, previouslyApplied);
        }
    }

    /**
     * Transmet a la transaction l'entite posee sur le fil, si elle l'est. Le reglage est local a
     * la transaction : il tombe avec elle, valide ou annulee, et ne peut pas fuir vers la
     * connexion suivante du pool.
     */
    private static void applyEntity(Connection connection) throws SQLException {
        UUID entity = ENTITY.get();
        if (entity == null) {
            APPLIED.remove();
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                 "SELECT set_config('app.entity_id', ?, true)")) {
            ps.setString(1, entity.toString());
            ps.execute();
        }
        APPLIED.set(entity);
    }

    private static void requireSameEntity() {
        UUID wanted = ENTITY.get();
        if (wanted != null && !wanted.equals(APPLIED.get())) {
            throw new IllegalStateException(
                "La transaction ouverte travaille " + describeApplied()
                + " ; l'unite de travail attend l'entite " + wanted + ".");
        }
    }

    private static String describeApplied() {
        UUID applied = APPLIED.get();
        return applied == null ? "sans entite" : "dans l'entite " + applied;
    }

    private static void restore(Connection previous, UUID previouslyApplied) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
        if (previouslyApplied == null) {
            APPLIED.remove();
        } else {
            APPLIED.set(previouslyApplied);
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
