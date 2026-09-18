package io.corebanking.deposits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.time.Periodicity;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Les ordres permanents : le virement que le client programme une fois, ce qu'il devient quand la
 * provision manque, et ce qu'il devient quand il n'y a rien a balayer.
 */
class StandingOrdersIT extends DepositsTestBase {

    private StandingOrderService service() {
        return new StandingOrderService(database, postingService);
    }

    @Test
    @DisplayName("un ordre a montant fixe part a l'echeance, consomme les plafonds du client, et les echeances se calculent depuis la date de debut")
    void a_fixed_order_leaves_on_its_due_date() {
        Decor decor = decor("SO1");
        produit(decor, "CC-SO1", "CURRENT_ACCOUNT", frais(decor));
        UUID client = client(decor.entityId(), "CLI-SO1");
        UUID payeur = ouvrir(decor, "SO1-PAYEUR", "CC-SO1", client);
        UUID beneficiaire = ouvrir(decor, "SO1-BENEF", "CC-SO1", client);
        verser(decor, payeur, "500000", "so1-prov");

        StandingOrderService.StandingOrder ordre = service().register(
            new StandingOrderService.Draft(decor.entityId(), payeur, "SO-001",
                StandingOrderService.Kind.FIXED, xof("50000"), null, beneficiaire, null, null, null,
                Periodicity.MONTHLY, J, null, 3, null, "loyer", ACTOR, APPROVER));
        assertThat(ordre.dueDate()).isEqualTo(J);
        assertThat(ordre.status()).isEqualTo("ACTIVE");

        // Une mise en place se decide a deux : elle engage des virements que personne ne
        // redemandera.
        assertThatThrownBy(() -> new StandingOrderService.Draft(decor.entityId(), payeur, "SO-002",
                StandingOrderService.Kind.FIXED, xof("50000"), null, beneficiaire, null, null, null,
                Periodicity.MONTHLY, J, null, null, null, null, ACTOR, ACTOR))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a deux");

        UUID run = UUID.randomUUID();
        List<UUID> echus = database.inTransaction(
            c -> StandingOrderService.due(c, decor.entityId(), J));
        assertThat(echus).containsExactly(ordre.id());

        StandingOrderService.Execution premiere = service().execute(ordre.id(), run, ACTOR);
        assertThat(premiere.outcome()).isEqualTo(StandingOrderService.Outcome.EXECUTED);
        assertThat(premiere.amount()).isEqualTo(xof("50000"));
        assertThat(premiere.fee()).as("le virement porte les frais du produit").isEqualTo(xof("200"));
        assertThat(solde(beneficiaire)).isEqualTo(xof("50000"));
        assertThat(solde(payeur)).isEqualTo(xof("500000").minus(xof("50236")));

        // L'echeance suivante se calcule depuis la date de debut, pas de proche en proche.
        StandingOrderService.StandingOrder apres = database.inTransaction(
            c -> StandingOrderService.require(c, ordre.id()));
        assertThat(apres.dueDate()).isEqualTo(J.plusMonths(1));
        assertThat(apres.occurrence()).isEqualTo(1);
        assertThat(apres.attempts()).isZero();

        // Ce qui est echu aujourd'hui l'est une fois : l'ordre ne repart pas le meme jour.
        List<UUID> encore = database.inTransaction(
            c -> StandingOrderService.due(c, decor.entityId(), J));
        assertThat(encore).isEmpty();
    }

