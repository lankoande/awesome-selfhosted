package io.corebanking.tfj;

import static org.assertj.core.api.Assertions.assertThat;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountStatus;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.ledger.store.Branches;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.ledger.store.Reconciliation;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L'arrete tient la balance par agence et prouve chaque nuit que les liaisons s'eliminent. */
class TfjInterbranchIT extends TfjTestBase {

    @Test
    @DisplayName("la journee arrete les soldes par agence, et un compte de liaison qui ne se reflete plus bloque la journee suivante")
    void branch_balances_and_liaison_mirror() {
        LocalDate jour = businessDate();
        Account liaisonA = account("LIAISON-A", AccountKind.GL, NormalBalance.DEBIT);
        Account liaisonB = account("LIAISON-B", AccountKind.GL, NormalBalance.DEBIT);
        Reseau reseau = database.inTransaction(c -> new Reseau(
            Branches.headOffice(c, ENTITY),
            Branches.create(c, ENTITY, "A", "Agence A", Branches.Kind.BRANCH, null, jour,
                            Map.of(Currencies.XOF, liaisonA.id())),
            Branches.create(c, ENTITY, "B", "Agence B", Branches.Kind.BRANCH, null, jour,
                            Map.of(Currencies.XOF, liaisonB.id()))));
        Account client = compte("CLI-BR-A", AccountKind.CUSTOMER, NormalBalance.CREDIT, reseau.a());
        Account caisseA = compte("CAISSE-BR-A", AccountKind.INTERNAL, NormalBalance.DEBIT, reseau.a());
        Account caisseB = compte("CAISSE-BR-B", AccountKind.INTERNAL, NormalBalance.DEBIT, reseau.b());
        deposit(client, caisseA, "100000", jour, "br-depot");
        // Retrait deplace : le client de A a la caisse de B.
        postingService.post(PostingCommand.online(
            IdempotencyKey.of("br-retrait"), ENTITY, jour, "CASH_WITHDRAWAL", ACTOR,
            List.of(PostingLine.debit(client.id(), xof("20000"), jour, null),
                    PostingLine.credit(caisseB.id(), xof("20000"), jour, null)))
            .withBranch(reseau.b()));

        TfjRun run = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);

        assertThat(run.isCompleted()).as(run.summary()).isTrue();
        Map<String, BigDecimal> cliche = clicheParAgence(jour);
        assertThat(cliche).containsEntry(liaisonA.code() + "@A", montant("-20000"))
                          .containsEntry(liaisonA.code() + "@SIEGE", montant("20000"))
                          .containsEntry(liaisonB.code() + "@B", montant("20000"))
                          .containsEntry(liaisonB.code() + "@SIEGE", montant("-20000"))
                          .containsEntry(client.code() + "@A", montant("80000"))
                          .containsEntry(caisseB.code() + "@B", montant("-20000"));
        database.inTransaction(c -> {
            assertThat(Reconciliation.interbranchMirror(c, ENTITY, jour)).isEmpty();
            assertThat(Reconciliation.interbranchMirrorReplayed(c, ENTITY)).isEmpty();
            return null;
        });

        // Une ecriture d'ordre divers sur le compte de liaison de A, dans les livres de A seuls :
        // equilibree pour l'agence, admise par le journal — et le miroir ne se reflete plus.
        LocalDate lendemain = businessDate();
        postingService.post(PostingCommand.online(
            IdempotencyKey.of("br-od"), ENTITY, lendemain, "MANUAL", ACTOR,
            List.of(PostingLine.debit(liaisonA.id(), xof("1000"), lendemain, null),
                    PostingLine.credit(caisseA.id(), xof("1000"), lendemain, null)))
            .withBranch(reseau.a()));

        TfjRun suivant = engine.run(ENTITY, lendemain, ACTOR, RunMode.REAL);

        assertThat(suivant.isCompleted()).isFalse();
        assertThat(suivant.failedStep()).isPresent();
        assertThat(suivant.failedStep().get().name()).isEqualTo("RECONCILIATION");
        assertThat(suivant.summary()).contains("LIAISON_AGENCE_MIROIR").contains("agence A");
        assertThat(businessDate()).isEqualTo(lendemain);
    }

    private record Reseau(UUID siege, UUID a, UUID b) {}

    private static Account compte(String code, AccountKind kind, NormalBalance normal, UUID branch) {
        Account account = new Account(UUID.randomUUID(), ENTITY, code, kind, normal, Currencies.XOF,
                                      true, false, 1, AccountStatus.ACTIVE, branch);
        database.inTransaction(c -> { Accounts.create(c, account); return null; });
        return account;
    }

    private static Money xof(String amount) {
        return Money.of(amount, Currencies.XOF);
    }

    private static BigDecimal montant(String amount) {
        return new BigDecimal(amount).setScale(5);
    }

    /** Cliche par agence du jour : « code du compte @ code de l'agence » → solde. */
    private static Map<String, BigDecimal> clicheParAgence(LocalDate date) {
        return database.inTransaction(c -> {
            Map<String, BigDecimal> cliche = new LinkedHashMap<>();
            try (var ps = c.prepareStatement(
                "SELECT a.code, b.code, bb.closing_balance FROM branch_balance_daily bb"
                + " JOIN account a ON a.id = bb.account_id JOIN branch b ON b.id = bb.branch_id"
                + " WHERE bb.business_date = ? AND a.legal_entity_id = ?")) {
                ps.setObject(1, date);
                ps.setObject(2, ENTITY);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        cliche.put(rs.getString(1) + "@" + rs.getString(2), rs.getBigDecimal(3));
                    }
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Cliche par agence", e);
            }
            return cliche;
        });
    }
}
