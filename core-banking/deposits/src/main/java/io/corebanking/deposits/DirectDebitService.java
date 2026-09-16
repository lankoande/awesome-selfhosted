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
import io.corebanking.ledger.store.AccountBlockedException;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.ledger.store.Balances;
import io.corebanking.ledger.store.Branches;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.product.ProductCatalog;
import io.corebanking.product.ProductVersion;
import io.corebanking.schema.expr.EvaluationContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Prelevements : mandats, prelevements recus et emis.
 *
 * <ul>
 *   <li><b>Un mandat</b> est l'autorisation qu'un client donne a un creancier — de la banque ou
 *       d'ailleurs — de debiter son compte : reference, validite, plafond par prelevement.
 *       Enregistre a deux, revoque par le client ; sans mandat en vigueur, rien ne se debite.</li>
 *   <li><b>Un prelevement recu</b> est presente par le creancier avec son echeance. A l'echeance
 *       — tout de suite si elle est arrivee, par l'arrete sinon — le debiteur est debite du
 *       montant, du frais et de la taxe du produit ; le montant va au creancier s'il est de la
 *       banque, au compte de reglement du produit s'il est d'ailleurs, ou il attend le
 *       correspondant. Sans provision, sans mandat en vigueur, sur un compte qui ne peut pas
 *       operer, le prelevement est <b>rejete</b> et le motif est enregistre : un rejet est un
 *       resultat, pas une erreur, et le creancier en est informe. Regle, il peut encore etre
 *       <b>rembourse</b> au debiteur qui le conteste ; avant reglement, le creancier peut le
 *       rappeler et il s'annule par contre-passation.</li>
 *   <li><b>Un prelevement emis</b> est la remise d'un client creancier sur un debiteur d'une
 *       autre banque : a l'echeance, le creancier est credite <b>sauf bonne fin</b>, la valeur va
 *       au compte de prelevements a l'encaissement, tenu au siege, et le montant reste bloque
 *       jusqu'au reglement par le correspondant ; un retour du debiteur contre-passe le credit,
 *       ou, apres reglement, le reprend au creancier.</li>
 *   <li>Un prelevement ne consomme pas les plafonds du client : c'est le mandat qui le borne, et
 *       il ne reveille pas un compte dormant — l'acte est celui du creancier.</li>
 * </ul>
 *
 * <p>L'execution d'un prelevement, en ligne comme a l'arrete, tient dans une transaction : la
 * comptabilisation se tente sous un point de sauvegarde, et un refus du ledger — provision,
 * blocage — y ramene avant d'enregistrer le rejet. Rien de la tentative refusee ne reste, et le
 * rejet est ecrit avec la transaction qui l'a constate, reelle ou a blanc.
 */
public final class DirectDebitService {

    public static final String HOLD_TYPE = "DIRECT_DEBIT_COLLECTION";
    /** L'etape de l'arrete qui execute les prelevements a l'echeance ; cle de ses ecritures. */
    public static final String STEP = "DIRECT_DEBITS";

    private final Database database;
    private final PostingService postingService;

    public DirectDebitService(Database database, PostingService postingService) {
        this.database = Objects.requireNonNull(database, "database");
        this.postingService = Objects.requireNonNull(postingService, "postingService");
    }

    // ------------------------------------------------------------------ modele

    public record Mandate(UUID id, UUID legalEntityId, UUID accountId, String reference,
                          String creditorId, String creditorName, UUID creditorAccountId,
                          String creditorBank, String creditorAccount, LocalDate signedOn,
                          LocalDate validFrom, LocalDate validTo, Money maxAmount, String status,
                          LocalDate revokedOn, String revocationReason, UUID createdBy,
                          UUID approvedBy) {
        public boolean internal() {
            return creditorAccountId != null;
        }
    }

    /**
     * @param creditorAccountId le compte du creancier s'il est client de la banque ; sinon
     *                          {@code creditorBank} et {@code creditorAccount} le designent
     * @param maxAmount         plafond par prelevement, ou nul
     */
    public record MandateDraft(UUID legalEntityId, UUID accountId, String reference,
                               String creditorId, String creditorName, UUID creditorAccountId,
                               String creditorBank, String creditorAccount, LocalDate signedOn,
                               LocalDate validFrom, LocalDate validTo, Money maxAmount,
                               UUID createdBy, UUID approvedBy) {
        public MandateDraft {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(signedOn, "signedOn");
            Objects.requireNonNull(validFrom, "validFrom");
            if (isBlank(reference) || isBlank(creditorId) || isBlank(creditorName)) {
                throw new IllegalArgumentException(
                    "Un mandat designe son creancier et porte une reference");
            }
            boolean internal = creditorAccountId != null;
            boolean external = !isBlank(creditorBank) && !isBlank(creditorAccount);
            if (internal == external) {
                throw new IllegalArgumentException("Le creancier est un compte de la banque, ou une "
                    + "banque et un compte d'ailleurs : l'un ou l'autre");
            }
            if (validTo != null && validTo.isBefore(validFrom)) {
                throw new IllegalArgumentException("Un mandat ne finit pas avant de commencer");
            }
            if (maxAmount != null && !maxAmount.isPositive()) {
                throw new IllegalArgumentException("Le plafond d'un mandat est positif");
            }
            if (createdBy == null || approvedBy == null || approvedBy.equals(createdBy)) {
                throw new IllegalArgumentException(
                    "Un mandat s'enregistre a deux : le demandeur ne peut pas etre le valideur");
            }
        }
    }

    public enum DirectionKind { RECEIVED, ISSUED }

    /**
     * @param accountId le compte du client de la banque : debiteur d'un prelevement recu,
     *                  creancier d'un prelevement emis
     */
    public record DirectDebit(UUID id, UUID legalEntityId, DirectionKind direction, UUID accountId,
                              UUID mandateId, Money amount, Money fee, Money tax, LocalDate dueDate,
                              String counterpartyName, String counterpartyBank,
                              String counterpartyAccount, String mandateReference,
                              String reference, String channel, String status,
                              LocalDate presentedOn, LocalDate executedOn, UUID executedRunId,
                              LocalDate valueDate, UUID entryId, UUID feeEntryId, UUID holdId,
                              UUID clearingAccountId, String rejectionReason, LocalDate settledOn,
                              UUID settlementAccountId, UUID settlementEntryId,
                              LocalDate closedOn, UUID closeEntryId, String closeReason,
                              UUID createdBy) {}

