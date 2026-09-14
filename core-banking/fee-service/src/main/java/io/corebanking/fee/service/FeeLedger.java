package io.corebanking.fee.service;

import io.corebanking.fee.FeePeriod;
import io.corebanking.kernel.id.Ids;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.store.LedgerStoreException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Acces au registre des commissions liquidees et des exonerations. */
public final class FeeLedger {

    /** Couple compte et commission : la cle de toutes les lectures du module. */
    public record Key(UUID accountId, String feeCode) {}

    /** Fenetre d'exoneration accordee a un compte pour une commission. */
    public record Exemption(LocalDate from, LocalDate to) {}

    private FeeLedger() {}

    // ------------------------------------------------------------------ lectures

    /**
     * Derniere fin de periode deja liquidee, par couple compte et commission.
     *
     * <p>Les periodes annulees sont exclues : l'annulation d'un TFJ doit rendre ses periodes a
     * nouveau exigibles, sans quoi une journee annulee ferait perdre definitivement les
     * commissions qu'elle portait.
     */
    public static Map<Key, LocalDate> lastChargedEnd(Connection c, Collection<UUID> accountIds) {
        Map<Key, LocalDate> last = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT account_id, fee_code, MAX(period_end) FROM fee_charge"
            + " WHERE account_id = ANY (?) AND outcome <> 'CANCELLED'"
            + " GROUP BY account_id, fee_code")) {
            ps.setArray(1, uuidArray(c, accountIds));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    last.put(new Key(rs.getObject(1, UUID.class), rs.getString(2)),
                             rs.getObject(3, LocalDate.class));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des periodes deja liquidees", e);
        }
        return last;
    }

    /**
     * Nombre de facturations annulees par periode, pour un lot de comptes.
     *
     * <p>Sert a construire la cle d'idempotence de la refacturation. Une periode dont la
     * facturation a ete annulee redevient exigible, mais l'ecriture d'origine subsiste au journal,
     * contre-passee : la reutiliser comme cle ferait passer la nouvelle facturation pour un rejeu.
     */
    public static Map<Key, Integer> cancelledGenerations(Connection c, Collection<UUID> accountIds) {
        Map<Key, Integer> generations = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT account_id, fee_code, period_end, count(*) FROM fee_charge"
            + " WHERE account_id = ANY (?) AND outcome = 'CANCELLED'"
            + " GROUP BY account_id, fee_code, period_end")) {
            ps.setArray(1, uuidArray(c, accountIds));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    generations.put(new Key(rs.getObject(1, UUID.class),
                                            rs.getString(2) + "@" + rs.getObject(3, LocalDate.class)),
                                    rs.getInt(4));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des facturations annulees", e);
        }
        return generations;
    }

    /** Cle de generation : la commission et la periode, le compte etant deja porte par la cle. */
    public static Key generationKey(UUID accountId, String feeCode, LocalDate periodEnd) {
        return new Key(accountId, feeCode + "@" + periodEnd);
    }

    /** Exonerations en vigueur ou a venir, par couple compte et commission. */
    public static Map<Key, List<Exemption>> exemptions(Connection c, Collection<UUID> accountIds) {
        Map<Key, List<Exemption>> exemptions = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT account_id, fee_code, valid_from, valid_to FROM account_fee_exemption"
            + " WHERE account_id = ANY (?) ORDER BY account_id, fee_code, valid_from")) {
            ps.setArray(1, uuidArray(c, accountIds));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    exemptions.computeIfAbsent(
                        new Key(rs.getObject(1, UUID.class), rs.getString(2)),
                        key -> new ArrayList<>())
                        .add(new Exemption(rs.getObject(3, LocalDate.class),
                                           rs.getObject(4, LocalDate.class)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des exonerations", e);
        }
        return exemptions;
    }

    /** Commissions reportees, de la plus ancienne a la plus recente : une dette se solde dans l'ordre. */
    public static List<FeeCharge> arrears(Connection c, Collection<UUID> accountIds,
                                          Map<UUID, CurrencyRef> currencies) {
        List<FeeCharge> arrears = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            SELECT_CHARGE + " WHERE account_id = ANY (?) AND outcome = 'DEFERRED'"
            + " ORDER BY charge_date, account_id, fee_code")) {
            ps.setArray(1, uuidArray(c, accountIds));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    arrears.add(read(rs, currencies));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des commissions reportees", e);
        }
        return arrears;
    }

    // ------------------------------------------------------------------ ecritures

    public static UUID record(Connection c, FeeCharge charge) {
        UUID id = charge.id() == null ? Ids.newId() : charge.id();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO fee_charge(id, legal_entity_id, account_id, fee_code, period_index,"
            + " period_start, period_end, charge_date, basis_amount, gross_amount, net_amount,"
            + " tax_amount, total_amount, tax_rate_percent, charged_days, period_days, outcome,"
            + " entry_id, batch_run_id, attempts, generation, settled_on)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, charge.legalEntityId());
            ps.setObject(3, charge.accountId());
            ps.setString(4, charge.feeCode());
            ps.setInt(5, charge.period().index());
            ps.setObject(6, charge.period().start());
            ps.setObject(7, charge.period().end());
            ps.setObject(8, charge.chargeDate());
            ps.setBigDecimal(9, charge.basisAmount().amount());
            ps.setBigDecimal(10, charge.gross().amount());
            ps.setBigDecimal(11, charge.net().amount());
            ps.setBigDecimal(12, charge.tax().amount());
            ps.setBigDecimal(13, charge.total().amount());
            ps.setBigDecimal(14, charge.taxRatePercent());
            ps.setInt(15, charge.chargedDays());
            ps.setInt(16, charge.period().days());
            ps.setString(17, charge.outcome().name());
            ps.setObject(18, charge.entryId());
            ps.setObject(19, charge.batchRunId());
            ps.setInt(20, charge.attempts());
            ps.setInt(21, charge.generation());
            ps.setObject(22, charge.settledOn());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException(
                "Enregistrement de la commission " + charge.feeCode() + " du compte "
                + charge.accountId() + " pour " + charge.period(), e);
        }
        return id;
    }

    /** Fait evoluer le denouement d'une commission reportee. Les montants restent figes. */
    public static void settle(Connection c, FeeCharge charge) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE fee_charge SET outcome = ?, entry_id = ?, attempts = ?, settled_on = ?"
            + " WHERE id = ? AND outcome = 'DEFERRED'")) {
            ps.setString(1, charge.outcome().name());
            ps.setObject(2, charge.entryId());
            ps.setInt(3, charge.attempts());
            ps.setObject(4, charge.settledOn());
            ps.setObject(5, charge.id());
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException(
                    "Commission " + charge.id() + " introuvable ou deja denouee : le denouement "
                    + "d'une commission ne se rejoue pas.");
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Denouement de la commission " + charge.id(), e);
        }
    }

    /**
     * Neutralise les liquidations produites par un traitement annule.
     *
     * <p>Sans cela, l'annulation d'un TFJ contre-passerait ses ecritures tout en laissant les
     * periodes marquees comme facturees : les commissions de la journee seraient definitivement
     * perdues, et l'ecart ne se verrait nulle part.
     */
    public static int cancelRun(Connection c, UUID batchRunId) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE fee_charge SET outcome = 'CANCELLED' WHERE batch_run_id = ?"
            + " AND outcome <> 'CANCELLED'")) {
            ps.setObject(1, batchRunId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Annulation des commissions du traitement " + batchRunId, e);
        }
    }

    /** Accorde une exoneration. Le valideur ne peut pas etre celui qui l'accorde. */
    public static UUID grantExemption(Connection c, UUID accountId, String feeCode, LocalDate from,
                                      LocalDate to, String reason, UUID grantedBy, UUID approvedBy) {
        UUID id = Ids.newId();
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO account_fee_exemption(id, account_id, fee_code, valid_from, valid_to,"
            + " reason, granted_by, approved_by) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, accountId);
            ps.setString(3, feeCode);
            ps.setObject(4, from);
            ps.setObject(5, to);
            ps.setString(6, reason);
            ps.setObject(7, grantedBy);
            ps.setObject(8, approvedBy);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException(
                "Exoneration de la commission " + feeCode + " pour le compte " + accountId, e);
        }
        return id;
    }

    // ------------------------------------------------------------------ interne

    private static final String SELECT_CHARGE =
        "SELECT id, legal_entity_id, account_id, fee_code, period_index, period_start, period_end,"
        + " charge_date, basis_amount, gross_amount, net_amount, tax_amount, total_amount,"
        + " tax_rate_percent, charged_days, outcome, entry_id, batch_run_id, attempts, generation,"
        + " settled_on FROM fee_charge";

    private static FeeCharge read(ResultSet rs, Map<UUID, CurrencyRef> currencies)
        throws SQLException {
        UUID accountId = rs.getObject(3, UUID.class);
        CurrencyRef currency = currencies.get(accountId);
        if (currency == null) {
            throw new LedgerStoreException("Devise inconnue pour le compte " + accountId);
        }
        return new FeeCharge(
            rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), accountId, rs.getString(4),
            new FeePeriod(rs.getInt(5), rs.getObject(6, LocalDate.class),
                          rs.getObject(7, LocalDate.class)),
            rs.getObject(8, LocalDate.class),
            Money.of(rs.getBigDecimal(9), currency), Money.of(rs.getBigDecimal(10), currency),
            Money.of(rs.getBigDecimal(11), currency), Money.of(rs.getBigDecimal(12), currency),
            Money.of(rs.getBigDecimal(13), currency), rs.getBigDecimal(14), rs.getInt(15),
            FeeOutcome.valueOf(rs.getString(16)), rs.getObject(17, UUID.class),
            rs.getObject(18, UUID.class), rs.getInt(19), rs.getInt(20),
            rs.getObject(21, LocalDate.class));
    }

    static Array uuidArray(Connection c, Collection<UUID> ids) throws SQLException {
        return c.createArrayOf("uuid", ids.toArray());
    }
}
