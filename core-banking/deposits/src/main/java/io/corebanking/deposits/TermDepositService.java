package io.corebanking.deposits;

import io.corebanking.calendar.BusinessCalendar;
import io.corebanking.calendar.Calendars;
import io.corebanking.interest.daycount.DayCountConvention;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.id.Ids;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.kernel.time.Periodicity;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.domain.posting.PostingResult;
import io.corebanking.ledger.domain.posting.PostingService;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.ledger.store.Balances;
import io.corebanking.ledger.store.Branches;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.product.ProductCatalog;
import io.corebanking.product.ProductVersion;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Depots a terme : un contrat, pas un solde remunere.
 *
 * <p><b>Le taux est fige a la souscription.</b> Le produit propose le bareme du jour et le plafond
 * de ce qu'une agence peut consentir au-dela ; ce que le contrat retient, il le garde. Un DAT dont
 * le taux suivrait le bareme ne serait pas un depot a terme : ce serait un compte d'epargne avec
 * une date d'echeance.
 *
 * <p><b>Le capital est bloque</b> par un blocage de compte, pas par une convention que chaque
 * service devrait connaitre : aucun retrait, aucun prelevement, aucun ordre permanent, aucune
 * commission ne peut l'entamer, quel qu'en soit le chemin. Le blocage tombe au denouement.
 *
 * <p><b>Les interets courent jour apres jour</b> au taux du contrat, sur le capital, et se
 * constatent en charge contre un compte de courus — le meme mecanisme que les interets sur depots
 * et sur credits : cumul en precision entiere, impute par ecart entre le cumul arrondi et ce qui a
 * deja ete impute. Aucune derive d'arrondi, quel que soit le rattrapage.
 *
 * <p><b>Une sortie avant terme est une rupture, pas un retrait.</b> Les interets sont recalcules au
 * taux de penalite sur la periode reellement courue, et ce qui a ete constate au-dela est repris.
 * Servir le taux convenu a qui ne tient pas la duree reviendrait a le servir a tout le monde : la
 * duree est la contrepartie du prix.
 */
public final class TermDepositService {

    /** Nom de l'etape d'arrete, porte par les cles d'idempotence. */
    static final String STEP_ACCRUAL = "TERM_DEPOSIT_ACCRUAL";
    static final String STEP_MATURITY = "TERM_DEPOSIT_MATURITY";

    private static final String TYPE_SUBSCRIPTION = "TERM_DEPOSIT_SUBSCRIPTION";
    private static final String TYPE_ACCRUAL = "TERM_DEPOSIT_ACCRUAL";
    private static final String TYPE_SETTLEMENT = "TERM_DEPOSIT_INTEREST";
    private static final String TYPE_CLOSURE = "TERM_DEPOSIT_CLOSURE";
    private static final int PRECISION = 5;

    private final Database database;
    private final PostingService postingService;
    private final AccountLifecycle lifecycle;

    public TermDepositService(Database database, PostingService postingService) {
        this.database = database;
        this.postingService = postingService;
        this.lifecycle = new AccountLifecycle(database, postingService);
    }

    // ------------------------------------------------------------------ modele

    /** Ce que le client a demande pour le terme, decide a la souscription. */
    public enum MaturityInstruction { PAY_OUT, RENEW_PRINCIPAL, RENEW_ALL }

    public record TermDeposit(UUID id, UUID legalEntityId, String reference, UUID depositAccountId,
                              UUID settlementAccountId, Money principal,
                              BigDecimal annualRatePercent, BigDecimal penaltyRatePercent,
                              DayCountConvention dayCount, BigDecimal withholdingPercent,
                              int termMonths, LocalDate valueDate, LocalDate maturityDate,
                              Periodicity interestPayment, MaturityInstruction maturityInstruction,
                              UUID accruedAccountId, UUID expenseAccountId,
                              UUID withholdingAccountId, Money accruedTotal, Money settledTotal,
                              BigDecimal accruedPrecise, LocalDate accruedThrough,
                              LocalDate settledThrough, LocalDate nextPaymentDate, String status,
                              UUID renewalOf, UUID renewedAs, LocalDate closedOn,
                              String breakReason, Money penaltyAmount, Money paidOut,
                              UUID blockId, UUID subscriptionEntryId, UUID closureEntryId,
                              UUID createdBy, UUID approvedBy) {

        public boolean active() {
            return "ACTIVE".equals(status);
        }

        /** Interets constates et pas encore regles : ce que le compte de courus doit porter. */
        public Money outstandingInterest() {
            return accruedTotal.minus(settledTotal);
        }

    }

    /**
     * Souscription.
     *
     * @param grantedRatePercent taux consenti, nul pour le taux de reference du produit. Il est
     *     borne par le plafond du produit : une agence ne fixe pas seule le prix de la ressource.
     * @param interestPayment nul pour un service au terme.
     */
    public record Draft(UUID legalEntityId, String reference, UUID depositAccountId,
                        UUID settlementAccountId, Money principal, BigDecimal grantedRatePercent,
                        int termMonths, Periodicity interestPayment,
                        MaturityInstruction maturityInstruction, UUID createdBy, UUID approvedBy) {

        public Draft {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(depositAccountId, "depositAccountId");
            Objects.requireNonNull(settlementAccountId, "settlementAccountId");
            Objects.requireNonNull(maturityInstruction, "maturityInstruction");
            if (principal == null || !principal.isPositive()) {
                throw new IllegalArgumentException("Un depot a terme porte son capital : "
                                                   + principal);
            }
            if (depositAccountId.equals(settlementAccountId)) {
                throw new IllegalArgumentException("Le compte de depot et le compte de reglement "
                    + "sont le meme : le capital ne serait jamais sorti du disponible");
            }
            if (termMonths <= 0) {
                throw new IllegalArgumentException("Une duree se compte en mois pleins : "
                                                   + termMonths);
            }
            if (grantedRatePercent != null && grantedRatePercent.signum() < 0) {
                throw new IllegalArgumentException("Un taux consenti n'est pas negatif : "
                                                   + grantedRatePercent);
            }
            if (createdBy == null || approvedBy == null || approvedBy.equals(createdBy)) {
                throw new IllegalArgumentException("Une souscription se decide a deux : elle "
                    + "engage la banque sur un prix et sur une duree");
            }
        }
    }

