package io.corebanking.interest.service;

import static io.corebanking.kernel.money.Currencies.XOF;
import static org.assertj.core.api.Assertions.assertThat;

import io.corebanking.interest.accrual.AccrualSide;
import io.corebanking.interest.daycount.DayCountConvention;
import io.corebanking.interest.rate.FlatRate;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.store.Balances;
import io.corebanking.ledger.store.Reconciliation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Le calcul par lot doit donner exactement le meme montant que le calcul compte par compte.
 *
 * <p>C'est la garantie qui autorise l'optimisation. Un traitement de masse vingt fois plus rapide
 * mais qui arrondit differemment ne serait pas une optimisation : ce serait un second moteur, aux
 * resultats divergents, et la divergence n'apparaitrait qu'a la premiere reclamation.
 */
class BatchEquivalenceIT extends InterestTestBase {

    private static final LocalDate DEPART = BUSINESS_DATE;

    private record Fixture(List<Account> accounts, Account charges, Account courus) {}

    private Fixture seed(String prefix, List<String> balances, int daysBack) {
        Account caisse = gl("GL-CAISSE-" + prefix, NormalBalance.DEBIT);
        Account charges = gl("GL-CHARGES-" + prefix, NormalBalance.DEBIT);
        Account courus = gl("GL-COURUS-" + prefix, NormalBalance.CREDIT);

        List<Account> accounts = new ArrayList<>();
        for (int i = 0; i < balances.size(); i++) {
            Account client = customer(prefix + "-CLI-" + i);
            accounts.add(client);
            // Des dates de valeur echelonnees : les series different d'un compte a l'autre.
            LocalDate valeur = DEPART.minusDays(daysBack - (i % daysBack));
            postingService.post(PostingCommand.online(
                IdempotencyKey.of(prefix + "-dep-" + i), ENTITY, BUSINESS_DATE, "DEPOSIT", ACTOR,
                List.of(PostingLine.debit(caisse.id(), Money.of(balances.get(i), XOF), valeur, null),
                        PostingLine.credit(client.id(), Money.of(balances.get(i), XOF), valeur,
                                           null))));
        }
        return new Fixture(accounts, charges, courus);
    }

    private InterestTerms terms(Fixture fixture) {
        return new InterestTerms(FlatRate.of("6.75"), DayCountConvention.ACT_365,
                                 AccrualSide.CREDITOR, fixture.charges().id(),
                                 fixture.courus().id());
    }

