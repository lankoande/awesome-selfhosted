package io.corebanking.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountNature;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.loan.Contagion;
import io.corebanking.loan.LoanTerms;
import io.corebanking.loan.RiskBucket;
import io.corebanking.loan.RiskGrid;
import io.corebanking.loan.ScheduleGenerator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La fin de vie d'un credit : ce qui sort de l'actif, ce qui l'absorbe, ce qui reste du — et ce
 * qu'un encaissement devient quand il n'y a plus de creance a diminuer.
 */
class LoanWriteOffIT extends LoanTestBase {

    private LoanWriteOffService writeOffs() {
        return new LoanWriteOffService(database, postingService);
    }

    @Test
    @DisplayName("la provision et les interets reserves absorbent la sortie avant la perte : passer en perte un interet reserve ne coute rien au resultat")
    void the_provision_and_the_reserved_interest_absorb_the_loss() {
        Perte decor = perte("WO1");
        UUID contrat = credit(decor, "REF-WO1", "CRED-WO1");

        // Deux echeances impayees, le credit classe et provisionne, les interets suspendus.
        loanService.makeDue(decor.entityId(), PREMIERE_ECHEANCE, ACTOR, UUID.randomUUID());
        LocalDate tresTard = PREMIERE_ECHEANCE.plusMonths(7);                   // au-dela de 180 j
        classification().classify(decor.entityId(), tresTard, ACTOR, UUID.randomUUID());

        Money capital = solde(decor.pret());
        Money provisionAvant = solde(decor.provisions());
        Money reservesAvant = solde(decor.reserves());
        assertThat(provisionAvant.isPositive()).as("classe compromise : provision constituee").isTrue();
        assertThat(reservesAvant.isPositive()).as("interets sortis du resultat a la suspension").isTrue();

        LoanWriteOffService.WriteOff perte = writeOffs().writeOff(
            contrat, tresTard, "creance compromise depuis plus de six mois, recours epuises",
            ACTOR, APPROVER);

        // Ce qui sort est absorbe, puis constate en perte — et l'egalite est portee par la base.
        assertThat(perte.principalWritten()).isEqualTo(capital);
        assertThat(perte.reservedUsed()).as("les interets reserves viennent en premier")
            .isEqualTo(reservesAvant);
        assertThat(perte.principalWritten().plus(perte.receivablesWritten())
                       .plus(perte.provisionReleased()))
            .isEqualTo(perte.provisionUsed().plus(perte.reservedUsed())
                           .plus(perte.lossRecognised()));

        // Le bilan : plus d'encours, plus de creances, provision et reserves consommees.
        assertThat(solde(decor.pret())).isEqualTo(xof("0"));
        assertThat(solde(decor.creances())).isEqualTo(xof("0"));
        assertThat(solde(decor.reserves())).isEqualTo(reservesAvant.minus(perte.reservedUsed()));
        assertThat(solde(decor.provisions()))
            .isEqualTo(provisionAvant.minus(perte.provisionUsed()));
        assertThat(solde(decor.perteCompte())).isEqualTo(perte.lossRecognised());

        // La creance n'est pas eteinte : elle est au hors bilan, pour son montant entier.
        Money engagement = perte.principalWritten().plus(perte.receivablesWritten());
        assertThat(solde(decor.horsBilan())).isEqualTo(engagement);
        assertThat(perte.outstanding()).isEqualTo(engagement);

        // Le contrat est sorti de l'actif : plus rien n'y devient exigible.
        assertThat(database.inTransaction(c -> LoanStore.requireContract(c, contrat)).status())
            .isEqualTo(LoanContract.Status.WRITTEN_OFF);
        assertThat(creances(contrat)).allMatch(Creance::cancelled);
        assertThatThrownBy(() -> writeOffs().writeOff(contrat, tresTard, "encore", ACTOR, APPROVER))
            .isInstanceOf(LoanWriteOffService.WriteOffRefusedException.class)
            .hasMessageContaining("WRITTEN_OFF");
    }