    @Test
    @DisplayName("sans provision, l'echeance se retente un nombre borne de fois puis elle est abandonnee : la suivante reste due a sa date")
    void a_failed_instalment_is_retried_then_abandoned() {
        Decor decor = decor("SO2");
        produit(decor, "CC-SO2", "CURRENT_ACCOUNT", frais(decor));
        UUID client = client(decor.entityId(), "CLI-SO2");
        UUID payeur = ouvrir(decor, "SO2-PAYEUR", "CC-SO2", client);
        UUID beneficiaire = ouvrir(decor, "SO2-BENEF", "CC-SO2", client);
        verser(decor, payeur, "1000", "so2-prov");

        StandingOrderService.StandingOrder ordre = service().register(
            new StandingOrderService.Draft(decor.entityId(), payeur, "SO-010",
                StandingOrderService.Kind.FIXED, xof("50000"), null, beneficiaire, null, null, null,
                Periodicity.MONTHLY, J, null, null, 2, "loyer", ACTOR, APPROVER));

        StandingOrderService.Execution premiere = service().execute(ordre.id(), UUID.randomUUID(),
                                                                     ACTOR);
        assertThat(premiere.outcome()).isEqualTo(StandingOrderService.Outcome.REJECTED);
        assertThat(premiere.reason()).isEqualTo("SANS_PROVISION");
        StandingOrderService.StandingOrder apresUn = database.inTransaction(
            c -> StandingOrderService.require(c, ordre.id()));
        assertThat(apresUn.attempts()).isEqualTo(1);
        assertThat(apresUn.dueDate()).as("l'echeance ne bouge pas").isEqualTo(J);
        assertThat(apresUn.nextAttemptDate()).as("la tentative suivante est un jour ouvre plus tard")
            .isAfter(J);

        // Deuxieme tentative, le jour ouvre suivant : les tentatives sont epuisees, l'echeance
        // est abandonnee.
        StandingOrderService.Execution seconde = executeAt(ordre.id(), apresUn.nextAttemptDate());
        assertThat(seconde.outcome()).isEqualTo(StandingOrderService.Outcome.REJECTED);
        assertThat(seconde.attempt()).isEqualTo(2);
        StandingOrderService.StandingOrder apresDeux = database.inTransaction(
            c -> StandingOrderService.require(c, ordre.id()));
        assertThat(apresDeux.dueDate()).as("la suivante reste due a sa date, pas deux loyers le "
            + "meme mois").isEqualTo(J.plusMonths(1));
        assertThat(apresDeux.attempts()).isZero();
        assertThat(solde(beneficiaire)).isEqualTo(xof("0"));

        List<StandingOrderService.Execution> historique = database.inTransaction(
            c -> StandingOrderService.executions(c, ordre.id()));
        assertThat(historique).hasSize(2)
            .allMatch(e -> e.outcome() == StandingOrderService.Outcome.REJECTED);
    }

    @Test
    @DisplayName("un balayage vire ce qui depasse le plancher, et ne consomme pas de tentative quand il n'y a rien a balayer")
    void a_sweep_moves_what_is_above_the_floor() {
        Decor decor = decor("SO3");
        produit(decor, "CC-SO3", "CURRENT_ACCOUNT", frais(decor));
        UUID client = client(decor.entityId(), "CLI-SO3");
        UUID courant = ouvrir(decor, "SO3-COURANT", "CC-SO3", client);
        UUID epargne = ouvrir(decor, "SO3-EPARGNE", "CC-SO3", client);
        verser(decor, courant, "300000", "so3-prov");

        StandingOrderService.StandingOrder ordre = service().register(
            new StandingOrderService.Draft(decor.entityId(), courant, "SO-020",
                StandingOrderService.Kind.SWEEP, null, xof("200000"), epargne, null, null, null,
                Periodicity.MONTHLY, J, null, null, null, "epargne automatique", ACTOR, APPROVER));

        StandingOrderService.Execution premiere = service().execute(ordre.id(), UUID.randomUUID(),
                                                                     ACTOR);
        assertThat(premiere.outcome()).isEqualTo(StandingOrderService.Outcome.EXECUTED);
        // Ce qui depasse le plancher, net des frais du virement (200) et de leur taxe (36) : le
        // plancher est ce que le client a demande a garder, et le balayage ne le creuse pas.
        assertThat(premiere.amount()).as("ce qui depasse le plancher").isEqualTo(xof("99764"));
        assertThat(solde(epargne)).isEqualTo(xof("99764"));
        assertThat(solde(courant)).as("le plancher reste, aux frais pres").isEqualTo(xof("200000"));

        // Le mois suivant, le compte est au plancher : rien a balayer, et ce n'est pas un echec.
        StandingOrderService.Execution seconde = service().execute(ordre.id(), UUID.randomUUID(),
                                                                     ACTOR);
        assertThat(seconde).isNull();
        StandingOrderService.StandingOrder apres = database.inTransaction(
            c -> StandingOrderService.require(c, ordre.id()));
        assertThat(apres.dueDate()).isEqualTo(J.plusMonths(1));

        // A l'echeance suivante, toujours rien : l'echeance passe sans tentative consommee.
        StandingOrderService.Execution sansObjet = executeAt(ordre.id(), J.plusMonths(1));
        assertThat(sansObjet.outcome()).isEqualTo(StandingOrderService.Outcome.SKIPPED);
        assertThat(sansObjet.reason()).isEqualTo("RIEN_A_BALAYER");
        StandingOrderService.StandingOrder ensuite = database.inTransaction(
            c -> StandingOrderService.require(c, ordre.id()));
        assertThat(ensuite.attempts()).isZero();
        // Le 15 novembre 2026 est un dimanche : l'echeance se traite le lundi.
        assertThat(ensuite.dueDate()).isEqualTo(J.plusMonths(2).plusDays(1));

        // Un balayage ne porte pas de montant, et un ordre a montant fixe en porte un.
        assertThatThrownBy(() -> new StandingOrderService.Draft(decor.entityId(), courant, "SO-021",
                StandingOrderService.Kind.SWEEP, xof("1000"), xof("0"), epargne, null, null, null,
                Periodicity.MONTHLY, J, null, null, null, null, ACTOR, APPROVER))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ne porte pas de montant");
    }

