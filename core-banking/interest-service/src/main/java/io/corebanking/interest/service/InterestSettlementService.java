package io.corebanking.interest.service;

import io.corebanking.interest.accrual.AccrualSide;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.id.Ids;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.domain.posting.PostingSource;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.LedgerStoreException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reglement des interets courus : capitalisation au client, arrete des agios.
 *
 * <h2>Ce qui est regle, et jusqu'a quand</h2>
 *
 * <p>A chaque fin de periode civile prevue par le produit, les interets courus depuis le dernier
 * reglement sont repris du compte de courus et regles au client : credites, net de retenue a la
 * source, pour un compte remunere ; debites, taxe comprise, pour un compte a decouvert. Le montant
 * regle est le cumul exact arrondi <b>a la fin de periode</b>, moins ce qui a deja ete regle —
 * meme lorsque l'arrete tourne plusieurs jours apres, un trimestre se terminant un samedi. Le
 * moteur du cumul arrondi rend cette lecture exacte : le cumul de n'importe quelle journee est
 * connu, et son arrondi est ce que le client doit recevoir a cette journee.
 *
 * <h2>Date de valeur</h2>
 *
 * <p>Les interets regles portent date de valeur du lendemain de la fin de periode. Les interets de
 * la fin de periode elle-meme ont ete calcules sur le solde <b>avant</b> reglement ; dater le
 * reglement du meme jour ferait remunerer, a tout recalcul, un solde que le compte n'avait pas.
 * Le lendemain, la capitalisation produit des interets : c'est ce que capitaliser veut dire.
 *
 * <h2>Position et journal</h2>
 *
 * <p>Chaque reglement est enregistre et porte dans la position du compte : ce que le sous-livre
 * affirme se trouver au compte de courus est {@code impute - regle}, et la reconciliation le
 * verifie chaque nuit. Un recalcul retroactif posterieur ne remet pas en cause ce que le client a
 * recu ; l'ecart se retrouve dans la position et le reglement suivant le regularise, en plus ou
 * en moins.
 */
public final class InterestSettlementService {

    public static final String TYPE_CAPITALISATION = "INTEREST_CAPITALISATION";
    public static final String TYPE_OVERDRAFT_CHARGE = "OVERDRAFT_INTEREST_CHARGE";

    private final Database database;
    private final io.corebanking.ledger.domain.posting.PostingService postingService;

    public InterestSettlementService(Database database,
                                     io.corebanking.ledger.domain.posting.PostingService posting) {
        this.database = database;
        this.postingService = posting;
    }

    /** Compte rendu d'une passe de reglement. */
    public record Outcome(int examined, int settled, Money grossTotal, List<UUID> entries,
                          List<String> anomalies) {
        public Outcome {
            entries = List.copyOf(entries);
            anomalies = List.copyOf(anomalies);
        }
    }

    /** Reglement d'un compte, tel qu'il a ete effectue. */
    public record Settlement(UUID accountId, AccrualSide side, LocalDate periodEnd, Money gross,
                             Money withholding, Money tax, Money net, UUID entryId) {}

    /** Reglement prepare, pas encore comptabilise. */
    private record Planned(UUID accountId, AccrualSide side, LocalDate periodEnd, Money gross,
                           String withholdingCode, BigDecimal withholdingRate, Money withholding,
                           UUID withholdingAccount, BigDecimal taxRate, Money tax, UUID taxAccount,
                           Money net, InterestTerms terms) {}

    /**
     * Regle les comptes du lot dont une fin de periode est atteinte et pas encore reglee.
     *
     * @param through journee de valeur arretee ; toute fin de periode a cette date ou avant, non
     *                encore reglee, l'est maintenant
     */
    public Outcome settle(UUID legalEntityId, List<UUID> accountIds, LocalDate through,
                          TermsProvider terms, LocalDate bookingDate, UUID actorId,
                          UUID batchRunId) {
        List<String> anomalies = new ArrayList<>();
        List<UUID> entries = new ArrayList<>();
        int settled = 0;
        Money grossTotal = null;

        for (UUID accountId : accountIds) {
            try {
                Optional<Settlement> done = settleOne(legalEntityId, accountId, through, terms,
                                                     bookingDate, actorId, batchRunId);
                if (done.isPresent()) {
                    settled++;
                    grossTotal = grossTotal == null ? done.get().gross()
                                                    : grossTotal.plus(done.get().gross());
                    if (done.get().entryId() != null) {
                        entries.add(done.get().entryId());
                    }
                }
            } catch (RuntimeException e) {
                anomalies.add("Compte " + accountId + " non regle : " + e.getMessage());
            }
        }
        return new Outcome(accountIds.size(), settled, grossTotal, entries, anomalies);
    }