    @Test
    @DisplayName("ce qui est encaisse apres la perte est un produit de recuperation, jamais un remboursement, et il sort du hors bilan d'autant")
    void what_comes_back_is_income_not_a_repayment() {
        Perte decor = perte("WO2");
        UUID contrat = credit(decor, "REF-WO2", "CRED-WO2");
        loanService.makeDue(decor.entityId(), PREMIERE_ECHEANCE, ACTOR, UUID.randomUUID());
        LocalDate tresTard = PREMIERE_ECHEANCE.plusMonths(7);
        classification().classify(decor.entityId(), tresTard, ACTOR, UUID.randomUUID());
        LoanWriteOffService.WriteOff perte = writeOffs().writeOff(
            contrat, tresTard, "recours epuises", ACTOR, APPROVER);
        Money engagement = perte.outstanding();

        // Le client verse 200 000 deux ans plus tard, au guichet.
        alimenter(decor.decor(), "200000", tresTard, "wo2-caisse");
        LoanWriteOffService.Recovery recouvre = writeOffs().recover(
            perte.id(), xof("200000"), decor.courant().id(), tresTard,
            IdempotencyKey.of("REC-WO2-1"), ACTOR);
        assertThat(recouvre.amount()).isEqualTo(xof("200000"));

        assertThat(solde(decor.recuperations())).as("un produit, pas un remboursement")
            .isEqualTo(xof("200000"));
        assertThat(solde(decor.pret())).as("le capital ne reapparait pas").isEqualTo(xof("0"));
        assertThat(solde(decor.horsBilan())).isEqualTo(engagement.minus(xof("200000")));

        LoanWriteOffService.WriteOff apres = database.inTransaction(
            c -> LoanWriteOffService.require(c, perte.id()));
        assertThat(apres.recovered()).isEqualTo(xof("200000"));
        assertThat(apres.outstanding()).isEqualTo(engagement.minus(xof("200000")));

        // On ne recouvre pas plus que ce qui a ete passe en perte.
        assertThatThrownBy(() -> writeOffs().recover(perte.id(), engagement, decor.courant().id(),
                                                     tresTard, IdempotencyKey.of("REC-WO2-2"),
                                                     ACTOR))
            .isInstanceOf(LoanWriteOffService.WriteOffRefusedException.class)
            .hasMessageContaining("pas plus que ce qui a ete passe en perte");

        // Le hors bilan de l'entite vaut ce que le suivi dit : c'est ce que la nuit rapprochera.
        Money attendu = database.inTransaction(
            c -> LoanWriteOffService.outstandingWrittenOff(c, decor.entityId(), Currencies.XOF));
        assertThat(attendu).isEqualTo(solde(decor.horsBilan()));
    }

    @Test
    @DisplayName("un credit sain ne se passe pas en perte, et une revision de taux publie un nouvel echeancier sur le capital restant du sans refaire le passe")
    void a_rate_revision_reprices_what_is_left() {
        Perte decor = perte("WO3");
        UUID contrat = credit(decor, "REF-WO3", "CRED-WO3");
        loanService.makeDue(decor.entityId(), PREMIERE_ECHEANCE, ACTOR, UUID.randomUUID());

        // La revision se decide a deux.
        assertThatThrownBy(() -> loanService.reviseRate(contrat, new BigDecimal("15"),
                                                        PREMIERE_ECHEANCE, ACTOR, ACTOR))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a deux");

        Money capital = solde(decor.pret());
        UUID revise = loanService.reviseRate(contrat, new BigDecimal("15"), PREMIERE_ECHEANCE,
                                             ACTOR, APPROVER);
        assertThat(revise).isNotNull();
        assertThat(solde(decor.pret())).as("une revision ne touche pas au capital")
            .isEqualTo(capital);

        List<Echeance> plan = echeances(contrat);
        assertThat(plan).as("les onze echeances a venir, recalculees").hasSize(11);
        assertThat(plan.get(0).dueDate()).isEqualTo(PREMIERE_ECHEANCE.plusMonths(1));
        BigDecimal taux = database.inTransaction(
            c -> LoanStore.requireContract(c, contrat).terms().annualRatePercent());
        assertThat(taux).isEqualByComparingTo("15");

        // Et un credit passe en perte ne se revise plus.
        LocalDate tresTard = PREMIERE_ECHEANCE.plusMonths(7);
        classification().classify(decor.entityId(), tresTard, ACTOR, UUID.randomUUID());
        writeOffs().writeOff(contrat, tresTard, "recours epuises", ACTOR, APPROVER);
        assertThatThrownBy(() -> loanService.reviseRate(contrat, new BigDecimal("9"), tresTard,
                                                        ACTOR, APPROVER))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("WRITTEN_OFF");
    }

    // ------------------------------------------------------------------ decor

    /** Decor de credit augmente des comptes de provision, de perte et de hors bilan. */
    private record Perte(Decor decor, Account dotations, Account provisions, Account reserves,
                         Account perteCompte, Account recuperations, Account horsBilan,
                         Account horsBilanContrepartie) {
        UUID entityId() {
            return decor.entityId();
        }

        Account pret() {
            return decor.pret();
        }

        Account courant() {
            return decor.courant();
        }

        Account creances() {
            return decor.creances();
        }
    }

    private static LoanClassificationService classification() {
        return new LoanClassificationService(database, postingService, loanService);
    }