    @Test
    @DisplayName("un ordre vers une autre banque depose un ordre de paiement : il ne comptabilise pas lui-meme")
    void an_external_order_places_a_payment_order() {
        Decor decor = decor("SO4");
        // Le compte de reglement des paiements sortants est un compte general du siege.
        io.corebanking.ledger.domain.account.Account reglement = account(
            decor.entityId(), "SO4-REGLEMENT",
            io.corebanking.ledger.domain.account.AccountKind.GL,
            io.corebanking.ledger.domain.account.NormalBalance.CREDIT);
        produit(decor, "CC-SO4", "CURRENT_ACCOUNT", Map.of(
            DepositCatalog.P_TRANSFER_FEE, "200",
            DepositCatalog.P_PAYMENT_FEE, "1000",
            DepositCatalog.P_FEE_INCOME, decor.produitsFrais().id().toString(),
            DepositCatalog.P_TAX_RATE, "18",
            DepositCatalog.P_TAX_ACCOUNT, decor.taxe().id().toString(),
            DepositCatalog.P_PAYMENT_CLEARING, reglement.id().toString()));
        UUID client = client(decor.entityId(), "CLI-SO4");
        UUID payeur = ouvrir(decor, "SO4-PAYEUR", "CC-SO4", client);
        verser(decor, payeur, "500000", "so4-prov");

        StandingOrderService.StandingOrder ordre = service().register(
            new StandingOrderService.Draft(decor.entityId(), payeur, "SO-030",
                StandingOrderService.Kind.FIXED, xof("75000"), null, null, "Bailleur SARL",
                "BANQUE ATLANTIQUE", "CI0012345", Periodicity.MONTHLY, J, null, null, null,
                "loyer bureau", ACTOR, APPROVER));

        StandingOrderService.Execution execution = service().execute(ordre.id(), UUID.randomUUID(),
                                                                       ACTOR);
        assertThat(execution.outcome()).isEqualTo(StandingOrderService.Outcome.EXECUTED);
        assertThat(execution.paymentOrderId()).as("un ordre de paiement, pas une ecriture a part")
            .isNotNull();
        PaymentService.PaymentOrder paiement = database.inTransaction(
            c -> PaymentService.require(c, execution.paymentOrderId()));
        assertThat(paiement.status()).isEqualTo("ORDERED");
        assertThat(paiement.amount()).isEqualTo(xof("75000"));
        assertThat(paiement.beneficiaryName()).isEqualTo("Bailleur SARL");
        assertThat(solde(reglement)).as("les fonds sont au reglement, pas encore sortis")
            .isEqualTo(xof("75000"));

        // Un beneficiaire, et un seul.
        assertThatThrownBy(() -> new StandingOrderService.Draft(decor.entityId(), payeur, "SO-031",
                StandingOrderService.Kind.FIXED, xof("1000"), null, payeur, "Bailleur", "BANQUE",
                "CI001", Periodicity.MONTHLY, J, null, null, null, null, ACTOR, APPROVER))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("un beneficiaire, et un seul");
    }

