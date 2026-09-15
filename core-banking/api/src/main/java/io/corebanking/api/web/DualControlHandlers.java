package io.corebanking.api.web;

import io.corebanking.api.config.AccountDirectory;
import io.corebanking.api.usecase.AccountUseCases;
import io.corebanking.api.usecase.PartyUseCases;
import io.corebanking.deposits.AccountLifecycle;
import io.corebanking.deposits.BlockKind;
import io.corebanking.deposits.Holds;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.store.Database;
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
                                                 PartyService parties, AccountDirectory accounts) {
        return List.of(new OpenAccount(lifecycle), new CloseAccount(lifecycle, accounts),
                       new BlockAccount(lifecycle, accounts), new LiftBlock(lifecycle, accounts),
                       new PlaceHold(database, accounts), new ReleaseHold(database, accounts),
                       new VerifyKyc(parties));
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
}
