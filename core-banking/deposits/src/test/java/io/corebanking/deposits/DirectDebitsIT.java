package io.corebanking.deposits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.store.Branches;
import io.corebanking.ledger.store.Reconciliation;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Les prelevements : mandats enregistres a deux et revoques, prelevements recus executes a
 * l'echeance ou rejetes avec leur motif, regles, rappeles ou rembourses ; prelevements emis
 * credites sauf bonne fin, regles ou retournes.
 */
class DirectDebitsIT extends DepositsTestBase {

    private static Account encaissement(Decor decor, String code) {
        return account(decor.entityId(), code + "-PREL-ENCAISSEMENT", AccountKind.GL,
                       NormalBalance.DEBIT);
    }

    private static Account reglement(Decor decor, String code) {
        return account(decor.entityId(), code + "-REGLEMENT-SORTANT", AccountKind.GL,
                       NormalBalance.CREDIT);
    }

    private static Map<String, String> parametres(Decor decor, Account reglement,
                                                  Account encaissement, String frais) {
        Map<String, String> parametres = new HashMap<>(frais(decor));
        parametres.put(DepositCatalog.P_PAYMENT_CLEARING, reglement.id().toString());
        parametres.put(DepositCatalog.P_DIRECT_DEBIT_COLLECTION, encaissement.id().toString());
        if (frais != null) {
            parametres.put(DepositCatalog.P_DIRECT_DEBIT_FEE, frais);
        }
        return parametres;
    }

    private static DirectDebitService.MandateDraft mandatExterne(Decor decor, UUID compte,
                                                                 String reference) {
        return new DirectDebitService.MandateDraft(decor.entityId(), compte, reference, "CI-SDD-001",
            "Compagnie des eaux", null, "BK-CI-002", "CI93CI0020009876543210987654", J.minusMonths(1),
            J.minusMonths(1), null, null, ACTOR, APPROVER);
    }

    private static DirectDebitService.MandateDraft mandatInterne(Decor decor, UUID compte,
                                                                 UUID creancier, String reference,
                                                                 String plafond) {
        return new DirectDebitService.MandateDraft(decor.entityId(), compte, reference, "CI-ASS-007",
            "Assurances SA", creancier, null, null, J.minusMonths(1), J.minusMonths(1),
            J.plusYears(1), plafond == null ? null : xof(plafond), ACTOR, APPROVER);
    }

    private static DirectDebitService.Presentation presentation(Decor decor, UUID mandat,
                                                                String montant, LocalDate echeance,
                                                                String key) {
        return new DirectDebitService.Presentation(IdempotencyKey.of(key), decor.entityId(), mandat,
                                                   xof(montant), echeance, "FACT-" + key, null,
                                                   ACTOR);
    }

    private static DirectDebitService.Issue remise(Decor decor, UUID compte, String montant,
                                                   LocalDate echeance, String key) {
        return new DirectDebitService.Issue(IdempotencyKey.of(key), decor.entityId(), compte,
                                            xof(montant), echeance, "Abonne Dupont", "BK-CI-003",
                                            "CI93CI0030001111222233334444", "RUM-" + key,
                                            "ECH-" + key, null, ACTOR);
    }

    private static DirectDebitService.DirectDebit lire(UUID id) {
        return database.inTransaction(c -> DirectDebitService.require(c, id));
    }

