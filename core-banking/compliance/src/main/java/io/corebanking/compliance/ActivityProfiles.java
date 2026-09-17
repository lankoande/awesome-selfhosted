package io.corebanking.compliance;

import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.store.LedgerStoreException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Profil d'activite declare : ce que le client a annonce, et contre quoi l'atypie se mesure.
 *
 * <p>Sans lui, « incoherent avec le profil declare » n'a pas de sens : il faudrait comparer les
 * flux d'un client a ceux d'un autre, ce qui reviendrait a suspecter les gros comptes d'etre gros.
 * Le declarer est un acte d'agence, au moment de la connaissance client ; le relire est un acte de
 * conformite.
 */
public final class ActivityProfiles {

    private ActivityProfiles() {}

    public record Profile(UUID partyId, UUID legalEntityId, Money expectedMonthlyCredit,
                          Money expectedMonthlyDebit, LocalDate declaredOn, UUID declaredBy) {}

    /** Declare ou met a jour le profil : la derniere declaration remplace la precedente. */
    public static void declare(Connection c, UUID partyId, Money expectedMonthlyCredit,
                               Money expectedMonthlyDebit, LocalDate on, UUID declaredBy) {
        if (expectedMonthlyCredit == null || expectedMonthlyDebit == null
            || expectedMonthlyCredit.isNegative() || expectedMonthlyDebit.isNegative()) {
            throw new IllegalArgumentException("Un profil declare porte ses deux flux mensuels, "
                + "positifs ou nuls");
        }
        if (!expectedMonthlyCredit.currency().equals(expectedMonthlyDebit.currency())) {
            throw new IllegalArgumentException("Les deux flux du profil sont dans la meme devise");
        }
        UUID entity = ComplianceDates.entityOf(c, partyId);
        // Le profil se compare a des flux comptabilises en devise de tenue de compte. Dans une
        // autre devise, la comparaison serait une soustraction de deux nombres sans rapport : le
        // scenario ne se declencherait jamais, ou toujours, et personne ne saurait pourquoi.
        String functional = functionalCurrency(c, entity);
        if (!functional.equals(expectedMonthlyCredit.currency().code())) {
            throw new IllegalArgumentException(
                "Le profil d'activite se declare en devise de tenue de compte (" + functional
                + ") : c'est la devise des flux auxquels il sera compare. Declare en "
                + expectedMonthlyCredit.currency().code() + ", il serait compare a des montants "
                + "qui n'ont pas la meme unite.");
        }
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO party_activity_profile(party_id, legal_entity_id,"
            + " expected_monthly_credit, expected_monthly_debit, currency, declared_on,"
            + " declared_by) VALUES (?,?,?,?,?,?,?)"
            + " ON CONFLICT (party_id) DO UPDATE SET"
            + "   expected_monthly_credit = EXCLUDED.expected_monthly_credit,"
            + "   expected_monthly_debit = EXCLUDED.expected_monthly_debit,"
            + "   currency = EXCLUDED.currency, declared_on = EXCLUDED.declared_on,"
            + "   declared_by = EXCLUDED.declared_by, updated_at = now()")) {
            ps.setObject(1, partyId);
            ps.setObject(2, entity);
            ps.setBigDecimal(3, expectedMonthlyCredit.amount());
            ps.setBigDecimal(4, expectedMonthlyDebit.amount());
            ps.setString(5, expectedMonthlyCredit.currency().code());
            ps.setObject(6, on);
            ps.setObject(7, declaredBy);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Declaration du profil d'activite", e);
        }
    }

    private static String functionalCurrency(Connection c, UUID legalEntityId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT functional_currency FROM legal_entity WHERE id = ?")) {
            ps.setObject(1, legalEntityId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Entite inconnue : " + legalEntityId);
                }
                return rs.getString(1);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Devise de tenue de l'entite", e);
        }
    }

    public static Optional<Profile> find(Connection c, UUID partyId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT f.party_id, f.legal_entity_id, f.expected_monthly_credit,"
            + " f.expected_monthly_debit, f.currency, f.declared_on, f.declared_by, cur.scale,"
            + " cur.rounding_mode FROM party_activity_profile f"
            + "  JOIN currency cur ON cur.code = f.currency WHERE f.party_id = ?")) {
            ps.setObject(1, partyId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                io.corebanking.kernel.money.CurrencyRef currency =
                    new io.corebanking.kernel.money.CurrencyRef(rs.getString(5), rs.getInt(8),
                        java.math.RoundingMode.valueOf(rs.getString(9)));
                return Optional.of(new Profile(rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    Money.of(rs.getBigDecimal(3), currency).roundToCurrency(),
                    Money.of(rs.getBigDecimal(4), currency).roundToCurrency(),
                    rs.getObject(6, LocalDate.class), rs.getObject(7, UUID.class)));
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Profil d'activite du tiers " + partyId, e);
        }
    }
}