    /** Regle un compte, s'il y a lieu. */
    public Optional<Settlement> settleOne(UUID legalEntityId, UUID accountId, LocalDate through,
                                          TermsProvider terms, LocalDate bookingDate, UUID actorId,
                                          UUID batchRunId) {
        return settleOne(legalEntityId, accountId, through, terms.termsFor(accountId, through),
                         bookingDate, actorId, batchRunId);
    }

    /** Regle un compte pour des conditions deja resolues, s'il y a lieu. */
    public Optional<Settlement> settleOne(UUID legalEntityId, UUID accountId, LocalDate through,
                                          InterestTerms conditions, LocalDate bookingDate,
                                          UUID actorId, UUID batchRunId) {
        SettlementTerms settlement = conditions.settlement();
        if (settlement == null) {
            return Optional.empty();
        }
        LocalDate periodEnd = SettlementCalendar.lastPeriodEndOnOrBefore(settlement.periodicity(),
                                                                         through);

        Optional<Planned> planned = database.inTransaction(
            c -> plan(c, legalEntityId, accountId, conditions, periodEnd));
        if (planned.isEmpty()) {
            return Optional.empty();
        }
        Planned p = planned.get();

        UUID entryId = p.gross().isZero()
            ? null
            : post(legalEntityId, p, bookingDate, actorId, batchRunId);

        database.inTransaction(c -> {
            record(c, p, entryId, bookingDate, batchRunId);
            InterestPositions.recordSettlement(c, accountId, p.side(), p.gross(), periodEnd);
            return null;
        });
        return Optional.of(new Settlement(accountId, p.side(), periodEnd, p.gross(),
                                          p.withholding(), p.tax(), p.net(), entryId));
    }

    // ------------------------------------------------------------------ preparation

    private Optional<Planned> plan(Connection c, UUID legalEntityId, UUID accountId,
                                   InterestTerms terms, LocalDate periodEnd) {
        CurrencyRef currency = InterestPositions.currencyOf(c, accountId);
        InterestPositions.Position position = InterestPositions.load(c, accountId, terms.side(),
                                                                     currency);
        if (position.settledThrough() != null && !periodEnd.isAfter(position.settledThrough())) {
            return Optional.empty();                       // periode deja reglee
        }
        if (alreadyRecorded(c, accountId, terms.side(), periodEnd)) {
            return Optional.empty();                       // reprise : reglement deja enregistre
        }
        Optional<Money> cumulative = InterestPositions.roundedCumulativeAt(
            c, accountId, terms.side(), periodEnd, currency);
        if (cumulative.isEmpty()) {
            return Optional.empty();                       // rien de couru a cette date
        }
        Money gross = cumulative.get().minus(position.settledTotal());
        SettlementTerms settlement = terms.settlement();

        String withholdingCode = null;
        BigDecimal withholdingRate = BigDecimal.ZERO;
        Money withholding = Money.zero(currency);
        UUID withholdingAccount = null;
        if (terms.side() == AccrualSide.CREDITOR && settlement.withholdingCode() != null) {
            Withholding rate = WithholdingTaxes.rateAt(c, legalEntityId,
                                                       settlement.withholdingCode(), periodEnd)
                .orElseThrow(() -> new IllegalStateException(
                    "retenue " + settlement.withholdingCode() + " sans taux en vigueur au "
                    + periodEnd + " : declarer le taux avant de capitaliser"));
            withholdingCode = rate.code();
            withholdingRate = rate.ratePercent();
            withholdingAccount = rate.payableAccountId();
            withholding = gross.times(rate.ratePercent().movePointLeft(2)).roundToCurrency();
        }

        BigDecimal taxRate = BigDecimal.ZERO;
        Money tax = Money.zero(currency);
        UUID taxAccount = null;
        if (terms.side() == AccrualSide.DEBTOR && settlement.taxRatePercent().signum() > 0) {
            taxRate = settlement.taxRatePercent();
            taxAccount = settlement.taxAccount();
            tax = gross.times(taxRate.movePointLeft(2)).roundToCurrency();
        }

        Money net = terms.side() == AccrualSide.CREDITOR ? gross.minus(withholding)
                                                         : gross.plus(tax);
        return Optional.of(new Planned(accountId, terms.side(), periodEnd, gross, withholdingCode,
                                       withholdingRate, withholding, withholdingAccount, taxRate,
                                       tax, taxAccount, net, terms));
    }