    /** Presentation d'un prelevement recu, sur un mandat du debiteur. */
    public record Presentation(IdempotencyKey key, UUID legalEntityId, UUID mandateId,
                               Money amount, LocalDate dueDate, String reference, String channel,
                               UUID actorId) {
        public Presentation {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(mandateId, "mandateId");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(dueDate, "dueDate");
            Objects.requireNonNull(actorId, "actorId");
            requireAmount(amount);
        }
    }

    /** Remise d'un prelevement emis par un client creancier sur un debiteur d'une autre banque. */
    public record Issue(IdempotencyKey key, UUID legalEntityId, UUID accountId, Money amount,
                        LocalDate dueDate, String debtorName, String debtorBank,
                        String debtorAccount, String mandateReference, String reference,
                        String channel, UUID actorId) {
        public Issue {
            OperationsService.requireCommand(key, legalEntityId, accountId, amount, actorId);
            Objects.requireNonNull(dueDate, "dueDate");
            if (isBlank(debtorName) || isBlank(debtorBank) || isBlank(debtorAccount)
                || isBlank(mandateReference)) {
                throw new IllegalArgumentException(
                    "Un prelevement emis designe son debiteur — nom, banque, compte — et le mandat");
            }
        }
    }

    /** Le prelevement tel qu'il est apres presentation, execute si l'echeance est arrivee. */
    public record Presented(DirectDebit directDebit, boolean replayed) {}

