package io.corebanking.ledger.domain;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountStatus;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingContext;
import io.corebanking.ledger.domain.posting.PostingLine;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Jeux d'essai partages. */
public final class Fixtures {

    public static final UUID ENTITY = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    public static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000ac");
    public static final LocalDate TODAY = LocalDate.of(2026, 9, 13);

    private Fixtures() {}

    public static Account customerAccount(String code, CurrencyRef currency) {
        return new Account(UUID.randomUUID(), ENTITY, code, AccountKind.CUSTOMER,
                           NormalBalance.CREDIT, currency, true, true, 1, AccountStatus.ACTIVE);
    }

    public static Account glAccount(String code, CurrencyRef currency, NormalBalance normalBalance) {
        return new Account(UUID.randomUUID(), ENTITY, code, AccountKind.GL,
                           normalBalance, currency, true, false, 1, AccountStatus.ACTIVE);
    }

    public static PostingContext context(CurrencyRef functional, Account... accounts) {
        Map<UUID, Account> map = new LinkedHashMap<>();
        for (Account account : accounts) {
            map.put(account.id(), account);
        }
        return new PostingContext(functional, map);
    }

    public static PostingCommand command(List<PostingLine> lines) {
        return PostingCommand.online(IdempotencyKey.of(UUID.randomUUID().toString()),
                                     ENTITY, TODAY, "TEST", ACTOR, lines);
    }
}
