package io.corebanking.security.store;

import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.security.AccessDecision;
import io.corebanking.security.AccessTarget;
import io.corebanking.security.AuthorizationAudit;
import io.corebanking.security.Caller;
import io.corebanking.security.Operation;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Ecriture de la piste d'audit des habilitations.
 *
 * <h2>Deux choix a expliciter</h2>
 *
 * <p><b>La trace est ecrite dans sa propre transaction.</b> Un refus survient avant l'operation, et
 * la transaction metier est annulee ; si la trace partageait cette transaction, elle disparaitrait
 * avec elle. Autrement dit, le systeme n'aurait aucune memoire des tentatives refusees — exactement
 * ce qu'il faut conserver.
 *
 * <p><b>Un echec d'ecriture de la trace interrompt l'operation.</b> C'est deliberement le contraire
 * de l'usage courant en journalisation applicative. Une operation bancaire qui s'execute sans
 * pouvoir etre tracee est une operation dont personne ne pourra jamais rendre compte ; en cas de
 * defaillance de la base d'audit, le refus de servir est la bonne reponse.
 */
public final class JdbcAuthorizationAudit implements AuthorizationAudit {

    private final Database database;

    public JdbcAuthorizationAudit(Database database) {
        this.database = database;
    }

    @Override
    public void record(Caller caller, Operation operation, AccessTarget target,
                       AccessDecision decision) {
        database.inNewTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO authorization_audit(subject_id, username, roles, caller_entity_id,"
                + " caller_branch_id, operation, allowed, reason, target_entity_id,"
                + " target_branch_id, amount, currency, owner_subject_id)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, caller.subjectId());
                ps.setString(2, caller.username());
                ps.setString(3, String.join(",", caller.roles().stream().sorted().toList()));
                ps.setObject(4, caller.legalEntityId());
                ps.setObject(5, caller.branchId());
                ps.setString(6, operation.name());
                ps.setBoolean(7, decision.allowed());
                ps.setString(8, decision.reason());
                ps.setObject(9, target.legalEntityId());
                ps.setObject(10, target.branchId());
                ps.setBigDecimal(11, target.amount() == null ? null : target.amount().amount());
                ps.setString(12, target.amount() == null ? null
                                                         : target.amount().currency().code());
                ps.setString(13, target.ownerSubjectId());
                ps.executeUpdate();
                return null;
            } catch (SQLException e) {
                throw new LedgerStoreException(
                    "Ecriture de la piste d'audit des habilitations impossible : l'operation est "
                    + "interrompue. Une operation bancaire non tracable ne doit pas s'executer.", e);
            }
        });
    }

    /** Nombre de refus enregistres pour un porteur, pour la detection de comportement anormal. */
    public long deniedCountFor(String subjectId) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT count(*) FROM authorization_audit WHERE subject_id = ? AND NOT allowed")) {
                ps.setString(1, subjectId);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Comptage des refus", e);
            }
        });
    }

    /** Nombre de consultations tracees d'un porteur sur une operation donnee. */
    public long readCountFor(String subjectId, Operation operation) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT count(*) FROM authorization_audit"
                + " WHERE subject_id = ? AND operation = ? AND allowed")) {
                ps.setString(1, subjectId);
                ps.setString(2, operation.name());
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Comptage des consultations", e);
            }
        });
    }

}
