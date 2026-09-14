package io.corebanking.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.loan.AmortisationSchedule;
import io.corebanking.loan.LoanTerms;
import io.corebanking.loan.ScheduleGenerator;
import io.corebanking.ledger.store.LedgerStoreException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LoanLifecycleIT extends LoanTestBase {

    /** Credit de 1 000 000 XOF a 12 %, douze mensualites : annuite de 88 849 XOF. */
    private static AmortisationSchedule echeancier(String capital) {
        return ScheduleGenerator.generate(
            LoanTerms.of(Money.of(capital, Currencies.XOF))
                .ratePercent("12").instalments(12)
                .disbursedOn(DEBLOCAGE).firstDueDate(PREMIERE_ECHEANCE).build());
    }

    // ------------------------------------------------------------------ deblocage

    @Test
    @DisplayName("le deblocage fait naitre l'encours a l'actif et met les fonds a disposition")
    void deblocage() {
        Decor decor = decor("D1");
        product(decor, "CRED-D1", Map.of());
        UUID contrat = contract(decor, "REF-D1", "CRED-D1", "1000000");

        loanService.disburse(contrat, echeancier("1000000"), ACTOR, APPROVER);

        assertThat(solde(decor.pret())).isEqualTo(xof("1000000"));
        assertThat(solde(decor.courant())).isEqualTo(xof("1000000"));
    }

    @Test
    @DisplayName("un echeancier qui ne correspond pas au contrat est refuse")
    void echeancierIncoherent() {
        Decor decor = decor("D2");
        product(decor, "CRED-D2", Map.of());
        UUID contrat = contract(decor, "REF-D2", "CRED-D2", "1000000");

        assertThatThrownBy(() ->
            loanService.disburse(contrat, echeancier("2000000"), ACTOR, APPROVER))
            .isInstanceOf(LedgerStoreException.class)
            .hasMessageContaining("porte sur");
    }

    // ------------------------------------------------------------------ exigibilite

    @Test
    @DisplayName("a l'echeance, les charges sont constatees en produits et l'encours ne bouge pas")
    void exigibilite() {
        Decor decor = decor("E1");
        product(decor, "CRED-E1", Map.of());
        UUID contrat = contract(decor, "REF-E1", "CRED-E1", "1000000");
        loanService.disburse(contrat, echeancier("1000000"), ACTOR, APPROVER);

        var bilan = loanService.makeDue(decor.entityId(), PREMIERE_ECHEANCE, ACTOR,
                                        UUID.randomUUID());

        assertThat(bilan.anomalies()).isEmpty();
        assertThat(bilan.instalmentsMadeDue()).isEqualTo(1);

        // Premiere echeance : 78 849 de capital, 10 000 d'interets.
        assertThat(creances(contrat)).extracting(Creance::category, Creance::outstanding)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("PRINCIPAL", xof("78849")),
                org.assertj.core.groups.Tuple.tuple("INTEREST", xof("10000")));

        assertThat(solde(decor.produitsInterets())).isEqualTo(xof("10000"));
        assertThat(solde(decor.creances())).isEqualTo(xof("10000"));

        // Le point du test : rendre une echeance exigible ne cree aucun flux sur le capital. Il est
        // deja a l'actif depuis le deblocage, et l'amortir des l'exigibilite afficherait un actif
        // inferieur a ce que le client doit reellement.
        assertThat(solde(decor.pret())).isEqualTo(xof("1000000"));
    }

    @Test
    @DisplayName("une echeance n'est rendue exigible qu'une fois, meme si le traitement est rejoue")
    void exigibiliteIdempotente() {
        Decor decor = decor("E2");
        product(decor, "CRED-E2", Map.of());
        UUID contrat = contract(decor, "REF-E2", "CRED-E2", "1000000");
        loanService.disburse(contrat, echeancier("1000000"), ACTOR, APPROVER);

        UUID run = UUID.randomUUID();
        loanService.makeDue(decor.entityId(), PREMIERE_ECHEANCE, ACTOR, run);
        var second = loanService.makeDue(decor.entityId(), PREMIERE_ECHEANCE, ACTOR, run);

        assertThat(second.instalmentsMadeDue()).isZero();
        assertThat(creances(contrat)).hasSize(2);
        assertThat(solde(decor.produitsInterets())).isEqualTo(xof("10000"));
    }

    @Test
    @DisplayName("un rattrapage rend exigibles les echeances manquees, chacune a sa propre date")
    void rattrapage() {
        Decor decor = decor("E3");
        product(decor, "CRED-E3", Map.of());
        UUID contrat = contract(decor, "REF-E3", "CRED-E3", "1000000");
        loanService.disburse(contrat, echeancier("1000000"), ACTOR, APPROVER);

        var bilan = loanService.makeDue(decor.entityId(), LocalDate.of(2026, 12, 15), ACTOR,
                                        UUID.randomUUID());

        assertThat(bilan.instalmentsMadeDue()).isEqualTo(3);
        assertThat(creances(contrat)).extracting(Creance::dueDate).containsExactly(
            LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 15),
            LocalDate.of(2026, 11, 15), LocalDate.of(2026, 11, 15),
            LocalDate.of(2026, 12, 15), LocalDate.of(2026, 12, 15));
        // 10 000 + 9 212 + 8 415 : chaque echeance porte l'interet de sa propre periode.
        assertThat(solde(decor.produitsInterets())).isEqualTo(xof("27627"));
    }

    // ------------------------------------------------------------------ recouvrement

    @Test
    @DisplayName("le prelevement automatique solde l'echeance et l'encours diminue alors seulement")
    void prelevementAutomatique() {
        Decor decor = decor("R1");
        product(decor, "CRED-R1", Map.of(LoanCatalog.P_DIRECT_DEBIT, "true"));
        UUID contrat = contract(decor, "REF-R1", "CRED-R1", "1000000");
        loanService.disburse(contrat, echeancier("1000000"), ACTOR, APPROVER);

        loanService.makeDue(decor.entityId(), PREMIERE_ECHEANCE, ACTOR, UUID.randomUUID());

        // 1 000 000 debloques moins l'echeance de 88 849.
        assertThat(solde(decor.courant())).isEqualTo(xof("911151"));
        assertThat(creances(contrat)).allSatisfy(
            creance -> assertThat(creance.outstanding().isZero()).isTrue());
        // L'encours ne diminue qu'ici, du capital effectivement rembourse.
        assertThat(solde(decor.pret())).isEqualTo(xof("921151"));
        assertThat(solde(decor.creances()).isZero()).isTrue();
    }

    @Test
    @DisplayName("un compte insuffisamment provisionne est preleve partiellement, dans l'ordre")
    void prelevementPartiel() {
        Decor decor = decor("R2");
        product(decor, "CRED-R2", Map.of(LoanCatalog.P_DIRECT_DEBIT, "true"));
        UUID contrat = contract(decor, "REF-R2", "CRED-R2", "1000000");
        loanService.disburse(contrat, echeancier("1000000"), ACTOR, APPROVER);
        // Le client retire presque tout : il ne reste que 30 000 pour une echeance de 88 849.
        retirer(decor, "970000", DEBLOCAGE, "ret-r2");

        loanService.makeDue(decor.entityId(), PREMIERE_ECHEANCE, ACTOR, UUID.randomUUID());

        // Prendre ce qui est la reduit la dette et arrete le vieillissement de la part payee.
        // L'ordre standard solde d'abord les interets, puis entame le capital.
        assertThat(solde(decor.courant()).isZero()).isTrue();
        var restant = creances(contrat);
        assertThat(restant).filteredOn(c -> c.category().equals("INTEREST"))
            .allSatisfy(c -> assertThat(c.outstanding().isZero()).isTrue());
        assertThat(restant).filteredOn(c -> c.category().equals("PRINCIPAL"))
            .allSatisfy(c -> assertThat(c.outstanding()).isEqualTo(xof("58849")));
    }

    @Test
    @DisplayName("l'ordre d'imputation du produit decide de ce qui reste du")
    void ordreDImputationParametre() {
        Decor decor = decor("R3");
        product(decor, "CRED-R3", Map.of(
            LoanCatalog.P_DIRECT_DEBIT, "true",
            LoanCatalog.P_ALLOCATION_ORDER,
            "PRINCIPAL,RECOVERY_FEES,PENALTIES,FEES_AND_INSURANCE,LATE_INTEREST,INTEREST,"
            + "FUTURE_PRINCIPAL"));
        UUID contrat = contract(decor, "REF-R3", "CRED-R3", "1000000");
        loanService.disburse(contrat, echeancier("1000000"), ACTOR, APPROVER);
        retirer(decor, "970000", DEBLOCAGE, "ret-r3");

        loanService.makeDue(decor.entityId(), PREMIERE_ECHEANCE, ACTOR, UUID.randomUUID());

        // Capital d'abord : les 30 000 vont au capital, et ce sont les interets qui restent dus.
        var restant = creances(contrat);
        assertThat(restant).filteredOn(c -> c.category().equals("PRINCIPAL"))
            .allSatisfy(c -> assertThat(c.outstanding()).isEqualTo(xof("48849")));
        assertThat(restant).filteredOn(c -> c.category().equals("INTEREST"))
            .allSatisfy(c -> assertThat(c.outstanding()).isEqualTo(xof("10000")));
    }

    @Test
    @DisplayName("un reglement manuel est ventile et sa ventilation est conservee")
    void reglementManuel() {
        Decor decor = decor("R4");
        product(decor, "CRED-R4", Map.of());
        UUID contrat = contract(decor, "REF-R4", "CRED-R4", "1000000");
        loanService.disburse(contrat, echeancier("1000000"), ACTOR, APPROVER);
        loanService.makeDue(decor.entityId(), PREMIERE_ECHEANCE, ACTOR, UUID.randomUUID());

        var reglement = loanService.settle(contrat, xof("50000"), PREMIERE_ECHEANCE, "MANUAL",
                                           IdempotencyKey.of("REG-R4-1"), ACTOR, null);

        assertThat(reglement.allocated()).isEqualTo(xof("50000"));
        assertThat(reglement.unallocated().isZero()).isTrue();
        assertThat(reglement.allocations()).hasSize(2);
        assertThat(ventilation(reglement.paymentId())).isEqualTo(2);
    }

    @Test
    @DisplayName("l'excedent d'un reglement est restitue, jamais consomme en silence")
    void excedent() {
        Decor decor = decor("R5");
        product(decor, "CRED-R5", Map.of());
        UUID contrat = contract(decor, "REF-R5", "CRED-R5", "1000000");
        loanService.disburse(contrat, echeancier("1000000"), ACTOR, APPROVER);
        loanService.makeDue(decor.entityId(), PREMIERE_ECHEANCE, ACTOR, UUID.randomUUID());

        var reglement = loanService.settle(contrat, xof("100000"), PREMIERE_ECHEANCE, "MANUAL",
                                           IdempotencyKey.of("REG-R5-1"), ACTOR, null);

        assertThat(reglement.allocated()).isEqualTo(xof("88849"));
        assertThat(reglement.unallocated()).isEqualTo(xof("11151"));
        // Rien n'est impute sur du capital non echu de sa propre initiative : le remboursement
        // anticipe est une decision de gestion, pas un residu d'arithmetique.
        assertThat(solde(decor.pret())).isEqualTo(xof("921151"));
    }

    // ------------------------------------------------------------------ retard

    @Test
    @DisplayName("regler une mensualite ne solde pas la plus ancienne echeance : l'ordre est par nature avant l'age")
    void ordreParNatureAvantAge() {
        Decor decor = decor("T1");
        product(decor, "CRED-T1", Map.of(LoanCatalog.P_GRACE_DAYS, "5"));
        UUID contrat = contract(decor, "REF-T1", "CRED-T1", "1000000");
        loanService.disburse(contrat, echeancier("1000000"), ACTOR, APPROVER);
        loanService.makeDue(decor.entityId(), LocalDate.of(2026, 11, 15), ACTOR, UUID.randomUUID());

        // Deux mensualites exigibles, le client en verse une seule.
        loanService.settle(contrat, xof("88849"), LocalDate.of(2026, 11, 15), "MANUAL",
                           IdempotencyKey.of("REG-T1-1"), ACTOR, null);

        // Consequence de l'ordre standard, et elle surprend : les interets des DEUX echeances sont
        // soldes avant que le capital de la premiere ne soit entame. Le client qui verse le montant
        // exact d'une mensualite ne solde donc aucune echeance, et reste en retard de l'age de la
        // plus ancienne. C'est le comportement voulu — les interets courent sur le capital, les
        // eteindre en premier limite la dette — mais il doit etre explicable au guichet.
        var restant = creances(contrat);
        assertThat(restant).filteredOn(c -> c.category().equals("INTEREST"))
            .allSatisfy(c -> assertThat(c.outstanding().isZero()).isTrue());
        assertThat(restant).filteredOn(c -> c.category().equals("PRINCIPAL")
                                            && c.dueDate().equals(LocalDate.of(2026, 10, 15)))
            .allSatisfy(c -> assertThat(c.outstanding()).isEqualTo(xof("9212")));
    }

    @Test
    @DisplayName("les jours de retard se comptent sur la creance la plus ancienne, delai de grace deduit")
    void joursDeRetard() {
        Decor decor = decor("T1b");
        product(decor, "CRED-T1B", Map.of(LoanCatalog.P_GRACE_DAYS, "5"));
        UUID contrat = contract(decor, "REF-T1B", "CRED-T1B", "1000000");
        loanService.disburse(contrat, echeancier("1000000"), ACTOR, APPROVER);
        loanService.makeDue(decor.entityId(), PREMIERE_ECHEANCE, ACTOR, UUID.randomUUID());

        // Dans le delai de grace, le credit n'est pas en retard.
        assertThat(loanService.daysPastDue(contrat, LocalDate.of(2026, 10, 19))).isZero();
        // Au-dela, le compteur part de la date d'echeance, grace deduite.
        assertThat(loanService.daysPastDue(contrat, LocalDate.of(2026, 10, 31))).isEqualTo(16 - 5);

        // Un reglement partiel n'arrete pas le compteur tant que la creance n'est pas soldee : le
        // retard se mesure a l'age de l'impaye, pas a la bonne volonte du debiteur.
        loanService.settle(contrat, xof("50000"), LocalDate.of(2026, 10, 31), "MANUAL",
                           IdempotencyKey.of("REG-T1B-1"), ACTOR, null);
        assertThat(loanService.daysPastDue(contrat, LocalDate.of(2026, 10, 31))).isEqualTo(11);

        // Soldee, la creance disparait du compteur.
        loanService.settle(contrat, xof("38849"), LocalDate.of(2026, 10, 31), "MANUAL",
                           IdempotencyKey.of("REG-T1B-2"), ACTOR, null);
        assertThat(loanService.daysPastDue(contrat, LocalDate.of(2026, 10, 31))).isZero();
    }

    @Test
    @DisplayName("un credit a jour n'a aucun jour de retard")
    void aucunRetard() {
        Decor decor = decor("T2");
        product(decor, "CRED-T2", Map.of(LoanCatalog.P_DIRECT_DEBIT, "true"));
        UUID contrat = contract(decor, "REF-T2", "CRED-T2", "1000000");
        loanService.disburse(contrat, echeancier("1000000"), ACTOR, APPROVER);
        loanService.makeDue(decor.entityId(), PREMIERE_ECHEANCE, ACTOR, UUID.randomUUID());

        assertThat(loanService.daysPastDue(contrat, LocalDate.of(2026, 12, 31))).isZero();
    }

    // ------------------------------------------------------------------ rechelonnement

    @Test
    @DisplayName("un rechelonnement publie une version, conserve l'ancienne et ne touche pas aux impayes")
    void rechelonnement() {
        Decor decor = decor("V1");
        product(decor, "CRED-V1", Map.of());
        UUID contrat = contract(decor, "REF-V1", "CRED-V1", "1000000");
        loanService.disburse(contrat, echeancier("1000000"), ACTOR, APPROVER);
        loanService.makeDue(decor.entityId(), PREMIERE_ECHEANCE, ACTOR, UUID.randomUUID());

        // Un rechelonnement porte sur le capital restant du, a compter de sa date d'effet :
        // 921 151 XOF etales sur vingt-quatre mensualites.
        AmortisationSchedule nouveau = ScheduleGenerator.generate(
            LoanTerms.of(xof("921151")).ratePercent("12").instalments(24)
                .disbursedOn(LocalDate.of(2026, 10, 15))
                .firstDueDate(LocalDate.of(2026, 11, 15)).build());
        loanService.reschedule(contrat, nouveau, LoanStore.ScheduleReason.RESCHEDULING,
                               LocalDate.of(2026, 11, 1), ACTOR, APPROVER);

        assertThat(versions(contrat)).isEqualTo(2);
        // L'impaye d'octobre reste du : le reprendre dans le nouveau plan effacerait un impaye
        // constate, et avec lui les jours de retard qui declassent le credit.
        assertThat(creances(contrat)).hasSize(2);

        // Le TFJ de novembre lit le nouveau plan : 24 mensualites, donc une echeance plus faible.
        loanService.makeDue(decor.entityId(), LocalDate.of(2026, 11, 15), ACTOR, UUID.randomUUID());
        assertThat(creances(contrat)).hasSize(4);
        assertThat(creances(contrat)).filteredOn(c -> c.dueDate().equals(LocalDate.of(2026, 11, 15)))
            .extracting(Creance::original)
            .containsExactlyInAnyOrder(xof("34150"), xof("9212"));
    }

    @Test
    @DisplayName("un plan de remplacement qui reprend des echeances deja exigibles est refuse")
    void rechelonnementRetroactif() {
        Decor decor = decor("V4");
        product(decor, "CRED-V4", Map.of());
        UUID contrat = contract(decor, "REF-V4", "CRED-V4", "1000000");
        loanService.disburse(contrat, echeancier("1000000"), ACTOR, APPROVER);
        loanService.makeDue(decor.entityId(), PREMIERE_ECHEANCE, ACTOR, UUID.randomUUID());

        // L'erreur naturelle : regenerer un plan complet depuis l'origine. Les echeances deja
        // rendues exigibles y figurent, et seraient reclamees une seconde fois.
        AmortisationSchedule depuisLOrigine = ScheduleGenerator.generate(
            LoanTerms.of(xof("1000000")).ratePercent("12").instalments(24)
                .disbursedOn(DEBLOCAGE).firstDueDate(PREMIERE_ECHEANCE).build());

        assertThatThrownBy(() -> loanService.reschedule(
            contrat, depuisLOrigine, LoanStore.ScheduleReason.RESCHEDULING,
            LocalDate.of(2026, 11, 1), ACTOR, APPROVER))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("les reclamerait deux fois");
    }

    @Test
    @DisplayName("deux echeanciers en vigueur a la meme date sont refuses par la base")
    void deuxEcheanciersEnVigueur() {
        Decor decor = decor("V2");
        product(decor, "CRED-V2", Map.of());
        UUID contrat = contract(decor, "REF-V2", "CRED-V2", "1000000");
        loanService.disburse(contrat, echeancier("1000000"), ACTOR, APPROVER);

        assertThatThrownBy(() -> database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "INSERT INTO loan_schedule(id, contract_id, version, reason, effective_from,"
                + " created_by, approved_by) VALUES (?,?,99,'RESCHEDULING',?,?,?)")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, contrat);
                ps.setObject(3, DEBLOCAGE);
                ps.setObject(4, ACTOR);
                ps.setObject(5, APPROVER);
                ps.executeUpdate();
                return null;
            } catch (SQLException e) {
                throw new LedgerStoreException("insertion", e);
            }
        })).hasStackTraceContaining("ex_schedule_no_overlap");
    }

    @Test
    @DisplayName("un rechelonnement ne peut pas etre valide par celui qui le saisit")
    void rechelonnementSansSeparationDesTaches() {
        Decor decor = decor("V3");
        product(decor, "CRED-V3", Map.of());
        UUID contrat = contract(decor, "REF-V3", "CRED-V3", "1000000");

        assertThatThrownBy(() ->
            loanService.disburse(contrat, echeancier("1000000"), ACTOR, ACTOR))
            .hasStackTraceContaining("ck_schedule_approval");
    }

    // ------------------------------------------------------------------ immuabilite

    @Test
    @DisplayName("une creance ne remonte jamais")
    void creanceNeRemontePas() {
        Decor decor = decor("I1");
        product(decor, "CRED-I1", Map.of());
        UUID contrat = contract(decor, "REF-I1", "CRED-I1", "1000000");
        loanService.disburse(contrat, echeancier("1000000"), ACTOR, APPROVER);
        loanService.makeDue(decor.entityId(), PREMIERE_ECHEANCE, ACTOR, UUID.randomUUID());

        // Une creance qui remonterait ferait repartir a zero le compteur de jours de retard, et
        // avec lui le declassement et le provisionnement.
        assertThatThrownBy(() -> database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "UPDATE loan_receivable SET outstanding = outstanding + 1 WHERE contract_id = ?")) {
                ps.setObject(1, contrat);
                ps.executeUpdate();
                return null;
            } catch (SQLException e) {
                throw new LedgerStoreException("mise a jour", e);
            }
        })).hasStackTraceContaining("une creance ne remonte pas");
    }

    @Test
    @DisplayName("un reglement et sa ventilation sont immuables")
    void reglementImmuable() {
        Decor decor = decor("I2");
        product(decor, "CRED-I2", Map.of());
        UUID contrat = contract(decor, "REF-I2", "CRED-I2", "1000000");
        loanService.disburse(contrat, echeancier("1000000"), ACTOR, APPROVER);
        loanService.makeDue(decor.entityId(), PREMIERE_ECHEANCE, ACTOR, UUID.randomUUID());
        loanService.settle(contrat, xof("50000"), PREMIERE_ECHEANCE, "MANUAL",
                           IdempotencyKey.of("REG-I2-1"), ACTOR, null);

        assertThatThrownBy(() -> database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "UPDATE loan_payment SET amount = 1 WHERE contract_id = ?")) {
                ps.setObject(1, contrat);
                ps.executeUpdate();
                return null;
            } catch (SQLException e) {
                throw new LedgerStoreException("mise a jour", e);
            }
        })).hasStackTraceContaining("immuables");
    }

    @Test
    @DisplayName("l'annulation d'un traitement rend les echeances a nouveau exigibles")
    void annulationDuTraitement() {
        Decor decor = decor("A1");
        product(decor, "CRED-A1", Map.of());
        UUID contrat = contract(decor, "REF-A1", "CRED-A1", "1000000");
        loanService.disburse(contrat, echeancier("1000000"), ACTOR, APPROVER);
        UUID run = UUID.randomUUID();
        loanService.makeDue(decor.entityId(), PREMIERE_ECHEANCE, ACTOR, run);

        database.inTransaction(c -> LoanStore.cancelRun(c, run));

        // Sans cette reouverture, l'ecriture serait contre-passee et l'echeance jamais reclamee :
        // le client n'aurait plus rien a payer pour octobre, sans qu'aucun ecart n'apparaisse.
        assertThat(creances(contrat)).allSatisfy(c -> assertThat(c.cancelled()).isTrue());
        var second = loanService.makeDue(decor.entityId(), PREMIERE_ECHEANCE, ACTOR,
                                         UUID.randomUUID());
        assertThat(second.anomalies()).isEmpty();
        assertThat(second.instalmentsMadeDue()).isEqualTo(1);
        assertThat(creances(contrat)).filteredOn(c -> !c.cancelled()).hasSize(2);
    }

    // ------------------------------------------------------------------ outillage

    private static void retirer(Decor decor, String montant, LocalDate valueDate, String key) {
        postingService.post(io.corebanking.ledger.domain.posting.PostingCommand.online(
            IdempotencyKey.of(key), decor.entityId(), valueDate, "WITHDRAWAL", ACTOR,
            java.util.List.of(
                io.corebanking.ledger.domain.posting.PostingLine.debit(
                    decor.courant().id(), xof(montant), valueDate, null),
                io.corebanking.ledger.domain.posting.PostingLine.credit(
                    decor.caisse().id(), xof(montant), valueDate, null))));
    }

    private static int versions(UUID contractId) {
        return database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "SELECT count(*) FROM loan_schedule WHERE contract_id = ?")) {
                ps.setObject(1, contractId);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Comptage des versions", e);
            }
        });
    }

    private static int ventilation(UUID paymentId) {
        return database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "SELECT count(*) FROM loan_payment_allocation WHERE payment_id = ?")) {
                ps.setObject(1, paymentId);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Comptage de la ventilation", e);
            }
        });
    }
}
