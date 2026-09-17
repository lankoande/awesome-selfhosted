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
import io.corebanking.loan.service.LendingPolicies;
import io.corebanking.loan.service.LoanOrigination;
import io.corebanking.deposits.StandingOrderService;
import io.corebanking.loan.service.LoanWriteOffService;
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
                                                     posting,
                                                 io.corebanking.deposits.ChequeService cheques,
                                                 io.corebanking.deposits.DirectDebitService
                                                     directDebits,
                                                 LoanWriteOffService writeOffs,
                                                 StandingOrderService standingOrders,
                                                 io.corebanking.deposits.TermDepositService
                                                     termDeposits) {
        return List.of(new OpenAccount(lifecycle), new CloseAccount(lifecycle, accounts),
                       new BlockAccount(lifecycle, accounts), new LiftBlock(lifecycle, accounts),
                       new PlaceHold(database, accounts), new ReleaseHold(database, accounts),
                       new VerifyKyc(parties), new DisburseLoan(database, loans),
                       new PrepayLoan(database, loans), new ActivateProduct(database),
                       new AddValueDateRule(database), new AddChannelCutoff(database),
                       new AddHoliday(database),
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
                       new ActivateStatementLayout(database), new SetAccountLimit(database, accounts),
                       new IssueChequeBook(cheques, accounts),
                       new RegisterMandate(directDebits, accounts),
                       new SetSuspensePolicy(database), new QuoteFxRate(database),
                       new DeclareFxPosition(database), new DeclareRelationship(database),
                       new EndRelationship(database), new DeclareBeneficialOwner(database),
                       new EndBeneficialOwner(database), new SetKycPolicy(database),
                       new DecideApplication(database), new ClearCondition(database),
                       new SetLendingPolicy(database), new WriteOffLoan(database, writeOffs),
                       new ReviseLoanRate(database, loans),
                       new RegisterStandingOrder(database, standingOrders, accounts),
                       new SubscribeTermDeposit(termDeposits, accounts),
                       new BreakTermDeposit(database, termDeposits, accounts),
                       new DeclareMonitoringScenario(database), new ReportSuspicion(database),
                       new DeclareRegulatoryReport(database), new TransmitReport(database));
    }

    private static int integer(Map<String, Object> payload, String key) {
        String value = required(payload, key);
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Entier attendu pour " + key + " : " + value);
        }
    }


    /**
     * Decision sur une demande de credit : le plafond du role decide qui peut la prendre, et la
     * derogation, quand le dossier sort de la politique, s'ecrit dans la soumission.
     */
    static final class DecideApplication implements MakerChecker.Handler {
        private final Database database;

        DecideApplication(Database database) {
            this.database = database;
        }

        @Override public String name() { return "LOAN_APPLICATION_DECIDE"; }
        @Override public Operation operation() { return Operation.LOAN_APPLICATION_DECIDE; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            LoanOrigination.Application application = application(payload);
            AccessTarget target = AccessTarget.inEntity(application.legalEntityId());
            String granted = text(payload, "grantedAmount");
            // Le plafond porte sur ce qu'on accorde ; a defaut, sur ce qui est demande — un refus
            // ne libere rien, mais il se prend au meme niveau que l'accord qu'il remplace.
            return target.withAmount(granted == null ? application.requestedAmount()
                : Money.of(new java.math.BigDecimal(granted), application.currency()));
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "applicationId");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            LoanOrigination.Application application = application(payload);
            String granted = text(payload, "grantedAmount");
            LoanOrigination.Outcome outcome;
            try {
                outcome = LoanOrigination.Outcome.valueOf(
                    required(payload, "outcome").trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Sens de decision inconnu : "
                    + text(payload, "outcome") + " (APPROVED, REJECTED)");
            }
            String rate = text(payload, "grantedRatePercent");
            String term = text(payload, "grantedTermMonths");
            LoanOrigination.Verdict verdict = new LoanOrigination.Verdict(
                application.id(), outcome,
                granted == null ? null
                    : Money.of(new java.math.BigDecimal(granted), application.currency()),
                term == null ? null : integer(payload, "grantedTermMonths"),
                rate == null ? null : new java.math.BigDecimal(rate),
                date(payload, "decidedOn") == null ? businessDate(application) : date(payload, "decidedOn"),
                required(payload, "reason"), text(payload, "waiverReason"),
                Callers.actorId(maker), Callers.actorId(checker));
            return database.inTransaction(c -> LoanOrigination.decide(c, verdict));
        }

        private LoanOrigination.Application application(Map<String, Object> payload) {
            return database.inTransaction(
                c -> LoanOrigination.require(c, uuid(payload, "applicationId")));
        }

        private LocalDate businessDate(LoanOrigination.Application application) {
            return database.inTransaction(c -> io.corebanking.api.usecase.AccountUseCases
                .businessDate(c, application.legalEntityId()));
        }
    }

    /** Levee d'une condition suspensive : elle ouvre un versement, elle se constate a deux. */
    static final class ClearCondition implements MakerChecker.Handler {
        private final Database database;

        ClearCondition(Database database) {
            this.database = database;
        }

        @Override public String name() { return "LOAN_CONDITION_CLEAR"; }
        @Override public Operation operation() { return Operation.LOAN_CONDITION_CLEAR; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            return AccessTarget.inEntity(application(payload).legalEntityId());
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "conditionId");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            LoanOrigination.Application application = application(payload);
            LocalDate on = date(payload, "clearedOn") == null
                ? database.inTransaction(c -> io.corebanking.api.usecase.AccountUseCases
                      .businessDate(c, application.legalEntityId()))
                : date(payload, "clearedOn");
            return database.inTransaction(c -> LoanOrigination.clearCondition(
                c, uuid(payload, "conditionId"), on, text(payload, "evidence"),
                Callers.actorId(maker), Callers.actorId(checker)));
        }

        private LoanOrigination.Application application(Map<String, Object> payload) {
            return database.inTransaction(c -> LoanOrigination.require(
                c, LoanOrigination.requireCondition(c, uuid(payload, "conditionId"))
                       .applicationId()));
        }
    }

    /** Politique d'octroi : ce que la banque exige d'un dossier, ecrit a deux. */
    static final class SetLendingPolicy implements MakerChecker.Handler {
        private final Database database;

        SetLendingPolicy(Database database) {
            this.database = database;
        }

        @Override public String name() { return "LENDING_POLICY_SET"; }
        @Override public Operation operation() { return Operation.LENDING_POLICY_MANAGE; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            return AccessTarget.inEntity(uuid(payload, "legalEntityId"));
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "productCode");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            String ratio = text(payload, "maxDebtServiceRatioPercent");
            String amount = text(payload, "maxAmount");
            String term = text(payload, "maxTermMonths");
            String downPayment = text(payload, "minDownPaymentPercent");
            String validity = text(payload, "decisionValidityDays");
            LendingPolicies.Draft draft = new LendingPolicies.Draft(
                uuid(payload, "legalEntityId"), required(payload, "productCode"),
                ratio == null ? null : new java.math.BigDecimal(ratio),
                amount == null ? null : new java.math.BigDecimal(amount),
                term == null ? null : integer(payload, "maxTermMonths"),
                downPayment == null ? null : new java.math.BigDecimal(downPayment),
                Boolean.parseBoolean(text(payload, "collateralRequired")),
                validity == null ? null : integer(payload, "decisionValidityDays"),
                date(payload, "validFrom"), date(payload, "validTo"),
                Callers.actorId(maker), Callers.actorId(checker));
            return database.inTransaction(c -> LendingPolicies.declare(c, draft));
        }
    }


    /** Passage en perte : la sortie d'un actif des livres, a deux et sous plafond. */
    static final class WriteOffLoan implements MakerChecker.Handler {
        private final Database database;
        private final LoanWriteOffService writeOffs;

        WriteOffLoan(Database database, LoanWriteOffService writeOffs) {
            this.database = database;
            this.writeOffs = writeOffs;
        }

        @Override public String name() { return "LOAN_WRITE_OFF"; }
        @Override public Operation operation() { return Operation.LOAN_WRITE_OFF; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            LoanContract contract = LoanUseCases.require(database, uuid(payload, "contractId"));
            // Le plafond porte sur ce qui sort des livres, pas sur le capital d'origine : un
            // credit largement rembourse ne mobilise pas la meme delegation qu'un credit intact.
            Money exposure = database.inTransaction(
                c -> io.corebanking.loan.service.LoanStore.exposureOf(c, contract));
            return AccessTarget.inEntity(contract.legalEntityId()).withAmount(exposure);
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "contractId");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            LoanContract contract = LoanUseCases.require(database, uuid(payload, "contractId"));
            LocalDate on = date(payload, "writtenOffOn") == null
                ? database.inTransaction(c -> io.corebanking.api.usecase.AccountUseCases
                      .businessDate(c, contract.legalEntityId()))
                : date(payload, "writtenOffOn");
            return writeOffs.writeOff(contract.id(), on, required(payload, "reason"),
                                      Callers.actorId(maker), Callers.actorId(checker));
        }
    }

    /** Revision de taux : un nouvel echeancier sur le capital restant du, a deux. */
    static final class ReviseLoanRate implements MakerChecker.Handler {
        private final Database database;
        private final LoanService loans;

        ReviseLoanRate(Database database, LoanService loans) {
            this.database = database;
            this.loans = loans;
        }

        @Override public String name() { return "LOAN_RATE_REVISION"; }
        @Override public Operation operation() { return Operation.LOAN_RATE_REVISION; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
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
            LocalDate from = date(payload, "effectiveFrom") == null
                ? database.inTransaction(c -> io.corebanking.api.usecase.AccountUseCases
                      .businessDate(c, contract.legalEntityId()))
                : date(payload, "effectiveFrom");
            UUID scheduleId = loans.reviseRate(contract.id(),
                new java.math.BigDecimal(required(payload, "annualRatePercent")), from,
                Callers.actorId(maker), Callers.actorId(checker));
            return new LoanUseCases.Revised(contract.id(), scheduleId, from,
                                            required(payload, "annualRatePercent"));
        }
    }


    /** Mise en place d'un ordre permanent, a deux : il engage des virements a venir. */
    static final class RegisterStandingOrder implements MakerChecker.Handler {
        private final Database database;
        private final StandingOrderService standingOrders;
        private final AccountDirectory accounts;

        RegisterStandingOrder(Database database, StandingOrderService standingOrders,
                              AccountDirectory accounts) {
            this.database = database;
            this.standingOrders = standingOrders;
            this.accounts = accounts;
        }

        @Override public String name() { return "STANDING_ORDER_REGISTER"; }
        @Override public Operation operation() { return Operation.STANDING_ORDER_REGISTER; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            UUID entity = uuid(payload, "legalEntityId");
            io.corebanking.ledger.domain.account.Account account =
                accounts.require(uuid(payload, "accountId"));
            return account.branchId() == null ? AccessTarget.inEntity(entity)
                                              : AccessTarget.inBranch(entity, account.branchId());
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "reference");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            UUID entity = uuid(payload, "legalEntityId");
            UUID accountId = uuid(payload, "accountId");
            io.corebanking.kernel.money.CurrencyRef currency =
                accounts.require(accountId).currency();
            String amount = text(payload, "amount");
            String floor = text(payload, "floorAmount");
            StandingOrderService.Kind kind;
            try {
                kind = StandingOrderService.Kind.valueOf(
                    required(payload, "kind").trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Nature d'ordre permanent inconnue : "
                    + text(payload, "kind") + " (FIXED, SWEEP)");
            }
            io.corebanking.kernel.time.Periodicity frequency;
            try {
                frequency = io.corebanking.kernel.time.Periodicity.valueOf(
                    required(payload, "frequency").trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Periodicite inconnue : "
                    + text(payload, "frequency"));
            }
            String beneficiary = text(payload, "beneficiaryAccountId");
            String occurrences = text(payload, "occurrences");
            String maxAttempts = text(payload, "maxAttempts");
            return standingOrders.register(new StandingOrderService.Draft(
                entity, accountId, required(payload, "reference"), kind,
                amount == null ? null
                    : io.corebanking.kernel.money.Money.of(new java.math.BigDecimal(amount),
                                                           currency),
                floor == null ? null
                    : io.corebanking.kernel.money.Money.of(new java.math.BigDecimal(floor),
                                                           currency),
                beneficiary == null ? null : UUID.fromString(beneficiary),
                text(payload, "beneficiaryName"), text(payload, "beneficiaryBank"),
                text(payload, "beneficiaryAccount"), frequency, date(payload, "startDate"),
                date(payload, "endDate"),
                occurrences == null ? null : integer(payload, "occurrences"),
                maxAttempts == null ? null : integer(payload, "maxAttempts"),
                text(payload, "narrative"), Callers.actorId(maker), Callers.actorId(checker)));
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

    /** Heure limite d'un canal, dans le fuseau de l'entite : elle deplace des dates de valeur. */
    static final class AddChannelCutoff implements MakerChecker.Handler {
        private final Database database;

        AddChannelCutoff(Database database) {
            this.database = database;
        }

        @Override public String name() { return "CHANNEL_CUTOFF_ADD"; }
        @Override public Operation operation() { return Operation.CALENDAR_MANAGE; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            return AccessTarget.inEntity(uuid(payload, "legalEntityId"));
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            String channel = text(payload, "channel");
            return channel == null ? "*" : channel;
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            UUID entity = uuid(payload, "legalEntityId");
            LocalDate validFrom = date(payload, "validFrom");
            if (validFrom == null) {
                throw new IllegalArgumentException("Champ obligatoire absent : validFrom");
            }
            java.time.LocalTime time;
            try {
                time = java.time.LocalTime.parse(required(payload, "cutoffTime"));
            } catch (java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException("Heure limite attendue au format HH:mm : "
                                                   + text(payload, "cutoffTime"));
            }
            io.corebanking.calendar.ChannelCutoff cutoff = new io.corebanking.calendar.ChannelCutoff(
                text(payload, "channel"), time,
                Boolean.parseBoolean(String.valueOf(payload.getOrDefault("closesChannel", "false"))),
                validFrom, date(payload, "validTo"));
            UUID id = database.inTransaction(c -> Calendars.addCutoff(
                c, entity, cutoff, Callers.actorId(maker), Callers.actorId(checker)));
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

    /** Un plafond propre au compte : demande par l'un, valide par un autre, dans l'agence du compte. */
    /** Chequier : demande par l'un, valide par un autre de l'agence du compte, aux frais du produit. */
    static final class IssueChequeBook implements MakerChecker.Handler {
        private final io.corebanking.deposits.ChequeService cheques;
        private final AccountDirectory accounts;

        IssueChequeBook(io.corebanking.deposits.ChequeService cheques, AccountDirectory accounts) {
            this.cheques = cheques;
            this.accounts = accounts;
        }

        @Override public String name() { return "CHEQUE_BOOK_ISSUE"; }
        @Override public Operation operation() { return Operation.CHEQUE_BOOK_ISSUE; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            var account = accounts.require(uuid(payload, "accountId"));
            return AccessTarget.inBranch(account.legalEntityId(), account.branchId());
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            var account = accounts.require(uuid(payload, "accountId"));
            return cheques.issueBook(new io.corebanking.deposits.ChequeService.BookIssue(
                account.legalEntityId(), account.id(), integer(payload, "count"),
                Callers.actorId(maker), Callers.actorId(checker)));
        }
    }

    /** Mandat de prelevement : demande par l'un, valide par un autre de l'agence du compte. */
    static final class RegisterMandate implements MakerChecker.Handler {
        private final io.corebanking.deposits.DirectDebitService directDebits;
        private final AccountDirectory accounts;

        RegisterMandate(io.corebanking.deposits.DirectDebitService directDebits,
                        AccountDirectory accounts) {
            this.directDebits = directDebits;
            this.accounts = accounts;
        }

        @Override public String name() { return "MANDATE_REGISTER"; }
        @Override public Operation operation() { return Operation.MANDATE_REGISTER; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            var account = accounts.require(uuid(payload, "accountId"));
            return AccessTarget.inBranch(account.legalEntityId(), account.branchId());
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            var account = accounts.require(uuid(payload, "accountId"));
            String maxAmount = text(payload, "maxAmount");
            Money max = maxAmount == null || maxAmount.isBlank() ? null
                : new Requests.Amount(maxAmount, required(payload, "currency")).on(account);
            String creditorAccountId = text(payload, "creditorAccountId");
            return directDebits.registerMandate(new io.corebanking.deposits.DirectDebitService
                .MandateDraft(account.legalEntityId(), account.id(), required(payload, "reference"),
                              required(payload, "creditorId"), required(payload, "creditorName"),
                              creditorAccountId == null || creditorAccountId.isBlank() ? null
                                  : UUID.fromString(creditorAccountId),
                              text(payload, "creditorBank"), text(payload, "creditorAccount"),
                              LocalDate.parse(required(payload, "signedOn")),
                              LocalDate.parse(required(payload, "validFrom")),
                              date(payload, "validTo"), max, Callers.actorId(maker),
                              Callers.actorId(checker)));
        }
    }

    /** Relation entre tiers : elle donne un pouvoir ou engage un groupe, donc a deux. */
    static final class DeclareRelationship implements MakerChecker.Handler {
        private final Database database;

        DeclareRelationship(Database database) {
            this.database = database;
        }

        @Override public String name() { return "RELATIONSHIP_DECLARE"; }
        @Override public Operation operation() { return Operation.PARTY_RELATIONSHIP; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            return AccessTarget.inEntity(uuid(payload, "legalEntityId"));
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "kind");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            io.corebanking.party.RelationshipKind kind;
            try {
                kind = io.corebanking.party.RelationshipKind.valueOf(
                    required(payload, "kind").trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Nature de relation inconnue : "
                                                   + text(payload, "kind"));
            }
            LocalDate validFrom = date(payload, "validFrom");
            if (validFrom == null) {
                throw new IllegalArgumentException("Champ obligatoire absent : validFrom");
            }
            return database.inTransaction(c -> io.corebanking.party.Relationships.declare(
                c, new io.corebanking.party.Relationships.Draft(
                    uuid(payload, "legalEntityId"), uuid(payload, "fromPartyId"),
                    uuid(payload, "toPartyId"), kind, validFrom, Callers.actorId(maker),
                    Callers.actorId(checker))));
        }
    }

    /** Fin d'une relation : le pouvoir cesse, et cela se decide a deux comme il s'est donne. */
    static final class EndRelationship implements MakerChecker.Handler {
        private final Database database;

        EndRelationship(Database database) {
            this.database = database;
        }

        @Override public String name() { return "RELATIONSHIP_END"; }
        @Override public Operation operation() { return Operation.PARTY_RELATIONSHIP; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            return AccessTarget.inEntity(uuid(payload, "legalEntityId"));
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            UUID id = uuid(payload, "relationshipId");
            LocalDate on = date(payload, "endedOn");
            return database.inTransaction(c -> {
                var relationship = io.corebanking.party.Relationships.require(c, id);
                if (!relationship.legalEntityId().equals(uuid(payload, "legalEntityId"))) {
                    throw new IllegalArgumentException("Relation inconnue : " + id);
                }
                io.corebanking.party.Relationships.end(c, id,
                    on == null ? AccountUseCases.businessDate(c, relationship.legalEntityId()) : on,
                    Callers.actorId(maker), Callers.actorId(checker));
                return io.corebanking.party.Relationships.require(c, id);
            });
        }
    }

    /** Beneficiaire effectif : une declaration reglementaire, donc a deux. */
    static final class DeclareBeneficialOwner implements MakerChecker.Handler {
        private final Database database;

        DeclareBeneficialOwner(Database database) {
            this.database = database;
        }

        @Override public String name() { return "BENEFICIAL_OWNER_DECLARE"; }
        @Override public Operation operation() { return Operation.PARTY_RELATIONSHIP; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            return AccessTarget.inEntity(uuid(payload, "legalEntityId"));
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            java.math.BigDecimal percent;
            try {
                percent = new java.math.BigDecimal(required(payload, "ownershipPercent"));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Part detenue attendue : "
                                                   + text(payload, "ownershipPercent"));
            }
            UUID entity = uuid(payload, "legalEntityId");
            LocalDate declaredOn = date(payload, "declaredOn");
            return database.inTransaction(c -> io.corebanking.party.BeneficialOwners.declare(
                c, new io.corebanking.party.BeneficialOwners.Declaration(
                    entity, uuid(payload, "partyId"), uuid(payload, "ownerPartyId"), percent,
                    declaredOn == null ? AccountUseCases.businessDate(c, entity) : declaredOn,
                    Callers.actorId(maker), Callers.actorId(checker))));
        }
    }

    /** Fin d'une declaration de detention : la part a change de main. */
    static final class EndBeneficialOwner implements MakerChecker.Handler {
        private final Database database;

        EndBeneficialOwner(Database database) {
            this.database = database;
        }

        @Override public String name() { return "BENEFICIAL_OWNER_END"; }
        @Override public Operation operation() { return Operation.PARTY_RELATIONSHIP; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            return AccessTarget.inEntity(uuid(payload, "legalEntityId"));
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            UUID entity = uuid(payload, "legalEntityId");
            UUID id = uuid(payload, "ownerId");
            LocalDate on = date(payload, "endedOn");
            database.inTransaction(c -> {
                io.corebanking.party.BeneficialOwners.end(c, id,
                    on == null ? AccountUseCases.businessDate(c, entity) : on,
                    Callers.actorId(maker), Callers.actorId(checker));
                return null;
            });
            return new Requests.Created(id);
        }
    }

    /** Politique de diligence : ce que la banque exige d'un dossier, a deux. */
    static final class SetKycPolicy implements MakerChecker.Handler {
        private final Database database;

        SetKycPolicy(Database database) {
            this.database = database;
        }

        @Override public String name() { return "KYC_POLICY_SET"; }
        @Override public Operation operation() { return Operation.KYC_POLICY_MANAGE; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            return AccessTarget.inEntity(uuid(payload, "legalEntityId"));
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "partyKind") + "/" + text(payload, "kycLevel");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            io.corebanking.party.PartyKind partyKind;
            io.corebanking.party.KycLevel level;
            try {
                partyKind = io.corebanking.party.PartyKind.valueOf(
                    required(payload, "partyKind").trim().toUpperCase(java.util.Locale.ROOT));
                level = io.corebanking.party.KycLevel.valueOf(
                    required(payload, "kycLevel").trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Nature de tiers ou niveau de diligence inconnu :"
                    + " " + text(payload, "partyKind") + " / " + text(payload, "kycLevel"));
            }
            java.util.Set<io.corebanking.party.DocumentKind> documents =
                new java.util.LinkedHashSet<>();
            String declared = text(payload, "requiredDocuments");
            if (declared != null && !declared.isBlank()) {
                for (String kind : declared.split(",")) {
                    try {
                        documents.add(io.corebanking.party.DocumentKind.valueOf(
                            kind.trim().toUpperCase(java.util.Locale.ROOT)));
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("Nature de piece inconnue : " + kind);
                    }
                }
            }
            String threshold = text(payload, "ownershipThresholdPercent");
            return database.inTransaction(c -> io.corebanking.party.KycPolicies.replace(
                c, new io.corebanking.party.KycPolicies.Draft(
                    uuid(payload, "legalEntityId"), partyKind, level, documents,
                    Boolean.parseBoolean(String.valueOf(payload.getOrDefault(
                        "beneficialOwnersRequired", "false"))),
                    threshold == null || threshold.isBlank() ? null
                        : new java.math.BigDecimal(threshold),
                    Callers.actorId(maker), Callers.actorId(checker))));
        }
    }

    /** Cours de cloture : cote par l'un, valide par un second — il controle tout cours applique. */
    static final class QuoteFxRate implements MakerChecker.Handler {
        private final Database database;

        QuoteFxRate(Database database) {
            this.database = database;
        }

        @Override public String name() { return "FX_RATE_QUOTE"; }
        @Override public Operation operation() { return Operation.FX_RATE_QUOTE; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            return AccessTarget.inEntity(uuid(payload, "legalEntityId"));
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "currency");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            LocalDate quotedOn = date(payload, "quotedOn");
            if (quotedOn == null) {
                throw new IllegalArgumentException("Champ obligatoire absent : quotedOn");
            }
            java.math.BigDecimal rate;
            try {
                rate = new java.math.BigDecimal(required(payload, "rate"));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Cours attendu : " + text(payload, "rate"));
            }
            return database.inTransaction(c -> io.corebanking.ledger.store.FxRates.quote(
                c, new io.corebanking.ledger.store.FxRates.Quote(
                    uuid(payload, "legalEntityId"),
                    required(payload, "currency").trim().toUpperCase(java.util.Locale.ROOT),
                    quotedOn, rate, required(payload, "source"), Callers.actorId(maker),
                    Callers.actorId(checker))));
        }
    }

    /** Position de change : ses comptes et sa marge, a deux. */
    static final class DeclareFxPosition implements MakerChecker.Handler {
        private final Database database;

        DeclareFxPosition(Database database) {
            this.database = database;
        }

        @Override public String name() { return "FX_POSITION_DECLARE"; }
        @Override public Operation operation() { return Operation.FX_POSITION_MANAGE; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            return AccessTarget.inEntity(uuid(payload, "legalEntityId"));
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "currency");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            return database.inTransaction(c -> io.corebanking.ledger.store.FxPositions.declare(
                c, new io.corebanking.ledger.store.FxPositions.Draft(
                    uuid(payload, "legalEntityId"),
                    required(payload, "currency").trim().toUpperCase(java.util.Locale.ROOT),
                    uuid(payload, "positionAccountId"), uuid(payload, "counterValueAccountId"),
                    uuid(payload, "gainAccountId"), uuid(payload, "lossAccountId"),
                    integer(payload, "toleranceBps"), Callers.actorId(maker),
                    Callers.actorId(checker))));
        }
    }

    /** Politique de suspens : anciennete toleree et responsable d'une nature, a deux. */
    static final class SetSuspensePolicy implements MakerChecker.Handler {
        private final Database database;

        SetSuspensePolicy(Database database) {
            this.database = database;
        }

        @Override public String name() { return "SUSPENSE_POLICY_SET"; }
        @Override public Operation operation() { return Operation.SUSPENSE_MANAGE; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            return AccessTarget.inEntity(uuid(payload, "legalEntityId"));
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "kind");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            io.corebanking.deposits.Suspense.Kind kind;
            try {
                kind = io.corebanking.deposits.Suspense.Kind.valueOf(
                    required(payload, "kind").trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Nature de suspens inconnue : " + text(payload, "kind"));
            }
            LocalDate validFrom = date(payload, "validFrom");
            if (validFrom == null) {
                throw new IllegalArgumentException("Champ obligatoire absent : validFrom");
            }
            return database.inTransaction(c -> io.corebanking.deposits.Suspense.setPolicy(
                c, new io.corebanking.deposits.Suspense.Draft(
                    uuid(payload, "legalEntityId"), kind, integer(payload, "maxBusinessDays"),
                    required(payload, "owner"), validFrom, date(payload, "validTo"),
                    Callers.actorId(maker), Callers.actorId(checker))));
        }
    }

    static final class SetAccountLimit implements MakerChecker.Handler {
        private final Database database;
        private final AccountDirectory accounts;

        SetAccountLimit(Database database, AccountDirectory accounts) {
            this.database = database;
            this.accounts = accounts;
        }

        @Override public String name() { return "ACCOUNT_LIMIT_SET"; }
        @Override public Operation operation() { return Operation.ACCOUNT_LIMIT_MANAGE; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            var account = accounts.require(uuid(payload, "accountId"));
            return AccessTarget.inBranch(account.legalEntityId(), account.branchId());
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            var account = accounts.require(uuid(payload, "accountId"));
            io.corebanking.deposits.Limits.Kind kind;
            try {
                kind = io.corebanking.deposits.Limits.Kind.valueOf(
                    required(payload, "kind").trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Nature de plafond inconnue : " + text(payload, "kind"));
            }
            LocalDate validFrom = date(payload, "validFrom");
            if (validFrom == null) {
                throw new IllegalArgumentException("Champ obligatoire absent : validFrom");
            }
            var amount = io.corebanking.api.usecase.Amounts.in(
                required(payload, "amount"), text(payload, "currency"), account.currency(),
                "le plafond");
            UUID id = database.inTransaction(c -> io.corebanking.deposits.Limits.set(
                c, new io.corebanking.deposits.Limits.Draft(
                    account.legalEntityId(), account.id(), kind, amount, validFrom,
                    date(payload, "validTo"), Callers.actorId(maker), Callers.actorId(checker))));
            return new Requests.Created(id);
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

    /**
     * Souscription d'un depot a terme : elle engage la banque sur un prix et sur une duree, et le
     * taux consenti est borne par le produit. Le plafond du role porte sur le capital place : ce
     * n'est pas de l'argent qui sort, mais c'est de la ressource que la banque achete.
     */
    static final class SubscribeTermDeposit implements MakerChecker.Handler {
        private final io.corebanking.deposits.TermDepositService termDeposits;
        private final AccountDirectory accounts;

        SubscribeTermDeposit(io.corebanking.deposits.TermDepositService termDeposits,
                             AccountDirectory accounts) {
            this.termDeposits = termDeposits;
            this.accounts = accounts;
        }

        @Override public String name() { return "TERM_DEPOSIT_SUBSCRIBE"; }
        @Override public Operation operation() { return Operation.TERM_DEPOSIT_SUBSCRIBE; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            UUID entity = uuid(payload, "legalEntityId");
            io.corebanking.ledger.domain.account.Account account =
                accounts.require(uuid(payload, "depositAccountId"));
            AccessTarget target = account.branchId() == null
                ? AccessTarget.inEntity(entity)
                : AccessTarget.inBranch(entity, account.branchId());
            return target.withAmount(io.corebanking.kernel.money.Money.of(
                new java.math.BigDecimal(required(payload, "principal")), account.currency()));
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "reference");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            UUID entity = uuid(payload, "legalEntityId");
            UUID depositAccountId = uuid(payload, "depositAccountId");
            io.corebanking.kernel.money.CurrencyRef currency =
                accounts.require(depositAccountId).currency();
            String rate = text(payload, "grantedRatePercent");
            String payment = text(payload, "interestPayment");
            io.corebanking.kernel.time.Periodicity periodicity = null;
            if (payment != null && !"AT_MATURITY".equalsIgnoreCase(payment.trim())) {
                try {
                    periodicity = io.corebanking.kernel.time.Periodicity.valueOf(
                        payment.trim().toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Periodicite de service des interets "
                        + "inconnue : " + payment);
                }
            }
            io.corebanking.deposits.TermDepositService.MaturityInstruction instruction;
            try {
                instruction = io.corebanking.deposits.TermDepositService.MaturityInstruction
                    .valueOf(required(payload, "maturityInstruction").trim()
                                 .toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Instruction de terme inconnue : "
                    + text(payload, "maturityInstruction")
                    + " (PAY_OUT, RENEW_PRINCIPAL, RENEW_ALL)");
            }
            return termDeposits.subscribe(new io.corebanking.deposits.TermDepositService.Draft(
                entity, required(payload, "reference"), depositAccountId,
                uuid(payload, "settlementAccountId"),
                io.corebanking.kernel.money.Money.of(
                    new java.math.BigDecimal(required(payload, "principal")), currency),
                rate == null ? null : new java.math.BigDecimal(rate),
                integer(payload, "termMonths"), periodicity, instruction,
                Callers.actorId(maker), Callers.actorId(checker)));
        }
    }

    /**
     * Rupture avant terme : elle defait un engagement pris des deux cotes, et coute au client le
     * prix de la duree qu'il ne tient pas. Elle se decide donc a deux, comme la souscription.
     */
    static final class BreakTermDeposit implements MakerChecker.Handler {
        private final Database database;
        private final io.corebanking.deposits.TermDepositService termDeposits;
        private final AccountDirectory accounts;

        BreakTermDeposit(Database database,
                         io.corebanking.deposits.TermDepositService termDeposits,
                         AccountDirectory accounts) {
            this.database = database;
            this.termDeposits = termDeposits;
            this.accounts = accounts;
        }

        @Override public String name() { return "TERM_DEPOSIT_BREAK"; }
        @Override public Operation operation() { return Operation.TERM_DEPOSIT_BREAK; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            io.corebanking.deposits.TermDepositService.TermDeposit deposit = deposit(payload);
            io.corebanking.ledger.domain.account.Account account =
                accounts.require(deposit.depositAccountId());
            AccessTarget target = account.branchId() == null
                ? AccessTarget.inEntity(deposit.legalEntityId())
                : AccessTarget.inBranch(deposit.legalEntityId(), account.branchId());
            return target.withAmount(deposit.principal());
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "termDepositId");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            return termDeposits.breakEarly(uuid(payload, "termDepositId"),
                                           required(payload, "reason"), Callers.actorId(maker),
                                           Callers.actorId(checker));
        }

        private io.corebanking.deposits.TermDepositService.TermDeposit deposit(
                Map<String, Object> payload) {
            UUID id = uuid(payload, "termDepositId");
            return database.inTransaction(
                c -> io.corebanking.deposits.TermDepositService.require(c, id));
        }
    }


    /**
     * Declaration d'un scenario de surveillance.
     *
     * <p>Elle se decide a deux parce qu'elle decide de ce que la banque regarde — et, ce qui est
     * plus grave, de ce qu'elle ne regarde pas. Un seuil releve d'un trait par une seule main
     * eteint une typologie entiere sans que rien ne le signale.
     */
    static final class DeclareMonitoringScenario implements MakerChecker.Handler {
        private final Database database;

        DeclareMonitoringScenario(Database database) {
            this.database = database;
        }

        @Override public String name() { return "AML_SCENARIO_DECLARE"; }
        @Override public Operation operation() { return Operation.AML_SCENARIO_MANAGE; }

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
            io.corebanking.compliance.MonitoringScenarios.Method method;
            try {
                method = io.corebanking.compliance.MonitoringScenarios.Method.valueOf(
                    required(payload, "method").trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Methode de surveillance inconnue : "
                    + text(payload, "method") + " (CASH_THRESHOLD, STRUCTURING, "
                    + "ATYPICAL_ACTIVITY, DORMANT_REACTIVATION)");
            }
            return database.inTransaction(c ->
                io.corebanking.compliance.MonitoringScenarios.declare(c,
                    new io.corebanking.compliance.MonitoringScenarios.Draft(
                        uuid(payload, "legalEntityId"), required(payload, "code"),
                        required(payload, "label"), method,
                        decimal(payload, "thresholdAmount"), count(payload, "windowDays"),
                        count(payload, "minimumCount"), decimal(payload, "ratio"),
                        rating(payload), date(payload, "validFrom"), date(payload, "validTo"),
                        Callers.actorId(maker), Callers.actorId(checker))));
        }

        private static String rating(Map<String, Object> payload) {
            String value = text(payload, "riskRating");
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return io.corebanking.party.RiskRating.valueOf(
                    value.trim().toUpperCase(java.util.Locale.ROOT)).name();
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Notation de risque inconnue : " + value);
            }
        }

        private static java.math.BigDecimal decimal(Map<String, Object> payload, String key) {
            String value = text(payload, key);
            return value == null || value.isBlank() ? null : new java.math.BigDecimal(value.trim());
        }

        private static Integer count(Map<String, Object> payload, String key) {
            String value = text(payload, key);
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Entier attendu pour " + key + " : " + value);
            }
        }

        private static java.time.LocalDate date(Map<String, Object> payload, String key) {
            String value = text(payload, key);
            return value == null || value.isBlank() ? null
                : java.time.LocalDate.parse(value.trim());
        }
    }

    /**
     * Redaction d'une declaration de soupcon.
     *
     * <p>Elle se decide a deux dans les deux sens : declarer met en cause une personne et engage
     * la banque ; ne pas declarer l'engage autant. Elle cite les alertes qu'elle couvre, qui n'en
     * ressortent plus — leur sort est scelle par la declaration, pas par un classement.
     */
    static final class ReportSuspicion implements MakerChecker.Handler {
        private final Database database;

        ReportSuspicion(Database database) {
            this.database = database;
        }

        @Override public String name() { return "AML_REPORT_DRAFT"; }
        @Override public Operation operation() { return Operation.AML_REPORT; }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            return AccessTarget.inEntity(uuid(payload, "legalEntityId"));
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "reference");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            java.util.List<java.util.UUID> alerts = new java.util.ArrayList<>();
            for (String id : required(payload, "alertIds").split(",")) {
                if (!id.isBlank()) {
                    alerts.add(java.util.UUID.fromString(id.trim()));
                }
            }
            java.util.UUID entity = uuid(payload, "legalEntityId");
            return database.inTransaction(c -> {
                java.time.LocalDate on = businessDate(c, entity);
                java.util.UUID id = io.corebanking.compliance.SuspiciousActivityReports.draft(c,
                    new io.corebanking.compliance.SuspiciousActivityReports.Draft(
                        entity, uuid(payload, "partyId"), required(payload, "reference"), on,
                        required(payload, "narrative"), alerts, Callers.actorId(maker),
                        Callers.actorId(checker)));
                return io.corebanking.compliance.SuspiciousActivityReports.require(c, id);
            });
        }

        private static java.time.LocalDate businessDate(java.sql.Connection c,
                                                        java.util.UUID legalEntityId) {
            try (var ps = c.prepareStatement(
                "SELECT current_business_date FROM legal_entity WHERE id = ?")) {
                ps.setObject(1, legalEntityId);
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("Entite inconnue : " + legalEntityId);
                    }
                    return rs.getObject(1, java.time.LocalDate.class);
                }
            } catch (java.sql.SQLException e) {
                throw new io.corebanking.ledger.store.LedgerStoreException("Date comptable", e);
            }
        }
    }


    /**
     * Declaration reglementaire : ce que la banque doit a son superviseur, et quand.
     *
     * <p>A deux, parce que les trois facons de manquer a l'obligation se decident au meme
     * endroit : oublier une declaration, la dater trop large, ou la seuiller trop haut.
     */
    static final class DeclareRegulatoryReport implements MakerChecker.Handler {
        private final Database database;

        DeclareRegulatoryReport(Database database) {
            this.database = database;
        }

        @Override public String name() { return "REGULATORY_DECLARATION_DECLARE"; }

        @Override
        public Operation operation() {
            return Operation.REGULATORY_DECLARATION_MANAGE;
        }

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
            String threshold = text(payload, "thresholdAmount");
            String validTo = text(payload, "validTo");
            return database.inTransaction(c ->
                io.corebanking.regulatory.RegulatoryDeclarations.declare(c,
                    new io.corebanking.regulatory.RegulatoryDeclarations.Draft(
                        uuid(payload, "legalEntityId"), required(payload, "code"),
                        required(payload, "label"),
                        io.corebanking.regulatory.RegulatoryDeclarations.Recipient.valueOf(
                            required(payload, "recipient")),
                        io.corebanking.regulatory.RegulatoryDeclarations.Method.valueOf(
                            required(payload, "method")),
                        io.corebanking.regulatory.RegulatoryDeclarations.Frequency.valueOf(
                            required(payload, "frequency")),
                        integer(payload, "deadlineDays"),
                        threshold == null || threshold.isBlank() ? null
                            : new java.math.BigDecimal(threshold),
                        java.time.LocalDate.parse(required(payload, "validFrom")),
                        validTo == null || validTo.isBlank() ? null
                            : java.time.LocalDate.parse(validTo),
                        Callers.actorId(maker), Callers.actorId(checker))));
        }
    }

    /**
     * Transmission d'un etat au superviseur.
     *
     * <p>Produire est un travail : il se refait tant que rien n'est parti. Transmettre engage la
     * banque, et ne se defait pas — d'ou les deux personnes, et la reference rendue par le
     * destinataire, qui est la preuve du depot.
     */
    static final class TransmitReport implements MakerChecker.Handler {
        private final Database database;

        TransmitReport(Database database) {
            this.database = database;
        }

        @Override public String name() { return "REGULATORY_REPORT_TRANSMIT"; }

        @Override
        public Operation operation() {
            return Operation.REGULATORY_REPORT_TRANSMIT;
        }

        @Override
        public AccessTarget targetOf(Caller maker, Map<String, Object> payload) {
            java.util.UUID entity = uuid(payload, "legalEntityId");
            io.corebanking.regulatory.ReportFilings.Filing filing = database.inTransaction(
                c -> io.corebanking.regulatory.ReportFilings.require(
                    c, uuid(payload, "filingId")));
            if (!filing.legalEntityId().equals(entity)) {
                throw new IllegalArgumentException("Etat inconnu : " + filing.id());
            }
            return AccessTarget.inEntity(entity);
        }

        @Override
        public String resourceOf(Map<String, Object> payload) {
            return text(payload, "filingId");
        }

        @Override
        public Object execute(Caller maker, Caller checker, Map<String, Object> payload) {
            java.util.UUID entity = uuid(payload, "legalEntityId");
            String on = text(payload, "transmittedOn");
            return database.inTransaction(c -> {
                java.time.LocalDate date = on == null || on.isBlank()
                    ? businessDate(c, entity) : java.time.LocalDate.parse(on);
                return io.corebanking.regulatory.ReportFilings.transmit(c,
                    uuid(payload, "filingId"), date, required(payload, "reference"),
                    Callers.actorId(maker), Callers.actorId(checker));
            });
        }

        private static java.time.LocalDate businessDate(java.sql.Connection c,
                                                        java.util.UUID legalEntityId) {
            try (var ps = c.prepareStatement(
                "SELECT current_business_date FROM legal_entity WHERE id = ?")) {
                ps.setObject(1, legalEntityId);
                try (var rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("Entite inconnue : " + legalEntityId);
                    }
                    return rs.getObject(1, java.time.LocalDate.class);
                }
            } catch (java.sql.SQLException e) {
                throw new io.corebanking.ledger.store.LedgerStoreException("Date comptable", e);
            }
        }
    }

}
