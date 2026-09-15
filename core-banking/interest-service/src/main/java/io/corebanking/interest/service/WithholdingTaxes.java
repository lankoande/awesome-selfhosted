package io.corebanking.interest.service;

import io.corebanking.kernel.id.Ids;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.ledger.store.LedgerStoreException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Retenues a la source sur interets crediteurs : par entite, donc par pays, et datees.
 *
 * <p>Le cadre comptable est regional, la fiscalite est nationale. Le taux est declare par
 * l'entite juridique avec sa periode de validite ; deux periodes ne se chevauchent pas, la base
 * le refuse. Le produit ne porte que le code de la retenue — ou aucun s'il en est exonere — et
 * c'est a la date de fin de periode reglee que le taux est resolu, jamais a la date du jour : une
 * loi de finances au premier janvier ne change pas la capitalisation de decembre.
 */
public final class WithholdingTaxes {

    private WithholdingTaxes() {}

    public static UUID declare(Connection c, UUID legalEntityId, String code, BigDecimal ratePercent,
                               UUID payableAccountId, LocalDate validFrom, LocalDate validTo,
                               UUID actorId) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Code de retenue obligatoire");
        }
        if (ratePercent == null || ratePercent.signum() < 0
            || ratePercent.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("Taux de retenue hors de [0, 100] : " + ratePercent);
        }
        requirePayableAccount(c, legalEntityId, code, payableAccountId);

        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO interest_withholding(id, legal_entity_id, code, rate_percent,"
            + " payable_account_id, valid_from, valid_to, created_by) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, legalEntityId);
            ps.setString(3, code);
            ps.setBigDecimal(4, ratePercent);
            ps.setObject(5, payableAccountId);
            ps.setObject(6, validFrom);
            ps.setObject(7, validTo);
            ps.setObject(8, actorId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException(
                "Declaration de la retenue " + code + " refusee : deux periodes de validite ne "
                + "peuvent pas se chevaucher", e);
        }
        return id;
    }

    private static void requirePayableAccount(Connection c, UUID legalEntityId, String code,
                                              UUID accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException(
                "Retenue " + code + " sans compte de reversement : la retenue resterait dans le "
                + "produit de la banque.");
        }
        Map<UUID, Account> found = Accounts.loadAll(c, Set.of(accountId));
        Account account = found.get(accountId);
        if (account == null) {
            throw new IllegalArgumentException(
                "Retenue " + code + " : le compte de reversement " + accountId + " n'existe pas.");
        }
        if (!account.legalEntityId().equals(legalEntityId)) {
            throw new IllegalArgumentException(
                "Retenue " + code + " : le compte de reversement appartient a une autre entite.");
        }
        if (account.kind() != AccountKind.GL) {
            throw new IllegalArgumentException(
                "Retenue " + code + " : le compte de reversement doit etre un compte general.");
        }
    }

    /** Retenue en vigueur a une date, pour une entite et un code. */
    public static Optional<Withholding> rateAt(Connection c, UUID legalEntityId, String code,
                                               LocalDate date) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT code, rate_percent, payable_account_id, valid_from, valid_to"
            + "  FROM interest_withholding"
            + " WHERE legal_entity_id = ? AND code = ? AND valid_from <= ?"
            + "   AND (valid_to IS NULL OR valid_to >= ?)")) {
            ps.setObject(1, legalEntityId);
            ps.setString(2, code);
            ps.setObject(3, date);
            ps.setObject(4, date);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Withholding(rs.getString(1), rs.getBigDecimal(2),
                                                   rs.getObject(3, UUID.class),
                                                   rs.getObject(4, LocalDate.class),
                                                   rs.getObject(5, LocalDate.class)));
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de la retenue " + code, e);
        }
    }
}
