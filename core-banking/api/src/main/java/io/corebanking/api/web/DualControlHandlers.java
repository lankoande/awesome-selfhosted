package io.corebanking.api.web;

import io.corebanking.api.config.AccountDirectory;
import io.corebanking.api.usecase.AccountUseCases;
import io.corebanking.api.usecase.PartyUseCases;
import io.corebanking.deposits.AccountLifecycle;
import io.corebanking.deposits.BlockKind;
import io.corebanking.deposits.Holds;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.store.Branches;
import io.corebanking.ledger.store.Database;
import io.corebanking.loan.AmortisationMethod;
import io.corebanking.loan.AmortisationSchedule;
import io.corebanking.loan.InsuranceBasis;
import io.corebanking.loan.LoanTerms;
import io.corebanking.loan.PrepaymentMode;
import io.corebanking.loan.ScheduleGenerator;
import io.corebanking.loan.service.LoanContract;
import io.corebanking.loan.service.LoanService;
import io.corebanking.api.usecase.LoanUseCases;
import io.corebanking.api.usecase.ProductUseCases;
import io.corebanking.calendar.BusinessDayConvention;
import io.corebanking.calendar.Calendars;
import io.corebanking.calendar.OffsetUnit;
import io.corebanking.calendar.ValueDateRule;
import io.corebanking.interest.daycount.DayCountConvention;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.time.Periodicity;
import io.corebanking.ledger.domain.account.Direction;
import io.corebanking.product.ProductCatalog;
import io.corebanking.party.PartyService;
import io.corebanking.party.RiskRating;
import io.corebanking.security.AccessTarget;
import io.corebanking.security.Caller;
import io.corebanking.security.Operation;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Les cas d'usage a double validation, tels que l'API sait les rejouer depuis une requete en
 * attente. Chacun lit la requete, nomme la cible, et execute avec le maker pour auteur et le
 * checker pour approbateur.
 */
public final class DualControlHandlers {

    private DualControlHandlers() {}

    public static List<MakerChecker.Handler> all(Database database, AccountLifecycle lifecycle,
                                                 PartyService parties, AccountDirectory accounts,
                                                 LoanService loans) {
        return List.of(new OpenAccount(lifecycle), new CloseAccount(lifecycle, accounts),
                       new BlockAccount(lifecycle, accounts), new LiftBlock(lifecycle, accounts),
                       new PlaceHold(database, accounts), new ReleaseHold(database, accounts),
                       new VerifyKyc(parties), new DisburseLoan(database, loans),
                       new PrepayLoan(database, loans), new ActivateProduct(database),
                       new AddValueDateRule(database), new AddHoliday(database),
                       new CreateBranch(database), new CreateTill(database, accounts),
                       new RescheduleLoan(database, loans));
    }

