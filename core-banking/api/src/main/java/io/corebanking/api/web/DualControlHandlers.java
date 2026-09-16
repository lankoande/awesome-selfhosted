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
import io.corebanking.tfj.RunMode;
import io.corebanking.tfj.RunType;
import io.corebanking.tfj.TfjEngine;
import io.corebanking.ledger.store.FiscalYears;
import io.corebanking.loan.service.Collaterals;
import io.corebanking.loan.service.RiskProfiles;
import io.corebanking.product.SchemaCatalog;
import io.corebanking.api.usecase.ParameterUseCases;
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
                                                 LoanService loans,
                                                 io.corebanking.api.config.EodEngines engines,
                                                 io.corebanking.ledger.domain.posting.PostingService
                                                     posting) {
        return List.of(new OpenAccount(lifecycle), new CloseAccount(lifecycle, accounts),
                       new BlockAccount(lifecycle, accounts), new LiftBlock(lifecycle, accounts),
                       new PlaceHold(database, accounts), new ReleaseHold(database, accounts),
                       new VerifyKyc(parties), new DisburseLoan(database, loans),
                       new PrepayLoan(database, loans), new ActivateProduct(database),
                       new AddValueDateRule(database), new AddHoliday(database),
                       new CreateBranch(database), new CreateTill(database, accounts),
                       new RescheduleLoan(database, loans),
                       new RunPeriodEnd(engines, RunType.TFM), new RunPeriodEnd(engines, RunType.TFA),
                       new ResumePeriodEnd(engines, RunType.TFM),
                       new ResumePeriodEnd(engines, RunType.TFA),
                       new CancelPeriodEnd(engines, RunType.TFM),
                       new CancelPeriodEnd(engines, RunType.TFA),
                       new OpenFiscalYear(database), new AppropriateResult(database, posting),
                       new RegisterCollateral(database),
                       new AllocateCollateral(database), new ReleaseCollateral(database),
                       new ActivateCollateralPolicy(database), new ActivateRiskProfile(database),
                       new ActivateAccountingSchema(database),
                       new ActivateStatementLayout(database));
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

    // ------------------------------------------------------------------ arretes mensuel et annuel

    private static TfjEngine engineFor(io.corebanking.api.config.EodEngines engines, RunType type,
                                       UUID entity) {
        return type == RunType.TFA ? engines.yearEnd(entity) : engines.monthEnd(entity);
    }

    private static Operation closeOperation(RunType type) {
        return type == RunType.TFA ? Operation.YEAR_CLOSE : Operation.PERIOD_CLOSE;
    }

    /** Lancement d'un arrete mensuel ou annuel : demande par l'un, approuve par un autre. */
    static final class RunPeriodEnd implements MakerChecker.Handler {
        private final io.corebanking.api.config.EodEngines engines;
        private final RunType type;

        RunPeriodEnd(io.corebanking.api.config.EodEngines engines, RunType type) {
            this.engines = engines;
            this.type = type;
        }

        @Override public String name() { return type.name() + "_RUN"; }
        @Override public Operation operation() { return closeOperation(type); }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            return AccessTarget.inEntity(uuid(payload, "legalEntityId"));
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "businessDate");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            UUID entity = uuid(payload, "legalEntityId");
            LocalDate date = date(payload, "businessDate");
            if (date == null) {
                throw new IllegalArgumentException("Champ obligatoire absent : businessDate");
            }
            return engineFor(engines, type, entity).run(entity, date, Callers.actorId(maker),
                                                        RunMode.REAL);
        }
    }

    /** Reprise d'un arrete en echec, a deux comme son lancement. */
    static final class ResumePeriodEnd implements MakerChecker.Handler {
        private final io.corebanking.api.config.EodEngines engines;
        private final RunType type;

        ResumePeriodEnd(io.corebanking.api.config.EodEngines engines, RunType type) {
            this.engines = engines;
            this.type = type;
        }

        @Override public String name() { return type.name() + "_RESUME"; }
        @Override public Operation operation() { return closeOperation(type); }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            return AccessTarget.inEntity(uuid(payload, "legalEntityId"));
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "runId");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            UUID entity = uuid(payload, "legalEntityId");
            return engineFor(engines, type, entity).resume(uuid(payload, "runId"),
                                                           Callers.actorId(maker));
        }
    }

    /** Annulation d'un arrete : le mois ou l'exercice rouvert, en le disant. */
    static final class CancelPeriodEnd implements MakerChecker.Handler {
        private final io.corebanking.api.config.EodEngines engines;
        private final RunType type;

        CancelPeriodEnd(io.corebanking.api.config.EodEngines engines, RunType type) {
            this.engines = engines;
            this.type = type;
        }

        @Override public String name() { return type.name() + "_CANCEL"; }

        @Override
        public Operation operation() {
            return type == RunType.TFA ? Operation.YEAR_REOPEN : Operation.PERIOD_REOPEN;
        }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            required(payload, "reason");
            return AccessTarget.inEntity(uuid(payload, "legalEntityId"));
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "runId");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            UUID entity = uuid(payload, "legalEntityId");
            LocalDate reversalBookingDate = date(payload, "reversalBookingDate");
            if (reversalBookingDate == null) {
                throw new IllegalArgumentException(
                    "Champ obligatoire absent : reversalBookingDate");
            }
            return engineFor(engines, type, entity).cancel(
                uuid(payload, "runId"), Callers.actorId(maker), reversalBookingDate,
                required(payload, "reason"));
        }
    }

    /** Ouverture d'un exercice : ses bornes et son compte de resultat, a deux. */
    static final class OpenFiscalYear implements MakerChecker.Handler {
        private final Database database;

        OpenFiscalYear(Database database) {
            this.database = database;
        }

        @Override public String name() { return "FISCAL_YEAR_OPEN"; }
        @Override public Operation operation() { return Operation.FISCAL_YEAR_MANAGE; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            return AccessTarget.inEntity(uuid(payload, "legalEntityId"));
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "end");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            LocalDate start = date(payload, "start");
            LocalDate end = date(payload, "end");
            if (start == null || end == null) {
                throw new IllegalArgumentException("Champs obligatoires absents : start, end");
            }
            UUID id = database.inTransaction(c -> FiscalYears.open(
                c, uuid(payload, "legalEntityId"), start, end, uuid(payload, "resultAccountId"),
                Callers.actorId(maker), Callers.actorId(checker)));
            return new Requests.Created(id);
        }
    }

    /**
     * L'affectation du resultat : la decision de l'assemblee, demandee par l'un, validee par un
     * autre, comptabilisee a l'approbation avec les deux sujets. Les destinations et leur somme
     * sont verifiees contre le resultat determine, au moment d'ecrire.
     */
    static final class AppropriateResult implements MakerChecker.Handler {
        private final Database database;
        private final io.corebanking.ledger.domain.posting.PostingService posting;

        AppropriateResult(Database database,
                          io.corebanking.ledger.domain.posting.PostingService posting) {
            this.database = database;
            this.posting = posting;
        }

        @Override public String name() { return "RESULT_APPROPRIATE"; }
        @Override public Operation operation() { return Operation.RESULT_APPROPRIATION; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            return AccessTarget.inEntity(uuid(payload, "legalEntityId"));
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "fiscalYearId");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            LocalDate bookingDate = date(payload, "bookingDate");
            LocalDate decidedOn = date(payload, "decidedOn");
            if (bookingDate == null || decidedOn == null) {
                throw new IllegalArgumentException(
                    "Champs obligatoires absents : bookingDate, decidedOn");
            }
            if (!(payload.get("allocations") instanceof List<?> items) || items.isEmpty()) {
                throw new IllegalArgumentException("Champ obligatoire absent : allocations");
            }
            UUID fiscalYearId = uuid(payload, "fiscalYearId");
            return database.inTransaction(c -> {
                FiscalYears.FiscalYear year = FiscalYears.require(c, fiscalYearId);
                if (!year.legalEntityId().equals(uuid(payload, "legalEntityId"))) {
                    throw new FiscalYears.UnknownFiscalYearException(fiscalYearId);
                }
                var currency = io.corebanking.ledger.store.Balances.currencyOf(
                    c, year.resultAccountId());
                List<FiscalYears.Allocation> allocations = new java.util.ArrayList<>();
                for (Object item : items) {
                    if (!(item instanceof Map<?, ?> raw)) {
                        throw new IllegalArgumentException(
                            "Une destination est un objet : accountId, amount, currency");
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> allocation = (Map<String, Object>) raw;
                    allocations.add(new FiscalYears.Allocation(
                        uuid(allocation, "accountId"),
                        io.corebanking.api.usecase.Amounts.in(
                            required(allocation, "amount"), text(allocation, "currency"),
                            currency, "une destination du resultat")));
                }
                return FiscalYears.appropriate(c, posting, new FiscalYears.Appropriation(
                    fiscalYearId, bookingDate, decidedOn, text(payload, "reference"),
                    allocations, Callers.actorId(maker), Callers.actorId(checker)));
            });
        }
    }

    // ------------------------------------------------------------------ suretes

    /** Prise d'une surete : enregistree par l'un, validee par un autre. */
    static final class RegisterCollateral implements MakerChecker.Handler {
        private final Database database;

        RegisterCollateral(Database database) {
            this.database = database;
        }

        @Override public String name() { return "COLLATERAL_REGISTER"; }
        @Override public Operation operation() { return Operation.COLLATERAL_MANAGE; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            return AccessTarget.inEntity(uuid(payload, "legalEntityId"));
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "assetReference");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            UUID entity = uuid(payload, "legalEntityId");
            UUID id = database.inTransaction(c -> {
                CurrencyRef currency = io.corebanking.api.usecase.AccountUseCases.currency(
                    c, required(payload, "currency"));
                Money assetValue = Money.of(new java.math.BigDecimal(required(payload, "assetValue")),
                                            currency);
                Money secured = Money.of(new java.math.BigDecimal(required(payload, "securedAmount")),
                                         currency);
                UUID customer = payload.get("customerPartyId") == null ? null
                                                                        : uuid(payload, "customerPartyId");
                LocalDate valuedOn = date(payload, "valuedOn");
                if (valuedOn == null) {
                    throw new IllegalArgumentException("Champ obligatoire absent : valuedOn");
                }
                return Collaterals.register(c, new Collaterals.Draft(
                    entity, customer, required(payload, "assetReference"),
                    required(payload, "kind"), required(payload, "label"), assetValue, secured,
                    integer(payload, "rank"), valuedOn, Callers.actorId(maker),
                    Callers.actorId(checker)));
            });
            return new Requests.Created(id);
        }
    }

    private static Collaterals.Header requireCollateral(Database database, UUID entity, UUID id) {
        return database.inTransaction(c -> Collaterals.find(c, id))
            .filter(header -> header.legalEntityId().equals(entity))
            .orElseThrow(() -> new ParameterUseCases.UnknownParameterException("Surete", id));
    }

    /** Affectation d'une quote-part de surete a un credit. */
    static final class AllocateCollateral implements MakerChecker.Handler {
        private final Database database;

        AllocateCollateral(Database database) {
            this.database = database;
        }

        @Override public String name() { return "COLLATERAL_ALLOCATE"; }
        @Override public Operation operation() { return Operation.COLLATERAL_MANAGE; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            Collaterals.Header collateral = requireCollateral(
                database, uuid(payload, "legalEntityId"), uuid(payload, "collateralId"));
            return AccessTarget.inEntity(collateral.legalEntityId());
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "collateralId");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            Collaterals.Header collateral = requireCollateral(
                database, uuid(payload, "legalEntityId"), uuid(payload, "collateralId"));
            LoanContract contract = LoanUseCases.require(database, uuid(payload, "contractId"));
            java.math.BigDecimal share = new java.math.BigDecimal(required(payload, "sharePercent"));
            database.inTransaction(c -> {
                Collaterals.allocate(c, collateral.id(), contract.id(), share);
                return null;
            });
            return Map.of("collateralId", collateral.id(), "contractId", contract.id(),
                          "sharePercent", share);
        }
    }

    /** Mainlevee : la surete est marquee, jamais supprimee. */
    static final class ReleaseCollateral implements MakerChecker.Handler {
        private final Database database;

        ReleaseCollateral(Database database) {
            this.database = database;
        }

        @Override public String name() { return "COLLATERAL_RELEASE"; }
        @Override public Operation operation() { return Operation.COLLATERAL_MANAGE; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            Collaterals.Header collateral = requireCollateral(
                database, uuid(payload, "legalEntityId"), uuid(payload, "collateralId"));
            return AccessTarget.inEntity(collateral.legalEntityId());
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "collateralId");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            Collaterals.Header collateral = requireCollateral(
                database, uuid(payload, "legalEntityId"), uuid(payload, "collateralId"));
            if (!"ACTIVE".equals(collateral.status())) {
                throw new IllegalStateException(
                    "La surete " + collateral.assetReference() + " est deja " + collateral.status());
            }
            LocalDate on = date(payload, "on");
            database.inTransaction(c -> {
                Collaterals.release(c, collateral.id(),
                                    on != null ? on : io.corebanking.api.usecase.AccountUseCases
                                        .businessDate(c, collateral.legalEntityId()));
                return null;
            });
            return Map.of("collateralId", collateral.id(), "status", "RELEASED");
        }
    }

    // ------------------------------------------------------------------ activations

    /** Activation d'un regime de surete : jamais par son redacteur. */
    static final class ActivateCollateralPolicy implements MakerChecker.Handler {
        private final Database database;

        ActivateCollateralPolicy(Database database) {
            this.database = database;
        }

        @Override public String name() { return "COLLATERAL_POLICY_ACTIVATE"; }
        @Override public Operation operation() { return Operation.RISK_PARAMETER_ACTIVATE; }

        private Collaterals.PolicyHeader require(Map<String, Object> payload) {
            UUID id = uuid(payload, "policyId");
            return database.inTransaction(c -> Collaterals.findPolicy(c, id))
                .filter(h -> h.legalEntityId().equals(uuid(payload, "legalEntityId")))
                .orElseThrow(() -> new ParameterUseCases.UnknownParameterException(
                    "Regime de surete", id));
        }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            return AccessTarget.inEntity(require(payload).legalEntityId());
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "policyId");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            Collaterals.PolicyHeader policy = require(payload);
            UUID approver = Callers.actorId(checker);
            if (approver.equals(policy.createdBy())) {
                throw new IllegalStateException(
                    "Le regime " + policy.kind() + " ne peut pas etre active par son redacteur.");
            }
            database.inTransaction(c -> {
                Collaterals.activatePolicy(c, policy.id(), approver);
                return null;
            });
            return Map.of("policyId", policy.id(), "kind", policy.kind(), "status", "ACTIVE");
        }
    }

    /** Activation d'une grille de risque : elle decide du niveau de provision du portefeuille. */
    static final class ActivateRiskProfile implements MakerChecker.Handler {
        private final Database database;

        ActivateRiskProfile(Database database) {
            this.database = database;
        }

        @Override public String name() { return "RISK_PROFILE_ACTIVATE"; }
        @Override public Operation operation() { return Operation.RISK_PARAMETER_ACTIVATE; }

        private RiskProfiles.Header require(Map<String, Object> payload) {
            UUID id = uuid(payload, "profileId");
            return database.inTransaction(c -> RiskProfiles.find(c, id))
                .filter(h -> h.legalEntityId().equals(uuid(payload, "legalEntityId")))
                .orElseThrow(() -> new ParameterUseCases.UnknownParameterException(
                    "Profil de risque", id));
        }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            return AccessTarget.inEntity(require(payload).legalEntityId());
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "profileId");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            RiskProfiles.Header profile = require(payload);
            UUID approver = Callers.actorId(checker);
            if (approver.equals(profile.createdBy())) {
                throw new IllegalStateException(
                    "La grille " + profile.code() + " ne peut pas etre activee par son redacteur.");
            }
            database.inTransaction(c -> {
                RiskProfiles.activate(c, profile.id(), approver);
                return null;
            });
            return Map.of("profileId", profile.id(), "code", profile.code(), "status", "ACTIVE");
        }
    }

    /** Activation d'un schema comptable : il traduit toute operation ; jamais par son redacteur. */
    static final class ActivateAccountingSchema implements MakerChecker.Handler {
        private final Database database;

        ActivateAccountingSchema(Database database) {
            this.database = database;
        }

        @Override public String name() { return "ACCOUNTING_SCHEMA_ACTIVATE"; }
        @Override public Operation operation() { return Operation.ACCOUNTING_SCHEMA_ACTIVATE; }

        private SchemaCatalog.Header require(Map<String, Object> payload) {
            UUID id = uuid(payload, "schemaId");
            return database.inTransaction(c -> SchemaCatalog.find(c, id))
                .filter(h -> h.legalEntityId().equals(uuid(payload, "legalEntityId")))
                .orElseThrow(() -> new ParameterUseCases.UnknownParameterException(
                    "Schema comptable", id));
        }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            return AccessTarget.inEntity(require(payload).legalEntityId());
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "schemaId");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            SchemaCatalog.Header schema = require(payload);
            UUID approver = Callers.actorId(checker);
            if (approver.equals(schema.createdBy())) {
                throw new IllegalStateException(
                    "Le schema " + schema.code() + " ne peut pas etre active par son redacteur.");
            }
            database.inTransaction(c -> {
                SchemaCatalog.activate(c, schema.id(), approver);
                return null;
            });
            return Map.of("schemaId", schema.id(), "code", schema.code(), "status", "ACTIVE");
        }
    }

    /** Activation d'une maquette d'etat financier : jamais par son redacteur. */
    static final class ActivateStatementLayout implements MakerChecker.Handler {
        private final Database database;

        ActivateStatementLayout(Database database) {
            this.database = database;
        }

        @Override public String name() { return "STATEMENT_LAYOUT_ACTIVATE"; }
        @Override public Operation operation() { return Operation.STATEMENT_LAYOUT_ACTIVATE; }

        private io.corebanking.ledger.store.StatementLayouts.Layout require(
                Map<String, Object> payload) {
            UUID id = uuid(payload, "layoutId");
            return database.inTransaction(
                    c -> io.corebanking.ledger.store.StatementLayouts.find(c, id))
                .filter(layout -> layout.legalEntityId().equals(uuid(payload, "legalEntityId")))
                .orElseThrow(() -> new ParameterUseCases.UnknownParameterException(
                    "Maquette d'etat", id));
        }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            return AccessTarget.inEntity(require(payload).legalEntityId());
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "layoutId");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            var layout = require(payload);
            UUID approver = Callers.actorId(checker);
            if (approver.equals(layout.createdBy())) {
                throw new IllegalStateException(
                    "La maquette " + layout.code() + " ne peut pas etre activee par son redacteur.");
            }
            database.inTransaction(c -> {
                io.corebanking.ledger.store.StatementLayouts.activate(c, layout.id(), approver);
                return null;
            });
            return Map.of("layoutId", layout.id(), "code", layout.code(), "kind",
                          layout.kind().name(), "status", "ACTIVE");
        }
    }
}
