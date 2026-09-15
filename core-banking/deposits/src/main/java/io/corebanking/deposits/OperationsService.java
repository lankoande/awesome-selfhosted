package io.corebanking.deposits;

import io.corebanking.calendar.Calendars;
import io.corebanking.calendar.ValueDatePolicy;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountStatus;
import io.corebanking.ledger.domain.account.Direction;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.domain.posting.PostingResult;
import io.corebanking.ledger.domain.posting.PostingService;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.ledger.store.Balances;
import io.corebanking.ledger.store.Database;
import io.corebanking.party.AccountHolders;
import io.corebanking.product.ProductCatalog;
import io.corebanking.product.ProductVersion;
import io.corebanking.schema.AccountResolver;
import io.corebanking.schema.EventTemplate;
import io.corebanking.schema.SchemaEngine;
import io.corebanking.schema.expr.EvaluationContext;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Operations de base sur un compte de depot : versement, retrait, virement interne.
 *
 * <h2>Ce que le service garantit</h2>
 *
 * <ul>
 *   <li><b>La date de valeur se calcule, elle ne se fournit pas.</b> Elle vient des conditions de
 *       banque de l'entite, par type d'operation, canal et sens ; l'absence de condition est un
 *       refus, jamais un repli sur la date comptable.</li>
 *   <li><b>Les frais viennent du produit</b>, forfait par operation, taxe comprise, dans la meme
 *       ecriture que l'operation : un frais preleve a part est un frais qu'on oublie.</li>
 *   <li><b>Le compte et ses titulaires doivent pouvoir operer.</b> Un compte clos, un tiers
 *       bloque, un blocage de compte, un disponible insuffisant : chacun refuse en le disant. Les
 *       blocages et le disponible sont tenus par le ledger, pas par ce service — un prelevement
 *       automatique y est soumis autant qu'un retrait au guichet.</li>
 *   <li><b>Une operation rejouee rend son premier resultat.</b> La cle d'idempotence est celle de
 *       l'appelant, de bout en bout : un client qui appuie deux fois n'est pas debite deux
 *       fois.</li>
 *   <li><b>Un compte dormant se reveille</b> a la premiere operation de son client.</li>
 * </ul>
 *
 * <p>Ce que le service ne fait pas : les cheques (remise, compensation, opposition), les
 * plafonds par produit ou par client, les paiements sortants de l'entite. Les plafonds par
 * <b>role</b> sont l'affaire de la politique d'habilitation, appliquee avant d'arriver ici.
 */
public final class OperationsService {

    private final Database database;
    private final PostingService postingService;

    public OperationsService(Database database, PostingService postingService) {
        this.database = Objects.requireNonNull(database, "database");
        this.postingService = Objects.requireNonNull(postingService, "postingService");
    }

    /** Versement d'especes au guichet. */
    public record Deposit(IdempotencyKey key, UUID legalEntityId, UUID accountId,
                          UUID cashAccountId, Money amount, String channel, String narrative,
                          UUID actorId) {
        public Deposit {
            requireCommand(key, legalEntityId, accountId, amount, actorId);
            Objects.requireNonNull(cashAccountId, "cashAccountId");
        }
    }

    /** Retrait d'especes au guichet. */
    public record Withdrawal(IdempotencyKey key, UUID legalEntityId, UUID accountId,
                             UUID cashAccountId, Money amount, String channel, String narrative,
                             UUID actorId) {
        public Withdrawal {
            requireCommand(key, legalEntityId, accountId, amount, actorId);
            Objects.requireNonNull(cashAccountId, "cashAccountId");
        }
    }

    /** Virement entre deux comptes de l'entite. */
    public record Transfer(IdempotencyKey key, UUID legalEntityId, UUID sourceAccountId,
                           UUID destinationAccountId, Money amount, String channel,
                           String narrative, UUID actorId) {
        public Transfer {
            requireCommand(key, legalEntityId, sourceAccountId, amount, actorId);
            Objects.requireNonNull(destinationAccountId, "destinationAccountId");
            if (sourceAccountId.equals(destinationAccountId)) {
                throw new IllegalArgumentException("Un virement vers le compte emetteur ne vire rien");
            }
        }
    }

