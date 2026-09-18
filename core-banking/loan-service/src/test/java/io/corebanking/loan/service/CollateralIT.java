package io.corebanking.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.store.Balances;
import io.corebanking.loan.CollateralPolicy;
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

/** Les suretes vues depuis le provisionnement : c'est la que leur valorisation se lit. */
class CollateralIT extends LoanTestBase {

    private static final LocalDate ARRETE = LocalDate.of(2027, 1, 13);

    private static LoanClassificationService classification;

    private static LoanClassificationService service() {
        if (classification == null) {
            classification = new LoanClassificationService(database, postingService, loanService);
        }
        return classification;
    }

    private record Dossier(UUID entite, UUID contrat, Account provisions) {}

    /** Credit de 1 000 000, impaye de quatre-vingt-dix jours, classe a 20 % de provision. */
    private static Dossier dossier(String code) {
        Decor decor = decor(code);
        Account dotations = account(decor.entityId(), code + "-DOT", AccountKind.GL,
                                    NormalBalance.DEBIT);
        Account provisions = account(decor.entityId(), code + "-PROV", AccountKind.GL,
                                     NormalBalance.CREDIT);
        Account reserves = account(decor.entityId(), code + "-RES", AccountKind.GL,
                                   NormalBalance.CREDIT);
        database.inTransaction(c -> {
            UUID profil = RiskProfiles.createDraft(c, new RiskProfiles.Draft(
                decor.entityId(), "Grille", DEBLOCAGE.minusMonths(1), null,
                new RiskGrid("GRILLE", List.of(
                    new RiskBucket(0, "SAIN", "Sain", 0, 89, BigDecimal.ZERO, true),
                    new RiskBucket(1, "DOUTEUX", "Douteux", 90, null, new BigDecimal("20"), false)),
                    Contagion.NONE, null),
                ACTOR));
            RiskProfiles.activate(c, profil, APPROVER);
            return null;
        });

        Map<String, String> parametres = new LinkedHashMap<>();
        parametres.put(LoanCatalog.P_RISK_PROFILE, "GRILLE");
        parametres.put(LoanCatalog.P_PROVISION_EXPENSE, dotations.id().toString());
        parametres.put(LoanCatalog.P_PROVISION_ALLOWANCE, provisions.id().toString());
        parametres.put(LoanCatalog.P_RESERVED_INTEREST, reserves.id().toString());
        product(decor, "CRED-" + code, parametres);

        UUID contrat = contract(decor, "REF-" + code, "CRED-" + code, "1000000");
        loanService.disburse(contrat, ScheduleGenerator.generate(
            LoanTerms.of(xof("1000000")).ratePercent("12").instalments(12)
                .disbursedOn(DEBLOCAGE).firstDueDate(PREMIERE_ECHEANCE).build()), ACTOR, APPROVER);
        loanService.makeDue(decor.entityId(), PREMIERE_ECHEANCE, ACTOR, UUID.randomUUID());
        return new Dossier(decor.entityId(), contrat, provisions);
    }

    private static void regime(UUID entite, String type, String quotite, int ancienneteMax) {
        database.inTransaction(c -> {
            UUID regime = Collaterals.createPolicy(c, new Collaterals.PolicyDraft(
                entite, new CollateralPolicy(type, type, new BigDecimal(quotite), ancienneteMax),
                DEBLOCAGE.minusYears(1), null, ACTOR));
            Collaterals.activatePolicy(c, regime, APPROVER);
            return null;
        });
    }

    private static UUID surete(UUID entite, String actif, String type, String valeur,
                               String garanti, int rang, LocalDate expertise) {
        return database.inTransaction(c -> Collaterals.register(c, new Collaterals.Draft(
            entite, null, actif, type, type + " " + actif, xof(valeur), xof(garanti), rang,
            expertise, ACTOR, APPROVER)));
    }

    // ------------------------------------------------------------------ valorisation

    @Test
    @DisplayName("une surete eligible reduit la provision a hauteur de sa quotite")
    void sureteEligible() {
        Dossier dossier = dossier("G1");
        regime(dossier.entite(), "HYPOTHEQUE", "50", 36);
        UUID hypotheque = surete(dossier.entite(), "IMM-G1", "HYPOTHEQUE", "2000000", "600000", 1,
                                 DEBLOCAGE);
        database.inTransaction(c -> {
            Collaterals.allocate(c, hypotheque, dossier.contrat(), new BigDecimal("100"));
            return null;
        });

        service().classify(dossier.entite(), ARRETE, ACTOR, UUID.randomUUID());

        // 600 000 garantis a 50 % : 300 000 retenus. Assiette 1 010 000 - 300 000 = 710 000,
        // provisionnee a 20 % = 142 000.
        assertThat(soldeDe(dossier.provisions())).isEqualTo(xof("142000"));
    }