    private static int integer(Map<String, Object> payload, String key) {
        String value = required(payload, key);
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Entier attendu pour " + key + " : " + value);
        }
    }

    private static UUID uuid(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Champ obligatoire absent : " + key);
        }
        return UUID.fromString(value.toString());
    }

    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : value.toString();
    }

    private static String required(Map<String, Object> payload, String key) {
        String value = text(payload, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Champ obligatoire absent : " + key);
        }
        return value;
    }

    private static LocalDate date(Map<String, Object> payload, String key) {
        String value = text(payload, key);
        return value == null ? null : LocalDate.parse(value);
    }

    /** Ouverture : dans l'agence du maker, jamais celle que la requete proposerait. */
    static final class OpenAccount implements MakerChecker.Handler {
        private final AccountLifecycle lifecycle;

        OpenAccount(AccountLifecycle lifecycle) {
            this.lifecycle = lifecycle;
        }

        @Override public String name() { return "ACCOUNT_OPEN"; }
        @Override public Operation operation() { return Operation.ACCOUNT_OPEN; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            return AccessTarget.inBranch(uuid(payload, "legalEntityId"), Callers.branchId(maker));
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "code");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            UUID entity = uuid(payload, "legalEntityId");
            UUID id = lifecycle.open(new AccountLifecycle.Opening(
                entity, required(payload, "code"), uuid(payload, "holderPartyId"),
                required(payload, "productCode"), lifecycleCurrency(payload),
                Callers.branchId(maker), Callers.actorId(maker), Callers.actorId(checker)));
            return new Requests.Created(id);
        }

        private io.corebanking.kernel.money.CurrencyRef lifecycleCurrency(Map<String, Object> payload) {
            return io.corebanking.kernel.money.Currencies.require(required(payload, "currency"));
        }
    }

    static final class CloseAccount implements MakerChecker.Handler {
        private final AccountLifecycle lifecycle;
        private final AccountDirectory accounts;

        CloseAccount(AccountLifecycle lifecycle, AccountDirectory accounts) {
            this.lifecycle = lifecycle;
            this.accounts = accounts;
        }

        @Override public String name() { return "ACCOUNT_CLOSE"; }
        @Override public Operation operation() { return Operation.ACCOUNT_CLOSE; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            Account account = accounts.require(uuid(payload, "accountId"));
            return AccessTarget.inBranch(account.legalEntityId(), account.branchId());
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            UUID payout = payload.get("payoutAccountId") == null ? null
                                                                 : uuid(payload, "payoutAccountId");
            return lifecycle.close(new AccountLifecycle.Closing(
                uuid(payload, "accountId"), payout, Callers.actorId(maker),
                Callers.actorId(checker)));
        }
    }

    static final class BlockAccount implements MakerChecker.Handler {
        private final AccountLifecycle lifecycle;
        private final AccountDirectory accounts;

        BlockAccount(AccountLifecycle lifecycle, AccountDirectory accounts) {
            this.lifecycle = lifecycle;
            this.accounts = accounts;
        }

        @Override public String name() { return "ACCOUNT_BLOCK"; }
        @Override public Operation operation() { return Operation.ACCOUNT_BLOCK; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            Account account = accounts.require(uuid(payload, "accountId"));
            return AccessTarget.inBranch(account.legalEntityId(), account.branchId());
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            UUID id = lifecycle.block(new AccountLifecycle.Block(
                uuid(payload, "accountId"), BlockKind.valueOf(required(payload, "kind")),
                required(payload, "reason"), text(payload, "reference"), Callers.actorId(maker),
                Callers.actorId(checker)));
            return new Requests.Created(id);
        }
    }

    static final class LiftBlock implements MakerChecker.Handler {
        private final AccountLifecycle lifecycle;
        private final AccountDirectory accounts;

        LiftBlock(AccountLifecycle lifecycle, AccountDirectory accounts) {
            this.lifecycle = lifecycle;
            this.accounts = accounts;
        }

        @Override public String name() { return "ACCOUNT_UNBLOCK"; }
        @Override public Operation operation() { return Operation.ACCOUNT_BLOCK; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            Account account = accounts.require(uuid(payload, "accountId"));
            return AccessTarget.inBranch(account.legalEntityId(), account.branchId());
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            lifecycle.unblock(uuid(payload, "blockId"), required(payload, "reason"),
                              Callers.actorId(maker), Callers.actorId(checker));
            return Map.of("blockId", uuid(payload, "blockId").toString(), "lifted", true);
        }
    }

    static final class PlaceHold implements MakerChecker.Handler {
        private final Database database;
        private final AccountDirectory accounts;

        PlaceHold(Database database, AccountDirectory accounts) {
            this.database = database;
            this.accounts = accounts;
        }

        @Override public String name() { return "HOLD_PLACE"; }
        @Override public Operation operation() { return Operation.ACCOUNT_HOLD; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            Account account = accounts.require(uuid(payload, "accountId"));
            Money amount = new Requests.Amount(required(payload, "amount"),
                                               required(payload, "currency")).on(account);
            return AccessTarget.inEntity(account.legalEntityId()).withAmount(amount);
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            Account account = accounts.require(uuid(payload, "accountId"));
            Money amount = new Requests.Amount(required(payload, "amount"),
                                               required(payload, "currency")).on(account);
            UUID id = database.inTransaction(c -> Holds.place(c, new Holds.Placement(
                account.id(), amount, required(payload, "type"), text(payload, "reference"),
                AccountUseCases.businessDate(c, account.legalEntityId()),
                date(payload, "expiresOn"), Callers.actorId(maker))));
            return new Requests.Created(id);
        }
    }

    static final class ReleaseHold implements MakerChecker.Handler {
        private final Database database;
        private final AccountDirectory accounts;

        ReleaseHold(Database database, AccountDirectory accounts) {
            this.database = database;
            this.accounts = accounts;
        }

        @Override public String name() { return "HOLD_RELEASE"; }
        @Override public Operation operation() { return Operation.ACCOUNT_HOLD; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            return AccessTarget.inEntity(accounts.require(uuid(payload, "accountId")).legalEntityId());
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            Account account = accounts.require(uuid(payload, "accountId"));
            UUID holdId = uuid(payload, "holdId");
            database.inTransaction(c -> {
                Holds.release(c, holdId, AccountUseCases.businessDate(c, account.legalEntityId()),
                              Callers.actorId(maker));
                return null;
            });
            return Map.of("holdId", holdId.toString(), "released", true);
        }
    }

    static final class VerifyKyc implements MakerChecker.Handler {
        private final PartyService parties;

        VerifyKyc(PartyService parties) {
            this.parties = parties;
        }

        @Override public String name() { return "KYC_VERIFY"; }
        @Override public Operation operation() { return Operation.KYC_VERIFY; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            return AccessTarget.inEntity(parties.require(uuid(payload, "partyId")).legalEntityId());
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "partyId");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            return new PartyUseCases.VerifyKyc(parties).execute(new PartyUseCases.Verification(
                uuid(payload, "partyId"), RiskRating.valueOf(required(payload, "rating")),
                date(payload, "verifiedOn"), Callers.actorId(maker), Callers.actorId(checker)));
        }
    }

    // ------------------------------------------------------------------ credit

    /**
     * Deblocage : l'argent sort. Le maker propose les conditions, le checker les approuve ; le
     * plafond porte sur le capital verse. Les conditions non dites prennent la valeur par defaut
     * des conditions de credit.
     */
    static final class DisburseLoan implements MakerChecker.Handler {
        private final Database database;
        private final LoanService loans;

        DisburseLoan(Database database, LoanService loans) {
            this.database = database;
            this.loans = loans;
        }

        @Override public String name() { return "LOAN_DISBURSE"; }
        @Override public Operation operation() { return Operation.LOAN_DISBURSE; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            LoanContract contract = LoanUseCases.require(database, uuid(payload, "contractId"));
            return AccessTarget.inEntity(contract.legalEntityId()).withAmount(contract.principal());
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "contractId");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            LoanContract contract = LoanUseCases.require(database, uuid(payload, "contractId"));
            AmortisationSchedule schedule = ScheduleGenerator.generate(terms(contract, payload));
            String fees = text(payload, "upfrontFees");
            Money upfrontFees = fees == null ? null
                : Money.of(new java.math.BigDecimal(fees), contract.currency());
            UUID scheduleId = loans.disburse(contract.id(), schedule, upfrontFees,
                                             Callers.actorId(maker), Callers.actorId(checker));
            return new LoanUseCases.Disbursed(contract.id(), scheduleId, schedule.instalments());
        }

        private static LoanTerms terms(LoanContract contract, Map<String, Object> payload) {
            LoanTerms.Builder terms = LoanTerms.of(contract.principal())
                .disbursedOn(contract.disbursedOn());
            if (text(payload, "annualRatePercent") != null) {
                terms.ratePercent(text(payload, "annualRatePercent"));
            }
            if (text(payload, "frequency") != null) {
                terms.frequency(Periodicity.valueOf(text(payload, "frequency")));
            }
            if (text(payload, "instalments") != null) {
                terms.instalments(integer(payload, "instalments"));
            }
            if (text(payload, "graceInstalments") != null) {
                terms.grace(integer(payload, "graceInstalments"));
            }
            if (text(payload, "firstDueDate") != null) {
                terms.firstDueDate(date(payload, "firstDueDate"));
            }
            if (text(payload, "method") != null) {
                terms.method(AmortisationMethod.valueOf(text(payload, "method")));
            }
            if (text(payload, "dayCount") != null) {
                terms.dayCount(DayCountConvention.valueOf(text(payload, "dayCount")));
            }
            if (text(payload, "periodicFee") != null) {
                terms.periodicFee(Money.of(new java.math.BigDecimal(text(payload, "periodicFee")),
                                           contract.currency()));
            }
            if (text(payload, "insuranceBasis") != null) {
                String rate = text(payload, "insuranceRatePercent");
                terms.insurance(InsuranceBasis.valueOf(text(payload, "insuranceBasis")),
                                rate == null ? "0" : rate);
            }
            if (text(payload, "taxOnInterestPercent") != null) {
                terms.taxOnInterestPercent(text(payload, "taxOnInterestPercent"));
            }
            return terms.build();
        }
    }

    /**
     * Remboursement anticipe : un droit de l'emprunteur, que l'agent enregistre ; le nouvel
     * echeancier qu'il engendre s'approuve a deux, comme tout echeancier. La cle d'idempotence
     * est celle de la requete d'origine, gardee avec elle.
     */
    static final class PrepayLoan implements MakerChecker.Handler {
        private final Database database;
        private final LoanService loans;

        PrepayLoan(Database database, LoanService loans) {
            this.database = database;
            this.loans = loans;
        }

        @Override public String name() { return "LOAN_PREPAY"; }
        @Override public Operation operation() { return Operation.LOAN_PREPAY; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            LoanContract contract = LoanUseCases.require(database, uuid(payload, "contractId"));
            return AccessTarget.inBranch(contract.legalEntityId(), contract.branchId())
                .withAmount(LoanUseCases.amount(contract, text(payload, "amount"),
                                                text(payload, "currency")));
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "contractId");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            LoanContract contract = LoanUseCases.require(database, uuid(payload, "contractId"));
            Money amount = LoanUseCases.amount(contract, text(payload, "amount"),
                                               text(payload, "currency"));
            PrepaymentMode mode = PrepaymentMode.valueOf(required(payload, "mode"));
            LocalDate on = database.inTransaction(
                c -> io.corebanking.api.usecase.AccountUseCases.businessDate(
                    c, contract.legalEntityId()));
            return loans.prepay(contract.id(), amount, mode, on,
                                IdempotencyKey.of(required(payload, "idempotencyKey")),
                                Callers.actorId(maker), Callers.actorId(checker));
        }
    }

    // ------------------------------------------------------------------ parametrage

    /** Activation d'une version de produit : jamais par son redacteur, la base le refuse aussi. */
    static final class ActivateProduct implements MakerChecker.Handler {
        private final Database database;

        ActivateProduct(Database database) {
            this.database = database;
        }

        @Override public String name() { return "PRODUCT_ACTIVATE"; }
        @Override public Operation operation() { return Operation.PRODUCT_ACTIVATE; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            ProductCatalog.VersionHeader version = ProductUseCases.requireVersion(
                database, uuid(payload, "legalEntityId"), uuid(payload, "versionId"));
            return AccessTarget.inEntity(version.legalEntityId());
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "versionId");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            ProductCatalog.VersionHeader version = ProductUseCases.requireVersion(
                database, uuid(payload, "legalEntityId"), uuid(payload, "versionId"));
            UUID approver = Callers.actorId(checker);
            if (approver.equals(version.createdBy())) {
                throw new IllegalStateException(
                    "La version " + version.code() + " ne peut pas etre activee par son "
                    + "redacteur : un parametrage se valide a deux.");
            }
            database.inTransaction(c -> {
                ProductCatalog.activate(c, version.id(), approver);
                return null;
            });
            return new ProductUseCases.Activation(version.id(), version.code(), "ACTIVE");
        }
    }

    /** Regle de date de valeur : elle deplace des dates de valeur, donc des interets. */
    static final class AddValueDateRule implements MakerChecker.Handler {
        private final Database database;

        AddValueDateRule(Database database) {
            this.database = database;
        }

        @Override public String name() { return "VALUE_DATE_RULE_ADD"; }
        @Override public Operation operation() { return Operation.CALENDAR_MANAGE; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            return AccessTarget.inEntity(uuid(payload, "legalEntityId"));
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "operationType");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            UUID entity = uuid(payload, "legalEntityId");
            LocalDate validFrom = date(payload, "validFrom");
            if (validFrom == null) {
                throw new IllegalArgumentException("Champ obligatoire absent : validFrom");
            }
            ValueDateRule rule = new ValueDateRule(
                required(payload, "operationType"), text(payload, "channel"),
                Direction.valueOf(required(payload, "direction")), integer(payload, "offset"),
                OffsetUnit.valueOf(required(payload, "unit")),
                BusinessDayConvention.valueOf(required(payload, "convention")), validFrom,
                date(payload, "validTo"));
            UUID id = database.inTransaction(c -> Calendars.addRule(
                c, entity, rule, Callers.actorId(maker), Callers.actorId(checker)));
            return new Requests.Created(id);
        }
    }

    /** Jour ferie du calendrier de l'entite : il deplace des dates de valeur et des echeances. */
    static final class AddHoliday implements MakerChecker.Handler {
        private final Database database;

        AddHoliday(Database database) {
            this.database = database;
        }

        @Override public String name() { return "HOLIDAY_ADD"; }
        @Override public Operation operation() { return Operation.CALENDAR_MANAGE; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            return AccessTarget.inEntity(uuid(payload, "legalEntityId"));
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "date");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            UUID entity = uuid(payload, "legalEntityId");
            LocalDate date = date(payload, "date");
            if (date == null) {
                throw new IllegalArgumentException("Champ obligatoire absent : date");
            }
            String label = text(payload, "label");
            UUID calendar = database.inTransaction(c -> Calendars.calendarIdOf(c, entity)
                .orElseThrow(() -> new IllegalStateException(
                    "Aucun calendrier n'est rattache a l'entite " + entity)));
            database.inTransaction(c -> {
                Calendars.addHoliday(c, calendar, date, label);
                return null;
            });
            return new Requests.HolidayDeclared(calendar, date, label);
        }
    }

    // ------------------------------------------------------------------ reseau

    /** Creation d'une agence ou d'une region, avec ses comptes de liaison par devise. */
    static final class CreateBranch implements MakerChecker.Handler {
        private final Database database;

        CreateBranch(Database database) {
            this.database = database;
        }

        @Override public String name() { return "BRANCH_CREATE"; }
        @Override public Operation operation() { return Operation.BRANCH_MANAGE; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            return AccessTarget.inEntity(uuid(payload, "legalEntityId"));
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "code");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            UUID entity = uuid(payload, "legalEntityId");
            Map<CurrencyRef, UUID> liaison = new java.util.LinkedHashMap<>();
            if (payload.get("liaisonAccounts") instanceof Map<?, ?> accounts) {
                accounts.forEach((currency, accountId) -> liaison.put(
                    Currencies.require(String.valueOf(currency)),
                    UUID.fromString(String.valueOf(accountId))));
            }
            Branches.Kind kind = Branches.Kind.valueOf(required(payload, "kind"));
            UUID parent = payload.get("parentId") == null ? null : uuid(payload, "parentId");
            LocalDate openedOn = date(payload, "openedOn");
            UUID id = database.inTransaction(c -> Branches.create(
                c, entity, required(payload, "code"), required(payload, "name"), kind, parent,
                openedOn != null ? openedOn
                    : io.corebanking.api.usecase.AccountUseCases.businessDate(c, entity),
                liaison));
            return new Requests.Created(id);
        }
    }

    /** Une caisse affecte un compte de la banque a une personne : elle se cree a deux, dans l'agence du compte. */
    static final class CreateTill implements MakerChecker.Handler {
        private final Database database;
        private final AccountDirectory accounts;

        CreateTill(Database database, AccountDirectory accounts) {
            this.database = database;
            this.accounts = accounts;
        }

        @Override public String name() { return "TILL_CREATE"; }
        @Override public Operation operation() { return Operation.TILL_MANAGE; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            Account cash = accounts.require(uuid(payload, "cashAccountId"));
            return AccessTarget.inBranch(uuid(payload, "legalEntityId"), cash.branchId());
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "code");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            UUID difference = payload.get("differenceAccountId") == null ? null
                                                                          : uuid(payload, "differenceAccountId");
            UUID id = database.inTransaction(c -> io.corebanking.deposits.Tills.create(
                c, new io.corebanking.deposits.Tills.Draft(
                    uuid(payload, "legalEntityId"), required(payload, "code"),
                    uuid(payload, "cashAccountId"), text(payload, "tellerSubjectId"), difference,
                    Callers.actorId(maker), Callers.actorId(checker))));
            return new Requests.Created(id);
        }
    }

    /**
     * Rechelonnement : un nouveau plan sur le capital non echu, a compter de sa date d'effet, aux
     * conditions financieres du contrat — taux, methode, accessoires. Les echeances deja rendues
     * exigibles restent dues. Un motif est exige : il modifie ce que le client devra.
     */
    static final class RescheduleLoan implements MakerChecker.Handler {
        private final Database database;
        private final LoanService loans;

        RescheduleLoan(Database database, LoanService loans) {
            this.database = database;
            this.loans = loans;
        }

        @Override public String name() { return "LOAN_RESCHEDULE"; }
        @Override public Operation operation() { return Operation.LOAN_RESCHEDULE; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            required(payload, "reason");
            LoanContract contract = LoanUseCases.require(database, uuid(payload, "contractId"));
            return AccessTarget.inEntity(contract.legalEntityId());
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "contractId");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            LoanContract contract = LoanUseCases.require(database, uuid(payload, "contractId"));
            if (contract.status() != LoanContract.Status.ACTIVE || contract.terms() == null) {
                throw new IllegalStateException(
                    "Le contrat " + contract.reference() + " n'est pas en cours d'amortissement :"
                    + " rien a rechelonner.");
            }
            int instalments = integer(payload, "instalments");
            LocalDate effectiveFrom = date(payload, "effectiveFrom");
            LocalDate firstDueDate = date(payload, "firstDueDate");
            if (effectiveFrom == null || firstDueDate == null) {
                throw new IllegalArgumentException(
                    "Champs obligatoires absents : effectiveFrom, firstDueDate");
            }
            Money remaining = database.inTransaction(c -> {
                Money outstanding = io.corebanking.ledger.store.Balances.current(
                    c, contract.loanAccountId());
                Money due = io.corebanking.loan.service.LoanStore
                    .openReceivables(c, contract.id(), contract.currency()).stream()
                    .filter(r -> r.category() == io.corebanking.loan.DueCategory.PRINCIPAL)
                    .map(io.corebanking.loan.Receivable::outstanding)
                    .reduce(Money.zero(contract.currency()), Money::plus);
                return outstanding.minus(due);
            });
            if (!remaining.isPositive()) {
                throw new IllegalStateException(
                    "Aucun capital non echu a rechelonner sur le contrat " + contract.reference());
            }
            LoanTerms terms = contract.terms().forRemaining(remaining, instalments, effectiveFrom,
                                                           firstDueDate);
            AmortisationSchedule schedule = ScheduleGenerator.generate(terms);
            UUID scheduleId = loans.reschedule(
                contract.id(), schedule,
                io.corebanking.loan.service.LoanStore.ScheduleReason.RESCHEDULING, effectiveFrom,
                Callers.actorId(maker), Callers.actorId(checker));
            return new LoanUseCases.Rescheduled(contract.id(), scheduleId, remaining,
                                                schedule.instalments());
        }
    }
}