    /**
     * Ce que l'operation a produit.
     *
     * @param valueDate    date de valeur de la ligne du client — du donneur d'ordre pour un virement
     * @param balanceAfter solde du compte du client apres l'operation
     * @param replayed     vrai si la cle etait deja connue : le resultat est celui de la premiere
     *                     execution, rien n'a ete comptabilise de nouveau
     */
    public record Receipt(UUID entryId, long entryNumber, LocalDate bookingDate,
                          LocalDate valueDate, Money amount, Money fee, Money tax,
                          Money balanceAfter, boolean replayed, UUID branchId, boolean remote) {}

    // ------------------------------------------------------------------ operations

    public Receipt deposit(Deposit command) {
        return database.inTransaction(c -> {
            LocalDate bookingDate = businessDate(c, command.legalEntityId());
            Account account = requireOperableAccount(c, command.legalEntityId(),
                                                     command.accountId(), command.amount(),
                                                     bookingDate);
            Account cash = requireInternal(c, command.legalEntityId(), command.cashAccountId(),
                                           command.amount().currency());
            ValueDatePolicy policy = Calendars.load(database, command.legalEntityId());
            LocalDate valueDate = policy.valueDateFor(OperationSchemas.CASH_DEPOSIT,
                                                      command.channel(), Direction.CREDIT,
                                                      bookingDate);
            Money zero = Money.zero(command.amount().currency());
            List<PostingLine> lines = lines(
                OperationSchemas.cashDeposit(account.currency()),
                EvaluationContext.builder().put("amount", command.amount()).build(),
                account, Map.of(OperationSchemas.ROLE_CASH, cash.id()), bookingDate);
            lines = withValueDate(lines, account.id(), valueDate);

            PostingResult result = postingService.post(PostingCommand.online(
                command.key(), command.legalEntityId(), bookingDate, OperationSchemas.CASH_DEPOSIT,
                command.actorId(), lines).withBranch(cash.branchId()));
            wakeIfDormant(c, account, bookingDate, command.actorId(), result);
            return receipt(c, result, account, valueDate, command.amount(), zero, zero,
                           cash.branchId());
        });
    }

    public Receipt withdraw(Withdrawal command) {
        return database.inTransaction(c -> {
            LocalDate bookingDate = businessDate(c, command.legalEntityId());
            Account account = requireOperableAccount(c, command.legalEntityId(),
                                                     command.accountId(), command.amount(),
                                                     bookingDate);
            Account cash = requireInternal(c, command.legalEntityId(), command.cashAccountId(),
                                           command.amount().currency());
            ProductVersion product = ProductCatalog.resolveForAccount(
                c, command.legalEntityId(), account.id(), bookingDate);
            Charges charges = Charges.of(DepositCatalog.withdrawalFee(product, account.currency()),
                                         product);
            ValueDatePolicy policy = Calendars.load(database, command.legalEntityId());
            LocalDate valueDate = policy.valueDateFor(OperationSchemas.CASH_WITHDRAWAL,
                                                      command.channel(), Direction.DEBIT,
                                                      bookingDate);
            List<PostingLine> lines = lines(
                OperationSchemas.cashWithdrawal(account.currency()),
                EvaluationContext.builder().put("amount", command.amount())
                    .put("fee", charges.fee()).put("tax", charges.tax()).build(),
                account, charges.roles(Map.of(OperationSchemas.ROLE_CASH, cash.id()), product),
                bookingDate);
            lines = withValueDate(lines, account.id(), valueDate);

            // L'agence de l'operation est celle de la caisse : le frais d'un retrait deplace
            // revient a l'agence qui sert, le compte du client reste dans la sienne.
            PostingResult result = postingService.post(PostingCommand.online(
                command.key(), command.legalEntityId(), bookingDate,
                OperationSchemas.CASH_WITHDRAWAL, command.actorId(), lines)
                .withBranch(cash.branchId()));
            wakeIfDormant(c, account, bookingDate, command.actorId(), result);
            return receipt(c, result, account, valueDate, command.amount(), charges.fee(),
                           charges.tax(), cash.branchId());
        });
    }