    private static Perte perte(String code) {
        Decor decor = decor(code);
        Account dotations = account(decor.entityId(), code + "-DOT", AccountKind.GL,
                                    NormalBalance.DEBIT);
        Account provisions = account(decor.entityId(), code + "-PROV", AccountKind.GL,
                                     NormalBalance.CREDIT);
        Account reserves = account(decor.entityId(), code + "-RESERVES", AccountKind.GL,
                                   NormalBalance.CREDIT);
        Account perte = account(decor.entityId(), code + "-PERTE", AccountKind.GL,
                                NormalBalance.DEBIT);
        Account recuperations = account(decor.entityId(), code + "-RECUP", AccountKind.GL,
                                        NormalBalance.CREDIT);
        Account horsBilan = horsBilan(decor.entityId(), code + "-HB", NormalBalance.DEBIT);
        Account contrepartie = horsBilan(decor.entityId(), code + "-HB-CTP", NormalBalance.CREDIT);
        database.inTransaction(c -> {
            UUID profil = RiskProfiles.createDraft(c, new RiskProfiles.Draft(
                decor.entityId(), "Grille de test", DEBLOCAGE.minusMonths(1), null,
                new RiskGrid("GRILLE", List.of(
                    new RiskBucket(0, "SAIN", "SAIN", 0, 29, new BigDecimal("0"), true),
                    new RiskBucket(1, "IMPAYE", "IMPAYE", 30, 89, new BigDecimal("0"), true),
                    new RiskBucket(2, "DOUTEUX", "DOUTEUX", 90, 179, new BigDecimal("20"), false),
                    new RiskBucket(3, "COMPROMIS", "COMPROMIS", 180, null, new BigDecimal("80"),
                                   false)),
                    Contagion.NONE, "DOUTEUX", 0), ACTOR));
            RiskProfiles.activate(c, profil, APPROVER);
            return null;
        });
        return new Perte(decor, dotations, provisions, reserves, perte, recuperations, horsBilan,
                         contrepartie);
    }

    private static UUID credit(Perte decor, String reference, String produitCode) {
        Map<String, String> parametres = new LinkedHashMap<>();
        parametres.put(LoanCatalog.P_RISK_PROFILE, "GRILLE");
        parametres.put(LoanCatalog.P_PROVISION_EXPENSE, decor.dotations().id().toString());
        parametres.put(LoanCatalog.P_PROVISION_ALLOWANCE, decor.provisions().id().toString());
        parametres.put(LoanCatalog.P_RESERVED_INTEREST, decor.reserves().id().toString());
        parametres.put(LoanCatalog.P_WRITE_OFF_LOSS, decor.perteCompte().id().toString());
        parametres.put(LoanCatalog.P_RECOVERY_INCOME, decor.recuperations().id().toString());
        parametres.put(LoanCatalog.P_WRITTEN_OFF, decor.horsBilan().id().toString());
        parametres.put(LoanCatalog.P_WRITTEN_OFF_COUNTERPART,
                       decor.horsBilanContrepartie().id().toString());
        product(decor.decor(), produitCode, parametres);
        UUID contrat = contract(decor.decor(), reference, produitCode, "1000000");
        loanService.disburse(contrat, ScheduleGenerator.generate(
            LoanTerms.of(xof("1000000")).ratePercent("12").instalments(12)
                .disbursedOn(DEBLOCAGE).firstDueDate(PREMIERE_ECHEANCE).build()), ACTOR, APPROVER);
        return contrat;
    }

    /** Un compte de hors bilan : l'engagement y vit, hors du bilan et hors du resultat. */
    private static Account horsBilan(UUID entityId, String code, NormalBalance sens) {
        Account compte = new Account(UUID.randomUUID(), entityId, code, AccountKind.GL, sens,
                                     Currencies.XOF, true, false, 1,
                                     io.corebanking.ledger.domain.account.AccountStatus.ACTIVE,
                                     null, AccountNature.OFF_BALANCE_SHEET);
        database.inTransaction(c -> {
            io.corebanking.ledger.store.Accounts.create(c, compte, DEBLOCAGE.minusMonths(1));
            return null;
        });
        return compte;
    }

    private record Echeance(int number, LocalDate dueDate) {}

    private static List<Echeance> echeances(UUID contractId) {
        return database.inTransaction(c -> {
            List<Echeance> found = new java.util.ArrayList<>();
            try (var ps = c.prepareStatement(
                "SELECT l.number, l.due_date FROM loan_schedule_line l"
                + "  JOIN loan_schedule s ON s.id = l.schedule_id"
                + " WHERE s.contract_id = ? AND s.superseded_on IS NULL ORDER BY l.number")) {
                ps.setObject(1, contractId);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        found.add(new Echeance(rs.getInt(1), rs.getObject(2, LocalDate.class)));
                    }
                }
            } catch (java.sql.SQLException e) {
                throw new io.corebanking.ledger.store.LedgerStoreException("Lecture du plan", e);
            }
            return found;
        });
    }
}