    private List<BigDecimal> cumulatives(List<Account> accounts) {
        return database.inTransaction(c -> {
            List<BigDecimal> values = new ArrayList<>();
            for (Account account : accounts) {
                try (var ps = c.prepareStatement(
                    "SELECT COALESCE(MAX(cumulative_precise), 0) FROM interest_accrual"
                    + " WHERE account_id = ? AND status = 'ACTIVE'")) {
                    ps.setObject(1, account.id());
                    try (var rs = ps.executeQuery()) {
                        rs.next();
                        values.add(rs.getBigDecimal(1));
                    }
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
            return values;
        });
    }

    @Test
    @DisplayName("lot et compte par compte produisent les memes cumuls, au centieme pres de la precision interne")
    void batch_and_per_account_agree_exactly() {
        List<String> soldes = List.of("1234567", "10000000", "999", "50000000", "7777777",
                                      "250000", "3141592", "88888888", "1", "600000");

        Fixture unitaire = seed("U", soldes, 7);
        Fixture parLot = seed("B", soldes, 7);

        // Chemin compte par compte.
        for (Account account : unitaire.accounts()) {
            interestService.accrueThrough(ENTITY, account.id(), BUSINESS_DATE,
                InterestTermsResolver.fixed(terms(unitaire)), BUSINESS_DATE, ACTOR, RUN);
        }

        // Chemin ensembliste, sur des comptes strictement equivalents.
        var batch = new BatchInterestAccrualService(database, postingService);
        var outcome = batch.accrue(ENTITY,
            parLot.accounts().stream().map(Account::id).toList(), BUSINESS_DATE,
            (accountId, valueDate) -> terms(parLot), BUSINESS_DATE, ACTOR, UUID.randomUUID(),
            "lot-1");

        assertThat(outcome.accountsAccrued()).isEqualTo(soldes.size());
        assertThat(cumulatives(parLot.accounts())).isEqualTo(cumulatives(unitaire.accounts()));
    }

    @Test
    @DisplayName("le lot impute une seule ecriture pour tout le portefeuille, au montant total exact")
    void the_batch_posts_one_aggregated_entry() {
        List<String> soldes = List.of("10000000", "20000000", "30000000");
        Fixture unitaire = seed("U2", soldes, 3);
        Fixture parLot = seed("B2", soldes, 3);

        for (Account account : unitaire.accounts()) {
            interestService.accrueThrough(ENTITY, account.id(), BUSINESS_DATE,
                InterestTermsResolver.fixed(terms(unitaire)), BUSINESS_DATE, ACTOR, RUN);
        }
        var batch = new BatchInterestAccrualService(database, postingService);
        var outcome = batch.accrue(ENTITY,
            parLot.accounts().stream().map(Account::id).toList(), BUSINESS_DATE,
            (accountId, valueDate) -> terms(parLot), BUSINESS_DATE, ACTOR, UUID.randomUUID(),
            "lot-1");

        // Une ecriture pour trois comptes, la ou le chemin unitaire en produit trois.
        assertThat(outcome.entries()).hasSize(1);

        database.inTransaction(c -> {
            Money agrege = Balances.current(c, parLot.courus().id());
            Money unitaireTotal = Balances.current(c, unitaire.courus().id());
            // Le montant impute est le meme : l'agregation ne change que le nombre d'ecritures.
            assertThat(agrege).isEqualTo(unitaireTotal);
            assertThat(Reconciliation.allBlockingChecks(c, ENTITY)).isEmpty();
            return null;
        });
    }

    @Test
    @DisplayName("un compte sans mouvement mais mal parametre est signale, pas ignore")
    void a_movementless_but_misconfigured_account_is_reported() {
        Account vierge = customer("SANS-MOUVEMENT");
        var batch = new BatchInterestAccrualService(database, postingService);

        var outcome = batch.accrue(ENTITY, List.of(vierge.id()), BUSINESS_DATE,
            (accountId, valueDate) -> {
                throw new io.corebanking.product.ProductNotFoundException("EP-ABSENT", valueDate);
            },
            BUSINESS_DATE, ACTOR, UUID.randomUUID(), "lot-1");

        // Il aura des mouvements demain : le defaut doit sortir aujourd'hui, pas a la reclamation.
        assertThat(outcome.anomalies()).hasSize(1);
        assertThat(outcome.anomalies().get(0)).contains(vierge.id().toString());
        assertThat(outcome.accountsAccrued()).isZero();
    }

    @Test
    @DisplayName("le lot impute une paire de lignes par agence : la charge d'interets est dans le resultat de l'agence du client, sans liaison")
    void the_batch_posts_one_pair_of_lines_per_branch() {
        Account liaisonA = gl("LIAISON-BR-A", NormalBalance.DEBIT);
        Account liaisonB = gl("LIAISON-BR-B", NormalBalance.DEBIT);
        UUID agenceA = database.inTransaction(c -> io.corebanking.ledger.store.Branches.create(
            c, ENTITY, "BR-A", "Agence A", io.corebanking.ledger.store.Branches.Kind.BRANCH, null,
            BUSINESS_DATE, java.util.Map.of(XOF, liaisonA.id())));
        UUID agenceB = database.inTransaction(c -> io.corebanking.ledger.store.Branches.create(
            c, ENTITY, "BR-B", "Agence B", io.corebanking.ledger.store.Branches.Kind.BRANCH, null,
            BUSINESS_DATE, java.util.Map.of(XOF, liaisonB.id())));
        Account charges = gl("CHARGES-BR", NormalBalance.DEBIT);
        Account courus = gl("COURUS-BR", NormalBalance.CREDIT);
        Account caisse = gl("CAISSE-BR", NormalBalance.DEBIT);
        Account clientA = clientDe("CLI-BR-A", agenceA);
        Account clientB = clientDe("CLI-BR-B", agenceB);
        for (Account client : List.of(clientA, clientB)) {
            postingService.post(io.corebanking.ledger.domain.posting.PostingCommand.online(
                io.corebanking.kernel.id.IdempotencyKey.of("br-" + client.code()), ENTITY,
                BUSINESS_DATE, "DEPOSIT", ACTOR,
                List.of(io.corebanking.ledger.domain.posting.PostingLine.debit(
                            caisse.id(), Money.of("10000000", XOF), BUSINESS_DATE, null),
                        io.corebanking.ledger.domain.posting.PostingLine.credit(
                            client.id(), Money.of("10000000", XOF), BUSINESS_DATE, null))));
        }
        InterestTerms terms = new InterestTerms(
            io.corebanking.interest.rate.FlatRate.of("6"),
            io.corebanking.interest.daycount.DayCountConvention.ACT_365,
            io.corebanking.interest.accrual.AccrualSide.CREDITOR, charges.id(), courus.id(), null);

        var batch = new BatchInterestAccrualService(database, postingService);
        var outcome = batch.accrue(ENTITY, List.of(clientA.id(), clientB.id()), BUSINESS_DATE,
            (accountId, valueDate) -> terms, BUSINESS_DATE, ACTOR, UUID.randomUUID(), "lot-br");

        // Deux comptes, deux agences, deux ecritures — chacune dans les livres de son agence.
        assertThat(outcome.accountsAccrued()).isEqualTo(2);
        assertThat(outcome.entries()).hasSize(2);
        database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "SELECT DISTINCT e.branch_id FROM journal_entry e WHERE e.id = ANY (?)")) {
                ps.setArray(1, c.createArrayOf("uuid", outcome.entries().toArray()));
                try (var rs = ps.executeQuery()) {
                    java.util.Set<UUID> agences = new java.util.HashSet<>();
                    while (rs.next()) {
                        agences.add(rs.getObject(1, UUID.class));
                    }
                    assertThat(agences).containsExactlyInAnyOrder(agenceA, agenceB);
                }
            } catch (java.sql.SQLException e) {
                throw new io.corebanking.ledger.store.LedgerStoreException("Agences", e);
            }
            try (var ps = c.prepareStatement(
                "SELECT count(*) FROM journal_line WHERE entry_id = ANY (?) AND kind = 'LIAISON'")) {
                ps.setArray(1, c.createArrayOf("uuid", outcome.entries().toArray()));
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getLong(1)).isZero();
                }
            } catch (java.sql.SQLException e) {
                throw new io.corebanking.ledger.store.LedgerStoreException("Liaisons", e);
            }
            assertThat(Balances.current(c, courus.id())).isEqualTo(Money.of("3288", XOF));
            assertThat(Reconciliation.allBlockingChecks(c, ENTITY)).isEmpty();
            return null;
        });
    }

    private static Account clientDe(String code, UUID agence) {
        Account account = new Account(UUID.randomUUID(), ENTITY, code,
                                      io.corebanking.ledger.domain.account.AccountKind.CUSTOMER,
                                      NormalBalance.CREDIT, XOF, true, true, 1,
                                      io.corebanking.ledger.domain.account.AccountStatus.ACTIVE,
                                      agence);
        database.inTransaction(c -> { io.corebanking.ledger.store.Accounts.create(c, account); return null; });
        return account;
    }
}