    public Receipt transfer(Transfer command) {
        return database.inTransaction(c -> {
            LocalDate bookingDate = businessDate(c, command.legalEntityId());
            Account source = requireOperableAccount(c, command.legalEntityId(),
                                                    command.sourceAccountId(), command.amount(),
                                                    bookingDate);
            Account destination = requireOperableAccount(c, command.legalEntityId(),
                                                         command.destinationAccountId(),
                                                         command.amount(), bookingDate);
            ProductVersion product = ProductCatalog.resolveForAccount(
                c, command.legalEntityId(), source.id(), bookingDate);
            Charges charges = Charges.of(DepositCatalog.transferFee(product, source.currency()),
                                         product);
            ValueDatePolicy policy = Calendars.load(database, command.legalEntityId());
            LocalDate debitValue = policy.valueDateFor(OperationSchemas.TRANSFER, command.channel(),
                                                       Direction.DEBIT, bookingDate);
            LocalDate creditValue = policy.valueDateFor(OperationSchemas.TRANSFER,
                                                        command.channel(), Direction.CREDIT,
                                                        bookingDate);
            List<PostingLine> lines = lines(
                OperationSchemas.transfer(source.currency()),
                EvaluationContext.builder().put("amount", command.amount())
                    .put("fee", charges.fee()).put("tax", charges.tax()).build(),
                source,
                charges.roles(Map.of(OperationSchemas.ROLE_DESTINATION, destination.id()), product),
                bookingDate);
            lines = withValueDate(lines, source.id(), debitValue);
            lines = withValueDate(lines, destination.id(), creditValue);

            // Le frais d'un virement revient a l'agence du compte emetteur.
            PostingResult result = postingService.post(PostingCommand.online(
                command.key(), command.legalEntityId(), bookingDate, OperationSchemas.TRANSFER,
                command.actorId(), lines).withBranch(source.branchId()));
            wakeIfDormant(c, source, bookingDate, command.actorId(), result);
            wakeIfDormant(c, destination, bookingDate, command.actorId(), result);
            return receipt(c, result, source, debitValue, command.amount(), charges.fee(),
                           charges.tax(), source.branchId());
        });
    }

    // ------------------------------------------------------------------ frais

    /** Frais et taxe d'une operation, arrondis chacun pour soi. */
    private record Charges(Money fee, Money tax) {

        static Charges of(Money fee, ProductVersion product) {
            Money tax = fee.isPositive()
                ? fee.times(DepositCatalog.taxRatePercent(product).movePointLeft(2)).roundToCurrency()
                : Money.zero(fee.currency());
            return new Charges(fee.roundToCurrency(), tax);
        }

        Map<String, UUID> roles(Map<String, UUID> base, ProductVersion product) {
            Map<String, UUID> roles = new java.util.HashMap<>(base);
            if (fee.isPositive()) {
                roles.put(OperationSchemas.ROLE_FEE_INCOME, DepositCatalog.feeIncome(product));
            }
            if (tax.isPositive()) {
                roles.put(OperationSchemas.ROLE_TAX, DepositCatalog.taxAccount(product));
            }
            return roles;
        }
    }

    // ------------------------------------------------------------------ exigences

    /**
     * Le compte du client peut operer : compte client de l'entite, dans la devise, ni clos ni en
     * cours de cloture, et ses titulaires peuvent operer. Les blocages et le disponible sont
     * verifies par le ledger au moment d'ecrire.
     */
    private static Account requireOperableAccount(Connection c, UUID legalEntityId, UUID accountId,
                                                  Money amount, LocalDate at) {
        Account account = Accounts.loadAll(c, Set.of(accountId)).get(accountId);
        if (account == null) {
            throw new IllegalArgumentException("Compte inconnu : " + accountId);
        }
        if (!account.legalEntityId().equals(legalEntityId)) {
            throw new IllegalArgumentException(
                "Le compte " + account.code() + " releve d'une autre entite juridique");
        }
        if (account.kind() != AccountKind.CUSTOMER) {
            throw new IllegalArgumentException(
                "Le compte " + account.code() + " n'est pas un compte client : une operation de "
                + "guichet ne s'impute pas sur un compte general");
        }
        if (!account.currency().equals(amount.currency())) {
            throw new IllegalArgumentException(
                "Le compte " + account.code() + " est en " + account.currency().code()
                + ", l'operation en " + amount.currency().code());
        }
        if (!account.status().acceptsPosting()) {
            throw new AccountNotOperableException(account,
                "compte " + account.status() + " : aucune operation n'y est plus possible");
        }
        AccountHolders.requireOperableHolders(c, accountId, at);
        return account;
    }