    @Test
    @DisplayName("une expertise perimee ne couvre rien, et l'anomalie remonte")
    void expertisePerimee() {
        Dossier dossier = dossier("G2");
        regime(dossier.entite(), "HYPOTHEQUE", "50", 12);
        UUID hypotheque = surete(dossier.entite(), "IMM-G2", "HYPOTHEQUE", "2000000", "600000", 1,
                                 LocalDate.of(2024, 1, 1));
        database.inTransaction(c -> {
            Collaterals.allocate(c, hypotheque, dossier.contrat(), new BigDecimal("100"));
            return null;
        });

        var bilan = service().classify(dossier.entite(), ARRETE, ACTOR, UUID.randomUUID());

        // Provision pleine : une valeur d'il y a trois ans n'est pas une valeur. Et l'exclusion
        // est signalee — croire couvrir un encours qu'on ne couvre pas ne se decouvre qu'a la
        // realisation.
        assertThat(soldeDe(dossier.provisions())).isEqualTo(xof("202000"));
        assertThat(bilan.anomalies()).hasSize(1);
        assertThat(bilan.anomalies().get(0)).contains("perimee");
    }

    @Test
    @DisplayName("un type de surete sans regime est ecarte, jamais retenu a cent pour cent")
    void typeSansRegime() {
        Dossier dossier = dossier("G3");
        UUID gage = surete(dossier.entite(), "STK-G3", "GAGE_SUR_STOCK", "5000000", "5000000", 1,
                           DEBLOCAGE);
        database.inTransaction(c -> {
            Collaterals.allocate(c, gage, dossier.contrat(), new BigDecimal("100"));
            return null;
        });

        var bilan = service().classify(dossier.entite(), ARRETE, ACTOR, UUID.randomUUID());

        assertThat(soldeDe(dossier.provisions())).isEqualTo(xof("202000"));
        assertThat(bilan.anomalies().get(0)).contains("aucune quotite parametree");
    }

    @Test
    @DisplayName("un second rang n'est couvert que par ce que le premier laisse, meme au profit d'un tiers")
    void secondRang() {
        Dossier dossier = dossier("G4");
        regime(dossier.entite(), "HYPOTHEQUE", "50", 36);
        // Premier rang de 1 800 000 au profit d'une autre banque : il ne reste que 200 000 des
        // 2 000 000 de l'immeuble.
        surete(dossier.entite(), "IMM-G4", "HYPOTHEQUE", "2000000", "1800000", 1, DEBLOCAGE);
        UUID second = surete(dossier.entite(), "IMM-G4", "HYPOTHEQUE", "2000000", "600000", 2,
                             DEBLOCAGE);
        database.inTransaction(c -> {
            Collaterals.allocate(c, second, dossier.contrat(), new BigDecimal("100"));
            return null;
        });

        service().classify(dossier.entite(), ARRETE, ACTOR, UUID.randomUUID());

        // 200 000 disponibles, retenus a 50 % : 100 000. Ignorer le rang aurait retenu 300 000 et
        // sous-provisionne de 40 000.
        assertThat(soldeDe(dossier.provisions())).isEqualTo(xof("182000"));
    }

    @Test
    @DisplayName("une meme surete affectee a deux credits n'est comptee qu'a sa quote-part")
    void suretePartagee() {
        Dossier premier = dossier("G5");
        // Second credit du meme client, garanti par la meme hypotheque.
        UUID second = contract(decorDe(premier.entite()), "REF-G5B", "CRED-G5", "1000000");

        regime(premier.entite(), "HYPOTHEQUE", "50", 36);
        UUID hypotheque = surete(premier.entite(), "IMM-G5", "HYPOTHEQUE", "4000000", "1200000", 1,
                                 DEBLOCAGE);
        database.inTransaction(c -> {
            Collaterals.allocate(c, hypotheque, premier.contrat(), new BigDecimal("50"));
            Collaterals.allocate(c, hypotheque, second, new BigDecimal("50"));
            return null;
        });

        service().classify(premier.entite(), ARRETE, ACTOR, UUID.randomUUID());

        // 1 200 000 garantis, 50 % affectes a ce credit, quotite 50 % : 300 000. La compter en
        // entier sur chacun des deux credits diviserait la provision du client par deux.
        assertThat(soldeDe(premier.provisions())).isEqualTo(xof("142000"));
    }