    private static void requireAmount(Money amount) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Montant de prelevement non positif : " + amount);
        }
        if (!amount.isBookable()) {
            throw new IllegalArgumentException("Montant de prelevement non imputable : " + amount);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // ------------------------------------------------------------------ mandats

    public Mandate registerMandate(MandateDraft draft) {
        return database.inTransaction(c -> {
            LocalDate on = OperationsService.businessDate(c, draft.legalEntityId());
            CurrencyRef currency = Balances.currencyOf(c, draft.accountId());
            Account debtor = OperationsService.requireOperableAccount(
                c, draft.legalEntityId(), draft.accountId(), Money.of(1, currency), on);
            if (draft.maxAmount() != null && !draft.maxAmount().currency().equals(currency)) {
                throw new IllegalArgumentException("Le plafond du mandat est en "
                    + draft.maxAmount().currency().code() + ", le compte en " + currency.code());
            }
            if (draft.creditorAccountId() != null) {
                Account creditor = Accounts.loadAll(c, Set.of(draft.creditorAccountId()))
                    .get(draft.creditorAccountId());
                if (creditor == null || !creditor.legalEntityId().equals(draft.legalEntityId())
                    || creditor.kind() != AccountKind.CUSTOMER
                    || !creditor.currency().equals(currency)) {
                    throw new IllegalArgumentException("Le creancier de la banque est un compte "
                        + "client de l'entite, en " + currency.code());
                }
                if (creditor.id().equals(debtor.id())) {
                    throw new IllegalArgumentException("Un compte ne se preleve pas lui-meme");
                }
                if (!creditor.status().acceptsPosting()) {
                    throw new IllegalStateException("Le compte creancier " + creditor.code()
                        + " est " + creditor.status() + " : il ne recoit plus rien");
                }
            }
            UUID id = Ids.newId();
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO debit_mandate(id, legal_entity_id, account_id, reference, creditor_id,"
                + " creditor_name, creditor_account_id, creditor_bank, creditor_account, signed_on,"
                + " valid_from, valid_to, max_amount, currency, created_by, approved_by)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setObject(1, id);
                ps.setObject(2, draft.legalEntityId());
                ps.setObject(3, debtor.id());
                ps.setString(4, draft.reference().trim());
                ps.setString(5, draft.creditorId().trim());
                ps.setString(6, draft.creditorName().trim());
                ps.setObject(7, draft.creditorAccountId());
                ps.setString(8, draft.creditorAccountId() == null ? draft.creditorBank().trim() : null);
                ps.setString(9, draft.creditorAccountId() == null ? draft.creditorAccount().trim() : null);
                ps.setObject(10, draft.signedOn());
                ps.setObject(11, draft.validFrom());
                ps.setObject(12, draft.validTo());
                ps.setBigDecimal(13, draft.maxAmount() == null ? null : draft.maxAmount().amount());
                ps.setString(14, currency.code());
                ps.setObject(15, draft.createdBy());
                ps.setObject(16, draft.approvedBy());
                ps.executeUpdate();
            } catch (SQLException e) {
                if ("23505".equals(e.getSQLState())) {
                    throw new IllegalStateException("Le mandat " + draft.reference().trim()
                        + " du creancier " + draft.creditorId().trim() + " existe deja", e);
                }
                throw new LedgerStoreException("Enregistrement du mandat", e);
            }
            return requireMandate(c, id);
        });
    }

    /** Le client revoque : plus rien ne se preleve sur ce mandat. Deja revoque, il le reste. */
    public Mandate revokeMandate(UUID legalEntityId, UUID mandateId, String reason, UUID actorId) {
        if (isBlank(reason)) {
            throw new IllegalArgumentException("Le motif de la revocation est obligatoire");
        }
        return database.inTransaction(c -> {
            Mandate mandate = lockMandate(c, mandateId);
            if (!mandate.legalEntityId().equals(legalEntityId)) {
                throw new UnknownMandateException(mandateId);
            }
            if ("REVOKED".equals(mandate.status())) {
                return mandate;
            }
            LocalDate on = OperationsService.businessDate(c, legalEntityId);
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE debit_mandate SET status = 'REVOKED', revoked_on = ?, revocation_reason = ?"
                + " WHERE id = ?")) {
                ps.setObject(1, on);
                ps.setString(2, reason.trim());
                ps.setObject(3, mandateId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Revocation du mandat", e);
            }
            return requireMandate(c, mandateId);
        });
    }

    // ------------------------------------------------------------------ presentation et remise

    /**
     * Presente un prelevement recu. L'echeance arrivee, il s'execute tout de suite ; a venir,
     * l'arrete de l'echeance l'executera. Rejoue avec sa cle, la presentation rend le prelevement
     * tel qu'il est.
     */
    public Presented present(Presentation presentation) {
        record Inserted(UUID id, boolean replayed) {}
        Inserted inserted = database.inTransaction(c -> {
            Optional<DirectDebit> existing = byKey(c, presentation.legalEntityId(), presentation.key());
            if (existing.isPresent()) {
                return new Inserted(existing.get().id(), true);
            }
            LocalDate on = OperationsService.businessDate(c, presentation.legalEntityId());
            Mandate mandate = findMandate(c, presentation.mandateId())
                .filter(m -> m.legalEntityId().equals(presentation.legalEntityId()))
                .orElseThrow(() -> new UnknownMandateException(presentation.mandateId()));
            requireUsable(mandate, presentation.amount(), presentation.dueDate());
            Account debtor = Accounts.loadAll(c, Set.of(mandate.accountId())).get(mandate.accountId());
            if (debtor == null) {
                throw new LedgerStoreException("Compte du mandat introuvable : " + mandate.accountId());
            }
            if (!mandate.internal()) {
                // Un creancier d'ailleurs est paye par la compensation : le produit doit savoir
                // ou les fonds attendent le correspondant. Dit ici, pas au moment d'executer.
                ProductVersion product = ProductCatalog.resolveForAccount(
                    c, presentation.legalEntityId(), debtor.id(), on);
                DepositCatalog.paymentClearing(product).orElseThrow(() ->
                    new PaymentService.NotAllowedException("Le produit " + product.code()
                        + " n'admet pas de prelevement d'un creancier exterieur : aucun compte de "
                        + "reglement sortant n'y est declare"));
            }
            UUID id = Ids.newId();
            insert(c, id, presentation.legalEntityId(), DirectionKind.RECEIVED, debtor.id(),
                   mandate.id(), presentation.key(), presentation.amount(), presentation.dueDate(),
                   mandate.creditorName(), mandate.creditorBank(), mandate.creditorAccount(),
                   mandate.reference(), presentation.reference(), presentation.channel(), on,
                   presentation.actorId());
            return new Inserted(id, false);
        });
        if (inserted.replayed()) {
            return new Presented(database.inTransaction(c -> require(c, inserted.id())), true);
        }
        return new Presented(execute(inserted.id(), null, presentation.actorId()), false);
    }

    /** Remet un prelevement emis ; a l'echeance arrivee, le creancier est credite tout de suite. */
    public Presented issue(Issue issue) {
        record Inserted(UUID id, boolean replayed) {}
        Inserted inserted = database.inTransaction(c -> {
            Optional<DirectDebit> existing = byKey(c, issue.legalEntityId(), issue.key());
            if (existing.isPresent()) {
                return new Inserted(existing.get().id(), true);
            }
            LocalDate on = OperationsService.businessDate(c, issue.legalEntityId());
            Account creditor = OperationsService.requireOperableAccount(
                c, issue.legalEntityId(), issue.accountId(), issue.amount(), on);
            ProductVersion product = ProductCatalog.resolveForAccount(
                c, issue.legalEntityId(), creditor.id(), on);
            DepositCatalog.directDebitCollection(product).orElseThrow(() ->
                new PaymentService.NotAllowedException("Le produit " + product.code()
                    + " n'emet pas de prelevement : aucun compte de prelevements a l'encaissement "
                    + "n'y est declare"));
            UUID id = Ids.newId();
            insert(c, id, issue.legalEntityId(), DirectionKind.ISSUED, creditor.id(), null,
                   issue.key(), issue.amount(), issue.dueDate(), issue.debtorName().trim(),
                   issue.debtorBank().trim(), issue.debtorAccount().trim(),
                   issue.mandateReference().trim(), issue.reference(), issue.channel(), on,
                   issue.actorId());
            return new Inserted(id, false);
        });
        if (inserted.replayed()) {
            return new Presented(database.inTransaction(c -> require(c, inserted.id())), true);
        }
        return new Presented(execute(inserted.id(), null, issue.actorId()), false);
    }

    private static void requireUsable(Mandate mandate, Money amount, LocalDate dueDate) {
        if (!"ACTIVE".equals(mandate.status())) {
            throw new MandateStateException(mandate, "revoque le " + mandate.revokedOn()
                                            + " (" + mandate.revocationReason() + ")");
        }
        if (dueDate.isBefore(mandate.validFrom())
            || (mandate.validTo() != null && dueDate.isAfter(mandate.validTo()))) {
            throw new MandateStateException(mandate, "l'echeance " + dueDate
                + " est hors de sa validite, du " + mandate.validFrom()
                + (mandate.validTo() == null ? "" : " au " + mandate.validTo()));
        }
        if (mandate.maxAmount() != null) {
            if (!amount.currency().equals(mandate.maxAmount().currency())) {
                throw new IllegalArgumentException("Le prelevement est en " + amount.currency().code()
                    + ", le mandat en " + mandate.maxAmount().currency().code());
            }
            if (amount.isGreaterThan(mandate.maxAmount())) {
                throw new MandateStateException(mandate, "le montant "
                    + amount.roundToCurrency() + " depasse le plafond du mandat, "
                    + mandate.maxAmount().roundToCurrency());
            }
        }
    }

    private static void insert(Connection c, UUID id, UUID legalEntityId, DirectionKind direction,
                               UUID accountId, UUID mandateId, IdempotencyKey key, Money amount,
                               LocalDate dueDate, String counterpartyName, String counterpartyBank,
                               String counterpartyAccount, String mandateReference,
                               String reference, String channel, LocalDate on, UUID actorId) {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO direct_debit(id, legal_entity_id, direction, account_id, mandate_id,"
            + " idempotency_key, amount, currency, due_date, counterparty_name, counterparty_bank,"
            + " counterparty_account, mandate_reference, reference, channel, presented_on,"
            + " created_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, legalEntityId);
            ps.setString(3, direction.name());
            ps.setObject(4, accountId);
            ps.setObject(5, mandateId);
            ps.setString(6, key.value());
            ps.setBigDecimal(7, amount.amount());
            ps.setString(8, amount.currency().code());
            ps.setObject(9, dueDate);
            ps.setString(10, counterpartyName);
            ps.setString(11, counterpartyBank);
            ps.setString(12, counterpartyAccount);
            ps.setString(13, mandateReference);
            ps.setString(14, reference);
            ps.setString(15, channel);
            ps.setObject(16, on);
            ps.setObject(17, actorId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Enregistrement du prelevement", e);
        }
    }

    // ------------------------------------------------------------------ execution

    /** Un motif de rejet decide avant ou pendant la comptabilisation. */
    private static final class Rejection extends RuntimeException {
        private final String reason;

        Rejection(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }
    }

    /**
     * Execute un prelevement en attente dont l'echeance est arrivee : debit du debiteur ou credit
     * du creancier, ou rejet nomme. Un prelevement qui n'est pas a executer est rendu tel quel.
     *
     * @param runId le traitement d'arrete qui execute, ou nul en ligne
     */
    public DirectDebit execute(UUID id, UUID runId, UUID actorId) {
        return database.inTransaction(c -> {
            DirectDebit dd = lock(c, id);
            LocalDate on = OperationsService.businessDate(c, dd.legalEntityId());
            if (!"PENDING".equals(dd.status()) || dd.dueDate().isAfter(on)) {
                return dd;
            }
            Savepoint attempt = savepoint(c);
            try {
                return dd.direction() == DirectionKind.RECEIVED
                    ? collectReceived(c, dd, on, runId, actorId)
                    : collectIssued(c, dd, on, runId, actorId);
            } catch (Rejection r) {
                rollbackTo(c, attempt);
                return reject(c, dd, r.reason, on, runId);
            } catch (InsufficientFundsException e) {
                rollbackTo(c, attempt);
                return reject(c, dd, "SANS_PROVISION", on, runId);
            } catch (AccountBlockedException e) {
                rollbackTo(c, attempt);
                return reject(c, dd, "COMPTE_BLOQUE", on, runId);
            } catch (RuntimeException e) {
                // Un defaut technique ou de parametrage : rien de la tentative ne reste, et la
                // transaction englobante — un arrete a blanc — reste utilisable pour la suite.
                rollbackTo(c, attempt);
                throw e;
            }
        });
    }

    private DirectDebit collectReceived(Connection c, DirectDebit dd, LocalDate on, UUID runId,
                                        UUID actorId) {
        Mandate mandate = requireMandate(c, dd.mandateId());
        if (!"ACTIVE".equals(mandate.status())) {
            throw new Rejection("MANDAT_REVOQUE");
        }
        Account debtor;
        try {
            debtor = OperationsService.requireOperableAccount(c, dd.legalEntityId(), dd.accountId(),
                                                              dd.amount(), on);
        } catch (OperationsService.AccountNotOperableException | IllegalArgumentException e) {
            throw new Rejection("COMPTE_INOPERABLE");
        }
        ProductVersion product = ProductCatalog.resolveForAccount(c, dd.legalEntityId(),
                                                                  debtor.id(), on);
        OperationsService.Charges charges = OperationsService.Charges.of(
            DepositCatalog.directDebitFee(product, debtor.currency()), product);
        Money total = dd.amount().plus(charges.fee()).plus(charges.tax());
        // Le disponible se controle ici pour nommer le rejet ; le ledger le controle encore au
        // moment d'ecrire, sous verrou, et son refus est ramene au point de sauvegarde.
        if (total.isGreaterThan(Balances.available(c, debtor.id(), on))) {
            throw new Rejection("SANS_PROVISION");
        }
        UUID headOffice = Branches.headOffice(c, dd.legalEntityId());
        Account creditor;
        if (mandate.internal()) {
            creditor = Accounts.loadAll(c, Set.of(mandate.creditorAccountId()))
                .get(mandate.creditorAccountId());
            if (creditor == null || !creditor.status().acceptsPosting()) {
                throw new Rejection("CREANCIER_INOPERABLE");
            }
        } else {
            UUID clearingId = DepositCatalog.paymentClearing(product)
                .orElseThrow(() -> new Rejection("PRODUIT_SANS_REGLEMENT"));
            creditor = OperationsService.requireInternal(c, dd.legalEntityId(), clearingId,
                                                         debtor.currency());
        }
        ValueDatePolicy policy = Calendars.load(database, dd.legalEntityId());
        LocalDate valueDate = policy.valueDateFor(OperationSchemas.DIRECT_DEBIT, dd.channel(),
                                                  Direction.DEBIT, on);
        List<PostingLine> lines = OperationsService.lines(
            OperationSchemas.directDebit(debtor.currency()),
            EvaluationContext.builder().put("amount", dd.amount()).put("fee", charges.fee())
                .put("tax", charges.tax()).build(),
            debtor, charges.roles(Map.of(OperationSchemas.ROLE_CREDITOR, creditor.id()), product),
            on);
        lines = OperationsService.withValueDate(lines, debtor.id(), valueDate);
        if (mandate.internal()) {
            lines = OperationsService.withValueDate(lines, creditor.id(),
                policy.valueDateFor(OperationSchemas.DIRECT_DEBIT, dd.channel(), Direction.CREDIT, on));
        } else {
            // Le reglement est tenu au siege, comme le nostro qui le soldera.
            lines = atBranch(lines, creditor.id(), headOffice);
        }
        PostingResult result = postingService.post(
            command(dd, runId, on, OperationSchemas.DIRECT_DEBIT, actorId, lines, null)
                .withBranch(debtor.branchId()));
        boolean settled = mandate.internal();
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE direct_debit SET status = ?, executed_on = ?, executed_run_id = ?, value_date = ?,"
            + " entry_id = ?, fee = ?, tax = ?, clearing_account_id = ?, settled_on = ?,"
            + " settlement_account_id = ?, settlement_entry_id = ? WHERE id = ?")) {
            ps.setString(1, settled ? "SETTLED" : "COLLECTED");
            ps.setObject(2, on);
            ps.setObject(3, runId);
            ps.setObject(4, valueDate);
            ps.setObject(5, result.entryId());
            ps.setBigDecimal(6, charges.fee().amount());
            ps.setBigDecimal(7, charges.tax().amount());
            ps.setObject(8, settled ? null : creditor.id());
            ps.setObject(9, settled ? on : null);
            ps.setObject(10, settled ? creditor.id() : null);
            ps.setObject(11, settled ? result.entryId() : null);
            ps.setObject(12, dd.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Execution du prelevement", e);
        }
        return require(c, dd.id());
    }

    private DirectDebit collectIssued(Connection c, DirectDebit dd, LocalDate on, UUID runId,
                                      UUID actorId) {
        Account creditor;
        try {
            creditor = OperationsService.requireOperableAccount(c, dd.legalEntityId(),
                                                                dd.accountId(), dd.amount(), on);
        } catch (OperationsService.AccountNotOperableException | IllegalArgumentException e) {
            throw new Rejection("COMPTE_INOPERABLE");
        }
        ProductVersion product = ProductCatalog.resolveForAccount(c, dd.legalEntityId(),
                                                                  creditor.id(), on);
        UUID collectionId = DepositCatalog.directDebitCollection(product)
            .orElseThrow(() -> new Rejection("PRODUIT_SANS_ENCAISSEMENT"));
        Account collection = OperationsService.requireInternal(c, dd.legalEntityId(), collectionId,
                                                               creditor.currency());
        UUID headOffice = Branches.headOffice(c, dd.legalEntityId());
        ValueDatePolicy policy = Calendars.load(database, dd.legalEntityId());
        LocalDate valueDate = policy.valueDateFor(OperationSchemas.DIRECT_DEBIT_ISSUE, dd.channel(),
                                                  Direction.CREDIT, on);
        List<PostingLine> lines = OperationsService.lines(
            OperationSchemas.directDebitIssue(creditor.currency()),
            EvaluationContext.builder().put("amount", dd.amount()).build(),
            creditor, Map.of(OperationSchemas.ROLE_COLLECTION, collection.id()), on);
        lines = OperationsService.withValueDate(lines, creditor.id(), valueDate);
        lines = atBranch(lines, collection.id(), headOffice);
        PostingResult result = postingService.post(
            command(dd, runId, on, OperationSchemas.DIRECT_DEBIT_ISSUE, actorId, lines, null)
                .withBranch(creditor.branchId()));
        // Le frais, dans sa propre ecriture : un retour contre-passe la remise, pas le service.
        OperationsService.Charges charges = OperationsService.Charges.of(
            DepositCatalog.directDebitFee(product, creditor.currency()), product);
        UUID feeEntry = null;
        if (charges.fee().isPositive()) {
            List<PostingLine> feeLines = OperationsService.lines(
                OperationSchemas.directDebitFee(creditor.currency()),
                EvaluationContext.builder().put("fee", charges.fee()).put("tax", charges.tax())
                    .build(),
                creditor, charges.roles(Map.of(), product), on);
            try {
                feeEntry = postingService.post(
                    command(dd, runId, on, OperationSchemas.DIRECT_DEBIT_FEE, actorId, feeLines, "FEE")
                        .withBranch(creditor.branchId())).entryId();
            } catch (InsufficientFundsException e) {
                throw new Rejection("FRAIS_SANS_PROVISION");
            }
        }
        // Credite sauf bonne fin : le montant reste indisponible jusqu'au reglement.
        UUID hold = Holds.place(c, new Holds.Placement(creditor.id(), dd.amount(), HOLD_TYPE,
                                                       dd.id().toString(), on, null, actorId));
        OperationsService.wakeIfDormant(c, creditor, on, actorId, result);
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE direct_debit SET status = 'COLLECTED', executed_on = ?, executed_run_id = ?,"
            + " value_date = ?, entry_id = ?, fee_entry_id = ?, fee = ?, tax = ?, hold_id = ?,"
            + " clearing_account_id = ? WHERE id = ?")) {
            ps.setObject(1, on);
            ps.setObject(2, runId);
            ps.setObject(3, valueDate);
            ps.setObject(4, result.entryId());
            ps.setObject(5, feeEntry);
            ps.setBigDecimal(6, charges.fee().amount());
            ps.setBigDecimal(7, charges.tax().amount());
            ps.setObject(8, hold);
            ps.setObject(9, collection.id());
            ps.setObject(10, dd.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Execution du prelevement emis", e);
        }
        return require(c, dd.id());
    }

    /** En ligne, une cle par prelevement ; a l'arrete, une cle par traitement : un arrete annule puis rejoue comptabilise de nouveau. */
    private static PostingCommand command(DirectDebit dd, UUID runId, LocalDate on, String type,
                                          UUID actorId, List<PostingLine> lines, String suffix) {
        String part = suffix == null ? "" : "-" + suffix;
        if (runId == null) {
            return PostingCommand.online(IdempotencyKey.of(type + "-" + dd.id() + part),
                                         dd.legalEntityId(), on, type, actorId, lines);
        }
        return PostingCommand.batch(
            suffix == null ? IdempotencyKey.forBatch(runId.toString(), STEP, dd.id())
                           : IdempotencyKey.forBatch(runId.toString(), STEP, dd.id(), suffix),
            dd.legalEntityId(), on, type, actorId, runId, lines);
    }

    private static DirectDebit reject(Connection c, DirectDebit dd, String reason, LocalDate on,
                                      UUID runId) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE direct_debit SET status = 'REJECTED', executed_on = ?, executed_run_id = ?,"
            + " rejection_reason = ? WHERE id = ? AND status = 'PENDING'")) {
            ps.setObject(1, on);
            ps.setObject(2, runId);
            ps.setString(3, reason);
            ps.setObject(4, dd.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Rejet du prelevement", e);
        }
        return require(c, dd.id());
    }

    private static Savepoint savepoint(Connection c) {
        try {
            return c.setSavepoint();
        } catch (SQLException e) {
            throw new LedgerStoreException("Point de sauvegarde", e);
        }
    }

    private static void rollbackTo(Connection c, Savepoint savepoint) {
        try {
            c.rollback(savepoint);
        } catch (SQLException e) {
            throw new LedgerStoreException("Retour au point de sauvegarde", e);
        }
    }

    /** Les prelevements en attente dont l'echeance est arrivee, dans l'ordre de presentation. */
    public static List<UUID> due(Connection c, UUID legalEntityId, LocalDate businessDate) {
        List<UUID> ids = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT id FROM direct_debit WHERE legal_entity_id = ? AND status = 'PENDING'"
            + " AND due_date <= ? ORDER BY due_date, created_at, id")) {
            ps.setObject(1, legalEntityId);
            ps.setObject(2, businessDate);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getObject(1, UUID.class));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Prelevements a l'echeance", e);
        }
        return ids;
    }

    /**
     * Defait ce qu'un traitement annule a execute : les prelevements qu'il a executes ou rejetes
     * redeviennent en attente, leurs blocages tombent ; leurs ecritures, elles, sont
     * contre-passees par le moteur. Un prelevement que la suite a fait avancer — regle,
     * rembourse, retourne — refuse l'annulation, parce que la suite s'appuie sur lui.
     */
    public static int cancelRun(Connection c, UUID runId, LocalDate on, UUID actorId) {
        for (DirectDebit dd : requireCancellable(c, runId)) {
            if (dd.holdId() != null) {
                Holds.release(c, dd.holdId(), on, actorId);
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE direct_debit SET status = 'PENDING', executed_on = NULL, executed_run_id = NULL,"
            + " value_date = NULL, entry_id = NULL, fee_entry_id = NULL, fee = 0, tax = 0,"
            + " hold_id = NULL, clearing_account_id = NULL, rejection_reason = NULL,"
            + " settled_on = NULL, settlement_account_id = NULL, settlement_entry_id = NULL"
            + " WHERE executed_run_id = ?")) {
            ps.setObject(1, runId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Retablissement des prelevements du traitement", e);
        }
    }

    /**
     * Les prelevements executes par un traitement, tels qu'il les a laisses ; si la suite en a
     * fait avancer un, l'annulation est refusee avant que rien ne soit defait.
     */
    public static List<DirectDebit> requireCancellable(Connection c, UUID runId) {
        List<DirectDebit> executed = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            SELECT + " WHERE d.executed_run_id = ? FOR UPDATE OF d")) {
            ps.setObject(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    executed.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Prelevements du traitement", e);
        }
        for (DirectDebit dd : executed) {
            boolean asExecuted = "REJECTED".equals(dd.status()) || "COLLECTED".equals(dd.status())
                || ("SETTLED".equals(dd.status()) && dd.settlementEntryId() != null
                    && dd.settlementEntryId().equals(dd.entryId()));
            if (!asExecuted) {
                throw new IllegalStateException("Le prelevement " + dd.id() + " execute par ce "
                    + "traitement est depuis en etat " + dd.status() + " : la suite s'appuie sur "
                    + "lui, le traitement ne s'annule plus");
            }
        }
        return executed;
    }

    // ------------------------------------------------------------------ suivi

    /**
     * Le correspondant a regle : pour un prelevement recu, le reglement passe au nostro ; pour un
     * prelevement emis, le nostro recoit et le blocage tombe.
     */
    public DirectDebit settle(UUID id, UUID nostroAccountId, UUID actorId) {
        return database.inTransaction(c -> {
            DirectDebit dd = lock(c, id);
            requireStatus(dd, "COLLECTED");
            Account nostro = requireNostro(c, dd, nostroAccountId);
            LocalDate on = OperationsService.businessDate(c, dd.legalEntityId());
            UUID headOffice = Branches.headOffice(c, dd.legalEntityId());
            String narrative;
            List<PostingLine> lines;
            String type;
            if (dd.direction() == DirectionKind.RECEIVED) {
                narrative = "Reglement du prelevement " + dd.id() + " a " + dd.counterpartyName();
                type = OperationSchemas.DIRECT_DEBIT_SETTLEMENT;
                lines = List.of(PostingLine.debit(dd.clearingAccountId(), dd.amount(), on, narrative),
                                PostingLine.credit(nostro.id(), dd.amount(), on, narrative));
            } else {
                narrative = "Encaissement du prelevement " + dd.id() + " sur " + dd.counterpartyBank();
                type = OperationSchemas.DIRECT_DEBIT_COLLECTION;
                lines = List.of(PostingLine.debit(nostro.id(), dd.amount(), on, narrative),
                                PostingLine.credit(dd.clearingAccountId(), dd.amount(), on, narrative));
                Holds.release(c, dd.holdId(), on, actorId);
            }
            PostingResult result = postingService.post(PostingCommand.online(
                IdempotencyKey.of(type + "-" + dd.id()), dd.legalEntityId(), on, type, actorId,
                lines).withBranch(headOffice));
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE direct_debit SET status = 'SETTLED', settled_on = ?,"
                + " settlement_account_id = ?, settlement_entry_id = ? WHERE id = ?")) {
                ps.setObject(1, on);
                ps.setObject(2, nostro.id());
                ps.setObject(3, result.entryId());
                ps.setObject(4, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new LedgerStoreException("Reglement du prelevement", e);
            }
            return require(c, id);
        });
    }

    /**
     * Avant execution, un prelevement se retire sans ecriture ; un prelevement recu execute mais
     * pas encore regle se rappelle par contre-passation, frais compris.
     */
    public DirectDebit cancel(UUID id, String reason, UUID actorId) {
        requireReason(reason, "de l'annulation");
        return database.inTransaction(c -> {
            DirectDebit dd = lock(c, id);
            LocalDate on = OperationsService.businessDate(c, dd.legalEntityId());
            UUID entry = null;
            if ("PENDING".equals(dd.status())) {
                // rien a defaire
            } else if ("COLLECTED".equals(dd.status()) && dd.direction() == DirectionKind.RECEIVED) {
                entry = postingService.reverse(dd.entryId(), dd.executedOn(), on,
                    IdempotencyKey.of("DIRECT_DEBIT_CANCEL-" + dd.id()),
                    "Rappel du prelevement : " + reason.trim()).entryId();
            } else {
                throw new DirectDebitStateException(dd, "seul un prelevement en attente, ou recu et "
                                                    + "non regle, s'annule");
            }
            close(c, dd.id(), "CANCELLED", on, entry, reason);
            return require(c, id);
        });
    }

    /** Le debiteur conteste un prelevement recu regle : le montant lui revient, les frais restent acquis. */
    public DirectDebit refund(UUID id, String reason, UUID actorId) {
        requireReason(reason, "du remboursement");
        return database.inTransaction(c -> {
            DirectDebit dd = lock(c, id);
            if (dd.direction() != DirectionKind.RECEIVED) {
                throw new DirectDebitStateException(dd, "seul un prelevement recu se rembourse");
            }
            requireStatus(dd, "SETTLED");
            LocalDate on = OperationsService.businessDate(c, dd.legalEntityId());
            Account debtor = Accounts.loadAll(c, Set.of(dd.accountId())).get(dd.accountId());
            Account from = Accounts.loadAll(c, Set.of(dd.settlementAccountId()))
                .get(dd.settlementAccountId());
            String narrative = "Remboursement du prelevement " + dd.id() + " : " + reason.trim();
            PostingLine debit = PostingLine.debit(from.id(), dd.amount(), on, narrative);
            if (from.kind() != AccountKind.CUSTOMER) {
                debit = debit.withBranch(Branches.headOffice(c, dd.legalEntityId()));
            }
            PostingResult result = postingService.post(PostingCommand.online(
                IdempotencyKey.of(OperationSchemas.DIRECT_DEBIT_REFUND + "-" + dd.id()),
                dd.legalEntityId(), on, OperationSchemas.DIRECT_DEBIT_REFUND, actorId,
                List.of(debit, PostingLine.credit(debtor.id(), dd.amount(), on, narrative)))
                .withBranch(debtor.branchId()));
            close(c, dd.id(), "REFUNDED", on, result.entryId(), reason);
            return require(c, id);
        });
    }

    /**
     * Le debiteur d'ailleurs ne paie pas un prelevement emis : avant reglement, la remise est
     * contre-passee et le blocage tombe ; apres, le montant est repris au creancier vers le nostro.
     * Les frais restent acquis.
     */
    public DirectDebit returnIssued(UUID id, String reason, UUID actorId) {
        requireReason(reason, "du retour");
        return database.inTransaction(c -> {
            DirectDebit dd = lock(c, id);
            if (dd.direction() != DirectionKind.ISSUED) {
                throw new DirectDebitStateException(dd, "seul un prelevement emis revient impaye");
            }
            LocalDate on = OperationsService.businessDate(c, dd.legalEntityId());
            UUID entry;
            if ("COLLECTED".equals(dd.status())) {
                Holds.release(c, dd.holdId(), on, actorId);
                entry = postingService.reverse(dd.entryId(), dd.executedOn(), on,
                    IdempotencyKey.of(OperationSchemas.DIRECT_DEBIT_RETURN + "-" + dd.id()),
                    "Prelevement impaye : " + reason.trim()).entryId();
            } else if ("SETTLED".equals(dd.status())) {
                Account creditor = Accounts.loadAll(c, Set.of(dd.accountId())).get(dd.accountId());
                String narrative = "Retour du prelevement " + dd.id() + " : " + reason.trim();
                entry = postingService.post(PostingCommand.online(
                    IdempotencyKey.of(OperationSchemas.DIRECT_DEBIT_RETURN + "-" + dd.id()),
                    dd.legalEntityId(), on, OperationSchemas.DIRECT_DEBIT_RETURN, actorId,
                    List.of(PostingLine.debit(creditor.id(), dd.amount(), on, narrative),
                            PostingLine.credit(dd.settlementAccountId(), dd.amount(), on, narrative)
                                .withBranch(Branches.headOffice(c, dd.legalEntityId()))))
                    .withBranch(creditor.branchId())).entryId();
            } else {
                throw new DirectDebitStateException(dd, "un retour suppose un prelevement credite ou regle");
            }
            close(c, dd.id(), "RETURNED", on, entry, reason);
            return require(c, id);
        });
    }

    private static void close(Connection c, UUID id, String status, LocalDate on, UUID entryId,
                              String reason) {
        try (PreparedStatement ps = c.prepareStatement(
            "UPDATE direct_debit SET status = ?, closed_on = ?, close_entry_id = ?, close_reason = ?"
            + " WHERE id = ?")) {
            ps.setString(1, status);
            ps.setObject(2, on);
            ps.setObject(3, entryId);
            ps.setString(4, reason.trim());
            ps.setObject(5, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LedgerStoreException("Cloture du prelevement", e);
        }
    }

    private static Account requireNostro(Connection c, DirectDebit dd, UUID nostroAccountId) {
        Objects.requireNonNull(nostroAccountId, "nostroAccountId");
        Account nostro = Accounts.loadAll(c, Set.of(nostroAccountId)).get(nostroAccountId);
        if (nostro == null || !nostro.legalEntityId().equals(dd.legalEntityId())
            || nostro.kind() != AccountKind.NOSTRO
            || !nostro.currency().code().equals(dd.amount().currency().code())) {
            throw new IllegalArgumentException(
                "Un prelevement se regle sur un compte nostro de l'entite, en "
                + dd.amount().currency().code());
        }
        return nostro;
    }

    private static void requireReason(String reason, String what) {
        if (isBlank(reason)) {
            throw new IllegalArgumentException("Le motif " + what + " est obligatoire");
        }
    }

    private static void requireStatus(DirectDebit dd, String expected) {
        if (!expected.equals(dd.status())) {
            throw new DirectDebitStateException(dd, "attendu " + expected);
        }
    }

    // ------------------------------------------------------------------ lecture

    private static final String SELECT_MANDATE =
        "SELECT m.id, m.legal_entity_id, m.account_id, m.reference, m.creditor_id, m.creditor_name,"
        + " m.creditor_account_id, m.creditor_bank, m.creditor_account, m.signed_on, m.valid_from,"
        + " m.valid_to, m.max_amount, m.status, m.revoked_on, m.revocation_reason, m.created_by,"
        + " m.approved_by, cur.code, cur.scale, cur.rounding_mode"
        + " FROM debit_mandate m JOIN currency cur ON cur.code = m.currency";

    public static Optional<Mandate> findMandate(Connection c, UUID mandateId) {
        return oneMandate(c, SELECT_MANDATE + " WHERE m.id = ?", mandateId);
    }

    public static Mandate requireMandate(Connection c, UUID mandateId) {
        return findMandate(c, mandateId).orElseThrow(() -> new UnknownMandateException(mandateId));
    }

    private static Mandate lockMandate(Connection c, UUID mandateId) {
        return oneMandate(c, SELECT_MANDATE + " WHERE m.id = ? FOR UPDATE OF m", mandateId)
            .orElseThrow(() -> new UnknownMandateException(mandateId));
    }

    private static Optional<Mandate> oneMandate(Connection c, String sql, UUID id) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readMandate(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du mandat", e);
        }
    }

    /** Les mandats d'un compte, du plus recent au plus ancien. */
    public static List<Mandate> mandates(Connection c, UUID accountId) {
        List<Mandate> mandates = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            SELECT_MANDATE + " WHERE m.account_id = ? ORDER BY m.signed_on DESC, m.created_at DESC")) {
            ps.setObject(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    mandates.add(readMandate(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des mandats", e);
        }
        return mandates;
    }

    private static Mandate readMandate(ResultSet rs) throws SQLException {
        CurrencyRef currency = new CurrencyRef(rs.getString(19), rs.getInt(20),
                                               RoundingMode.valueOf(rs.getString(21)));
        BigDecimal max = rs.getBigDecimal(13);
        return new Mandate(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
            rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getString(6),
            rs.getObject(7, UUID.class), rs.getString(8), rs.getString(9),
            rs.getObject(10, LocalDate.class), rs.getObject(11, LocalDate.class),
            rs.getObject(12, LocalDate.class), max == null ? null : Money.of(max, currency),
            rs.getString(14), rs.getObject(15, LocalDate.class), rs.getString(16),
            rs.getObject(17, UUID.class), rs.getObject(18, UUID.class));
    }

    private static final String SELECT =
        "SELECT d.id, d.legal_entity_id, d.direction, d.account_id, d.mandate_id, d.amount, d.fee,"
        + " d.tax, d.due_date, d.counterparty_name, d.counterparty_bank, d.counterparty_account,"
        + " d.mandate_reference, d.reference, d.channel, d.status, d.presented_on, d.executed_on,"
        + " d.executed_run_id, d.value_date, d.entry_id, d.fee_entry_id, d.hold_id,"
        + " d.clearing_account_id, d.rejection_reason, d.settled_on, d.settlement_account_id,"
        + " d.settlement_entry_id, d.closed_on, d.close_entry_id, d.close_reason, d.created_by,"
        + " cur.code, cur.scale, cur.rounding_mode"
        + " FROM direct_debit d JOIN currency cur ON cur.code = d.currency";

    public static Optional<DirectDebit> find(Connection c, UUID id) {
        return one(c, SELECT + " WHERE d.id = ?", ps -> ps.setObject(1, id));
    }

    public static DirectDebit require(Connection c, UUID id) {
        return find(c, id).orElseThrow(() -> new UnknownDirectDebitException(id));
    }

    private static DirectDebit lock(Connection c, UUID id) {
        return one(c, SELECT + " WHERE d.id = ? FOR UPDATE OF d", ps -> ps.setObject(1, id))
            .orElseThrow(() -> new UnknownDirectDebitException(id));
    }

    private static Optional<DirectDebit> byKey(Connection c, UUID legalEntityId, IdempotencyKey key) {
        return one(c, SELECT + " WHERE d.legal_entity_id = ? AND d.idempotency_key = ?", ps -> {
            ps.setObject(1, legalEntityId);
            ps.setString(2, key.value());
        });
    }

    /** Les prelevements de l'entite, du plus recent au plus ancien, par sens et statut s'ils sont donnes. */
    public static List<DirectDebit> page(Connection c, UUID legalEntityId, DirectionKind direction,
                                         String status, int offset, int limit) {
        List<DirectDebit> debits = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            SELECT + " WHERE d.legal_entity_id = ? AND (?::text IS NULL OR d.direction = ?)"
            + " AND (?::text IS NULL OR d.status = ?)"
            + " ORDER BY d.presented_on DESC, d.created_at DESC, d.id OFFSET ? LIMIT ?")) {
            ps.setObject(1, legalEntityId);
            ps.setString(2, direction == null ? null : direction.name());
            ps.setString(3, direction == null ? null : direction.name());
            ps.setString(4, status);
            ps.setString(5, status);
            ps.setInt(6, offset);
            ps.setInt(7, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    debits.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des prelevements", e);
        }
        return debits;
    }

    public static long count(Connection c, UUID legalEntityId, DirectionKind direction,
                             String status) {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT count(*) FROM direct_debit d WHERE d.legal_entity_id = ?"
            + " AND (?::text IS NULL OR d.direction = ?) AND (?::text IS NULL OR d.status = ?)")) {
            ps.setObject(1, legalEntityId);
            ps.setString(2, direction == null ? null : direction.name());
            ps.setString(3, direction == null ? null : direction.name());
            ps.setString(4, status);
            ps.setString(5, status);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Decompte des prelevements", e);
        }
    }

    /** Les prelevements d'un compte, du plus recent au plus ancien. */
    public static List<DirectDebit> ofAccount(Connection c, UUID accountId) {
        List<DirectDebit> debits = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
            SELECT + " WHERE d.account_id = ? ORDER BY d.presented_on DESC, d.created_at DESC, d.id")) {
            ps.setObject(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    debits.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture des prelevements du compte", e);
        }
        return debits;
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private static Optional<DirectDebit> one(Connection c, String sql, Binder binder) {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LedgerStoreException("Lecture du prelevement", e);
        }
    }

    private static DirectDebit read(ResultSet rs) throws SQLException {
        CurrencyRef currency = new CurrencyRef(rs.getString(33), rs.getInt(34),
                                               RoundingMode.valueOf(rs.getString(35)));
        return new DirectDebit(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
            DirectionKind.valueOf(rs.getString(3)), rs.getObject(4, UUID.class),
            rs.getObject(5, UUID.class), Money.of(rs.getBigDecimal(6), currency),
            Money.of(rs.getBigDecimal(7), currency), Money.of(rs.getBigDecimal(8), currency),
            rs.getObject(9, LocalDate.class), rs.getString(10), rs.getString(11), rs.getString(12),
            rs.getString(13), rs.getString(14), rs.getString(15), rs.getString(16),
            rs.getObject(17, LocalDate.class), rs.getObject(18, LocalDate.class),
            rs.getObject(19, UUID.class), rs.getObject(20, LocalDate.class),
            rs.getObject(21, UUID.class), rs.getObject(22, UUID.class), rs.getObject(23, UUID.class),
            rs.getObject(24, UUID.class), rs.getString(25), rs.getObject(26, LocalDate.class),
            rs.getObject(27, UUID.class), rs.getObject(28, UUID.class),
            rs.getObject(29, LocalDate.class), rs.getObject(30, UUID.class), rs.getString(31),
            rs.getObject(32, UUID.class));
    }

    private static List<PostingLine> atBranch(List<PostingLine> lines, UUID accountId, UUID branch) {
        List<PostingLine> placed = new ArrayList<>(lines.size());
        for (PostingLine line : lines) {
            placed.add(line.accountId().equals(accountId) ? line.withBranch(branch) : line);
        }
        return placed;
    }

    public static class UnknownMandateException extends RuntimeException {
        public UnknownMandateException(UUID mandateId) {
            super("Mandat de prelevement inconnu : " + mandateId);
        }
    }

    public static class UnknownDirectDebitException extends RuntimeException {
        public UnknownDirectDebitException(UUID id) {
            super("Prelevement inconnu : " + id);
        }
    }

    /** Le mandat n'admet pas ce prelevement : revoque, hors validite, au-dela de son plafond. */
    public static class MandateStateException extends IllegalStateException {
        public MandateStateException(Mandate mandate, String detail) {
            super("Mandat " + mandate.reference() + " du creancier " + mandate.creditorId() + " : "
                  + detail);
        }
    }

    /** Le prelevement n'est pas dans l'etat que l'acte suppose. */
    public static class DirectDebitStateException extends IllegalStateException {
        public DirectDebitStateException(DirectDebit dd, String detail) {
            super("Prelevement " + dd.id() + " en etat " + dd.status() + " : " + detail);
        }
    }
}