    /** Compte de contrepartie de la banque — caisse — dans la devise, de l'entite. */
    static Account requireInternal(Connection c, UUID legalEntityId, UUID accountId,
                                   CurrencyRef currency) {
        Account account = Accounts.loadAll(c, Set.of(accountId)).get(accountId);
        if (account == null) {
            throw new IllegalArgumentException("Compte de caisse inconnu : " + accountId);
        }
        if (!account.legalEntityId().equals(legalEntityId)
            || account.kind() == AccountKind.CUSTOMER
            || !account.currency().equals(currency)) {
            throw new IllegalArgumentException(
                "Le compte " + account.code() + " ne peut pas servir de caisse a cette operation : "
                + "il doit etre un compte interne de l'entite, dans la devise de l'operation");
        }
        return account;
    }

    // ------------------------------------------------------------------ interne

    private static List<PostingLine> lines(EventTemplate template, EvaluationContext input,
                                           Account customer, Map<String, UUID> roles,
                                           LocalDate bookingDate) {
        AccountResolver resolver = reference -> switch (reference.kind()) {
            case CONTRACT -> customer.id();
            case PARAMETER -> {
                UUID resolved = roles.get(reference.value());
                if (resolved == null) {
                    throw new AccountResolver.UnresolvableAccountException(reference,
                        "role sans compte pour cette operation");
                }
                yield resolved;
            }
            default -> throw new AccountResolver.UnresolvableAccountException(reference,
                "seuls le compte du client et les comptes de role sont resolvables ici");
        };
        return SchemaEngine.linesFor(template, input, resolver, customer.currency(), bookingDate);
    }

    /** La date de valeur des conditions de banque ne concerne que la ligne du client. */
    private static List<PostingLine> withValueDate(List<PostingLine> lines, UUID accountId,
                                                   LocalDate valueDate) {
        List<PostingLine> adjusted = new ArrayList<>(lines.size());
        for (PostingLine line : lines) {
            adjusted.add(line.accountId().equals(accountId)
                ? new PostingLine(line.accountId(), line.direction(), line.amount(), valueDate,
                                  line.label(), line.fxRate())
                : line);
        }
        return adjusted;
    }

    private void wakeIfDormant(Connection c, Account account, LocalDate on, UUID actorId,
                               PostingResult result) {
        if (account.status() == AccountStatus.DORMANT && !result.replayed()) {
            Dormancy.reactivate(c, account.id(), on, actorId);
        }
    }

    /**
     * @param operationBranch agence qui a realise l'operation ; l'operation est <b>deplacee</b>
     *                        quand ce n'est pas l'agence gestionnaire du compte du client
     */
    private static Receipt receipt(Connection c, PostingResult result, Account customer,
                                   LocalDate valueDate, Money amount, Money fee, Money tax,
                                   UUID operationBranch) {
        Money after = result.balancesAfter().get(customer.id());
        if (after == null) {
            after = Balances.current(c, customer.id());
        }
        boolean remote = operationBranch != null && !operationBranch.equals(customer.branchId());
        return new Receipt(result.entryId(), result.entryNumber(), result.bookingDate(), valueDate,
                           amount, fee, tax, after, result.replayed(), operationBranch, remote);
    }

    static LocalDate businessDate(Connection c, UUID legalEntityId) {
        try (var ps = c.prepareStatement(
            "SELECT current_business_date FROM legal_entity WHERE id = ?")) {
            ps.setObject(1, legalEntityId);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Entite inconnue : " + legalEntityId);
                }
                return rs.getObject(1, LocalDate.class);
            }
        } catch (java.sql.SQLException e) {
            throw new io.corebanking.ledger.store.LedgerStoreException("Date comptable", e);
        }
    }

    private static void requireCommand(IdempotencyKey key, UUID legalEntityId, UUID accountId,
                                       Money amount, UUID actorId) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(legalEntityId, "legalEntityId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(actorId, "actorId");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Montant d'operation non positif : " + amount);
        }
        if (!amount.isBookable()) {
            throw new IllegalArgumentException(
                "Montant non comptabilisable dans la devise : " + amount);
        }
    }

    /** Le compte ne peut pas faire l'objet de l'operation demandee. */
    public static class AccountNotOperableException extends RuntimeException {
        public AccountNotOperableException(Account account, String detail) {
            super("Compte " + account.code() + " : " + detail);
        }
    }
}