    private boolean alreadyRecorded(Connection c, UUID accountId, AccrualSide side,
                                    LocalDate periodEnd) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT 1 FROM interest_settlement WHERE account_id = ? AND side = ?"
            + " AND period_end = ? AND status = 'ACTIVE'")) {
            ps.setObject(1, accountId);
            ps.setString(2, side.name());
            ps.setObject(3, periodEnd);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Recherche du reglement d'interets", e);
        }
    }

    // ------------------------------------------------------------------ imputation

    /**
     * Un brut negatif — un recalcul retroactif a reduit les interets sous ce qui avait ete regle —
     * inverse le sens de chaque ligne : le client est debite de ce qu'il a recu en trop, la
     * retenue reversee en trop est reprise. Jamais de montant negatif au journal.
     */
    private UUID post(UUID legalEntityId, Planned p, LocalDate bookingDate, UUID actorId,
                      UUID batchRunId) {
        LocalDate valueDate = p.periodEnd().plusDays(1);
        boolean positive = p.gross().isPositive();
        List<PostingLine> lines = new ArrayList<>();
        String type;

        if (p.side() == AccrualSide.CREDITOR) {
            type = TYPE_CAPITALISATION;
            lines.add(line(p.terms().accruedAccount(), p.gross(), positive, valueDate,
                           "Interets capitalises au " + p.periodEnd()));
            lines.add(line(p.accountId(), p.net(), !positive, valueDate,
                           "Interets nets au " + p.periodEnd()));
            if (!p.withholding().isZero()) {
                lines.add(line(p.withholdingAccount(), p.withholding(), !positive, valueDate,
                               "Retenue " + p.withholdingCode() + " sur interets"));
            }
        } else {
            type = TYPE_OVERDRAFT_CHARGE;
            lines.add(line(p.accountId(), p.net(), positive, valueDate,
                           "Agios arretes au " + p.periodEnd()));
            lines.add(line(p.terms().accruedAccount(), p.gross(), !positive, valueDate,
                           "Agios courus regles"));
            if (!p.tax().isZero()) {
                lines.add(line(p.taxAccount(), p.tax(), !positive, valueDate, "Taxe sur agios"));
            }
        }

        IdempotencyKey key = IdempotencyKey.forBatch(String.valueOf(batchRunId),
                                                     "INTEREST_SETTLEMENT", p.accountId(),
                                                     p.side(), p.periodEnd());
        return postingService.post(new PostingCommand(
            key, legalEntityId, bookingDate, type, actorId, PostingSource.BATCH, batchRunId, lines,
            Map.of("account", p.accountId().toString(), "side", p.side().name(),
                   "period_end", p.periodEnd().toString()))).entryId();
    }

    private static PostingLine line(UUID account, Money amount, boolean debit, LocalDate valueDate,
                                    String label) {
        Money absolute = amount.abs();
        return debit ? PostingLine.debit(account, absolute, valueDate, label)
                     : PostingLine.credit(account, absolute, valueDate, label);
    }

    private void record(Connection c, Planned p, UUID entryId, LocalDate bookingDate,
                        UUID batchRunId) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO interest_settlement(id, account_id, side, period_end, gross_amount,"
            + " withholding_code, withholding_rate_percent, withholding_amount, tax_rate_percent,"
            + " tax_amount, net_amount, entry_id, booking_date, value_date, batch_run_id)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, p.accountId());
            ps.setString(3, p.side().name());
            ps.setObject(4, p.periodEnd());
            ps.setBigDecimal(5, p.gross().amount());
            ps.setString(6, p.withholdingCode());
            ps.setBigDecimal(7, p.withholdingRate());
            ps.setBigDecimal(8, p.withholding().amount());
            ps.setBigDecimal(9, p.taxRate());
            ps.setBigDecimal(10, p.tax().amount());
            ps.setBigDecimal(11, p.net().amount());
            ps.setObject(12, entryId);
            ps.setObject(13, bookingDate);
            ps.setObject(14, p.periodEnd().plusDays(1));
            ps.setObject(15, batchRunId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Enregistrement du reglement d'interets", e);
        }
    }
}
