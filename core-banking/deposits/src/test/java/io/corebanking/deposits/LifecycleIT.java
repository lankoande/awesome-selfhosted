package io.corebanking.deposits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.interest.accrual.AccrualSide;
import io.corebanking.interest.service.InterestPositions;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.ledger.domain.account.AccountStatus;
import io.corebanking.ledger.store.AccountBlockedException;
import io.corebanking.ledger.store.Reconciliation;
import io.corebanking.loan.service.LoanCatalog;
import io.corebanking.party.AccountHolders;
import io.corebanking.party.HolderRole;
import io.corebanking.party.PartyService;
import io.corebanking.product.ProductCatalog;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Ouverture, blocage, cloture : un compte de depot de sa naissance a son solde de tout compte. */
class LifecycleIT extends DepositsTestBase {

    @Test
    @DisplayName("un compte s'ouvre a deux, a un tiers verifie, sur un produit de depot, a la date comptable")
    void ouverture() {
        Decor decor = decor("OUV");
        produit(decor, "EP-OUV", "SAVINGS_ACCOUNT", Map.of());
        UUID titulaire = client(decor.entityId(), "T-OUV");

        UUID compte = ouvrir(decor, "CLI-OUV-1", "EP-OUV", titulaire);

        var account = compte(compte);
        assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.controlAvailable()).isTrue();
        database.inTransaction(c -> {
            assertThat(ProductCatalog.resolveForAccount(c, decor.entityId(), compte, J).code())
                .isEqualTo("EP-OUV");
            assertThat(AccountHolders.holdersOf(c, compte, J))
                .extracting(AccountHolders.Holder::partyId, AccountHolders.Holder::role)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(titulaire, HolderRole.HOLDER));
            var events = AccountLifecycle.history(c, compte);
            assertThat(events).hasSize(1);
            assertThat(events.get(0).kind()).isEqualTo("OPENED");
            assertThat(events.get(0).occurredOn()).isEqualTo(J);
            assertThat(events.get(0).approverId()).isEqualTo(APPROVER);
            return null;
        });
    }

    @Test
    @DisplayName("rien ne s'ouvre a un tiers non verifie, seul, ni sur un produit de credit")
    void ouvertureRefusee() {
        Decor decor = decor("OUVR");
        produit(decor, "EP-OUVR", "SAVINGS_ACCOUNT", Map.of());
        UUID enAttente = clientNonVerifie(decor.entityId(), "T-OUVR-1");
        UUID verifie = client(decor.entityId(), "T-OUVR-2");

        assertThatThrownBy(() -> ouvrir(decor, "CLI-OUVR-1", "EP-OUVR", enAttente))
            .isInstanceOf(PartyService.PartyNotOperableException.class)
            .hasMessageContaining("PENDING");

        assertThatThrownBy(() -> lifecycle.open(new AccountLifecycle.Opening(
                decor.entityId(), "CLI-OUVR-2", verifie, "EP-OUVR", Currencies.XOF, siege(decor),
                ACTOR, ACTOR)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("a deux");

        // Un produit de credit porte un parametrage complet et valide : ce n'est pas lui qui
        // refuse, c'est la famille.
        database.inTransaction(c -> {
            UUID version = ProductCatalog.createDraft(c, new ProductCatalog.Draft(
                decor.entityId(), "PRET-OUVR", "TERM_LOAN", "Credit", "XOF", J.minusMonths(1), null,
                Map.of(LoanCatalog.P_ACCRUED, decor.charges().id().toString(),
                       LoanCatalog.P_ACCRUED_INTEREST, decor.agiosCourus().id().toString(),
                       LoanCatalog.P_INTEREST_INCOME, decor.produitsAgios().id().toString(),
                       LoanCatalog.P_TAX_ACCOUNT, decor.taxe().id().toString()),
                List.of(), ACTOR));
            ProductCatalog.activate(c, version, APPROVER);
            return null;
        });
        assertThatThrownBy(() -> ouvrir(decor, "CLI-OUVR-3", "PRET-OUVR", verifie))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("TERM_LOAN");
    }

    @Test
    @DisplayName("un blocage en debit laisse entrer les fonds ; un blocage total n'accepte plus que la banque")
    void blocages() {
        Decor decor = decor("BLQ");
        produit(decor, "EP-BLQ", "SAVINGS_ACCOUNT", Map.of());
        UUID compte = ouvrir(decor, "CLI-BLQ", "EP-BLQ", client(decor.entityId(), "T-BLQ"));
        verser(decor, compte, "100000", "blq-1");

        UUID saisie = lifecycle.block(new AccountLifecycle.Block(
            compte, BlockKind.DEBIT, "saisie-attribution", "ATD-2026-17", ACTOR, APPROVER));

        assertThatThrownBy(() -> retirer(decor, compte, "10000", "blq-2"))
            .isInstanceOf(AccountBlockedException.class)
            .hasMessageContaining("DEBIT");
        verser(decor, compte, "5000", "blq-3");
        assertThat(solde(compte)).isEqualTo(xof("105000"));
        // Le statut ne change pas : le blocage est un etat superpose.
        assertThat(compte(compte).status()).isEqualTo(AccountStatus.ACTIVE);

        UUID gel = lifecycle.block(new AccountLifecycle.Block(
            compte, BlockKind.TOTAL, "gel des avoirs", null, ACTOR, APPROVER));
        assertThatThrownBy(() -> verser(decor, compte, "1", "blq-4"))
            .isInstanceOf(AccountBlockedException.class)
            .hasMessageContaining("TOTAL");
        database.inTransaction(c -> {
            assertThat(AccountLifecycle.activeBlocks(c, compte))
                .extracting(AccountLifecycle.ActiveBlock::kind)
                .containsExactly(BlockKind.DEBIT, BlockKind.TOTAL);
            return null;
        });

        // Pose et levee se font a deux.
        assertThatThrownBy(() -> lifecycle.unblock(gel, "mainlevee", ACTOR, ACTOR))
            .isInstanceOf(IllegalArgumentException.class);
        lifecycle.unblock(gel, "mainlevee du gel", ACTOR, APPROVER);
        lifecycle.unblock(saisie, "mainlevee de la saisie", APPROVER, ACTOR);
        assertThatThrownBy(() -> lifecycle.unblock(saisie, "encore", ACTOR, APPROVER))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("deja");

        retirer(decor, compte, "10000", "blq-5");
        assertThat(solde(compte)).isEqualTo(xof("95000"));
        assertThat(historique(compte)).containsExactly(
            "OPENED", "BLOCKED", "BLOCKED", "UNBLOCKED", "UNBLOCKED");
    }

    @Test
    @DisplayName("la cloture regle les interets jusqu'a la veille, verse le solde et ferme tout")
    void cloture() {
        Decor decor = decor("CLO");
        produit(decor, "EP-CLO", "SAVINGS_ACCOUNT", Map.of());
        UUID titulaire = client(decor.entityId(), "T-CLO");
        UUID compte = ouvrir(decor, "CLI-CLO", "EP-CLO", titulaire);
        verser(decor, compte, "1000000", "clo-1");

        LocalDate jourDeCloture = LocalDate.of(2026, 9, 25);
        dater(decor, jourDeCloture);
        AccountLifecycle.Closure closure = lifecycle.close(new AccountLifecycle.Closing(
            compte, decor.caisse().id(), ACTOR, APPROVER));

        // Dix journees, du 15 au 24 : 1 000 000 x 6 % x 10 / 365 = 1 643,84 -> 1 644 ; IRC 15 %
        // = 246,60 -> 247 ; net 1 397. Le 25 n'est pas remunere.
        assertThat(closure.closedOn()).isEqualTo(jourDeCloture);
        assertThat(closure.interestPaid()).isEqualTo(xof("1644"));
        assertThat(closure.interestCharged().isZero()).isTrue();
        assertThat(closure.paidOut()).isEqualTo(xof("1001397"));
        assertThat(closure.payoutEntryId()).isNotNull();
        assertThat(solde(compte).isZero()).isTrue();
        assertThat(solde(decor.courus()).isZero()).isTrue();
        assertThat(solde(decor.irc())).isEqualTo(xof("247"));
        assertThat(solde(decor.charges())).isEqualTo(xof("1644"));
        assertThat(solde(decor.caisse())).isEqualTo(xof("-1397"));

        var account = compte(compte);
        assertThat(account.status()).isEqualTo(AccountStatus.CLOSED);
        database.inTransaction(c -> {
            var position = InterestPositions.load(c, compte, AccrualSide.CREDITOR, Currencies.XOF);
            assertThat(position.settledThrough()).isEqualTo(jourDeCloture.minusDays(1));
            assertThat(position.unsettled().isZero()).isTrue();
            assertThat(AccountHolders.holdersOf(c, compte, jourDeCloture.plusDays(1))).isEmpty();
            assertThat(AccountHolders.holdersOf(c, compte, jourDeCloture)).hasSize(1);
            assertThat(closedAt(c, compte)).isEqualTo(jourDeCloture);
            assertThat(productValidTo(c, compte)).isEqualTo(jourDeCloture);
            assertThat(Reconciliation.allBlockingChecks(c, decor.entityId())).isEmpty();
            return null;
        });
        assertThat(historique(compte)).containsExactly("OPENED", "CLOSED");

        // Un compte clos n'accepte plus rien, et ne se clot pas deux fois.
        assertThatThrownBy(() -> verser(decor, compte, "1", "clo-2"))
            .isInstanceOf(OperationsService.AccountNotOperableException.class)
            .hasMessageContaining("CLOSED");
        assertThatThrownBy(() -> lifecycle.close(new AccountLifecycle.Closing(
                compte, decor.caisse().id(), ACTOR, APPROVER)))
            .isInstanceOf(OperationsService.AccountNotOperableException.class);
    }

    @Test
    @DisplayName("un compte a zero se clot sans compte de reversement ; un solde a verser en exige un")
    void clotureSansSolde() {
        Decor decor = decor("CLZ");
        produit(decor, "EP-CLZ", "SAVINGS_ACCOUNT", Map.of());
        UUID titulaire = client(decor.entityId(), "T-CLZ");
        UUID vide = ouvrir(decor, "CLI-CLZ-1", "EP-CLZ", titulaire);
        UUID garni = ouvrir(decor, "CLI-CLZ-2", "EP-CLZ", titulaire);
        verser(decor, garni, "5000", "clz-1");

        AccountLifecycle.Closure closure = lifecycle.close(new AccountLifecycle.Closing(
            vide, null, ACTOR, APPROVER));
        assertThat(closure.paidOut().isZero()).isTrue();
        assertThat(closure.payoutEntryId()).isNull();
        assertThat(compte(vide).status()).isEqualTo(AccountStatus.CLOSED);

        assertThatThrownBy(() -> lifecycle.close(new AccountLifecycle.Closing(
                garni, null, ACTOR, APPROVER)))
            .isInstanceOf(AccountLifecycle.ClosureRefusedException.class)
            .hasMessageContaining("aucun compte de reversement");
        assertThat(compte(garni).status()).isEqualTo(AccountStatus.ACTIVE);

        // Le compte d'attente des comptes clos convient autant que la caisse.
        lifecycle.close(new AccountLifecycle.Closing(garni, decor.attente().id(), ACTOR, APPROVER));
        assertThat(solde(decor.attente())).isEqualTo(xof("5000"));
    }

    @Test
    @DisplayName("tout ce qui s'oppose a la cloture est nomme d'un coup, et rien n'est comptabilise")
    void clotureRefusee() {
        Decor decor = decor("CLR");
        produit(decor, "EP-CLR", "SAVINGS_ACCOUNT", Map.of());
        UUID compte = ouvrir(decor, "CLI-CLR", "EP-CLR", client(decor.entityId(), "T-CLR"));
        verser(decor, compte, "1000000", "clr-1");
        database.inTransaction(c -> Holds.place(c, new Holds.Placement(
            compte, xof("100000"), "CAUTION", "LOYER", J, null, ACTOR)));
        lifecycle.block(new AccountLifecycle.Block(
            compte, BlockKind.DEBIT, "opposition", null, ACTOR, APPROVER));
        dater(decor, LocalDate.of(2026, 9, 20));

        assertThatThrownBy(() -> lifecycle.close(new AccountLifecycle.Closing(
                compte, decor.caisse().id(), ACTOR, APPROVER)))
            .isInstanceOf(AccountLifecycle.ClosureRefusedException.class)
            .hasMessageContaining("blocage DEBIT")
            .hasMessageContaining("blocage(s) de montant en cours pour 100000");

        assertThat(compte(compte).status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(solde(compte)).isEqualTo(xof("1000000"));
        assertThat(solde(decor.courus()).isZero()).isTrue();
        assertThat(historique(compte)).containsExactly("OPENED", "BLOCKED");
    }

    @Test
    @DisplayName("un solde debiteur refuse la cloture, et les agios calcules pour elle sont defaits avec elle")
    void clotureSurSoldeDebiteur() {
        Decor decor = decor("CLD");
        produit(decor, "CC-CLD", "CURRENT_ACCOUNT", decouvert(decor));
        UUID compte = ouvrir(decor, "CLI-CLD", "CC-CLD", client(decor.entityId(), "T-CLD"));
        verser(decor, compte, "100000", "cld-1");
        autoriser(compte, "300000", J);
        retirer(decor, compte, "250000", "cld-2");
        assertThat(solde(compte)).isEqualTo(xof("-150000"));
        dater(decor, LocalDate.of(2026, 9, 20));

        assertThatThrownBy(() -> lifecycle.close(new AccountLifecycle.Closing(
                compte, decor.caisse().id(), ACTOR, APPROVER)))
            .isInstanceOf(AccountLifecycle.ClosureRefusedException.class)
            .hasMessageContaining("solde debiteur");

        // Tout ou rien : les agios calcules jusqu'a la veille pour la cloture n'existent plus.
        assertThat(compte(compte).status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(solde(decor.agiosCourus()).isZero()).isTrue();
        assertThat(solde(decor.produitsAgios()).isZero()).isTrue();
        database.inTransaction(c -> {
            var position = InterestPositions.load(c, compte, AccrualSide.DEBTOR, Currencies.XOF);
            assertThat(position.accruedThrough()).isNull();
            assertThat(position.postedTotal().isZero()).isTrue();
            return null;
        });

        // Le client regularise : la cloture arrete les agios des cinq journees debitrices.
        verser(decor, compte, "200000", "cld-3");
        AccountLifecycle.Closure closure = lifecycle.close(new AccountLifecycle.Closing(
            compte, decor.caisse().id(), ACTOR, APPROVER));
        // 150 000 x 12 % x 5 / 365 = 246,58 -> 247 ; taxe 10 % = 25 ; le client paie 272.
        assertThat(closure.interestCharged()).isEqualTo(xof("247"));
        assertThat(closure.interestPaid().isZero()).isTrue();
        assertThat(closure.paidOut()).isEqualTo(xof("49728"));
        assertThat(solde(decor.produitsAgios())).isEqualTo(xof("247"));
        assertThat(solde(decor.taf())).isEqualTo(xof("25"));
        assertThat(compte(compte).status()).isEqualTo(AccountStatus.CLOSED);
        database.inTransaction(c -> {
            assertThat(Reconciliation.allBlockingChecks(c, decor.entityId())).isEmpty();
            return null;
        });
    }

    // ------------------------------------------------------------------ outillage

    private static void autoriser(UUID accountId, String montant, LocalDate depuis) {
        database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "INSERT INTO overdraft_limit(account_id, amount, valid_from) VALUES (?,?,?)")) {
                ps.setObject(1, accountId);
                ps.setBigDecimal(2, new BigDecimal(montant));
                ps.setObject(3, depuis);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new io.corebanking.ledger.store.LedgerStoreException("Autorisation", e);
            }
            return null;
        });
    }

    private static LocalDate closedAt(java.sql.Connection c, UUID accountId) {
        try (var ps = c.prepareStatement("SELECT closed_at FROM account WHERE id = ?")) {
            ps.setObject(1, accountId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, LocalDate.class);
            }
        } catch (SQLException e) {
            throw new io.corebanking.ledger.store.LedgerStoreException("closed_at", e);
        }
    }

    private static LocalDate productValidTo(java.sql.Connection c, UUID accountId) {
        try (var ps = c.prepareStatement(
            "SELECT valid_to FROM account_product WHERE account_id = ? ORDER BY valid_from DESC"
            + " LIMIT 1")) {
            ps.setObject(1, accountId);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, LocalDate.class);
            }
        } catch (SQLException e) {
            throw new io.corebanking.ledger.store.LedgerStoreException("valid_to", e);
        }
    }
}