    @Test
    @DisplayName("l'ordre s'eteint au nombre d'echeances prevu, et sa revocation arrete ce qui n'est pas parti")
    void an_order_completes_or_is_revoked() {
        Decor decor = decor("SO5");
        produit(decor, "CC-SO5", "CURRENT_ACCOUNT", frais(decor));
        UUID client = client(decor.entityId(), "CLI-SO5");
        UUID payeur = ouvrir(decor, "SO5-PAYEUR", "CC-SO5", client);
        UUID beneficiaire = ouvrir(decor, "SO5-BENEF", "CC-SO5", client);
        verser(decor, payeur, "500000", "so5-prov");

        StandingOrderService.StandingOrder deuxFois = service().register(
            new StandingOrderService.Draft(decor.entityId(), payeur, "SO-040",
                StandingOrderService.Kind.FIXED, xof("10000"), null, beneficiaire, null, null, null,
                Periodicity.MONTHLY, J, null, 2, null, "deux mensualites", ACTOR, APPROVER));
        service().execute(deuxFois.id(), UUID.randomUUID(), ACTOR);
        executeAt(deuxFois.id(), J.plusMonths(1));
        StandingOrderService.StandingOrder eteint = database.inTransaction(
            c -> StandingOrderService.require(c, deuxFois.id()));
        assertThat(eteint.status()).isEqualTo("COMPLETED");
        assertThat(solde(beneficiaire)).isEqualTo(xof("20000"));

        // Un ordre eteint ne part plus, et ne se revoque plus.
        List<UUID> apresExtinction = database.inTransaction(
            c -> StandingOrderService.due(c, decor.entityId(), J.plusMonths(2)));
        assertThat(apresExtinction).isEmpty();
        assertThatThrownBy(() -> service().cancel(deuxFois.id(), J, "trop tard", ACTOR))
            .isInstanceOf(StandingOrderService.StandingOrderRefusedException.class)
            .hasMessageContaining("COMPLETED");

        // La revocation d'un ordre en cours arrete ce qui n'est pas parti.
        StandingOrderService.StandingOrder permanent = service().register(
            new StandingOrderService.Draft(decor.entityId(), payeur, "SO-041",
                StandingOrderService.Kind.FIXED, xof("10000"), null, beneficiaire, null, null, null,
                Periodicity.MONTHLY, J, null, null, null, "sans fin", ACTOR, APPROVER));
        assertThatThrownBy(() -> service().cancel(permanent.id(), J, "  ", ACTOR))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("motif");
        service().cancel(permanent.id(), J, "le client a demenage", ACTOR);
        assertThat(database.inTransaction(
            c -> StandingOrderService.require(c, permanent.id())).status()).isEqualTo("CANCELLED");
        List<UUID> apresRevocation = database.inTransaction(
            c -> StandingOrderService.due(c, decor.entityId(), J));
        assertThat(apresRevocation).isEmpty();
    }