    /** Depot a terme refuse. */
    public static class TermDepositRefusedException extends RuntimeException {
        public TermDepositRefusedException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ souscription

    public TermDeposit subscribe(Draft draft) {
        return database.inTransaction(c -> {
            LocalDate on = OperationsService.businessDate(c, draft.legalEntityId());
            Account deposit = OperationsService.requireOperableAccount(
                c, draft.legalEntityId(), draft.depositAccountId(), draft.principal(), on);
            Account settlement = OperationsService.requireOperableAccount(
                c, draft.legalEntityId(), draft.settlementAccountId(), draft.principal(), on);
            if (!deposit.currency().equals(settlement.currency())) {
                throw new IllegalArgumentException("Un depot a terme ne change pas de devise : "
                    + settlement.currency().code() + " vers " + deposit.currency().code());
            }
            ProductVersion product = ProductCatalog.resolveForAccount(
                c, draft.legalEntityId(), deposit.id(), on);
            requireFamily(product, deposit);
            if (!Balances.current(c, deposit.id()).isZero()) {
                throw new TermDepositRefusedException("Le compte " + deposit.code() + " porte deja "
                    + "un solde : un compte de depot a terme ne sert qu'a un contrat");
            }
            CurrencyRef currency = deposit.currency();
            Money minimum = TermDepositCatalog.minimumAmount(product, currency);
            if (draft.principal().isLessThan(minimum)) {
                throw new TermDepositRefusedException("Le produit " + product.code() + " exige un "
                    + "capital d'au moins " + minimum + " : " + draft.principal());
            }
            int min = TermDepositCatalog.minimumMonths(product);
            int max = TermDepositCatalog.maximumMonths(product);
            if (draft.termMonths() < min || draft.termMonths() > max) {
                throw new TermDepositRefusedException("Le produit " + product.code() + " admet des "
                    + "durees de " + min + " a " + max + " mois : " + draft.termMonths());
            }
            BigDecimal rate = draft.grantedRatePercent() == null
                ? TermDepositCatalog.ratePercent(product) : draft.grantedRatePercent();
            BigDecimal ceiling = TermDepositCatalog.maxRatePercent(product);
            if (rate.compareTo(ceiling) > 0) {
                throw new TermDepositRefusedException("Le taux consenti " + rate + " % depasse le "
                    + "plafond du produit " + product.code() + " : " + ceiling + " %. Le prix de "
                    + "la ressource ne se fixe pas en agence");
            }
            if (draft.maturityInstruction() != MaturityInstruction.PAY_OUT
                && !TermDepositCatalog.renewalAllowed(product)) {
                throw new TermDepositRefusedException("Le produit " + product.code() + " n'admet "
                    + "pas la reconduction : un depot reconduit sans l'avoir demande bloquerait "
                    + "l'argent du client une periode de plus");
            }
            BigDecimal withholding = TermDepositCatalog.withholdingPercent(product);
            UUID withholdingAccount = TermDepositCatalog.withholdingAccount(product).orElse(null);
            if (withholding.signum() > 0 && withholdingAccount == null) {
                throw new TermDepositRefusedException("Le produit " + product.code() + " declare "
                    + "une retenue a la source sans compte d'imputation");
            }
            BusinessCalendar calendar = Calendars.load(database, draft.legalEntityId()).calendar();
            // Le terme est l'anniversaire de la date de valeur, reporte au jour ouvre suivant
            // s'il tombe un jour ferie : les interets courent sur la periode [valeur, terme[,
            // soit exactement 365 journees pour un an.
            LocalDate maturity = calendar.nextBusinessDayOrSame(
                on.plusMonths(draft.termMonths()));
            UUID id = Ids.newId();
            // Le capital sort du disponible du client et entre au depot a terme, en date du jour :
            // une date de valeur differee ferait courir les interets sur un capital que le client
            // aurait encore.
            PostingResult result = postingService.post(PostingCommand.online(
                IdempotencyKey.of("TERM_DEPOSIT|" + id), draft.legalEntityId(), on,
                TYPE_SUBSCRIPTION, draft.createdBy(),
                List.of(PostingLine.debit(settlement.id(), draft.principal(), on,
                                          "Depot a terme " + draft.reference()),
                        PostingLine.credit(deposit.id(), draft.principal(), on,
                                           "Depot a terme " + draft.reference())))
                .withBranch(settlement.branchId()));
            LocalDate nextPayment = draft.interestPayment() == null
                ? maturity
                : calendar.nextBusinessDayOrSame(nextPeriodEnd(on, draft.interestPayment(), 1));
            if (nextPayment.isAfter(maturity)) {
                nextPayment = maturity;
            }
            // Le blocage est ce qui rend le terme opposable a tous les chemins de debit : le
            // guichet, le virement, le prelevement, l'ordre permanent, la commission.
            UUID blockId = lifecycle.block(new AccountLifecycle.Block(deposit.id(),
                BlockKind.DEBIT,
                "depot a terme " + draft.reference() + " jusqu'au " + maturity,
                draft.reference(), draft.createdBy(), draft.approvedBy()));
            insert(c, id, draft, deposit, product, rate, withholding, withholdingAccount, on,
                   maturity, nextPayment, blockId, result.entryId());
            return require(c, id);
        });
    }

    private static void requireFamily(ProductVersion product, Account deposit) {
        if (!"TERM_DEPOSIT".equals(product.productType())) {
            throw new TermDepositRefusedException("Le compte " + deposit.code() + " est rattache "
                + "au produit " + product.code() + " (" + product.productType() + ") : un depot a "
                + "terme se porte sur un compte de la famille TERM_DEPOSIT");
        }
    }

    /**
     * Fin de la {@code n}-ieme periode de service des interets, depuis la souscription.
     *
     * <p>C'est le jour ou la periode se referme : les interets des journees ecoulees y sont
     * servis, et la periode suivante commence le meme jour. Le terme suit la meme regle.
     */
    private static LocalDate nextPeriodEnd(LocalDate from, Periodicity periodicity, int rank) {
        return periodicity.startOfPeriod(from, rank);
    }

    // ------------------------------------------------------------------ interets courus

    /** Compte rendu d'une passe d'interets. */
    public record Accrued(int deposits, Money amount) {}

