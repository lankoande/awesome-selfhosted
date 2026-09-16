package io.corebanking.deposits;

import io.corebanking.calendar.Calendars;
import io.corebanking.calendar.ValueDatePolicy;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.id.Ids;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.Direction;
import io.corebanking.ledger.domain.error.InsufficientFundsException;
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
import io.corebanking.schema.expr.EvaluationContext;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Cheques : chequiers, paiement d'un cheque emis, opposition, incidents, remises.
 *
 * <ul>
 *   <li><b>Un chequier se delivre a deux</b>, aux frais du produit, preleves a la delivrance ;
 *       ses numeros suivent ceux du chequier precedent du compte, et deux chequiers d'un compte
 *       ne partagent aucun numero — le schema le tient.</li>
 *   <li><b>Un cheque emis se paie une fois</b>, au guichet ou par compensation, dans la limite du
 *       disponible ; presente sans provision, il est <b>rejete</b> et l'incident est enregistre,
 *       hors de la transaction refusee, parce qu'il fonde l'interdiction bancaire et la
 *       declaration a la centrale des incidents. Le cheque peut etre represente.</li>
 *   <li><b>L'opposition</b> n'a que les motifs que la loi admet — perte, vol, utilisation
 *       frauduleuse, procedure collective du porteur — et n'atteint pas un cheque deja paye.</li>
 *   <li><b>Une remise de cheque credite le client sauf bonne fin</b> : la valeur va au compte
 *       de cheques a l'encaissement, tenu au siege, et le montant est bloque sur le compte du
 *       client jusqu'au reglement par le correspondant ; un impaye contre-passe le credit.</li>
 *   <li>Les cheques ne consomment pas les plafonds du client : ils sont l'instrument d'un tiers
 *       porteur, et un refus de plafond ne serait pas un defaut de provision.</li>
 * </ul>
 */
public final class ChequeService {

    public static final String HOLD_TYPE = "CHEQUE_COLLECTION";

    private final Database database;
    private final PostingService postingService;

    public ChequeService(Database database, PostingService postingService) {
        this.database = Objects.requireNonNull(database, "database");
        this.postingService = Objects.requireNonNull(postingService, "postingService");
    }

    // ------------------------------------------------------------------ modele

    public record Book(UUID id, UUID legalEntityId, UUID accountId, long firstNumber,
                       long lastNumber, LocalDate deliveredOn, String status, Money fee,
                       UUID feeEntryId, UUID createdBy, UUID approvedBy) {}

    public record Cheque(UUID legalEntityId, UUID bookId, UUID accountId, long number,
                         String status, Money amount, String beneficiary, LocalDate paidOn,
                         UUID entryId, LocalDate stoppedOn, String stopReason) {}

    public record Incident(UUID id, UUID legalEntityId, UUID accountId, long number, Money amount,
                           LocalDate occurredOn, String reason, String presentedBy) {}

    public record ChequeDeposit(UUID id, UUID legalEntityId, UUID accountId, Money amount,
                                String draweeBank, String chequeNumber, String drawerName,
                                String channel, String status, LocalDate depositedOn,
                                LocalDate valueDate, UUID entryId, UUID holdId,
                                UUID collectionAccountId, LocalDate settledOn,
                                UUID settlementAccountId, UUID settlementEntryId,
                                LocalDate returnedOn, UUID returnEntryId, String returnReason,
                                UUID createdBy) {}

    public record BookIssue(UUID legalEntityId, UUID accountId, int count, UUID createdBy,
                            UUID approvedBy) {
        public BookIssue {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(accountId, "accountId");
            if (count < 1 || count > 200) {
                throw new IllegalArgumentException("Un chequier compte de 1 a 200 cheques : " + count);
            }
            if (createdBy == null || approvedBy == null || approvedBy.equals(createdBy)) {
                throw new IllegalArgumentException(
                    "Un chequier se delivre a deux : le demandeur ne peut pas etre le valideur");
            }
        }
    }

    public enum PaymentMode { CASH, CLEARING }

    /**
     * @param counterpartyAccountId la caisse qui paie au guichet, le nostro par compensation
     * @param beneficiary           le porteur, tel que le cheque le nomme
     */
    public record Payment(IdempotencyKey key, UUID legalEntityId, UUID accountId, long number,
                          Money amount, PaymentMode mode, UUID counterpartyAccountId,
                          String beneficiary, String channel, UUID actorId) {
        public Payment {
            OperationsService.requireCommand(key, legalEntityId, accountId, amount, actorId);
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(counterpartyAccountId, "counterpartyAccountId");
            if (number < 1) {
                throw new IllegalArgumentException("Numero de cheque invalide : " + number);
            }
        }
    }