    @Test
    @DisplayName("une mainlevee libere le rang et retire la couverture")
    void mainlevee() {
        Dossier dossier = dossier("G6");
        regime(dossier.entite(), "HYPOTHEQUE", "50", 36);
        UUID hypotheque = surete(dossier.entite(), "IMM-G6", "HYPOTHEQUE", "2000000", "600000", 1,
                                 DEBLOCAGE);
        database.inTransaction(c -> {
            Collaterals.allocate(c, hypotheque, dossier.contrat(), new BigDecimal("100"));
            return null;
        });
        service().classify(dossier.entite(), ARRETE, ACTOR, UUID.randomUUID());
        assertThat(soldeDe(dossier.provisions())).isEqualTo(xof("142000"));

        database.inTransaction(c -> {
            Collaterals.release(c, hypotheque, ARRETE);
            return null;
        });
        service().classify(dossier.entite(), ARRETE.plusDays(1), ACTOR, UUID.randomUUID());

        // La provision remonte au niveau non couvert : la surete est marquee, pas supprimee —
        // l'historique des rangs est une piece du dossier.
        assertThat(soldeDe(dossier.provisions())).isEqualTo(xof("202000"));
    }

    // ------------------------------------------------------------------ refus

    @Test
    @DisplayName("affecter plus de cent pour cent d'une surete est refuse par la base")
    void quotePartsExcessives() {
        Dossier dossier = dossier("G7");
        regime(dossier.entite(), "HYPOTHEQUE", "50", 36);
        UUID hypotheque = surete(dossier.entite(), "IMM-G7", "HYPOTHEQUE", "2000000", "600000", 1,
                                 DEBLOCAGE);
        UUID autre = contract(decorDe(dossier.entite()), "REF-G7B", "CRED-G7", "1000000");

        assertThatThrownBy(() -> database.inTransaction(c -> {
            Collaterals.allocate(c, hypotheque, dossier.contrat(), new BigDecimal("70"));
            Collaterals.allocate(c, hypotheque, autre, new BigDecimal("70"));
            return null;
        })).hasStackTraceContaining("ne garantit pas plus qu'elle-meme");
    }

    @Test
    @DisplayName("deux suretes de meme rang sur le meme actif sont refusees")
    void rangsEnDoublon() {
        Dossier dossier = dossier("G8");
        regime(dossier.entite(), "HYPOTHEQUE", "50", 36);
        surete(dossier.entite(), "IMM-G8", "HYPOTHEQUE", "2000000", "600000", 1, DEBLOCAGE);

        // Deux suretes de meme rang rendraient l'absorption dependante de l'ordre de lecture : la
        // couverture ne serait pas reproductible d'un arrete a l'autre.
        assertThatThrownBy(() ->
            surete(dossier.entite(), "IMM-G8", "HYPOTHEQUE", "2000000", "400000", 1, DEBLOCAGE))
            .hasStackTraceContaining("uq_collateral_rank");
    }

    @Test
    @DisplayName("un regime de surete ne peut pas etre valide par celui qui le saisit")
    void regimeSansSeparationDesTaches() {
        Dossier dossier = dossier("G9");

        assertThatThrownBy(() -> database.inTransaction(c -> {
            UUID regime = Collaterals.createPolicy(c, new Collaterals.PolicyDraft(
                dossier.entite(), new CollateralPolicy("NANTISSEMENT", "Nantissement",
                                                       new BigDecimal("80"), 0),
                DEBLOCAGE, null, ACTOR));
            Collaterals.activatePolicy(c, regime, ACTOR);
            return null;
        })).hasStackTraceContaining("ck_collateral_policy_approval");
    }

    // ------------------------------------------------------------------ outillage

    private static Decor decorDe(UUID entite) {
        return new Decor(entite,
            account(entite, "SEC-PRET-" + entite.toString().substring(0, 8), AccountKind.CUSTOMER,
                    NormalBalance.DEBIT),
            account(entite, "SEC-CC-" + entite.toString().substring(0, 8), AccountKind.CUSTOMER,
                    NormalBalance.CREDIT),
            account(entite, "SEC-CRE-" + entite.toString().substring(0, 8), AccountKind.GL,
                    NormalBalance.DEBIT),
            account(entite, "SEC-PRD-" + entite.toString().substring(0, 8), AccountKind.GL,
                    NormalBalance.CREDIT),
            account(entite, "SEC-TAX-" + entite.toString().substring(0, 8), AccountKind.GL,
                    NormalBalance.CREDIT),
            account(entite, "SEC-CSH-" + entite.toString().substring(0, 8), AccountKind.GL,
                    NormalBalance.DEBIT),
            account(entite, "SEC-ICNE-" + entite.toString().substring(0, 8), AccountKind.GL,
                    NormalBalance.DEBIT));
    }

    private static Money soldeDe(Account compte) {
        return database.inTransaction(c -> Balances.current(c, compte.id()));
    }
}