    /**
     * Constate les interets du jour sur tous les depots vivants d'une entite.
     *
     * <p>Le cumul est tenu en precision entiere depuis la date de valeur ; l'impute du jour est
     * l'ecart entre le cumul arrondi et ce qui a deja ete impute. Un rattrapage de plusieurs
     * journees donne donc exactement la meme somme qu'autant d'arretes successifs.
     */
    public Accrued accrue(UUID legalEntityId, LocalDate through, UUID runId, UUID actorId) {
        List<TermDeposit> deposits = database.inTransaction(
            c -> accruable(c, legalEntityId, through));
        if (deposits.isEmpty()) {
            return new Accrued(0, null);
        }
        Money total = null;
        int touched = 0;
        for (TermDeposit deposit : deposits) {
            Money posted = database.inTransaction(c -> accrueOne(c, deposit, through, runId,
                                                                  actorId));
            if (posted != null) {
                total = total == null ? posted : total.plus(posted);
                touched++;
            }
        }
        return new Accrued(touched, total);
    }

    private Money accrueOne(Connection c, TermDeposit snapshot, LocalDate through, UUID runId,
                            UUID actorId) {
        TermDeposit deposit = lock(c, snapshot.id());
        if (!deposit.active()) {
            return null;
        }
        // Les interets courent sur [valeur, terme[ : le jour du terme, le capital est rendu ou
        // reconduit, et c'est le contrat suivant qui le porte. Le remunerer deux fois paierait
        // une journee que personne n'a placee.
        LocalDate end = deposit.maturityDate().minusDays(1);
        LocalDate last = through.isAfter(end) ? end : through;
        LocalDate from = deposit.accruedThrough() == null
            ? deposit.valueDate() : deposit.accruedThrough().plusDays(1);
        if (last.isBefore(from)) {
            return null;
        }
        BigDecimal rate = deposit.annualRatePercent().movePointLeft(2);
        BigDecimal cumulative = deposit.accruedPrecise();
        List<Object[]> days = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(last); day = day.plusDays(1)) {
            BigDecimal fraction = deposit.dayCount().dayFraction(day);
            BigDecimal amount = deposit.principal().amount().multiply(rate).multiply(fraction)
                .setScale(PRECISION, RoundingMode.HALF_UP);
            cumulative = cumulative.add(amount);
            days.add(new Object[] {day, fraction, amount, cumulative});
        }
        Money rounded = Money.of(cumulative, deposit.principal().currency()).roundToCurrency();
        Money delta = rounded.minus(deposit.accruedTotal());
        recordAccruals(c, deposit, days, runId);
        if (delta.isZero()) {
            updateAccrual(c, deposit.id(), cumulative, rounded, last);
            return null;
        }
        Account depositAccount = Accounts.loadAll(c, Set.of(deposit.depositAccountId()))
            .get(deposit.depositAccountId());
        postingService.post(PostingCommand.batch(
            IdempotencyKey.forBatch(runId.toString(), STEP_ACCRUAL, deposit.id(), last),
            deposit.legalEntityId(), through, TYPE_ACCRUAL, actorId, runId,
            List.of(PostingLine.debit(deposit.expenseAccountId(), delta, through,
                                      "Interets courus " + deposit.reference()),
                    PostingLine.credit(deposit.accruedAccountId(), delta, through,
                                       "Interets courus " + deposit.reference())))
            .withBranch(depositAccount == null ? null : depositAccount.branchId()));
        updateAccrual(c, deposit.id(), cumulative, rounded, last);
        return delta;
    }