    @Test
    @DisplayName("un beneficiaire clos fait un rejet nomme, pas une anomalie ; et un compte porteur d'un ordre actif ne se clot pas")
    void a_closed_beneficiary_is_a_named_rejection() {
        Decor decor = decor("SO6");
        produit(decor, "CC-SO6", "CURRENT_ACCOUNT", frais(decor));
        UUID client = client(decor.entityId(), "CLI-SO6");
        UUID payeur = ouvrir(decor, "SO6-PAYEUR", "CC-SO6", client);
        UUID beneficiaire = ouvrir(decor, "SO6-BENEF", "CC-SO6", client);
        verser(decor, payeur, "500000", "so6-prov");

        StandingOrderService.StandingOrder ordre = service().register(
            new StandingOrderService.Draft(decor.entityId(), payeur, "SO-060",
                StandingOrderService.Kind.FIXED, xof("10000"), null, beneficiaire, null, null, null,
                Periodicity.MONTHLY, J, null, null, 1, "pension", ACTOR, APPROVER));

        // Un compte que vise un ordre permanent actif ne se clot pas : au client de le revoquer.
        assertThatThrownBy(() -> lifecycle.close(new AccountLifecycle.Closing(
                beneficiaire, decor.caisse().id(), ACTOR, APPROVER)))
            .isInstanceOf(AccountLifecycle.ClosureRefusedException.class)
            .hasMessageContaining("SO-060");
        assertThatThrownBy(() -> lifecycle.close(new AccountLifecycle.Closing(
                payeur, decor.caisse().id(), ACTOR, APPROVER)))
            .isInstanceOf(AccountLifecycle.ClosureRefusedException.class)
            .hasMessageContaining("ordre permanent");

        // Clos par une autre voie, le beneficiaire fait un rejet nomme : la situation d'un
        // client n'arrete pas la journee de la banque.
        database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "UPDATE account SET status = 'CLOSED', closed_at = ? WHERE id = ?")) {
                ps.setObject(1, J);
                ps.setObject(2, beneficiaire);
                ps.executeUpdate();
            } catch (java.sql.SQLException e) {
                throw new io.corebanking.ledger.store.LedgerStoreException("Cloture forcee", e);
            }
            return null;
        });
        StandingOrderService.Execution rejet = service().execute(ordre.id(), UUID.randomUUID(),
                                                                  ACTOR);
        assertThat(rejet.outcome()).isEqualTo(StandingOrderService.Outcome.REJECTED);
        assertThat(rejet.reason()).isEqualTo("BENEFICIAIRE_INOPERABLE");
        assertThat(solde(payeur)).as("rien n'a bouge").isEqualTo(xof("500000"));
    }

    @Test
    @DisplayName("l'annulation d'un arrete ne ressuscite pas un ordre que le client a revoque depuis")
    void cancelling_a_run_does_not_revive_a_revoked_order() {
        Decor decor = decor("SO7");
        produit(decor, "CC-SO7", "CURRENT_ACCOUNT", frais(decor));
        UUID client = client(decor.entityId(), "CLI-SO7");
        UUID payeur = ouvrir(decor, "SO7-PAYEUR", "CC-SO7", client);
        UUID beneficiaire = ouvrir(decor, "SO7-BENEF", "CC-SO7", client);
        verser(decor, payeur, "500000", "so7-prov");

        StandingOrderService.StandingOrder ordre = service().register(
            new StandingOrderService.Draft(decor.entityId(), payeur, "SO-070",
                StandingOrderService.Kind.FIXED, xof("10000"), null, beneficiaire, null, null, null,
                Periodicity.MONTHLY, J, null, null, null, "loyer", ACTOR, APPROVER));
        UUID run = UUID.randomUUID();
        service().execute(ordre.id(), run, ACTOR);
        // Le lendemain matin, le client revoque ; le soir, l'arrete de la veille est annule.
        service().cancel(ordre.id(), J.plusDays(1), "le client a demenage", ACTOR);
        int rendus = database.inTransaction(
            c -> StandingOrderService.cancelRun(c, run, J.plusDays(1)));
        assertThat(rendus).isEqualTo(1);

        StandingOrderService.StandingOrder apres = database.inTransaction(
            c -> StandingOrderService.require(c, ordre.id()));
        assertThat(apres.status()).as("une revocation survit a l'annulation de l'arrete")
            .isEqualTo("CANCELLED");
        // L'echeance est bien rendue — l'arrete ne l'a plus payee — mais rien ne repartira.
        assertThat(apres.occurrence()).isZero();
        assertThat(apres.dueDate()).isEqualTo(J);
        List<UUID> plusRienNePart = database.inTransaction(
            c -> StandingOrderService.due(c, decor.entityId(), J));
        assertThat(plusRienNePart).isEmpty();
    }

    /**
     * Execute une echeance a une date donnee : le service lit la date comptable de l'entite, et
     * l'arrete l'avance chaque nuit.
     */
    private StandingOrderService.Execution executeAt(UUID orderId, LocalDate on) {
        UUID entityId = database.inTransaction(
            c -> StandingOrderService.require(c, orderId).legalEntityId());
        database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "UPDATE legal_entity SET current_business_date = ? WHERE id = ?")) {
                ps.setObject(1, on);
                ps.setObject(2, entityId);
                ps.executeUpdate();
            } catch (java.sql.SQLException e) {
                throw new io.corebanking.ledger.store.LedgerStoreException("Date comptable", e);
            }
            return null;
        });
        return service().execute(orderId, UUID.randomUUID(), ACTOR);
    }
}
