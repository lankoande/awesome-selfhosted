package io.corebanking.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.loan.AmortisationSchedule;
import io.corebanking.loan.LoanTerms;
import io.corebanking.loan.ScheduleGenerator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L'origination : la demande, l'instruction, la decision et les conditions — et ce qu'elles
 * engagent au deblocage.
 */
class LoanOriginationIT extends LoanTestBase {

    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    @Test
    @DisplayName("de la demande au contrat : l'instruction lit les engagements, la decision accorde moins que demande, et la condition suspensive retient le versement sans retenir la signature")
    void from_application_to_disbursement() {
        Decor decor = decor("ORIG1");
        product(decor, "CRED-ORIG1", Map.of());
        UUID client = client(decor.entityId(), "CLI-ORIG1");

        LoanOrigination.Application demande = database.inTransaction(
            c -> LoanOrigination.submit(c, new LoanOrigination.Request(
                decor.entityId(), null, "DEM-001", client, "CRED-ORIG1", xof("6000000"), 24,
                "vehicule utilitaire", DEBLOCAGE, ACTOR)));
        assertThat(demande.status()).isEqualTo(LoanOrigination.Status.SUBMITTED);

        // L'instruction : rien d'existant, une mensualite simulee par le moteur qui editera
        // l'echeancier, un taux d'endettement qui en decoule.
        LoanOrigination.Assessment instruction = database.inTransaction(
            c -> LoanOrigination.assess(c, new LoanOrigination.Instruction(
                demande.id(), xof("1200000"), xof("100000"), xof("0"), new BigDecimal("12"),
                640, "Bureau d'information sur le credit", DEBLOCAGE, ACTOR)));
        assertThat(instruction.existingCommitments()).isEqualTo(xof("0"));
        assertThat(instruction.requestedInstalment()).isEqualTo(xof("282441"));
        assertThat(instruction.debtServiceRatioPercent()).isEqualByComparingTo("31.87");
        assertThat(instruction.withinPolicy()).as("aucune politique declaree : rien n'est exige")
            .isTrue();

        // La decision, a deux : on accorde moins que demande, jamais plus.
        assertThatThrownBy(() -> database.inTransaction(
                c -> LoanOrigination.decide(c, new LoanOrigination.Verdict(
                    demande.id(), LoanOrigination.Outcome.APPROVED, xof("9000000"), 24,
                    new BigDecimal("12"), DEBLOCAGE, "dossier solide", null, ACTOR, APPROVER))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("pas plus que demande");
        assertThatThrownBy(() -> database.inTransaction(
                c -> LoanOrigination.decide(c, new LoanOrigination.Verdict(
                    demande.id(), LoanOrigination.Outcome.APPROVED, xof("5000000"), 24,
                    new BigDecimal("12"), DEBLOCAGE, "dossier solide", null, ACTOR, ACTOR))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a deux");

        LoanOrigination.Decision decision = database.inTransaction(
            c -> LoanOrigination.decide(c, new LoanOrigination.Verdict(
                demande.id(), LoanOrigination.Outcome.APPROVED, xof("5000000"), 24,
                new BigDecimal("12"), DEBLOCAGE, "capacite de remboursement etablie", null,
                ACTOR, APPROVER)));
        assertThat(decision.grantedAmount()).isEqualTo(xof("5000000"));
        assertThat(decision.validUntil()).as("a defaut de politique, l'offre vaut trente jours")
            .isEqualTo(DEBLOCAGE.plusDays(30));

        // Une condition suspensive, posee avant la contractualisation.
        LoanOrigination.Condition assurance = database.inTransaction(
            c -> LoanOrigination.addCondition(c, demande.id(),
                LoanOrigination.ConditionKind.PRECEDENT, "attestation d'assurance du vehicule",
                DEBLOCAGE.plusDays(10), DEBLOCAGE, ACTOR));
        assertThat(assurance.open()).isTrue();

        // La contractualisation prend le montant accorde, pas le demande.
        UUID contrat = database.inTransaction(
            c -> LoanOrigination.contractualise(c, new LoanOrigination.Contracting(
                demande.id(), "PRET-ORIG1", decor.pret().id(), decor.courant().id(), DEBLOCAGE,
                ACTOR)));
        LoanContract cree = database.inTransaction(c -> LoanStore.requireContract(c, contrat));
        assertThat(cree.principal()).isEqualTo(xof("5000000"));
        assertThat(database.inTransaction(c -> LoanOrigination.require(c, demande.id())).status())
            .isEqualTo(LoanOrigination.Status.CONTRACTED);

        // Le contrat est signe ; le versement, lui, attend la piece.
        assertThatThrownBy(() -> loanService.disburse(contrat, echeancier("5000000", "12", 24),
                                                      ACTOR, APPROVER))
            .isInstanceOf(LoanOrigination.ApplicationStateException.class)
            .hasMessageContaining("attestation d'assurance")
            .hasMessageContaining("retient le versement");

        assertThatThrownBy(() -> database.inTransaction(c -> {
            LoanOrigination.clearCondition(c, assurance.id(), DEBLOCAGE, "police AXA 2026", ACTOR,
                                           ACTOR);
            return null;
        })).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a deux");

        database.inTransaction(c -> LoanOrigination.clearCondition(
            c, assurance.id(), DEBLOCAGE, "police AXA 2026", ACTOR, APPROVER));
        loanService.disburse(contrat, echeancier("5000000", "12", 24), ACTOR, APPROVER);
        assertThat(solde(decor.pret())).isEqualTo(xof("5000000"));

        // Le journal du dossier porte tout le parcours.
        List<LoanOrigination.Event> journal = database.inTransaction(
            c -> LoanOrigination.events(c, demande.id()));
        assertThat(journal).extracting(LoanOrigination.Event::kind)
            .containsExactly("SUBMITTED", "ASSESSED", "DECIDED", "CONDITION_ADDED", "CONTRACTED",
                             "CONDITION_CLEARED");
    }

    @Test
    @DisplayName("un dossier hors politique ne se refuse pas tout seul : il nomme ses depassements, et la derogation s'ecrit")
    void a_file_outside_policy_needs_a_written_waiver() {
        Decor decor = decor("ORIG2");
        product(decor, "CRED-ORIG2", Map.of());
        UUID client = client(decor.entityId(), "CLI-ORIG2");
        database.inTransaction(c -> LendingPolicies.declare(c, new LendingPolicies.Draft(
            decor.entityId(), "CRED-ORIG2", new BigDecimal("33"), new BigDecimal("10000000"),
            36, null, false, 15, DEBLOCAGE.minusYears(1), null, ACTOR, APPROVER)));

        LoanOrigination.Application demande = database.inTransaction(
            c -> LoanOrigination.submit(c, new LoanOrigination.Request(
                decor.entityId(), null, "DEM-002", client, "CRED-ORIG2", xof("8000000"), 48,
                "travaux", DEBLOCAGE, ACTOR)));

        LoanOrigination.Assessment instruction = database.inTransaction(
            c -> LoanOrigination.assess(c, new LoanOrigination.Instruction(
                demande.id(), xof("500000"), xof("0"), xof("0"), new BigDecimal("12"), null, null,
                DEBLOCAGE, ACTOR)));
        assertThat(instruction.withinPolicy()).isFalse();
        assertThat(instruction.breaches()).anySatisfy(b -> assertThat(b).contains("endettement"))
            .anySatisfy(b -> assertThat(b).contains("duree 48 mois au-dela de 36"));

        // Sans derogation ecrite, la decision est refusee — et elle nomme ce qui depasse.
        assertThatThrownBy(() -> database.inTransaction(
                c -> LoanOrigination.decide(c, new LoanOrigination.Verdict(
                    demande.id(), LoanOrigination.Outcome.APPROVED, xof("8000000"), 48,
                    new BigDecimal("12"), DEBLOCAGE, "client historique", null, ACTOR, APPROVER))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("hors politique").hasMessageContaining("endettement")
            .hasMessageContaining("derogation");

        // Ramene dans la politique, le meme dossier passe sans derogation : la decision se
        // recalcule sur ce qu'elle accorde, pas sur ce qui a ete instruit.
        LoanOrigination.Decision decision = database.inTransaction(
            c -> LoanOrigination.decide(c, new LoanOrigination.Verdict(
                demande.id(), LoanOrigination.Outcome.APPROVED, xof("1000000"), 36,
                new BigDecimal("12"), DEBLOCAGE, "montant ramene a la capacite", null, ACTOR,
                APPROVER)));
        assertThat(decision.waiverReason()).isNull();
        assertThat(decision.validUntil()).as("la politique fixe la validite de l'offre")
            .isEqualTo(DEBLOCAGE.plusDays(15));

        // Une seule decision par dossier : la refaire suppose de reinstruire.
        assertThatThrownBy(() -> database.inTransaction(
                c -> LoanOrigination.decide(c, new LoanOrigination.Verdict(
                    demande.id(), LoanOrigination.Outcome.REJECTED, null, null, null, DEBLOCAGE,
                    "finalement non", null, ACTOR, APPROVER))))
            .isInstanceOf(LoanOrigination.ApplicationStateException.class)
            .hasMessageContaining("ne se decide plus");
    }

    @Test
    @DisplayName("la decision engage le deblocage : ni un autre taux, ni une duree plus longue")
    void the_disbursement_applies_what_was_granted() {
        Decor decor = decor("ORIG3");
        product(decor, "CRED-ORIG3", Map.of());
        UUID client = client(decor.entityId(), "CLI-ORIG3");
        UUID demande = database.inTransaction(
            c -> LoanOrigination.submit(c, new LoanOrigination.Request(
                decor.entityId(), null, "DEM-003", client, "CRED-ORIG3", xof("2400000"), 12,
                "tresorerie", DEBLOCAGE, ACTOR))).id();
        database.inTransaction(c -> LoanOrigination.assess(c, new LoanOrigination.Instruction(
            demande, xof("2000000"), xof("0"), xof("0"), new BigDecimal("9"), null, null,
            DEBLOCAGE, ACTOR)));
        database.inTransaction(c -> LoanOrigination.decide(c, new LoanOrigination.Verdict(
            demande, LoanOrigination.Outcome.APPROVED, xof("2400000"), 12, new BigDecimal("9"),
            DEBLOCAGE, "accord", null, ACTOR, APPROVER)));
        UUID contrat = database.inTransaction(
            c -> LoanOrigination.contractualise(c, new LoanOrigination.Contracting(
                demande, "PRET-ORIG3", decor.pret().id(), decor.courant().id(), DEBLOCAGE, ACTOR)));

        assertThatThrownBy(() -> loanService.disburse(contrat, echeancier("2400000", "14", 12),
                                                      ACTOR, APPROVER))
            .isInstanceOf(LoanOrigination.ApplicationStateException.class)
            .hasMessageContaining("Taux accorde 9").hasMessageContaining("14");
        assertThatThrownBy(() -> loanService.disburse(contrat, echeancier("2400000", "9", 24),
                                                      ACTOR, APPROVER))
            .isInstanceOf(LoanOrigination.ApplicationStateException.class)
            .hasMessageContaining("Duree accordee 12 mois");

        loanService.disburse(contrat, echeancier("2400000", "9", 12), ACTOR, APPROVER);
        assertThat(solde(decor.pret())).isEqualTo(xof("2400000"));

        // Un contrat sans dossier d'origination ne se voit rien imposer : le controle ne
        // s'invente pas de conditions.
        UUID direct = contract(decor, "PRET-DIRECT3", "CRED-ORIG3", "500000");
        loanService.disburse(direct, echeancier("500000", "18", 36), ACTOR, APPROVER);
    }

    @Test
    @DisplayName("les engagements existants se lisent dans les echeanciers en vigueur, pas dans la declaration du client")
    void existing_commitments_are_read_from_the_schedules() {
        Decor decor = decor("ORIG4");
        product(decor, "CRED-ORIG4", Map.of());
        UUID client = client(decor.entityId(), "CLI-ORIG4");
        UUID premier = contract(decor, "PRET-ORIG4", "CRED-ORIG4", "3000000");
        database.inTransaction(c -> {
            LoanStore.assignCustomer(c, premier, client);
            return null;
        });
        loanService.disburse(premier, echeancier("3000000", "12", 24), ACTOR, APPROVER);

        Money charge = database.inTransaction(c -> LoanOrigination.monthlyCommitments(
            c, client, Currencies.XOF, DEBLOCAGE));
        assertThat(charge).as("la mensualite du credit en cours").isEqualTo(xof("141220"));

        UUID demande = database.inTransaction(
            c -> LoanOrigination.submit(c, new LoanOrigination.Request(
                decor.entityId(), null, "DEM-004", client, "CRED-ORIG4", xof("1200000"), 12,
                "second credit", DEBLOCAGE, ACTOR))).id();
        LoanOrigination.Assessment instruction = database.inTransaction(
            c -> LoanOrigination.assess(c, new LoanOrigination.Instruction(
                demande, xof("1000000"), xof("0"), xof("0"), new BigDecimal("12"), null, null,
                DEBLOCAGE, ACTOR)));
        assertThat(instruction.existingCommitments()).isEqualTo(charge);
        assertThat(instruction.debtServiceRatioPercent())
            .as("le credit en cours pese sur la capacite du second")
            .isGreaterThan(new BigDecimal("20"));
    }

    @Test
    @DisplayName("une offre non contractualisee expire a l'arrete, et l'annulation de l'arrete la rend a l'accord")
    void an_offer_expires_and_the_cancellation_gives_it_back() {
        Decor decor = decor("ORIG5");
        product(decor, "CRED-ORIG5", Map.of());
        UUID client = client(decor.entityId(), "CLI-ORIG5");
        UUID demande = database.inTransaction(
            c -> LoanOrigination.submit(c, new LoanOrigination.Request(
                decor.entityId(), null, "DEM-005", client, "CRED-ORIG5", xof("1000000"), 12,
                "equipement", DEBLOCAGE, ACTOR))).id();
        database.inTransaction(c -> LoanOrigination.assess(c, new LoanOrigination.Instruction(
            demande, xof("2000000"), xof("0"), xof("0"), new BigDecimal("10"), null, null,
            DEBLOCAGE, ACTOR)));
        database.inTransaction(c -> LoanOrigination.decide(c, new LoanOrigination.Verdict(
            demande, LoanOrigination.Outcome.APPROVED, xof("1000000"), 12, new BigDecimal("10"),
            DEBLOCAGE, "accord", null, ACTOR, APPROVER)));

        LocalDate apres = DEBLOCAGE.plusDays(31);
        UUID run = UUID.randomUUID();
        List<String> expirees = database.inTransaction(
            c -> LoanOrigination.expire(c, decor.entityId(), apres, run, ACTOR));
        assertThat(expirees).containsExactly("DEM-005");
        assertThat(database.inTransaction(c -> LoanOrigination.require(c, demande)).status())
            .isEqualTo(LoanOrigination.Status.EXPIRED);

        // Une offre expiree ne produit plus de contrat.
        assertThatThrownBy(() -> database.inTransaction(
                c -> LoanOrigination.contractualise(c, new LoanOrigination.Contracting(
                    demande, "PRET-ORIG5", decor.pret().id(), decor.courant().id(), apres, ACTOR))))
            .isInstanceOf(LoanOrigination.ApplicationStateException.class)
            .hasMessageContaining("Seul un accord produit un contrat");

        int rendues = database.inTransaction(c -> LoanOrigination.cancelRun(c, run));
        assertThat(rendues).isEqualTo(1);
        LoanOrigination.Application rendue = database.inTransaction(
            c -> LoanOrigination.require(c, demande));
        assertThat(rendue.status()).isEqualTo(LoanOrigination.Status.APPROVED);
        assertThat(rendue.closedOn()).isNull();

        // Le jour meme de l'echeance, l'offre vaut encore ; le lendemain, elle ne vaut plus.
        List<String> leJourMeme = database.inTransaction(
            c -> LoanOrigination.expire(c, decor.entityId(), DEBLOCAGE.plusDays(30),
                                        UUID.randomUUID(), ACTOR));
        assertThat(leJourMeme).isEmpty();
    }

    @Test
    @DisplayName("ce qu'un dossier refuse : decider sans instruire, contractualiser deux fois, retirer un dossier devenu contrat")
    void what_the_file_refuses() {
        Decor decor = decor("ORIG6");
        product(decor, "CRED-ORIG6", Map.of());
        UUID client = client(decor.entityId(), "CLI-ORIG6");
        UUID demande = database.inTransaction(
            c -> LoanOrigination.submit(c, new LoanOrigination.Request(
                decor.entityId(), null, "DEM-006", client, "CRED-ORIG6", xof("1000000"), 12, null,
                DEBLOCAGE, ACTOR))).id();

        assertThatThrownBy(() -> database.inTransaction(
                c -> LoanOrigination.decide(c, new LoanOrigination.Verdict(
                    demande, LoanOrigination.Outcome.APPROVED, xof("1000000"), 12,
                    new BigDecimal("10"), DEBLOCAGE, "au feeling", null, ACTOR, APPROVER))))
            .isInstanceOf(LoanOrigination.ApplicationStateException.class)
            .hasMessageContaining("n'est pas instruite");

        // Une reference deja prise, un tiers d'une autre entite : refuses a la saisie.
        assertThatThrownBy(() -> database.inTransaction(
                c -> LoanOrigination.submit(c, new LoanOrigination.Request(
                    decor.entityId(), null, "DEM-006", client, "CRED-ORIG6", xof("1000000"), 12,
                    null, DEBLOCAGE, ACTOR))))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("DEM-006");

        database.inTransaction(c -> LoanOrigination.assess(c, new LoanOrigination.Instruction(
            demande, xof("3000000"), xof("0"), xof("0"), new BigDecimal("10"), null, null,
            DEBLOCAGE, ACTOR)));
        database.inTransaction(c -> LoanOrigination.decide(c, new LoanOrigination.Verdict(
            demande, LoanOrigination.Outcome.APPROVED, xof("1000000"), 12, new BigDecimal("10"),
            DEBLOCAGE, "accord", null, ACTOR, APPROVER)));
        database.inTransaction(c -> LoanOrigination.contractualise(c,
            new LoanOrigination.Contracting(demande, "PRET-ORIG6", decor.pret().id(),
                                            decor.courant().id(), DEBLOCAGE, ACTOR)));

        assertThatThrownBy(() -> database.inTransaction(
                c -> LoanOrigination.contractualise(c, new LoanOrigination.Contracting(
                    demande, "PRET-ORIG6-BIS", decor.pret().id(), decor.courant().id(), DEBLOCAGE,
                    ACTOR))))
            .isInstanceOf(LoanOrigination.ApplicationStateException.class)
            .hasMessageContaining("Seul un accord produit un contrat");
        assertThatThrownBy(() -> database.inTransaction(c -> {
            LoanOrigination.cancel(c, demande, DEBLOCAGE, "changement d'avis", ACTOR);
            return null;
        })).isInstanceOf(LoanOrigination.ApplicationStateException.class)
            .hasMessageContaining("ne se retire plus");

        // Une demande non decidee, elle, se retire.
        UUID autre = database.inTransaction(
            c -> LoanOrigination.submit(c, new LoanOrigination.Request(
                decor.entityId(), null, "DEM-007", client, "CRED-ORIG6", xof("500000"), 6, null,
                DEBLOCAGE, ACTOR))).id();
        database.inTransaction(c -> {
            LoanOrigination.cancel(c, autre, DEBLOCAGE, "le client renonce", ACTOR);
            return null;
        });
        assertThat(database.inTransaction(c -> LoanOrigination.require(c, autre)).status())
            .isEqualTo(LoanOrigination.Status.CANCELLED);
        List<LoanOrigination.Application> dossiers = database.inTransaction(
            c -> LoanOrigination.applications(c, decor.entityId(),
                                              LoanOrigination.Status.CANCELLED));
        assertThat(dossiers).extracting(LoanOrigination.Application::reference)
            .containsExactly("DEM-007");
    }

    private static AmortisationSchedule echeancier(String capital, String taux, int echeances) {
        return ScheduleGenerator.generate(
            LoanTerms.of(Money.of(capital, Currencies.XOF))
                .ratePercent(taux).instalments(echeances)
                .disbursedOn(DEBLOCAGE).firstDueDate(PREMIERE_ECHEANCE).build());
    }
}