    private static void recordAccruals(Connection c, TermDeposit deposit, List<Object[]> days,
                                       UUID runId) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO term_deposit_accrual(term_deposit_id, accrual_date, basis,"
            + " annual_rate_percent, year_fraction, precise_amount, cumulative_precise,"
            + " batch_run_id) VALUES (?,?,?,?,?,?,?,?)"
            + " ON CONFLICT (term_deposit_id, accrual_date) DO NOTHING")) {
            for (Object[] day : days) {
                ps.setObject(1, deposit.id());
                ps.setObject(2, day[0]);
                ps.setBigDecimal(3, deposit.principal().amount());
                ps.setBigDecimal(4, deposit.annualRatePercent());
                ps.setBigDecimal(5, (BigDecimal) day[1]);
                ps.setBigDecimal(6, (BigDecimal) day[2]);
                ps.setBigDecimal(7, (BigDecimal) day[3]);
                ps.setObject(8, runId);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new LedgerStoreException("Journees d'interets du depot a terme", e);
        }
    }

    private static void updateAccrual(Connection c, UUID id, BigDecimal precise, Money total,
                                      LocalDate through) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE term_deposit SET accrued_precise = ?, accrued_total = ?, accrued_through = ?"
            + " WHERE id = ?")) {
            ps.setBigDecimal(1, precise);
            ps.setBigDecimal(2, total.amount());
            ps.setObject(3, through);
            ps.setObject(4, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Cumul d'interets du depot a terme", e);
        }
    }

    // ------------------------------------------------------------------ echeances

    /** Les depots dont une echeance — service des interets ou terme — est arrivee. */
    public static List<UUID> due(Connection c, UUID legalEntityId, LocalDate businessDate) {
        List<UUID> ids = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id FROM term_deposit WHERE legal_entity_id = ? AND status = 'ACTIVE'"
            + "   AND next_payment_date <= ? ORDER BY reference")) {
            ps.setObject(1, legalEntityId);
            ps.setObject(2, businessDate);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getObject(1, UUID.class));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Depots a terme echus", e);
        }
        return ids;
    }

    /** Ce qu'une echeance a donne. */
    public record Payment(UUID termDepositId, LocalDate on, Money interestGross, Money withheld,
                          Money interestNet, Money principalPaid, boolean matured, UUID renewedAs,
                          UUID entryId) {}

    /**
     * Denoue une echeance : service des interets de la periode, ou terme du contrat.
     *
     * <p>Au terme, le capital suit l'instruction donnee a la souscription — verse, reconduit seul,
     * ou reconduit avec ses interets. Le renouvellement souscrit un <b>nouveau contrat au taux du
     * jour</b> : reconduire l'ancien taux engagerait la banque sur un prix qu'elle n'a pas decide,
     * et le client sur un prix qu'il n'a pas revu.
     */
    public Payment settle(UUID id, UUID runId, UUID actorId) {
        return database.inTransaction(c -> {
            TermDeposit deposit = lock(c, id);
            LocalDate on = OperationsService.businessDate(c, deposit.legalEntityId());
            if (!deposit.active() || deposit.nextPaymentDate().isAfter(on)) {
                return null;
            }
            boolean atMaturity = !deposit.maturityDate().isAfter(on);
            Money interest = deposit.outstandingInterest();
            Money withheld = withhold(deposit, interest);
            Money net = interest.minus(withheld);
            Account settlement = Accounts.loadAll(c, Set.of(deposit.settlementAccountId()))
                .get(deposit.settlementAccountId());
            List<PostingLine> lines = new ArrayList<>();
            String label = (atMaturity ? "Terme " : "Interets ") + deposit.reference();
            if (interest.isPositive()) {
                lines.add(PostingLine.debit(deposit.accruedAccountId(), interest, on, label));
                if (withheld.isPositive()) {
                    lines.add(PostingLine.credit(deposit.withholdingAccountId(), withheld, on,
                                                 "Retenue a la source " + deposit.reference()));
                }
            }
            UUID renewedAs = null;
            Money principalPaid = Money.zero(deposit.principal().currency());
            if (!atMaturity) {
                // Service periodique : seuls les interets nets vont au compte du client, le
                // capital reste bloque jusqu'au terme.
                if (net.isPositive()) {
                    lines.add(PostingLine.credit(settlement.id(), net, on, label));
                }
            } else {
                boolean renew = deposit.maturityInstruction() != MaturityInstruction.PAY_OUT;
                boolean renewInterest =
                    deposit.maturityInstruction() == MaturityInstruction.RENEW_ALL;
                // Ce qui n'est pas reconduit revient au client : le capital s'il n'est pas
                // reconduit, les interets nets s'ils ne le sont pas.
                Money toClient = renewInterest ? Money.zero(net.currency()) : net;
                if (!renew) {
                    principalPaid = deposit.principal();
                    lines.add(PostingLine.debit(deposit.depositAccountId(), deposit.principal(),
                                                on, label));
                    toClient = toClient.plus(deposit.principal());
                }
                if (toClient.isPositive()) {
                    lines.add(PostingLine.credit(settlement.id(), toClient, on, label));
                }
                if (renew && renewInterest && net.isPositive()) {
                    // Les interets rejoignent le capital sur le compte de depot : le nouveau
                    // contrat porte la somme.
                    lines.add(PostingLine.credit(deposit.depositAccountId(), net, on, label));
                }
            }
            if (atMaturity && deposit.maturityInstruction() == MaturityInstruction.PAY_OUT) {
                // Le blocage tombe avant l'ecriture qui rend le capital, dans la meme
                // transaction : il tient jusqu'au terme, et c'est le denouement lui-meme qui le
                // leve — le capital n'est jamais libre sans etre rendu.
                releaseBlock(deposit, on, actorId);
            }
            UUID entryId = lines.isEmpty() ? null : postingService.post(PostingCommand.batch(
                IdempotencyKey.forBatch(runId.toString(), STEP_MATURITY, deposit.id(),
                                        deposit.nextPaymentDate()),
                deposit.legalEntityId(), on, atMaturity ? TYPE_CLOSURE : TYPE_SETTLEMENT, actorId,
                runId, lines).withBranch(settlement.branchId())).entryId();
            markSettled(c, deposit, interest, on);
            if (!atMaturity) {
                advance(c, deposit, on);
                recordPayment(c, deposit, on, "INTEREST", interest, withheld, net,
                              Money.zero(net.currency()), null, entryId, runId);
                return new Payment(deposit.id(), on, interest, withheld, net,
                                   Money.zero(net.currency()), false, null, entryId);
            }
            close(c, deposit, on, principalPaid.plus(
                deposit.maturityInstruction() == MaturityInstruction.RENEW_ALL
                    ? Money.zero(net.currency()) : net), entryId, runId);
            if (deposit.maturityInstruction() != MaturityInstruction.PAY_OUT) {
                renewedAs = renew(c, deposit, on,
                                  deposit.maturityInstruction() == MaturityInstruction.RENEW_ALL
                                      ? deposit.principal().plus(net) : deposit.principal(),
                                  runId);
            }
            recordPayment(c, deposit, on, "MATURITY", interest, withheld, net, principalPaid,
                          renewedAs, entryId, runId);
            return new Payment(deposit.id(), on, interest, withheld, net, principalPaid, true,
                               renewedAs, entryId);
        });
    }

    /**
     * Reconduit un depot arrive a terme.
     *
     * <p>Le nouveau contrat prend le taux du jour et la duree du precedent. Le compte de depot est
     * le meme : son solde est deja le capital reconduit, et le blocage n'a pas a etre leve pour
     * etre repose — ce qui, l'instant d'une transaction, aurait rendu l'argent saisissable.
     */
    private UUID renew(Connection c, TermDeposit previous, LocalDate on, Money principal,
                       UUID runId) {
        ProductVersion product = ProductCatalog.resolveForAccount(
            c, previous.legalEntityId(), previous.depositAccountId(), on);
        BusinessCalendar calendar = Calendars.load(database, previous.legalEntityId()).calendar();
        LocalDate maturity = calendar.nextBusinessDayOrSame(on.plusMonths(previous.termMonths()));
        UUID id = Ids.newId();
        LocalDate nextPayment = previous.interestPayment() == null
            ? maturity
            : calendar.nextBusinessDayOrSame(nextPeriodEnd(on, previous.interestPayment(), 1));
        if (nextPayment.isAfter(maturity)) {
            nextPayment = maturity;
        }
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO term_deposit(id, legal_entity_id, reference, deposit_account_id,"
            + " settlement_account_id, principal, annual_rate_percent, penalty_rate_percent,"
            + " day_count, withholding_percent, term_months, value_date, maturity_date,"
            + " interest_payment, maturity_instruction, accrued_account_id, expense_account_id,"
            + " withholding_account_id, next_payment_date, block_id, renewal_of, batch_run_id,"
            + " created_by, approved_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, previous.legalEntityId());
            ps.setString(3, previous.reference() + "-R" + on.getYear() + monthOf(on));
            ps.setObject(4, previous.depositAccountId());
            ps.setObject(5, previous.settlementAccountId());
            ps.setBigDecimal(6, principal.amount());
            ps.setBigDecimal(7, TermDepositCatalog.ratePercent(product));
            ps.setBigDecimal(8, TermDepositCatalog.penaltyRatePercent(product));
            ps.setString(9, TermDepositCatalog.dayCount(product).name());
            ps.setBigDecimal(10, TermDepositCatalog.withholdingPercent(product));
            ps.setInt(11, previous.termMonths());
            ps.setObject(12, on);
            ps.setObject(13, maturity);
            ps.setString(14, previous.interestPayment() == null ? "AT_MATURITY"
                                                                : previous.interestPayment().name());
            ps.setString(15, previous.maturityInstruction().name());
            ps.setObject(16, TermDepositCatalog.accruedInterest(product));
            ps.setObject(17, TermDepositCatalog.interestExpense(product));
            ps.setObject(18, TermDepositCatalog.withholdingAccount(product).orElse(null));
            ps.setObject(19, nextPayment);
            // Le blocage n'est ni leve ni repose : l'instant d'une transaction sans blocage
            // rendrait le capital saisissable, et rien ne le justifie — le terme continue.
            ps.setObject(20, previous.blockId());
            ps.setObject(21, previous.id());
            ps.setObject(22, runId);
            // La reconduction n'a pas de valideur du jour : elle a ete decidee a deux a la
            // souscription, et c'est cette decision-la qu'elle execute.
            ps.setObject(23, previous.createdBy());
            ps.setObject(24, previous.approvedBy());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Reconduction du depot a terme", e);
        }
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE term_deposit SET renewed_as = ? WHERE id = ?")) {
            ps.setObject(1, id);
            ps.setObject(2, previous.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Chainage de la reconduction", e);
        }
        return id;
    }

    private static String monthOf(LocalDate on) {
        return on.getMonthValue() < 10 ? "0" + on.getMonthValue() : String.valueOf(on.getMonthValue());
    }

    // ------------------------------------------------------------------ rupture avant terme

    /** Ce qu'une rupture a donne. */
    public record Break(UUID termDepositId, LocalDate on, Money interestDue, Money clawedBack,
                        Money withheld, Money paidOut, UUID entryId) {}

    /**
     * Rupture avant terme : le client reprend ses fonds, la banque reprend son prix.
     *
     * <p>Les interets dus sont recalcules au <b>taux de penalite</b> sur la periode reellement
     * courue. Ce qui a ete constate au-dela est repris — en charge negative si rien n'a encore ete
     * verse, sur le compte du client si des interets lui ont deja ete servis. Les deux cas se
     * denouent dans la meme ecriture : ce que le client garde est ce que le contrat rompu prevoit,
     * pas ce que la banque avait deja provisionne pour lui.
     */
    public Break breakEarly(UUID id, String reason, UUID actorId, UUID approverId) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Une rupture avant terme porte son motif");
        }
        if (actorId == null || approverId == null || approverId.equals(actorId)) {
            throw new IllegalArgumentException("Une rupture avant terme se decide a deux : elle "
                + "defait un engagement pris des deux cotes");
        }
        return database.inTransaction(c -> {
            TermDeposit deposit = lock(c, id);
            if (!deposit.active()) {
                throw new TermDepositRefusedException("Le depot a terme " + deposit.reference()
                    + " est " + deposit.status() + " : il n'y a plus rien a rompre");
            }
            LocalDate on = OperationsService.businessDate(c, deposit.legalEntityId());
            if (!on.isBefore(deposit.maturityDate())) {
                throw new TermDepositRefusedException("Le depot a terme " + deposit.reference()
                    + " est arrive a terme le " + deposit.maturityDate()
                    + " : son denouement n'est pas une rupture");
            }
            Money interestDue = atPenaltyRate(deposit, on);
            Money constituted = deposit.accruedTotal();
            Money alreadyPaid = deposit.settledTotal();
            // Ce qui reste a servir au client, au taux de penalite, une fois deduit ce qu'il a
            // deja recu. Negatif, c'est une reprise : il a percu plus que la rupture ne lui
            // laisse.
            Money owed = interestDue.minus(alreadyPaid);
            Money clawedBack = constituted.minus(interestDue);
            Money withheld = withhold(deposit, owed.isPositive() ? owed
                                                                 : Money.zero(owed.currency()));
            Money net = owed.minus(withheld);
            Account settlement = Accounts.loadAll(c, Set.of(deposit.settlementAccountId()))
                .get(deposit.settlementAccountId());
            Money outstanding = deposit.outstandingInterest();
            List<PostingLine> lines = new ArrayList<>();
            // Le compte de courus est solde : ce qui y reste est repris, la part due suit le
            // client.
            if (outstanding.isPositive()) {
                lines.add(PostingLine.debit(deposit.accruedAccountId(), outstanding, on,
                                            "Rupture " + deposit.reference()));
            }
            if (clawedBack.isPositive()) {
                lines.add(PostingLine.credit(deposit.expenseAccountId(), clawedBack, on,
                    "Reprise d'interets sur rupture " + deposit.reference()));
            }
            if (withheld.isPositive()) {
                lines.add(PostingLine.credit(deposit.withholdingAccountId(), withheld, on,
                                             "Retenue a la source " + deposit.reference()));
            }
            Money paidOut = deposit.principal().plus(net);
            lines.add(PostingLine.debit(deposit.depositAccountId(), deposit.principal(), on,
                                        "Rupture " + deposit.reference()));
            if (net.isNegative()) {
                // Le client rend ce qu'il a percu en trop : le versement en est diminue.
                lines.add(PostingLine.debit(settlement.id(), net.negate(), on,
                    "Reprise d'interets sur rupture " + deposit.reference()));
                lines.add(PostingLine.credit(deposit.expenseAccountId(), net.negate(), on,
                    "Reprise d'interets sur rupture " + deposit.reference()));
                paidOut = deposit.principal();
                lines.add(PostingLine.credit(settlement.id(), deposit.principal(), on,
                                             "Rupture " + deposit.reference()));
            } else {
                lines.add(PostingLine.credit(settlement.id(), paidOut, on,
                                             "Rupture " + deposit.reference()));
            }
            releaseBlock(deposit, on, actorId);
            UUID entryId = postingService.post(PostingCommand.online(
                IdempotencyKey.of("TERM_DEPOSIT_BREAK|" + deposit.id()), deposit.legalEntityId(),
                on, TYPE_CLOSURE, actorId, lines).withBranch(settlement.branchId())).entryId();
            markSettled(c, deposit, outstanding, on);
            breakOff(c, deposit, on, reason, clawedBack, paidOut, entryId);
            recordPayment(c, deposit, on, "BREAK", outstanding, withheld, net,
                          deposit.principal(), null, entryId, null);
            return new Break(deposit.id(), on, interestDue, clawedBack, withheld, paidOut,
                             entryId);
        });
    }

    /** Interets qu'une rupture laisse : le taux de penalite sur la periode reellement courue. */
    private static Money atPenaltyRate(TermDeposit deposit, LocalDate on) {
        BigDecimal rate = deposit.penaltyRatePercent().movePointLeft(2);
        BigDecimal cumulative = BigDecimal.ZERO;
        for (LocalDate day = deposit.valueDate(); day.isBefore(on); day = day.plusDays(1)) {
            cumulative = cumulative.add(deposit.principal().amount().multiply(rate)
                .multiply(deposit.dayCount().dayFraction(day))
                .setScale(PRECISION, RoundingMode.HALF_UP));
        }
        return Money.of(cumulative, deposit.principal().currency()).roundToCurrency();
    }

    private static Money withhold(TermDeposit deposit, Money interest) {
        if (!interest.isPositive() || deposit.withholdingPercent().signum() == 0) {
            return Money.zero(interest.currency());
        }
        return interest.times(deposit.withholdingPercent().movePointLeft(2)).roundToCurrency();
    }

    private void releaseBlock(TermDeposit deposit, LocalDate on, UUID actorId) {
        if (deposit.blockId() == null) {
            return;
        }
        database.inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE account_block SET lifted_on = ?, lifted_by = ?"
                + " WHERE id = ? AND lifted_on IS NULL")) {
                ps.setObject(1, on);
                ps.setObject(2, actorId);
                ps.setObject(3, deposit.blockId());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Levee du blocage du depot a terme", e);
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ persistance

    private static void insert(Connection c, UUID id, Draft draft, Account deposit,
                               ProductVersion product, BigDecimal rate, BigDecimal withholding,
                               UUID withholdingAccount, LocalDate on, LocalDate maturity,
                               LocalDate nextPayment, UUID blockId, UUID entryId) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO term_deposit(id, legal_entity_id, reference, deposit_account_id,"
            + " settlement_account_id, principal, annual_rate_percent, penalty_rate_percent,"
            + " day_count, withholding_percent, term_months, value_date, maturity_date,"
            + " interest_payment, maturity_instruction, accrued_account_id, expense_account_id,"
            + " withholding_account_id, next_payment_date, block_id, subscription_entry_id,"
            + " created_by, approved_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, draft.legalEntityId());
            ps.setString(3, draft.reference());
            ps.setObject(4, deposit.id());
            ps.setObject(5, draft.settlementAccountId());
            ps.setBigDecimal(6, draft.principal().amount());
            ps.setBigDecimal(7, rate);
            ps.setBigDecimal(8, TermDepositCatalog.penaltyRatePercent(product));
            ps.setString(9, TermDepositCatalog.dayCount(product).name());
            ps.setBigDecimal(10, withholding);
            ps.setInt(11, draft.termMonths());
            ps.setObject(12, on);
            ps.setObject(13, maturity);
            ps.setString(14, draft.interestPayment() == null ? "AT_MATURITY"
                                                             : draft.interestPayment().name());
            ps.setString(15, draft.maturityInstruction().name());
            ps.setObject(16, TermDepositCatalog.accruedInterest(product));
            ps.setObject(17, TermDepositCatalog.interestExpense(product));
            ps.setObject(18, withholdingAccount);
            ps.setObject(19, nextPayment);
            ps.setObject(20, blockId);
            ps.setObject(21, entryId);
            ps.setObject(22, draft.createdBy());
            ps.setObject(23, draft.approvedBy());
            ps.executeUpdate();
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new IllegalStateException("Un depot a terme porte deja la reference "
                                                + draft.reference() + ", ou ce compte porte deja "
                                                + "un contrat vivant", e);
            }
            throw new LedgerStoreException("Souscription du depot a terme", e);
        }
    }

    private static void recordPayment(Connection c, TermDeposit deposit, LocalDate on, String kind,
                                      Money gross, Money withheld, Money net, Money principalPaid,
                                      UUID renewedAs, UUID entryId, UUID runId) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO term_deposit_payment(id, term_deposit_id, paid_on, due_date, kind,"
            + " interest_gross, withheld, interest_net, principal_paid, renewed_as, entry_id,"
            + " batch_run_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, deposit.id());
            ps.setObject(3, on);
            ps.setObject(4, deposit.nextPaymentDate());
            ps.setString(5, kind);
            ps.setBigDecimal(6, gross.amount());
            ps.setBigDecimal(7, withheld.amount());
            ps.setBigDecimal(8, net.amount());
            ps.setBigDecimal(9, principalPaid.amount());
            ps.setObject(10, renewedAs);
            ps.setObject(11, entryId);
            ps.setObject(12, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Enregistrement de l'echeance du depot a terme", e);
        }
    }

    // ------------------------------------------------------------------ annulation d'un arrete

    /**
     * Defait ce qu'un arrete annule a fait sur les depots a terme.
     *
     * <p>Les ecritures sont contre-passees par le moteur ; ici on remet le sous-livre dans l'etat
     * ou l'arrete l'a trouve — un sous-livre qui survivrait a la contre-passation de ses ecritures
     * ferait echouer le rapprochement du lendemain, et personne ne saurait lequel des deux a
     * raison.
     *
     * <ul>
     *   <li>les journees d'interets de l'arrete sont effacees, et le cumul revient a ce que les
     *       journees restantes disent ;</li>
     *   <li>les echeances qu'il a servies sont defaites : le contrat reprend son etat, son
     *       echeance et ses interets non regles ;</li>
     *   <li>la reconduction qu'il a creee disparait, et le contrat qu'elle prolongeait redevient
     *       vivant.</li>
     * </ul>
     */
    public static int cancelRun(Connection c, UUID runId) {
        int undone = undoPayments(c, runId);
        undoAccruals(c, runId);
        return undone;
    }

    private static int undoPayments(Connection c, UUID runId) {
        List<UUID> renewals = new ArrayList<>();
        int undone = 0;
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT term_deposit_id, due_date, interest_gross, principal_paid, renewed_as"
            + "  FROM term_deposit_payment WHERE batch_run_id = ?")) {
            ps.setObject(1, runId);
            List<Object[]> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Object[] {rs.getObject(1, UUID.class),
                                           rs.getObject(2, LocalDate.class),
                                           rs.getBigDecimal(3), rs.getObject(5, UUID.class)});
                }
            }
            for (Object[] row : rows) {
                if (row[3] != null) {
                    renewals.add((UUID) row[3]);
                }
                try (PreparedStatement update = c.prepareStatement(
                    "UPDATE term_deposit SET status = 'ACTIVE', closed_on = NULL,"
                    + "   paid_out = NULL, closure_entry_id = NULL, renewed_as = NULL,"
                    + "   batch_run_id = NULL, next_payment_date = ?,"
                    + "   settled_total = settled_total - ?,"
                    + "   settled_through = NULL WHERE id = ?")) {
                    update.setObject(1, row[1]);
                    update.setBigDecimal(2, (java.math.BigDecimal) row[2]);
                    update.setObject(3, row[0]);
                    update.executeUpdate();
                }
                undone++;
            }
            try (PreparedStatement delete = c.prepareStatement(
                "DELETE FROM term_deposit_payment WHERE batch_run_id = ?")) {
                delete.setObject(1, runId);
                delete.executeUpdate();
            }
            // La reconduction n'a jamais eu lieu : elle n'a ni journee ni echeance a elle, le
            // contrat qu'elle prolongeait vient d'etre rendu a sa place.
            for (UUID renewal : renewals) {
                try (PreparedStatement delete = c.prepareStatement(
                    "DELETE FROM term_deposit WHERE id = ?")) {
                    delete.setObject(1, renewal);
                    delete.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Annulation des echeances de depots a terme", e);
        }
        return undone;
    }

    private static void undoAccruals(Connection c, UUID runId) {
        List<UUID> touched = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT DISTINCT term_deposit_id FROM term_deposit_accrual WHERE batch_run_id = ?")) {
            ps.setObject(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    touched.add(rs.getObject(1, UUID.class));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Journees d'interets du traitement", e);
        }
        if (touched.isEmpty()) {
            return;
        }
        try (PreparedStatement delete = c.prepareStatement(
            "DELETE FROM term_deposit_accrual WHERE batch_run_id = ?")) {
            delete.setObject(1, runId);
            delete.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Annulation des journees d'interets", e);
        }
        // Le cumul redevient celui que les journees restantes portent : zero s'il n'en reste
        // aucune. Le montant impute, lui, a ete contre-passe par le moteur ; les deux se
        // retrouvent, et le rapprochement du lendemain le verifie.
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE term_deposit d"
            + "   SET accrued_through = (SELECT MAX(x.accrual_date)"
            + "                            FROM term_deposit_accrual x"
            + "                           WHERE x.term_deposit_id = d.id),"
            + "       accrued_precise = COALESCE((SELECT x.cumulative_precise"
            + "                                     FROM term_deposit_accrual x"
            + "                                    WHERE x.term_deposit_id = d.id"
            + "                                    ORDER BY x.accrual_date DESC LIMIT 1), 0),"
            + "       accrued_total = ROUND(COALESCE((SELECT x.cumulative_precise"
            + "                                         FROM term_deposit_accrual x"
            + "                                        WHERE x.term_deposit_id = d.id"
            + "                                        ORDER BY x.accrual_date DESC LIMIT 1), 0),"
            + "                             (SELECT cur.scale FROM account a"
            + "                                JOIN currency cur ON cur.code = a.currency"
            + "                               WHERE a.id = d.deposit_account_id))"
            + " WHERE d.id = ANY (?)")) {
            ps.setArray(1, c.createArrayOf("uuid", touched.toArray()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Cumuls d'interets apres annulation", e);
        }
    }

    private static void markSettled(Connection c, TermDeposit deposit, Money settled,
                                    LocalDate on) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE term_deposit SET settled_total = settled_total + ?, settled_through = ?"
            + " WHERE id = ?")) {
            ps.setBigDecimal(1, settled.amount());
            ps.setObject(2, on);
            ps.setObject(3, deposit.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Reglement des interets du depot a terme", e);
        }
    }

    /** Echeance d'interets suivante ; jamais au-dela du terme, qui les servira tous. */
    private void advance(Connection c, TermDeposit deposit, LocalDate on) {
        BusinessCalendar calendar = Calendars.load(database, deposit.legalEntityId()).calendar();
        int rank = 1;
        LocalDate next;
        do {
            next = calendar.nextBusinessDayOrSame(
                nextPeriodEnd(deposit.valueDate(), deposit.interestPayment(), ++rank));
        } while (!next.isAfter(on));
        if (next.isAfter(deposit.maturityDate())) {
            next = deposit.maturityDate();
        }
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE term_deposit SET next_payment_date = ? WHERE id = ?")) {
            ps.setObject(1, next);
            ps.setObject(2, deposit.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Echeance suivante du depot a terme", e);
        }
    }

    private static void close(Connection c, TermDeposit deposit, LocalDate on, Money paidOut,
                              UUID entryId, UUID runId) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE term_deposit SET status = 'MATURED', closed_on = ?, paid_out = ?,"
            + " closure_entry_id = ?, batch_run_id = ? WHERE id = ?")) {
            ps.setObject(1, on);
            ps.setBigDecimal(2, paidOut.amount());
            ps.setObject(3, entryId);
            ps.setObject(4, runId);
            ps.setObject(5, deposit.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Cloture du depot a terme", e);
        }
    }

    private static void breakOff(Connection c, TermDeposit deposit, LocalDate on, String reason,
                                 Money penalty, Money paidOut, UUID entryId) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE term_deposit SET status = 'BROKEN', closed_on = ?, break_reason = ?,"
            + " penalty_amount = ?, paid_out = ?, closure_entry_id = ? WHERE id = ?")) {
            ps.setObject(1, on);
            ps.setString(2, reason.trim());
            ps.setBigDecimal(3, penalty.amount());
            ps.setBigDecimal(4, paidOut.amount());
            ps.setObject(5, entryId);
            ps.setObject(6, deposit.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Rupture du depot a terme", e);
        }
    }

    private TermDeposit lock(Connection c, UUID id) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id FROM term_deposit WHERE id = ? FOR UPDATE")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Depot a terme inconnu : " + id);
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Verrou du depot a terme " + id, e);
        }
        return require(c, id);
    }

    // ------------------------------------------------------------------ lecture

    private static final String SELECT =
        "SELECT d.id, d.legal_entity_id, d.reference, d.deposit_account_id,"
        + " d.settlement_account_id, d.principal, d.annual_rate_percent, d.penalty_rate_percent,"
        + " d.day_count, d.withholding_percent, d.term_months, d.value_date, d.maturity_date,"
        + " d.interest_payment, d.maturity_instruction, d.accrued_account_id,"
        + " d.expense_account_id, d.withholding_account_id, d.accrued_total, d.settled_total,"
        + " d.accrued_precise, d.accrued_through, d.settled_through, d.next_payment_date,"
        + " d.status, d.renewal_of, d.renewed_as, d.closed_on, d.break_reason, d.penalty_amount,"
        + " d.paid_out, d.block_id, d.subscription_entry_id, d.closure_entry_id, d.created_by,"
        + " d.approved_by,"
        + " cur.code, cur.scale, cur.rounding_mode"
        + "  FROM term_deposit d JOIN account a ON a.id = d.deposit_account_id"
        + "  JOIN currency cur ON cur.code = a.currency";

    public static TermDeposit require(Connection c, UUID id) {
        return find(c, id).orElseThrow(
            () -> new IllegalArgumentException("Depot a terme inconnu : " + id));
    }

    public static Optional<TermDeposit> find(Connection c, UUID id) {
        try (PreparedStatement ps = c.prepareStatement(SELECT + " WHERE d.id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du depot a terme " + id, e);
        }
    }

    /** Les depots d'une entite, filtres par statut quand il est donne. */
    public static List<TermDeposit> deposits(Connection c, UUID legalEntityId, String status) {
        List<TermDeposit> deposits = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            SELECT + " WHERE d.legal_entity_id = ? AND (?::text IS NULL OR d.status = ?)"
            + " ORDER BY d.value_date DESC, d.reference")) {
            ps.setObject(1, legalEntityId);
            ps.setString(2, status);
            ps.setString(3, status);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    deposits.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Depots a terme de l'entite", e);
        }
        return deposits;
    }

    /** Ce que chaque echeance a donne, de la plus recente a la plus ancienne. */
    public static List<Payment> payments(Connection c, UUID termDepositId) {
        TermDeposit deposit = require(c, termDepositId);
        CurrencyRef currency = deposit.principal().currency();
        List<Payment> payments = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT term_deposit_id, paid_on, interest_gross, withheld, interest_net,"
            + " principal_paid, kind, renewed_as, entry_id FROM term_deposit_payment"
            + " WHERE term_deposit_id = ? ORDER BY paid_on DESC, created_at DESC")) {
            ps.setObject(1, termDepositId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    payments.add(new Payment(
                        rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class),
                        Money.of(rs.getBigDecimal(3), currency).roundToCurrency(),
                        Money.of(rs.getBigDecimal(4), currency).roundToCurrency(),
                        Money.of(rs.getBigDecimal(5), currency).roundToCurrency(),
                        Money.of(rs.getBigDecimal(6), currency).roundToCurrency(),
                        !"INTEREST".equals(rs.getString(7)), rs.getObject(8, UUID.class),
                        rs.getObject(9, UUID.class)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Echeances du depot a terme", e);
        }
        return payments;
    }

    /** Les depots vivants dont une journee d'interets reste a constater. */
    private static List<TermDeposit> accruable(Connection c, UUID legalEntityId,
                                               LocalDate through) {
        List<TermDeposit> deposits = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            SELECT + " WHERE d.legal_entity_id = ? AND d.status = 'ACTIVE'"
            + "   AND d.value_date <= ?"
            + "   AND (d.accrued_through IS NULL"
            + "        OR d.accrued_through < LEAST(?::date, d.maturity_date - 1))"
            + " ORDER BY d.reference")) {
            ps.setObject(1, legalEntityId);
            ps.setObject(2, through);
            ps.setObject(3, through);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    deposits.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Depots a terme a remunerer", e);
        }
        return deposits;
    }

    private static TermDeposit read(ResultSet rs) throws SQLException {
        CurrencyRef currency = new CurrencyRef(rs.getString(37), rs.getInt(38),
                                               RoundingMode.valueOf(rs.getString(39)));
        String payment = rs.getString(14);
        return new TermDeposit(
            rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
            rs.getObject(4, UUID.class), rs.getObject(5, UUID.class),
            Money.of(rs.getBigDecimal(6), currency).roundToCurrency(), rs.getBigDecimal(7),
            rs.getBigDecimal(8), DayCountConvention.valueOf(rs.getString(9)), rs.getBigDecimal(10),
            rs.getInt(11), rs.getObject(12, LocalDate.class), rs.getObject(13, LocalDate.class),
            "AT_MATURITY".equals(payment) ? null : Periodicity.valueOf(payment),
            MaturityInstruction.valueOf(rs.getString(15)), rs.getObject(16, UUID.class),
            rs.getObject(17, UUID.class), rs.getObject(18, UUID.class),
            Money.of(rs.getBigDecimal(19), currency).roundToCurrency(),
            Money.of(rs.getBigDecimal(20), currency).roundToCurrency(), rs.getBigDecimal(21),
            rs.getObject(22, LocalDate.class), rs.getObject(23, LocalDate.class),
            rs.getObject(24, LocalDate.class), rs.getString(25), rs.getObject(26, UUID.class),
            rs.getObject(27, UUID.class), rs.getObject(28, LocalDate.class), rs.getString(29),
            money(rs.getBigDecimal(30), currency), money(rs.getBigDecimal(31), currency),
            rs.getObject(32, UUID.class), rs.getObject(33, UUID.class),
            rs.getObject(34, UUID.class), rs.getObject(35, UUID.class),
            rs.getObject(36, UUID.class));
    }

    private static Money money(BigDecimal amount, CurrencyRef currency) {
        return amount == null ? null : Money.of(amount, currency).roundToCurrency();
    }
}