    @Test
    @DisplayName("un mandat s'enregistre a deux sur un compte client, designe un creancier de la banque ou d'ailleurs, se revoque par le client ; sans mandat en vigueur rien ne se presente")
    void mandates() {
        Decor decor = decor("MAN");
        produit(decor, "CC-MAN", "CURRENT_ACCOUNT",
                parametres(decor, reglement(decor, "MAN"), encaissement(decor, "MAN"), null));
        UUID compte = ouvrir(decor, "CLI-MAN", "CC-MAN", client(decor.entityId(), "T-MAN"));
        UUID creancier = ouvrir(decor, "CLI-MAN-CR", "CC-MAN", client(decor.entityId(), "T-MAN2"));
        verser(decor, compte, "100000", "man-0");

        DirectDebitService.Mandate externe = directDebits.registerMandate(
            mandatExterne(decor, compte, "RUM-1"));
        assertThat(externe.status()).isEqualTo("ACTIVE");
        assertThat(externe.internal()).isFalse();
        assertThat(externe.creditorBank()).isEqualTo("BK-CI-002");
        DirectDebitService.Mandate interne = directDebits.registerMandate(
            mandatInterne(decor, compte, creancier, "RUM-2", "50000"));
        assertThat(interne.internal()).isTrue();
        assertThat(interne.maxAmount()).isEqualTo(xof("50000"));
        List<DirectDebitService.Mandate> mandats = database.inTransaction(
            c -> DirectDebitService.mandates(c, compte));
        assertThat(mandats).extracting(DirectDebitService.Mandate::id)
            .containsExactlyInAnyOrder(externe.id(), interne.id());

        // Une reference unique par creancier ; a deux ; un creancier, pas les deux ; sur un
        // compte client de l'entite ; jamais soi-meme.
        assertThatThrownBy(() -> directDebits.registerMandate(mandatExterne(decor, compte, "RUM-1")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("existe deja");
        assertThatThrownBy(() -> new DirectDebitService.MandateDraft(decor.entityId(), compte, "RUM-3",
                "X", "Y", null, "BK", "ACC", J, J, null, null, ACTOR, ACTOR))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a deux");
        assertThatThrownBy(() -> new DirectDebitService.MandateDraft(decor.entityId(), compte, "RUM-3",
                "X", "Y", creancier, "BK", "ACC", J, J, null, null, ACTOR, APPROVER))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("l'un ou l'autre");
        assertThatThrownBy(() -> directDebits.registerMandate(
                mandatExterne(decor, decor.caisse().id(), "RUM-4")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("compte client");
        assertThatThrownBy(() -> directDebits.registerMandate(
                mandatInterne(decor, compte, compte, "RUM-5", null)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lui-meme");

        // Presente hors validite ou au-dela du plafond du mandat, le prelevement est refuse
        // avant d'exister ; revoque, le mandat ne porte plus rien, et le reste.
        assertThatThrownBy(() -> directDebits.present(presentation(decor, interne.id(), "60000", J, "man-1")))
            .isInstanceOf(DirectDebitService.MandateStateException.class)
            .hasMessageContaining("plafond du mandat");
        assertThatThrownBy(() -> directDebits.present(presentation(decor, interne.id(), "1000",
                                                                    J.plusYears(2), "man-2")))
            .isInstanceOf(DirectDebitService.MandateStateException.class)
            .hasMessageContaining("hors de sa validite");
        assertThatThrownBy(() -> directDebits.revokeMandate(decor.entityId(), interne.id(), " ", ACTOR))
            .isInstanceOf(IllegalArgumentException.class);
        DirectDebitService.Mandate revoque = directDebits.revokeMandate(decor.entityId(), interne.id(),
                                                                        "resiliation", ACTOR);
        assertThat(revoque.status()).isEqualTo("REVOKED");
        assertThat(revoque.revokedOn()).isEqualTo(J);
        assertThat(directDebits.revokeMandate(decor.entityId(), interne.id(), "encore", ACTOR)
                       .revocationReason()).isEqualTo("resiliation");
        assertThatThrownBy(() -> directDebits.present(presentation(decor, interne.id(), "1000", J, "man-3")))
            .isInstanceOf(DirectDebitService.MandateStateException.class)
            .hasMessageContaining("revoque");
        Decor autre = decor("MAN2");
        assertThatThrownBy(() -> directDebits.revokeMandate(autre.entityId(), externe.id(), "x", ACTOR))
            .isInstanceOf(DirectDebitService.UnknownMandateException.class);
        assertThatThrownBy(() -> directDebits.present(presentation(autre, externe.id(), "1000", J, "man-4")))
            .isInstanceOf(DirectDebitService.UnknownMandateException.class);
        long total = database.inTransaction(
            c -> DirectDebitService.count(c, decor.entityId(), null, null));
        assertThat(total).as("rien n'a ete presente").isZero();
    }

    @Test
    @DisplayName("un prelevement recu a l'echeance debite le debiteur, frais compris, vers le reglement ou vers le creancier de la banque ; rejoue, le meme ; sans provision il est rejete et le motif reste ; regle puis rembourse ; rappele avant reglement")
    void received() {
        Decor decor = decor("PRR");
        Account reglement = reglement(decor, "PRR");
        Account nostro = account(decor.entityId(), "PRR-NOSTRO", AccountKind.NOSTRO,
                                 NormalBalance.DEBIT);
        produit(decor, "CC-PRR", "CURRENT_ACCOUNT",
                parametres(decor, reglement, encaissement(decor, "PRR"), "500"));
        produit(decor, "EP-PRR", "SAVINGS_ACCOUNT", frais(decor));
        UUID compte = ouvrir(decor, "CLI-PRR", "CC-PRR", client(decor.entityId(), "T-PRR"));
        UUID creancier = ouvrir(decor, "CLI-PRR-CR", "CC-PRR", client(decor.entityId(), "T-PRR2"));
        UUID epargne = ouvrir(decor, "CLI-PRR-EP", "EP-PRR", client(decor.entityId(), "T-PRR3"));
        verser(decor, compte, "100000", "prr-0");
        UUID externe = directDebits.registerMandate(mandatExterne(decor, compte, "RUM-E")).id();
        UUID interne = directDebits.registerMandate(
            mandatInterne(decor, compte, creancier, "RUM-I", null)).id();

        // A l'echeance : debite tout de suite, montant 30 000, frais 500, taxe 90 ; le montant
        // attend le correspondant sur le compte de reglement.
        DirectDebitService.Presented presente = directDebits.present(
            presentation(decor, externe, "30000", J, "prr-1"));
        assertThat(presente.replayed()).isFalse();
        DirectDebitService.DirectDebit recu = presente.directDebit();
        assertThat(recu.direction()).isEqualTo(DirectDebitService.DirectionKind.RECEIVED);
        assertThat(recu.status()).isEqualTo("COLLECTED");
        assertThat(recu.executedOn()).isEqualTo(J);
        assertThat(recu.valueDate()).isEqualTo(J);
        assertThat(recu.fee()).isEqualTo(xof("500"));
        assertThat(recu.tax()).isEqualTo(xof("90"));
        assertThat(recu.clearingAccountId()).isEqualTo(reglement.id());
        assertThat(recu.counterpartyName()).isEqualTo("Compagnie des eaux");
        assertThat(solde(compte)).isEqualTo(xof("69410"));
        assertThat(solde(reglement)).isEqualTo(xof("30000"));
        assertThat(directDebits.present(presentation(decor, externe, "30000", J, "prr-1")).replayed())
            .isTrue();
        assertThat(solde(compte)).isEqualTo(xof("69410"));

        // Regle sur le nostro ; puis conteste : le montant revient au debiteur, les frais restent.
        assertThatThrownBy(() -> directDebits.refund(recu.id(), "conteste", ACTOR))
            .isInstanceOf(DirectDebitService.DirectDebitStateException.class);
        assertThatThrownBy(() -> directDebits.settle(recu.id(), decor.caisse().id(), ACTOR))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nostro");
        DirectDebitService.DirectDebit regle = directDebits.settle(recu.id(), nostro.id(), ACTOR);
        assertThat(regle.status()).isEqualTo("SETTLED");
        assertThat(regle.settlementAccountId()).isEqualTo(nostro.id());
        assertThat(solde(reglement).isZero()).isTrue();
        assertThat(solde(nostro)).isEqualTo(xof("-30000"));
        assertThatThrownBy(() -> directDebits.cancel(recu.id(), "trop tard", ACTOR))
            .isInstanceOf(DirectDebitService.DirectDebitStateException.class);
        DirectDebitService.DirectDebit rembourse = directDebits.refund(recu.id(), "conteste", ACTOR);
        assertThat(rembourse.status()).isEqualTo("REFUNDED");
        assertThat(rembourse.closeReason()).isEqualTo("conteste");
        assertThat(rembourse.closeEntryId()).isNotNull();
        assertThat(solde(compte)).isEqualTo(xof("99410"));
        assertThat(solde(nostro).isZero()).isTrue();

        // Vers un creancier de la banque : regle a l'execution, le creancier est credite.
        DirectDebitService.DirectDebit interneRecu = directDebits.present(
            presentation(decor, interne, "20000", J.minusDays(3), "prr-2")).directDebit();
        assertThat(interneRecu.status()).as("echeance passee : execute au jour").isEqualTo("SETTLED");
        assertThat(interneRecu.executedOn()).isEqualTo(J);
        assertThat(interneRecu.settlementAccountId()).isEqualTo(creancier);
        assertThat(interneRecu.clearingAccountId()).isNull();
        assertThat(solde(compte)).isEqualTo(xof("78820"));
        assertThat(solde(creancier)).isEqualTo(xof("20000"));
        // Rembourse par le creancier de la banque lui-meme.
        assertThat(directDebits.refund(interneRecu.id(), "double facturation", ACTOR).status())
            .isEqualTo("REFUNDED");
        assertThat(solde(creancier).isZero()).isTrue();
        assertThat(solde(compte)).isEqualTo(xof("98820"));

        // Sans provision : rejete, le motif reste, rien n'est ecrit ; le creancier peut
        // representer.
        DirectDebitService.DirectDebit rejete = directDebits.present(
            presentation(decor, externe, "500000", J, "prr-3")).directDebit();
        assertThat(rejete.status()).isEqualTo("REJECTED");
        assertThat(rejete.rejectionReason()).isEqualTo("SANS_PROVISION");
        assertThat(rejete.executedOn()).isEqualTo(J);
        assertThat(rejete.entryId()).isNull();
        assertThat(solde(compte)).isEqualTo(xof("98820"));
        assertThat(directDebits.present(presentation(decor, externe, "500000", J, "prr-3"))
                       .directDebit().status()).isEqualTo("REJECTED");

        // Un rappel avant reglement contre-passe tout, frais compris.
        DirectDebitService.DirectDebit rappele = directDebits.present(
            presentation(decor, externe, "10000", J, "prr-4")).directDebit();
        assertThat(rappele.status()).isEqualTo("COLLECTED");
        assertThat(solde(compte)).isEqualTo(xof("88230"));
        DirectDebitService.DirectDebit annule = directDebits.cancel(rappele.id(), "rappel du creancier", ACTOR);
        assertThat(annule.status()).isEqualTo("CANCELLED");
        assertThat(annule.closeEntryId()).isNotNull();
        assertThat(solde(compte)).isEqualTo(xof("98820"));
        assertThat(solde(reglement).isZero()).isTrue();

        // A venir : en attente, rien n'est debite ; retire avant echeance, sans ecriture.
        DirectDebitService.DirectDebit attente = directDebits.present(
            presentation(decor, externe, "5000", J.plusDays(5), "prr-5")).directDebit();
        assertThat(attente.status()).isEqualTo("PENDING");
        assertThat(solde(compte)).isEqualTo(xof("98820"));
        assertThatThrownBy(() -> directDebits.settle(attente.id(), nostro.id(), ACTOR))
            .isInstanceOf(DirectDebitService.DirectDebitStateException.class);
        assertThat(directDebits.execute(attente.id(), null, ACTOR).status())
            .as("pas encore a l'echeance").isEqualTo("PENDING");
        DirectDebitService.DirectDebit retire = directDebits.cancel(attente.id(), "retire", ACTOR);
        assertThat(retire.status()).isEqualTo("CANCELLED");
        assertThat(retire.closeEntryId()).isNull();

        // Le produit sans compte de reglement n'admet pas de creancier d'ailleurs.
        verser(decor, epargne, "10000", "prr-6");
        UUID mandatEpargne = directDebits.registerMandate(mandatExterne(decor, epargne, "RUM-EP")).id();
        assertThatThrownBy(() -> directDebits.present(presentation(decor, mandatEpargne, "1000", J, "prr-7")))
            .isInstanceOf(PaymentService.NotAllowedException.class)
            .hasMessageContaining("creancier exterieur");

        List<DirectDebitService.DirectDebit> rejetes = database.inTransaction(
            c -> DirectDebitService.page(c, decor.entityId(),
                                         DirectDebitService.DirectionKind.RECEIVED, "REJECTED", 0, 10));
        assertThat(rejetes).extracting(DirectDebitService.DirectDebit::id).containsExactly(rejete.id());
        long total = database.inTransaction(
            c -> DirectDebitService.count(c, decor.entityId(), null, null));
        assertThat(total).isEqualTo(5L);
        assertThatThrownBy(() -> directDebits.settle(UUID.randomUUID(), nostro.id(), ACTOR))
            .isInstanceOf(DirectDebitService.UnknownDirectDebitException.class);
        List<Reconciliation.Discrepancy> ecarts = database.inTransaction(
            c -> Reconciliation.allBlockingChecks(c, decor.entityId()));
        assertThat(ecarts).isEmpty();
    }

    @Test
    @DisplayName("un prelevement revoque ou presente sur un compte qui ne peut plus operer est rejete a l'execution, pas a la presentation")
    void rejected_at_execution() {
        Decor decor = decor("PRX");
        produit(decor, "CC-PRX", "CURRENT_ACCOUNT",
                parametres(decor, reglement(decor, "PRX"), encaissement(decor, "PRX"), null));
        UUID compte = ouvrir(decor, "CLI-PRX", "CC-PRX", client(decor.entityId(), "T-PRX"));
        verser(decor, compte, "100000", "prx-0");
        UUID mandat = directDebits.registerMandate(mandatExterne(decor, compte, "RUM-X")).id();

        DirectDebitService.DirectDebit attente = directDebits.present(
            presentation(decor, mandat, "1000", J.plusDays(2), "prx-1")).directDebit();
        assertThat(attente.status()).isEqualTo("PENDING");
        directDebits.revokeMandate(decor.entityId(), mandat, "resiliation", ACTOR);
        dater(decor, J.plusDays(2));
        DirectDebitService.DirectDebit rejete = directDebits.execute(attente.id(), null, ACTOR);
        assertThat(rejete.status()).isEqualTo("REJECTED");
        assertThat(rejete.rejectionReason()).isEqualTo("MANDAT_REVOQUE");
        assertThat(solde(compte)).isEqualTo(xof("100000"));

        // Un compte bloque en debit : le ledger refuse au moment d'ecrire, apres le controle du
        // disponible, et le rejet est nomme quand meme.
        UUID mandat2 = directDebits.registerMandate(mandatExterne(decor, compte, "RUM-Y")).id();
        lifecycle.block(new AccountLifecycle.Block(compte, BlockKind.DEBIT, "saisie", null,
                                                   ACTOR, APPROVER));
        DirectDebitService.DirectDebit bloque = directDebits.present(
            presentation(decor, mandat2, "1000", J, "prx-2")).directDebit();
        assertThat(bloque.status()).isEqualTo("REJECTED");
        assertThat(bloque.rejectionReason()).isEqualTo("COMPTE_BLOQUE");
        assertThat(solde(compte)).isEqualTo(xof("100000"));
    }

    @Test
    @DisplayName("un prelevement emis credite le creancier sauf bonne fin a l'echeance, frais dans leur ecriture, bloque jusqu'au reglement ; retourne avant reglement il est contre-passe, apres il est repris au creancier")
    void issued() {
        Decor decor = decor("PRE");
        Account encaissement = encaissement(decor, "PRE");
        Account nostro = account(decor.entityId(), "PRE-NOSTRO", AccountKind.NOSTRO,
                                 NormalBalance.DEBIT);
        produit(decor, "CC-PRE", "CURRENT_ACCOUNT",
                parametres(decor, reglement(decor, "PRE"), encaissement, "1000"));
        produit(decor, "EP-PRE", "SAVINGS_ACCOUNT", frais(decor));
        UUID creancier = ouvrir(decor, "CLI-PRE", "CC-PRE", client(decor.entityId(), "T-PRE"));
        UUID epargne = ouvrir(decor, "CLI-PRE-EP", "EP-PRE", client(decor.entityId(), "T-PRE2"));
        verser(decor, creancier, "10000", "pre-0");

        DirectDebitService.Presented remise = directDebits.issue(remise(decor, creancier, "80000", J, "pre-1"));
        assertThat(remise.replayed()).isFalse();
        DirectDebitService.DirectDebit emis = remise.directDebit();
        assertThat(emis.direction()).isEqualTo(DirectDebitService.DirectionKind.ISSUED);
        assertThat(emis.status()).isEqualTo("COLLECTED");
        assertThat(emis.valueDate()).as("deux jours ouvres").isEqualTo(LocalDate.of(2026, 9, 17));
        assertThat(emis.fee()).isEqualTo(xof("1000"));
        assertThat(emis.tax()).isEqualTo(xof("180"));
        assertThat(emis.feeEntryId()).isNotNull().isNotEqualTo(emis.entryId());
        assertThat(emis.holdId()).isNotNull();
        assertThat(emis.clearingAccountId()).isEqualTo(encaissement.id());
        assertThat(solde(creancier)).isEqualTo(xof("88820"));
        assertThat(disponible(creancier, J)).as("credite mais bloque").isEqualTo(xof("8820"));
        assertThat(solde(encaissement)).isEqualTo(xof("80000"));
        assertThat(directDebits.issue(remise(decor, creancier, "80000", J, "pre-1")).replayed()).isTrue();

        // Regle par le correspondant : le blocage tombe, l'encaissement se solde sur le nostro.
        DirectDebitService.DirectDebit regle = directDebits.settle(emis.id(), nostro.id(), ACTOR);
        assertThat(regle.status()).isEqualTo("SETTLED");
        assertThat(disponible(creancier, J)).isEqualTo(xof("88820"));
        assertThat(solde(encaissement).isZero()).isTrue();
        assertThat(solde(nostro)).isEqualTo(xof("80000"));
        // Puis retourne tardivement : le montant est repris au creancier, les frais restent.
        DirectDebitService.DirectDebit repris = directDebits.returnIssued(regle.id(), "compte clos", ACTOR);
        assertThat(repris.status()).isEqualTo("RETURNED");
        assertThat(solde(creancier)).isEqualTo(xof("8820"));
        assertThat(solde(nostro).isZero()).isTrue();

        // Retourne avant reglement : la remise est contre-passee, le blocage tombe, le frais reste.
        DirectDebitService.DirectDebit seconde = directDebits.issue(
            remise(decor, creancier, "5000", J, "pre-2")).directDebit();
        assertThat(solde(creancier)).isEqualTo(xof("12640"));
        DirectDebitService.DirectDebit impaye = directDebits.returnIssued(seconde.id(), "sans provision", ACTOR);
        assertThat(impaye.status()).isEqualTo("RETURNED");
        assertThat(impaye.closeEntryId()).isNotNull();
        assertThat(solde(creancier)).isEqualTo(xof("7640"));
        assertThat(disponible(creancier, J)).isEqualTo(xof("7640"));
        assertThat(solde(encaissement).isZero()).isTrue();
        List<Holds.Hold> blocages = database.inTransaction(c -> Holds.activeOn(c, creancier));
        assertThat(blocages).isEmpty();
        assertThatThrownBy(() -> directDebits.refund(seconde.id(), "x", ACTOR))
            .isInstanceOf(DirectDebitService.DirectDebitStateException.class)
            .hasMessageContaining("seul un prelevement recu");

        // A venir : en attente ; le produit sans compte d'encaissement n'emet rien.
        DirectDebitService.DirectDebit attente = directDebits.issue(
            remise(decor, creancier, "1000", J.plusDays(3), "pre-3")).directDebit();
        assertThat(attente.status()).isEqualTo("PENDING");
        assertThat(attente.holdId()).isNull();
        verser(decor, epargne, "1000", "pre-4");
        assertThatThrownBy(() -> directDebits.issue(remise(decor, epargne, "1000", J, "pre-5")))
            .isInstanceOf(PaymentService.NotAllowedException.class)
            .hasMessageContaining("n'emet pas de prelevement");
        List<Reconciliation.Discrepancy> ecarts = database.inTransaction(
            c -> Reconciliation.allBlockingChecks(c, decor.entityId()));
        assertThat(ecarts).isEmpty();
    }

    @Test
    @DisplayName("le prelevement d'un client d'agence passe par la liaison : le reglement et l'encaissement sont tenus au siege, le frais reste a l'agence")
    void branch_direct_debits_go_through_the_bridge() {
        Decor decor = decor("PRB");
        Account reglement = reglement(decor, "PRB");
        Account encaissement = encaissement(decor, "PRB");
        Account liaison = account(decor.entityId(), "PRB-LIAISON-A", AccountKind.GL,
                                  NormalBalance.DEBIT);
        UUID siege = siege(decor);
        UUID agence = database.inTransaction(c -> Branches.create(
            c, decor.entityId(), "A", "Agence A", Branches.Kind.BRANCH, null, J.minusMonths(1),
            Map.of(Currencies.XOF, liaison.id())));
        produit(decor, "CC-PRB", "CURRENT_ACCOUNT", parametres(decor, reglement, encaissement, "500"));
        UUID compte = ouvrir(decor, "CLI-PRB", "CC-PRB", client(decor.entityId(), "T-PRB"), agence);
        verser(decor, compte, "100000", "prb-0");
        UUID mandat = directDebits.registerMandate(mandatExterne(decor, compte, "RUM-B")).id();

        DirectDebitService.DirectDebit recu = directDebits.present(
            presentation(decor, mandat, "20000", J, "prb-1")).directDebit();
        Map<UUID, UUID> agences = agences(recu.entryId());
        assertThat(agences.get(reglement.id())).as("le reglement au siege").isEqualTo(siege);
        assertThat(agences.get(compte)).isEqualTo(agence);
        assertThat(agences.get(decor.produitsFrais().id())).as("le frais a l'agence").isEqualTo(agence);
        assertThat(agences).containsKey(liaison.id());

        DirectDebitService.DirectDebit emis = directDebits.issue(
            remise(decor, compte, "15000", J, "prb-2")).directDebit();
        agences = agences(emis.entryId());
        assertThat(agences.get(encaissement.id())).as("l'encaissement au siege").isEqualTo(siege);
        assertThat(agences.get(compte)).isEqualTo(agence);
        assertThat(agences).containsKey(liaison.id());
        List<Reconciliation.Discrepancy> ecarts = database.inTransaction(
            c -> Reconciliation.allBlockingChecks(c, decor.entityId()));
        assertThat(ecarts).isEmpty();
    }

    /** L'agence comptable de chaque compte dans une ecriture. */
    private static Map<UUID, UUID> agences(UUID entryId) {
        return database.inTransaction(c -> {
            Map<UUID, UUID> byAccount = new HashMap<>();
            try (var ps = c.prepareStatement(
                "SELECT account_id, branch_id FROM journal_line WHERE entry_id = ?")) {
                ps.setObject(1, entryId);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        byAccount.put(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class));
                    }
                }
            } catch (java.sql.SQLException e) {
                throw new io.corebanking.ledger.store.LedgerStoreException("Lignes", e);
            }
            return byAccount;
        });
    }
}