    /** Le cheque paye et le recu de l'ecriture ; rejoue, le recu est celui de la premiere fois. */
    public record Paid(Cheque cheque, OperationsService.Receipt receipt) {}

    public enum StopReason { LOSS, THEFT, FRAUDULENT_USE, BEARER_INSOLVENCY }

    public record Deposit(IdempotencyKey key, UUID legalEntityId, UUID accountId, Money amount,
                          String draweeBank, String chequeNumber, String drawerName,
                          String channel, UUID actorId) {
        public Deposit {
            OperationsService.requireCommand(key, legalEntityId, accountId, amount, actorId);
            if (isBlank(draweeBank) || isBlank(chequeNumber) || isBlank(drawerName)) {
                throw new IllegalArgumentException(
                    "Une remise designe le cheque : banque tiree, numero, tireur");
            }
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }

    public record Deposited(ChequeDeposit deposit, boolean replayed) {}

    // ------------------------------------------------------------------ chequiers

    public Book issueBook(BookIssue issue) {
        return database.inTransaction(c -> {
            LocalDate on = OperationsService.businessDate(c, issue.legalEntityId());
            Account account = OperationsService.requireOperableAccount(
                c, issue.legalEntityId(), issue.accountId(), Money.of(1, currencyOf(c, issue.accountId())), on);
            ProductVersion product = ProductCatalog.resolveForAccount(
                c, issue.legalEntityId(), account.id(), on);
            // La numerotation se fait sous le verrou du compte : deux chequiers delivres en meme
            // temps se suivent, au lieu de se disputer les memes numeros devant l'exclusion.
            lockAccount(c, account.id());
            long first = nextNumber(c, account.id());
            long last = first + issue.count() - 1;
            UUID id = Ids.newId();
            OperationsService.Charges charges = OperationsService.Charges.of(
                DepositCatalog.chequeBookFee(product, account.currency()), product);
            UUID feeEntry = null;
            if (charges.fee().isPositive()) {
                List<PostingLine> lines = OperationsService.lines(
                    OperationSchemas.chequeBookFee(account.currency()),
                    EvaluationContext.builder().put("fee", charges.fee()).put("tax", charges.tax())
                        .build(),
                    account, charges.roles(Map.of(), product), on);
                feeEntry = postingService.post(PostingCommand.online(
                    IdempotencyKey.of(OperationSchemas.CHEQUE_BOOK_FEE + "-" + id),
                    issue.legalEntityId(), on, OperationSchemas.CHEQUE_BOOK_FEE, issue.createdBy(),
                    lines).withBranch(account.branchId())).entryId();
            }
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO cheque_book(id, legal_entity_id, account_id, first_number,"
                + " last_number, delivered_on, fee, fee_entry_id, created_by, approved_by)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                ps.setObject(1, id);
                ps.setObject(2, issue.legalEntityId());
                ps.setObject(3, account.id());
                ps.setLong(4, first);
                ps.setLong(5, last);
                ps.setObject(6, on);
                ps.setBigDecimal(7, charges.fee().plus(charges.tax()).amount());
                ps.setObject(8, feeEntry);
                ps.setObject(9, issue.createdBy());
                ps.setObject(10, issue.approvedBy());
                ps.executeUpdate();
            } catch (SQLException e) {
                if ("23P01".equals(e.getSQLState())) {
                    throw new IllegalStateException("Les numeros " + first + " a " + last
                        + " sont deja ceux d'un chequier du compte " + account.code(), e);
                }
                throw new LedgerStoreException("Delivrance du chequier", e);
            }
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO cheque(legal_entity_id, book_id, account_id, number) VALUES (?,?,?,?)")) {
                for (long number = first; number <= last; number++) {
                    ps.setObject(1, issue.legalEntityId());
                    ps.setObject(2, id);
                    ps.setObject(3, account.id());
                    ps.setLong(4, number);
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (SQLException e) {
                throw new LedgerStoreException("Numerotation du chequier", e);
            }
            return book(c, id).orElseThrow();
        });
    }

    private static void lockAccount(Connection c, UUID accountId) {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM account WHERE id = ? FOR UPDATE")) {
            ps.setObject(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Compte inconnu : " + accountId);
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Verrou du compte " + accountId, e);
        }
    }

    /** Le numero qui suit le dernier chequier du compte ; un premier chequier commence a 1. */
    private static long nextNumber(Connection c, UUID accountId) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT COALESCE(MAX(last_number), 0) + 1 FROM cheque_book WHERE account_id = ?")) {
            ps.setObject(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Numerotation du chequier", e);
        }
    }

    // ------------------------------------------------------------------ paiement

    public Paid pay(Payment payment) {
        try {
            return database.inTransaction(c -> payWithin(c, payment));
        } catch (NoProvisionException | InsufficientFundsException e) {
            // La transaction refusee est defaite et ses verrous rendus : l'incident se constate
            // dans la sienne, apres elle — dans la sienne parce qu'il fonde l'interdiction
            // bancaire et la declaration a la centrale ; apres elle parce qu'elle tient encore
            // le cheque tant qu'elle n'est pas defaite.
            Incident incident = database.inNewTransaction(
                c -> recordIncident(c, payment, "SANS_PROVISION"));
            throw new ChequeRejectedException(incident);
        }
    }

    /** Le disponible manque au controle ; l'ecriture n'est pas tentee. */
    private static final class NoProvisionException extends RuntimeException {
        NoProvisionException() {
            super("Provision insuffisante", null, false, false);
        }
    }

    private Paid payWithin(Connection c, Payment payment) {
        LocalDate on = OperationsService.businessDate(c, payment.legalEntityId());
        Account account = OperationsService.requireOperableAccount(
            c, payment.legalEntityId(), payment.accountId(), payment.amount(), on);
        Cheque cheque = lockCheque(c, account.id(), payment.number());
        if ("STOPPED".equals(cheque.status())) {
            throw new ChequeStoppedException(cheque);
        }
        if ("PAID".equals(cheque.status())) {
            if (payment.key().value().equals(idempotencyKeyOf(c, account.id(), payment.number()))) {
                PostingResult replay = postingService.post(replayCommand(c, payment, account));
                return new Paid(cheque, receipt(c, replay, account, cheque.paidOn(), payment));
            }
            throw new ChequeStateException(cheque, "deja paye le " + cheque.paidOn());
        }
        Account counterparty = counterparty(c, payment, account.currency());
        ValueDatePolicy policy = Calendars.load(database, payment.legalEntityId());
        LocalDate valueDate = policy.valueDateFor(OperationSchemas.CHEQUE_PAYMENT, payment.channel(),
                                                  Direction.DEBIT, on);
        // Le disponible se controle ici pour nommer l'incident ; le ledger le controle encore
        // au moment d'ecrire, sous verrou.
        Money available = Balances.available(c, account.id(), on);
        if (payment.amount().isGreaterThan(available)) {
            throw new NoProvisionException();
        }
        List<PostingLine> lines = OperationsService.lines(
            OperationSchemas.chequePayment(account.currency()),
            EvaluationContext.builder().put("amount", payment.amount()).build(),
            account, Map.of(OperationSchemas.ROLE_COUNTERPARTY, counterparty.id()), on);
        lines = OperationsService.withValueDate(lines, account.id(), valueDate);
        UUID operationBranch = payment.mode() == PaymentMode.CASH
            ? counterparty.branchId() : Branches.headOffice(c, payment.legalEntityId());
        if (payment.mode() == PaymentMode.CLEARING) {
            lines = atBranch(lines, counterparty.id(), operationBranch);
        }
        PostingResult result = postingService.post(PostingCommand.online(
            payment.key(), payment.legalEntityId(), on, OperationSchemas.CHEQUE_PAYMENT,
            payment.actorId(), lines).withBranch(operationBranch));
        OperationsService.wakeIfDormant(c, account, on, payment.actorId(), result);
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE cheque SET status = 'PAID', amount = ?, beneficiary = ?, paid_on = ?,"
            + " entry_id = ?, idempotency_key = ? WHERE account_id = ? AND number = ?")) {
            ps.setBigDecimal(1, payment.amount().amount());
            ps.setString(2, payment.beneficiary());
            ps.setObject(3, on);
            ps.setObject(4, result.entryId());
            ps.setString(5, payment.key().value());
            ps.setObject(6, account.id());
            ps.setLong(7, payment.number());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Paiement du cheque", e);
        }
        return new Paid(cheque(c, account.id(), payment.number()).orElseThrow(),
                        receipt(c, result, account, valueDate, payment));
    }

    /** La commande telle qu'elle a ete passee, pour que le rejeu rende le premier recu. */
    private PostingCommand replayCommand(Connection c, Payment payment, Account account) {
        Account counterparty = counterparty(c, payment, account.currency());
        LocalDate on = OperationsService.businessDate(c, payment.legalEntityId());
        List<PostingLine> lines = OperationsService.lines(
            OperationSchemas.chequePayment(account.currency()),
            EvaluationContext.builder().put("amount", payment.amount()).build(),
            account, Map.of(OperationSchemas.ROLE_COUNTERPARTY, counterparty.id()), on);
        return PostingCommand.online(payment.key(), payment.legalEntityId(), on,
                                     OperationSchemas.CHEQUE_PAYMENT, payment.actorId(), lines);
    }

    private static Account counterparty(Connection c, Payment payment, CurrencyRef currency) {
        if (payment.mode() == PaymentMode.CASH) {
            Account cash = OperationsService.requireInternal(c, payment.legalEntityId(),
                                                             payment.counterpartyAccountId(),
                                                             currency);
            Tills.requireOpenOn(c, cash.id(), OperationsService.businessDate(c, payment.legalEntityId()));
            return cash;
        }
        Account nostro = Accounts.loadAll(c, Set.of(payment.counterpartyAccountId()))
            .get(payment.counterpartyAccountId());
        if (nostro == null || !nostro.legalEntityId().equals(payment.legalEntityId())
            || nostro.kind() != AccountKind.NOSTRO || !nostro.currency().equals(currency)) {
            throw new IllegalArgumentException(
                "Un cheque paye par compensation se regle sur un compte nostro de l'entite, en "
                + currency.code());
        }
        return nostro;
    }

    private static Incident recordIncident(Connection c, Payment payment, String reason) {
        UUID id = Ids.newId();
        LocalDate on = OperationsService.businessDate(c, payment.legalEntityId());
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO cheque_incident(id, legal_entity_id, account_id, number, amount, currency,"
            + " occurred_on, reason, presented_by, created_by) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, payment.legalEntityId());
            ps.setObject(3, payment.accountId());
            ps.setLong(4, payment.number());
            ps.setBigDecimal(5, payment.amount().amount());
            ps.setString(6, payment.amount().currency().code());
            ps.setObject(7, on);
            ps.setString(8, reason);
            ps.setString(9, payment.beneficiary());
            ps.setObject(10, payment.actorId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Enregistrement de l'incident de paiement", e);
        }
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE cheque SET status = 'REJECTED' WHERE account_id = ? AND number = ?"
            + " AND status IN ('UNUSED','REJECTED')")) {
            ps.setObject(1, payment.accountId());
            ps.setLong(2, payment.number());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Rejet du cheque", e);
        }
        return new Incident(id, payment.legalEntityId(), payment.accountId(), payment.number(),
                            payment.amount(), on, reason, payment.beneficiary());
    }

    // ------------------------------------------------------------------ opposition

    public Cheque stop(UUID legalEntityId, UUID accountId, long number, StopReason reason,
                       UUID actorId) {
        Objects.requireNonNull(reason, "reason");
        return database.inTransaction(c -> {
            LocalDate on = OperationsService.businessDate(c, legalEntityId);
            Cheque cheque = lockCheque(c, accountId, number);
            if (!cheque.legalEntityId().equals(legalEntityId)) {
                throw new UnknownChequeException(accountId, number);
            }
            if ("PAID".equals(cheque.status())) {
                throw new ChequeStateException(cheque, "deja paye le " + cheque.paidOn()
                                               + " : l'opposition est tardive");
            }
            if ("STOPPED".equals(cheque.status())) {
                return cheque;
            }
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE cheque SET status = 'STOPPED', stopped_on = ?, stop_reason = ?"
                + " WHERE account_id = ? AND number = ?")) {
                ps.setObject(1, on);
                ps.setString(2, reason.name());
                ps.setObject(3, accountId);
                ps.setLong(4, number);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Opposition sur le cheque", e);
            }
            return cheque(c, accountId, number).orElseThrow();
        });
    }

    // ------------------------------------------------------------------ remises

    public Deposited deposit(Deposit deposit) {
        return database.inTransaction(c -> {
            Optional<ChequeDeposit> existing = depositByKey(c, deposit.legalEntityId(), deposit.key());
            if (existing.isPresent()) {
                return new Deposited(existing.get(), true);
            }
            LocalDate on = OperationsService.businessDate(c, deposit.legalEntityId());
            Account account = OperationsService.requireOperableAccount(
                c, deposit.legalEntityId(), deposit.accountId(), deposit.amount(), on);
            ProductVersion product = ProductCatalog.resolveForAccount(
                c, deposit.legalEntityId(), account.id(), on);
            UUID collectionId = DepositCatalog.chequeCollection(product).orElseThrow(() ->
                new PaymentService.NotAllowedException("Le produit " + product.code()
                    + " n'admet pas de remise de cheque : aucun compte de cheques a "
                    + "l'encaissement n'y est declare"));
            Account collection = OperationsService.requireInternal(c, deposit.legalEntityId(),
                                                                   collectionId, account.currency());
            ValueDatePolicy policy = Calendars.load(database, deposit.legalEntityId());
            LocalDate valueDate = policy.valueDateFor(OperationSchemas.CHEQUE_DEPOSIT,
                                                      deposit.channel(), Direction.CREDIT, on);
            UUID headOffice = Branches.headOffice(c, deposit.legalEntityId());
            List<PostingLine> lines = OperationsService.lines(
                OperationSchemas.chequeDeposit(account.currency()),
                EvaluationContext.builder().put("amount", deposit.amount()).build(),
                account, Map.of(OperationSchemas.ROLE_COLLECTION, collection.id()), on);
            lines = OperationsService.withValueDate(lines, account.id(), valueDate);
            lines = atBranch(lines, collection.id(), headOffice);
            PostingResult result = postingService.post(PostingCommand.online(
                deposit.key(), deposit.legalEntityId(), on, OperationSchemas.CHEQUE_DEPOSIT,
                deposit.actorId(), lines).withBranch(account.branchId()));
            if (result.replayed()) {
                return new Deposited(depositByKey(c, deposit.legalEntityId(), deposit.key())
                    .orElseThrow(() -> new LedgerStoreException(
                        "Remise absente pour une cle d'idempotence deja traitee")), true);
            }
            OperationsService.wakeIfDormant(c, account, on, deposit.actorId(), result);
            UUID id = Ids.newId();
            // Credite sauf bonne fin : le montant reste indisponible jusqu'au reglement.
            UUID hold = Holds.place(c, new Holds.Placement(account.id(), deposit.amount(), HOLD_TYPE,
                                                           id.toString(), on, null, deposit.actorId()));
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO cheque_deposit(id, legal_entity_id, account_id, idempotency_key, amount,"
                + " currency, drawee_bank, cheque_number, drawer_name, channel, deposited_on,"
                + " value_date, entry_id, hold_id, collection_account_id, created_by)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setObject(1, id);
                ps.setObject(2, deposit.legalEntityId());
                ps.setObject(3, account.id());
                ps.setString(4, deposit.key().value());
                ps.setBigDecimal(5, deposit.amount().amount());
                ps.setString(6, account.currency().code());
                ps.setString(7, deposit.draweeBank().trim());
                ps.setString(8, deposit.chequeNumber().trim());
                ps.setString(9, deposit.drawerName().trim());
                ps.setString(10, deposit.channel());
                ps.setObject(11, on);
                ps.setObject(12, valueDate);
                ps.setObject(13, result.entryId());
                ps.setObject(14, hold);
                ps.setObject(15, collection.id());
                ps.setObject(16, deposit.actorId());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Enregistrement de la remise de cheque", e);
            }
            return new Deposited(requireDeposit(c, id), false);
        });
    }

    /** Le correspondant a paye : le blocage tombe, la valeur passe de l'encaissement au nostro. */
    public ChequeDeposit settleDeposit(UUID depositId, UUID nostroAccountId, UUID actorId) {
        return database.inTransaction(c -> {
            ChequeDeposit deposit = lockDeposit(c, depositId);
            if (!"DEPOSITED".equals(deposit.status())) {
                throw new ChequeDepositStateException(deposit, "attendu DEPOSITED");
            }
            Account nostro = Accounts.loadAll(c, Set.of(nostroAccountId)).get(nostroAccountId);
            if (nostro == null || !nostro.legalEntityId().equals(deposit.legalEntityId())
                || nostro.kind() != AccountKind.NOSTRO
                || !nostro.currency().code().equals(deposit.amount().currency().code())) {
                throw new IllegalArgumentException(
                    "L'encaissement se regle sur un compte nostro de l'entite, en "
                    + deposit.amount().currency().code());
            }
            LocalDate on = OperationsService.businessDate(c, deposit.legalEntityId());
            Holds.release(c, deposit.holdId(), on, actorId);
            String narrative = "Encaissement du cheque " + deposit.chequeNumber() + " sur "
                + deposit.draweeBank();
            PostingResult result = postingService.post(PostingCommand.online(
                IdempotencyKey.of(OperationSchemas.CHEQUE_COLLECTION + "-" + deposit.id()),
                deposit.legalEntityId(), on, OperationSchemas.CHEQUE_COLLECTION, actorId,
                List.of(PostingLine.debit(nostro.id(), deposit.amount(), on, narrative),
                        PostingLine.credit(deposit.collectionAccountId(), deposit.amount(), on,
                                           narrative)))
                .withBranch(Branches.headOffice(c, deposit.legalEntityId())));
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE cheque_deposit SET status = 'SETTLED', settled_on = ?,"
                + " settlement_account_id = ?, settlement_entry_id = ? WHERE id = ?")) {
                ps.setObject(1, on);
                ps.setObject(2, nostro.id());
                ps.setObject(3, result.entryId());
                ps.setObject(4, depositId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Reglement de la remise", e);
            }
            return requireDeposit(c, depositId);
        });
    }

    /** Impaye : le credit sauf bonne fin est contre-passe, le blocage tombe avec lui. */
    public ChequeDeposit returnDeposit(UUID depositId, String reason, UUID actorId) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Le motif de l'impaye est obligatoire");
        }
        return database.inTransaction(c -> {
            ChequeDeposit deposit = lockDeposit(c, depositId);
            if (!"DEPOSITED".equals(deposit.status())) {
                throw new ChequeDepositStateException(deposit, "attendu DEPOSITED");
            }
            LocalDate on = OperationsService.businessDate(c, deposit.legalEntityId());
            Holds.release(c, deposit.holdId(), on, actorId);
            PostingResult result = postingService.reverse(
                deposit.entryId(), deposit.depositedOn(), on,
                IdempotencyKey.of("CHEQUE_RETURN-" + deposit.id()),
                "Cheque impaye : " + reason.trim());
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE cheque_deposit SET status = 'RETURNED', returned_on = ?, return_entry_id = ?,"
                + " return_reason = ? WHERE id = ?")) {
                ps.setObject(1, on);
                ps.setObject(2, result.entryId());
                ps.setString(3, reason.trim());
                ps.setObject(4, depositId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Retour de la remise", e);
            }
            return requireDeposit(c, depositId);
        });
    }

    // ------------------------------------------------------------------ lecture

    private static final String SELECT_BOOK =
        "SELECT b.id, b.legal_entity_id, b.account_id, b.first_number, b.last_number, b.delivered_on,"
        + " b.status, b.fee, b.fee_entry_id, b.created_by, b.approved_by,"
        + " cur.code, cur.scale, cur.rounding_mode"
        + " FROM cheque_book b JOIN account a ON a.id = b.account_id"
        + " JOIN currency cur ON cur.code = a.currency";

    public static Optional<Book> book(Connection c, UUID bookId) {
        try (PreparedStatement ps = c.prepareStatement(SELECT_BOOK + " WHERE b.id = ?")) {
            ps.setObject(1, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readBook(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du chequier", e);
        }
    }

    public static List<Book> books(Connection c, UUID accountId) {
        List<Book> books = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            SELECT_BOOK + " WHERE b.account_id = ? ORDER BY b.first_number")) {
            ps.setObject(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    books.add(readBook(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des chequiers", e);
        }
        return books;
    }

    private static Book readBook(ResultSet rs) throws SQLException {
        CurrencyRef currency = new CurrencyRef(rs.getString(12), rs.getInt(13),
                                               RoundingMode.valueOf(rs.getString(14)));
        return new Book(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getLong(4), rs.getLong(5),
                        rs.getObject(6, LocalDate.class), rs.getString(7),
                        Money.of(rs.getBigDecimal(8), currency), rs.getObject(9, UUID.class),
                        rs.getObject(10, UUID.class), rs.getObject(11, UUID.class));
    }

    private static final String SELECT_CHEQUE =
        "SELECT q.legal_entity_id, q.book_id, q.account_id, q.number, q.status, q.amount,"
        + " q.beneficiary, q.paid_on, q.entry_id, q.stopped_on, q.stop_reason,"
        + " cur.code, cur.scale, cur.rounding_mode"
        + " FROM cheque q JOIN account a ON a.id = q.account_id"
        + " JOIN currency cur ON cur.code = a.currency";

    public static Optional<Cheque> cheque(Connection c, UUID accountId, long number) {
        return oneCheque(c, SELECT_CHEQUE + " WHERE q.account_id = ? AND q.number = ?", accountId,
                         number);
    }

    private static Cheque lockCheque(Connection c, UUID accountId, long number) {
        return oneCheque(c, SELECT_CHEQUE + " WHERE q.account_id = ? AND q.number = ? FOR UPDATE OF q",
                         accountId, number)
            .orElseThrow(() -> new UnknownChequeException(accountId, number));
    }

    private static Optional<Cheque> oneCheque(Connection c, String sql, UUID accountId, long number) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, accountId);
            ps.setLong(2, number);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readCheque(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du cheque", e);
        }
    }

    /** Les cheques d'un compte, par numero, d'un statut s'il est donne. */
    public static List<Cheque> cheques(Connection c, UUID accountId, String status) {
        List<Cheque> cheques = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            SELECT_CHEQUE + " WHERE q.account_id = ? AND (?::text IS NULL OR q.status = ?)"
            + " ORDER BY q.number")) {
            ps.setObject(1, accountId);
            ps.setString(2, status);
            ps.setString(3, status);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cheques.add(readCheque(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des cheques", e);
        }
        return cheques;
    }

    private static String idempotencyKeyOf(Connection c, UUID accountId, long number) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT idempotency_key FROM cheque WHERE account_id = ? AND number = ?")) {
            ps.setObject(1, accountId);
            ps.setLong(2, number);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du cheque", e);
        }
    }

    private static Cheque readCheque(ResultSet rs) throws SQLException {
        CurrencyRef currency = new CurrencyRef(rs.getString(12), rs.getInt(13),
                                               RoundingMode.valueOf(rs.getString(14)));
        java.math.BigDecimal amount = rs.getBigDecimal(6);
        return new Cheque(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                          rs.getObject(3, UUID.class), rs.getLong(4), rs.getString(5),
                          amount == null ? null : Money.of(amount, currency), rs.getString(7),
                          rs.getObject(8, LocalDate.class), rs.getObject(9, UUID.class),
                          rs.getObject(10, LocalDate.class), rs.getString(11));
    }

    public static List<Incident> incidents(Connection c, UUID accountId) {
        List<Incident> incidents = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT i.id, i.legal_entity_id, i.account_id, i.number, i.amount, i.occurred_on,"
            + " i.reason, i.presented_by, cur.code, cur.scale, cur.rounding_mode"
            + " FROM cheque_incident i JOIN currency cur ON cur.code = i.currency"
            + " WHERE i.account_id = ? ORDER BY i.occurred_on, i.created_at")) {
            ps.setObject(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    CurrencyRef currency = new CurrencyRef(rs.getString(9), rs.getInt(10),
                                                           RoundingMode.valueOf(rs.getString(11)));
                    incidents.add(new Incident(rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getLong(4),
                        Money.of(rs.getBigDecimal(5), currency), rs.getObject(6, LocalDate.class),
                        rs.getString(7), rs.getString(8)));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des incidents de paiement", e);
        }
        return incidents;
    }

    private static final String SELECT_DEPOSIT =
        "SELECT d.id, d.legal_entity_id, d.account_id, d.amount, d.drawee_bank, d.cheque_number,"
        + " d.drawer_name, d.channel, d.status, d.deposited_on, d.value_date, d.entry_id, d.hold_id,"
        + " d.collection_account_id, d.settled_on, d.settlement_account_id, d.settlement_entry_id,"
        + " d.returned_on, d.return_entry_id, d.return_reason, d.created_by,"
        + " cur.code, cur.scale, cur.rounding_mode"
        + " FROM cheque_deposit d JOIN currency cur ON cur.code = d.currency";

    public static Optional<ChequeDeposit> findDeposit(Connection c, UUID depositId) {
        return oneDeposit(c, SELECT_DEPOSIT + " WHERE d.id = ?", ps -> ps.setObject(1, depositId));
    }

    public static ChequeDeposit requireDeposit(Connection c, UUID depositId) {
        return findDeposit(c, depositId).orElseThrow(() -> new UnknownChequeDepositException(depositId));
    }

    private static ChequeDeposit lockDeposit(Connection c, UUID depositId) {
        return oneDeposit(c, SELECT_DEPOSIT + " WHERE d.id = ? FOR UPDATE OF d",
                          ps -> ps.setObject(1, depositId))
            .orElseThrow(() -> new UnknownChequeDepositException(depositId));
    }

    private static Optional<ChequeDeposit> depositByKey(Connection c, UUID legalEntityId,
                                                        IdempotencyKey key) {
        return oneDeposit(c, SELECT_DEPOSIT + " WHERE d.legal_entity_id = ? AND d.idempotency_key = ?",
                          ps -> {
                              ps.setObject(1, legalEntityId);
                              ps.setString(2, key.value());
                          });
    }

    /** Les remises de l'entite, de la plus recente a la plus ancienne, par statut s'il est donne. */
    public static List<ChequeDeposit> deposits(Connection c, UUID legalEntityId, String status,
                                               int offset, int limit) {
        List<ChequeDeposit> deposits = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            SELECT_DEPOSIT + " WHERE d.legal_entity_id = ? AND (?::text IS NULL OR d.status = ?)"
            + " ORDER BY d.deposited_on DESC, d.created_at DESC, d.id OFFSET ? LIMIT ?")) {
            ps.setObject(1, legalEntityId);
            ps.setString(2, status);
            ps.setString(3, status);
            ps.setInt(4, offset);
            ps.setInt(5, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    deposits.add(readDeposit(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des remises", e);
        }
        return deposits;
    }

    public static long countDeposits(Connection c, UUID legalEntityId, String status) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT count(*) FROM cheque_deposit d WHERE d.legal_entity_id = ?"
            + " AND (?::text IS NULL OR d.status = ?)")) {
            ps.setObject(1, legalEntityId);
            ps.setString(2, status);
            ps.setString(3, status);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Decompte des remises", e);
        }
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private static Optional<ChequeDeposit> oneDeposit(Connection c, String sql, Binder binder) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readDeposit(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture de la remise", e);
        }
    }

    private static ChequeDeposit readDeposit(ResultSet rs) throws SQLException {
        CurrencyRef currency = new CurrencyRef(rs.getString(22), rs.getInt(23),
                                               RoundingMode.valueOf(rs.getString(24)));
        return new ChequeDeposit(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
            rs.getObject(3, UUID.class), Money.of(rs.getBigDecimal(4), currency), rs.getString(5),
            rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
            rs.getObject(10, LocalDate.class), rs.getObject(11, LocalDate.class),
            rs.getObject(12, UUID.class), rs.getObject(13, UUID.class), rs.getObject(14, UUID.class),
            rs.getObject(15, LocalDate.class), rs.getObject(16, UUID.class),
            rs.getObject(17, UUID.class), rs.getObject(18, LocalDate.class),
            rs.getObject(19, UUID.class), rs.getString(20), rs.getObject(21, UUID.class));
    }

    // ------------------------------------------------------------------ interne

    private static CurrencyRef currencyOf(Connection c, UUID accountId) {
        return Balances.currencyOf(c, accountId);
    }

    private static OperationsService.Receipt receipt(Connection c, PostingResult result,
                                                     Account account, LocalDate valueDate,
                                                     Payment payment) {
        Money after = result.balancesAfter().get(account.id());
        if (after == null) {
            after = Balances.current(c, account.id());
        }
        Money zero = Money.zero(account.currency());
        UUID branch = payment.mode() == PaymentMode.CASH
            ? Accounts.loadAll(c, Set.of(payment.counterpartyAccountId()))
                .get(payment.counterpartyAccountId()).branchId()
            : account.branchId();
        boolean remote = branch != null && !branch.equals(account.branchId());
        return new OperationsService.Receipt(result.entryId(), result.entryNumber(),
            result.bookingDate(), valueDate, payment.amount(), zero, zero, after, result.replayed(),
            branch, remote);
    }

    private static List<PostingLine> atBranch(List<PostingLine> lines, UUID accountId, UUID branch) {
        List<PostingLine> placed = new ArrayList<>(lines.size());
        for (PostingLine line : lines) {
            placed.add(line.accountId().equals(accountId) ? line.withBranch(branch) : line);
        }
        return placed;
    }

    public static class UnknownChequeException extends RuntimeException {
        public UnknownChequeException(UUID accountId, long number) {
            super("Cheque inconnu : numero " + number + " sur le compte " + accountId);
        }
    }

    public static class UnknownChequeDepositException extends RuntimeException {
        public UnknownChequeDepositException(UUID depositId) {
            super("Remise de cheque inconnue : " + depositId);
        }
    }

    /** Le cheque n'est pas dans l'etat que l'acte suppose. */
    public static class ChequeStateException extends IllegalStateException {
        public ChequeStateException(Cheque cheque, String detail) {
            super("Cheque " + cheque.number() + " en etat " + cheque.status() + " : " + detail);
        }
    }

    public static class ChequeStoppedException extends IllegalStateException {
        public ChequeStoppedException(Cheque cheque) {
            super("Cheque " + cheque.number() + " frappe d'opposition le " + cheque.stoppedOn()
                  + " (" + cheque.stopReason() + ") : il ne se paie pas");
        }
    }

    /** Presente sans provision : rejete, et l'incident est enregistre. */
    public static class ChequeRejectedException extends IllegalStateException {
        private final Incident incident;

        public ChequeRejectedException(Incident incident) {
            super("Cheque " + incident.number() + " de " + incident.amount().roundToCurrency()
                  + " rejete le " + incident.occurredOn() + " : " + incident.reason()
                  + " ; incident " + incident.id() + " enregistre");
            this.incident = incident;
        }

        public Incident incident() {
            return incident;
        }
    }

    public static class ChequeDepositStateException extends IllegalStateException {
        public ChequeDepositStateException(ChequeDeposit deposit, String detail) {
            super("Remise " + deposit.id() + " en etat " + deposit.status() + " : " + detail);
        }
    }
}
