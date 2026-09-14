package io.corebanking.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.store.Balances;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.loan.Contagion;
import io.corebanking.loan.RiskBucket;
import io.corebanking.loan.RiskGrid;
import io.corebanking.loan.LoanTerms;
import io.corebanking.loan.ScheduleGenerator;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LoanClassificationIT extends LoanTestBase {

    private static LoanClassificationService classification;

    private static LoanClassificationService service() {
        if (classification == null) {
            classification = new LoanClassificationService(database, postingService, loanService);
        }
        return classification;
    }

    private static RiskBucket classe(int rang, String code, int de, Integer a, String taux,
                                     boolean sain) {
        return new RiskBucket(rang, code, code, de, a, new BigDecimal(taux), sain);
    }

    /** Grille illustrative : les seuils et taux reels relevent de l'instruction en vigueur. */
    private static RiskGrid grille(Contagion contagion, String suspendA) {
        return grille(contagion, suspendA, 0);
    }

    private static RiskGrid grille(Contagion contagion, String suspendA, int observation) {
        return new RiskGrid("GRILLE", List.of(
            classe(0, "SAIN", 0, 29, "0", true),
            classe(1, "IMPAYE", 30, 89, "0", true),
            classe(2, "DOUTEUX", 90, 179, "20", false),
            classe(3, "COMPROMIS", 180, null, "100", false)), contagion, suspendA, observation);
    }

    /** Decor de credit augmente des comptes de provision et d'interets reserves. */
    private record Risque(Decor decor, Account dotations, Account provisions, Account reserves,
                          Account produitsRetard) {}

    private static Risque decorRisque(String code, Contagion contagion, String suspendA) {
        return decorRisque(code, contagion, suspendA, 0);
    }

    private static Risque decorRisque(String code, Contagion contagion, String suspendA,
                                      int observation) {
        Decor decor = decor(code);
        Account dotations = account(decor.entityId(), code + "-DOT", AccountKind.GL,
                                    NormalBalance.DEBIT);
        Account provisions = account(decor.entityId(), code + "-PROV", AccountKind.GL,
                                     NormalBalance.CREDIT);
        Account reserves = account(decor.entityId(), code + "-RESERVES", AccountKind.GL,
                                   NormalBalance.CREDIT);
        Account produitsRetard = account(decor.entityId(), code + "-RETARD", AccountKind.GL,
                                         NormalBalance.CREDIT);
        database.inTransaction(c -> {
            UUID profil = RiskProfiles.createDraft(c, new RiskProfiles.Draft(
                decor.entityId(), "Grille de test", DEBLOCAGE.minusMonths(1), null,
                grille(contagion, suspendA, observation), ACTOR));
            RiskProfiles.activate(c, profil, APPROVER);
            return null;
        });
        return new Risque(decor, dotations, provisions, reserves, produitsRetard);
    }

    private static Map<String, String> parametres(Risque risque, Map<String, String> extra) {
        Map<String, String> parametres = new LinkedHashMap<>();
        parametres.put(LoanCatalog.P_RISK_PROFILE, "GRILLE");
        parametres.put(LoanCatalog.P_PROVISION_EXPENSE, risque.dotations().id().toString());
        parametres.put(LoanCatalog.P_PROVISION_ALLOWANCE, risque.provisions().id().toString());
        parametres.put(LoanCatalog.P_RESERVED_INTEREST, risque.reserves().id().toString());
        parametres.put(LoanCatalog.P_LATE_INCOME, risque.produitsRetard().id().toString());
        parametres.putAll(extra);
        return parametres;
    }

    /** Credit debloque, premiere echeance exigible, jamais reglee. */
    private static UUID credit(Risque risque, String reference, String produitCode,
                               Map<String, String> extra) {
        product(risque.decor(), produitCode, parametres(risque, extra));
        UUID contrat = contract(risque.decor(), reference, produitCode, "1000000");
        loanService.disburse(contrat, ScheduleGenerator.generate(
            LoanTerms.of(xof("1000000")).ratePercent("12").instalments(12)
                .disbursedOn(DEBLOCAGE).firstDueDate(PREMIERE_ECHEANCE).build()), ACTOR, APPROVER);
        loanService.makeDue(risque.decor().entityId(), PREMIERE_ECHEANCE, ACTOR, UUID.randomUUID());
        return contrat;
    }

    // ------------------------------------------------------------------ classement

    @Test
    @DisplayName("un credit a jour reste sain et ne provisionne rien")
    void creditSain() {
        Risque risque = decorRisque("X1", Contagion.NONE, "DOUTEUX");
        UUID contrat = credit(risque, "REF-X1", "CRED-X1",
                              Map.of(LoanCatalog.P_DIRECT_DEBIT, "true"));

        var bilan = service().classify(risque.decor().entityId(), LocalDate.of(2026, 10, 20),
                                       ACTOR, UUID.randomUUID());

        assertThat(bilan.anomalies()).isEmpty();
        assertThat(classement(contrat)).isEqualTo("SAIN");
        assertThat(soldeDe(risque.provisions()).isZero()).isTrue();
    }

    @Test
    @DisplayName("le declassement suit l'age de l'impaye et dote la provision sur l'encours net")
    void declassementEtDotation() {
        Risque risque = decorRisque("X2", Contagion.NONE, "DOUTEUX");
        UUID contrat = credit(risque, "REF-X2", "CRED-X2", Map.of());

        // Quatre-vingt-dix jours apres le 15 octobre : 13 janvier.
        var bilan = service().classify(risque.decor().entityId(), LocalDate.of(2027, 1, 13), ACTOR,
                                       UUID.randomUUID());

        assertThat(classement(contrat)).isEqualTo("DOUTEUX");
        assertThat(bilan.downgraded()).isEqualTo(1);
        // Encours : 1 000 000 de capital au compte de pret, plus 10 000 d'interets echus impayes.
        // Le capital echu n'est pas compte deux fois — il figure toujours au compte de pret.
        // 20 % de 1 010 000 = 202 000.
        assertThat(soldeDe(risque.provisions())).isEqualTo(xof("202000"));
        assertThat(soldeDe(risque.dotations())).isEqualTo(xof("202000"));
    }

    @Test
    @DisplayName("la provision ne dote que la variation, jamais le montant entier a chaque arrete")
    void dotationDifferentielle() {
        Risque risque = decorRisque("X3", Contagion.NONE, "DOUTEUX");
        credit(risque, "REF-X3", "CRED-X3", Map.of());
        UUID entite = risque.decor().entityId();

        service().classify(entite, LocalDate.of(2027, 1, 13), ACTOR, UUID.randomUUID());
        Money apresPremier = soldeDe(risque.provisions());
        service().classify(entite, LocalDate.of(2027, 1, 14), ACTOR, UUID.randomUUID());

        // Le second arrete ne change ni la classe ni l'encours : rien a doter.
        assertThat(soldeDe(risque.provisions())).isEqualTo(apresPremier);
    }

    @Test
    @DisplayName("un credit regularise voit sa provision reprise")
    void repriseDeProvision() {
        Risque risque = decorRisque("X4", Contagion.NONE, "DOUTEUX");
        UUID contrat = credit(risque, "REF-X4", "CRED-X4", Map.of());
        UUID entite = risque.decor().entityId();

        service().classify(entite, LocalDate.of(2027, 1, 13), ACTOR, UUID.randomUUID());
        assertThat(soldeDe(risque.provisions()).isPositive()).isTrue();

        // Le client solde tout ce qu'il doit.
        loanService.settle(contrat, xof("88849"), LocalDate.of(2027, 1, 14), "MANUAL",
                           io.corebanking.kernel.id.IdempotencyKey.of("REG-X4"), ACTOR, null);
        service().classify(entite, LocalDate.of(2027, 1, 14), ACTOR, UUID.randomUUID());

        assertThat(classement(contrat)).isEqualTo("SAIN");
        assertThat(soldeDe(risque.provisions()).isZero()).isTrue();
    }

    @Test
    @DisplayName("les garanties reduisent l'assiette de provision")
    void garanties() {
        Risque risque = decorRisque("X5", Contagion.NONE, "DOUTEUX");
        UUID contrat = credit(risque, "REF-X5", "CRED-X5", Map.of());
        UUID entite = risque.decor().entityId();
        hypotheque(entite, contrat, "IMM-X5", "800000", "800000", 1, DEBLOCAGE, "100");

        service().classify(entite, LocalDate.of(2027, 1, 13), ACTOR, UUID.randomUUID());

        // 800 000 garantis, retenus a 50 % par le regime des hypotheques : 400 000. Assiette
        // 1 010 000 - 400 000 = 610 000, provisionnee a 20 % = 122 000.
        assertThat(soldeDe(risque.provisions())).isEqualTo(xof("122000"));
    }

    // ------------------------------------------------------------------ contagion

    @Test
    @DisplayName("la contagion declasse tous les encours du client au niveau du plus degrade")
    void contagionClient() {
        Risque risque = decorRisque("X6", Contagion.CUSTOMER, "DOUTEUX");
        UUID entite = risque.decor().entityId();
        UUID client = UUID.randomUUID();

        UUID impaye = credit(risque, "REF-X6A", "CRED-X6", Map.of());
        // Second credit du meme client, preleve d'office et donc a jour.
        UUID sain = contract(risque.decor(), "REF-X6B", "CRED-X6", "1000000");
        loanService.disburse(sain, ScheduleGenerator.generate(
            LoanTerms.of(xof("1000000")).ratePercent("12").instalments(12)
                .disbursedOn(DEBLOCAGE).firstDueDate(LocalDate.of(2027, 6, 15)).build()),
            ACTOR, APPROVER);
        database.inTransaction(c -> {
            LoanStore.assignCustomer(c, impaye, client);
            LoanStore.assignCustomer(c, sain, client);
            return null;
        });

        var bilan = service().classify(entite, LocalDate.of(2027, 1, 13), ACTOR, UUID.randomUUID());

        // Le second credit n'a aucun impaye, et se retrouve pourtant classe DOUTEUX : un client qui
        // ne rembourse plus l'un de ses credits ne presente pas un risque different sur les autres.
        assertThat(bilan.contaminated()).isEqualTo(1);
        assertThat(classement(impaye)).isEqualTo("DOUTEUX");
        assertThat(classement(sain)).isEqualTo("DOUTEUX");
        assertThat(raison(sain)).isEqualTo("CONTAGION");
    }

    @Test
    @DisplayName("sans contagion, chaque credit est classe pour lui-meme")
    void sansContagion() {
        Risque risque = decorRisque("X7", Contagion.NONE, "DOUTEUX");
        UUID client = UUID.randomUUID();
        UUID impaye = credit(risque, "REF-X7A", "CRED-X7", Map.of());
        UUID sain = contract(risque.decor(), "REF-X7B", "CRED-X7", "1000000");
        loanService.disburse(sain, ScheduleGenerator.generate(
            LoanTerms.of(xof("1000000")).ratePercent("12").instalments(12)
                .disbursedOn(DEBLOCAGE).firstDueDate(LocalDate.of(2027, 6, 15)).build()),
            ACTOR, APPROVER);
        database.inTransaction(c -> {
            LoanStore.assignCustomer(c, impaye, client);
            LoanStore.assignCustomer(c, sain, client);
            return null;
        });

        service().classify(risque.decor().entityId(), LocalDate.of(2027, 1, 13), ACTOR,
                           UUID.randomUUID());

        assertThat(classement(impaye)).isEqualTo("DOUTEUX");
        assertThat(classement(sain)).isEqualTo("SAIN");
    }

    // ------------------------------------------------------------------ suspension

    @Test
    @DisplayName("au franchissement du seuil, les interets deja constates sortent du resultat")
    void suspensionDesInterets() {
        Risque risque = decorRisque("X8", Contagion.NONE, "DOUTEUX");
        UUID contrat = credit(risque, "REF-X8", "CRED-X8", Map.of());
        UUID entite = risque.decor().entityId();
        Money produitsAvant = soldeDe(risque.decor().produitsInterets());

        var bilan = service().classify(entite, LocalDate.of(2027, 1, 13), ACTOR, UUID.randomUUID());

        // Les 10 000 d'interets echus et impayes quittent le compte de produits pour les interets
        // reserves. Sans cette ecriture, la banque continuerait de porter en resultat des interets
        // qu'elle ne percevra pas : le produit net bancaire serait surevalue, et la non-conformite
        // directe.
        assertThat(bilan.suspended()).isEqualTo(1);
        assertThat(bilan.interestSuspended()).isEqualTo(xof("10000"));
        assertThat(soldeDe(risque.reserves())).isEqualTo(xof("10000"));
        assertThat(soldeDe(risque.decor().produitsInterets()))
            .isEqualTo(produitsAvant.minus(xof("10000")));
    }

    @Test
    @DisplayName("une fois suspendus, les interets suivants naissent directement en interets reserves")
    void interetsSuivantsReserves() {
        Risque risque = decorRisque("X9", Contagion.NONE, "DOUTEUX");
        UUID contrat = credit(risque, "REF-X9", "CRED-X9", Map.of());
        UUID entite = risque.decor().entityId();

        service().classify(entite, LocalDate.of(2027, 1, 13), ACTOR, UUID.randomUUID());
        Money reservesApresSuspension = soldeDe(risque.reserves());
        Money produitsApresSuspension = soldeDe(risque.decor().produitsInterets());

        // L'echeance de fevrier devient exigible sur un credit deja suspendu.
        loanService.makeDue(entite, LocalDate.of(2027, 2, 15), ACTOR, UUID.randomUUID());

        assertThat(soldeDe(risque.decor().produitsInterets())).isEqualTo(produitsApresSuspension);
        assertThat(soldeDe(risque.reserves())).isGreaterThan(reservesApresSuspension);
    }

    @Test
    @DisplayName("le transfert n'a lieu qu'au franchissement du seuil, pas a chaque arrete")
    void suspensionUneSeuleFois() {
        Risque risque = decorRisque("X10", Contagion.NONE, "DOUTEUX");
        credit(risque, "REF-X10", "CRED-X10", Map.of());
        UUID entite = risque.decor().entityId();

        service().classify(entite, LocalDate.of(2027, 1, 13), ACTOR, UUID.randomUUID());
        Money apresPremier = soldeDe(risque.reserves());
        var second = service().classify(entite, LocalDate.of(2027, 1, 14), ACTOR,
                                        UUID.randomUUID());

        assertThat(second.suspended()).isZero();
        assertThat(soldeDe(risque.reserves())).isEqualTo(apresPremier);
    }

    @Test
    @DisplayName("un profil sans seuil de suspension ne suspend jamais")
    void profilSansSuspension() {
        Risque risque = decorRisque("X11", Contagion.NONE, null);
        credit(risque, "REF-X11", "CRED-X11", Map.of());

        var bilan = service().classify(risque.decor().entityId(), LocalDate.of(2027, 6, 15), ACTOR,
                                       UUID.randomUUID());

        assertThat(bilan.suspended()).isZero();
        assertThat(soldeDe(risque.reserves()).isZero()).isTrue();
    }

    // ------------------------------------------------------------------ retour a meilleure fortune

    @Test
    @DisplayName("un credit regularise reste declasse tant que la periode d'observation court")
    void periodeDObservation() {
        Risque risque = decorRisque("X15", Contagion.NONE, "DOUTEUX", 90);
        UUID contrat = credit(risque, "REF-X15", "CRED-X15", Map.of());
        UUID entite = risque.decor().entityId();

        service().classify(entite, LocalDate.of(2027, 1, 13), ACTOR, UUID.randomUUID());
        assertThat(classement(contrat)).isEqualTo("DOUTEUX");

        // Le client solde tout le lendemain.
        loanService.settle(contrat, xof("88849"), LocalDate.of(2027, 1, 14), "MANUAL",
                           io.corebanking.kernel.id.IdempotencyKey.of("REG-X15"), ACTOR, null);
        service().classify(entite, LocalDate.of(2027, 1, 14), ACTOR, UUID.randomUUID());

        // Sans periode d'observation, le declassement et la provision disparaitraient le jour
        // meme — et le debiteur qui regle la veille de chaque arrete presenterait un portefeuille
        // sain a chaque arrete sans l'etre jamais.
        assertThat(classement(contrat)).isEqualTo("DOUTEUX");
        assertThat(raison(contrat)).isEqualTo("CURE");

        // La periode d'observation retient la <b>classe</b>, pas le montant : la provision suit
        // l'encours, qui a diminue du capital rembourse. 20 % de 921 151 = 184 230. Geler aussi le
        // montant surprovisionnerait un encours qui a reellement baisse.
        assertThat(soldeDe(risque.provisions())).isEqualTo(xof("184230"));
    }

    @Test
    @DisplayName("la periode ecoulee, le credit redevient sain et la provision est reprise")
    void sortieDObservation() {
        Risque risque = decorRisque("X16", Contagion.NONE, "DOUTEUX", 30);
        UUID contrat = credit(risque, "REF-X16", "CRED-X16", Map.of());
        UUID entite = risque.decor().entityId();

        service().classify(entite, LocalDate.of(2027, 1, 13), ACTOR, UUID.randomUUID());
        loanService.settle(contrat, xof("88849"), LocalDate.of(2027, 1, 14), "MANUAL",
                           io.corebanking.kernel.id.IdempotencyKey.of("REG-X16"), ACTOR, null);
        service().classify(entite, LocalDate.of(2027, 1, 14), ACTOR, UUID.randomUUID());
        assertThat(classement(contrat)).isEqualTo("DOUTEUX");

        // Trente jours apres le dernier arrete portant un impaye.
        service().classify(entite, LocalDate.of(2027, 2, 12), ACTOR, UUID.randomUUID());

        assertThat(classement(contrat)).isEqualTo("SAIN");
        assertThat(soldeDe(risque.provisions()).isZero()).isTrue();
    }

    @Test
    @DisplayName("l'observation ne joue que dans un sens : une degradation reste immediate")
    void degradationImmediate() {
        Risque risque = decorRisque("X17", Contagion.NONE, "DOUTEUX", 90);
        UUID contrat = credit(risque, "REF-X17", "CRED-X17", Map.of());
        UUID entite = risque.decor().entityId();

        service().classify(entite, LocalDate.of(2026, 11, 20), ACTOR, UUID.randomUUID());
        assertThat(classement(contrat)).isEqualTo("IMPAYE");

        service().classify(entite, LocalDate.of(2027, 1, 13), ACTOR, UUID.randomUUID());

        // Aucune observation ne retarde un declassement : le risque se constate quand il apparait.
        assertThat(classement(contrat)).isEqualTo("DOUTEUX");
        assertThat(raison(contrat)).isEqualTo("AGEING");
    }

    @Test
    @DisplayName("sans periode d'observation, le retour a meilleure fortune est immediat")
    void sansObservation() {
        Risque risque = decorRisque("X18", Contagion.NONE, "DOUTEUX", 0);
        UUID contrat = credit(risque, "REF-X18", "CRED-X18", Map.of());
        UUID entite = risque.decor().entityId();

        service().classify(entite, LocalDate.of(2027, 1, 13), ACTOR, UUID.randomUUID());
        loanService.settle(contrat, xof("88849"), LocalDate.of(2027, 1, 14), "MANUAL",
                           io.corebanking.kernel.id.IdempotencyKey.of("REG-X18"), ACTOR, null);
        service().classify(entite, LocalDate.of(2027, 1, 14), ACTOR, UUID.randomUUID());

        assertThat(classement(contrat)).isEqualTo("SAIN");
    }

    // ------------------------------------------------------------------ immuabilite

    @Test
    @DisplayName("une classification est figee : seul son statut evolue")
    void classificationFigee() {
        Risque risque = decorRisque("X12", Contagion.NONE, "DOUTEUX");
        UUID contrat = credit(risque, "REF-X12", "CRED-X12", Map.of());
        service().classify(risque.decor().entityId(), LocalDate.of(2027, 1, 13), ACTOR,
                           UUID.randomUUID());

        assertThatThrownBy(() -> database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "UPDATE loan_classification SET provision_amount = 1 WHERE contract_id = ?")) {
                ps.setObject(1, contrat);
                ps.executeUpdate();
                return null;
            } catch (SQLException e) {
                throw new LedgerStoreException("mise a jour", e);
            }
        })).hasStackTraceContaining("est figee");
    }

    @Test
    @DisplayName("deux grilles en vigueur a la meme date sont refusees")
    void deuxGrillesEnVigueur() {
        Risque risque = decorRisque("X13", Contagion.NONE, "DOUTEUX");

        assertThatThrownBy(() -> database.inTransaction(c -> {
            UUID second = RiskProfiles.createDraft(c, new RiskProfiles.Draft(
                risque.decor().entityId(), "Doublon", DEBLOCAGE, null,
                grille(Contagion.NONE, "DOUTEUX"), ACTOR));
            RiskProfiles.activate(c, second, APPROVER);
            return null;
        })).hasStackTraceContaining("ex_profile_no_overlap");
    }

    @Test
    @DisplayName("une grille relue en base est revalidee")
    void grilleRevalidee() {
        Risque risque = decorRisque("X14", Contagion.NONE, "DOUTEUX");
        // Un correctif manuel creuse un trou dans la grille : la relecture doit le refuser plutot
        // que de l'appliquer a tout le portefeuille.
        database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "UPDATE risk_bucket SET from_days = 45 WHERE code = 'IMPAYE'"
                + " AND profile_id IN (SELECT id FROM risk_profile WHERE legal_entity_id = ?)")) {
                ps.setObject(1, risque.decor().entityId());
                ps.executeUpdate();
                return null;
            } catch (SQLException e) {
                throw new LedgerStoreException("mise a jour", e);
            }
        });

        assertThatThrownBy(() -> database.inTransaction(c -> RiskProfiles.resolveAt(
            c, risque.decor().entityId(), "GRILLE", DEBLOCAGE)))
            .isInstanceOf(RiskGrid.InvalidRiskGridException.class)
            .hasMessageContaining("ne sont couverts par aucune classe");
    }

    /** Enregistre une hypotheque et l'affecte au credit. Le regime est cree une fois par entite. */
    private static UUID hypotheque(UUID entite, UUID contrat, String actif, String valeur,
                                   String garanti, int rang, LocalDate expertise,
                                   String quotePart) {
        return database.inTransaction(c -> {
            if (Collaterals.policiesAt(c, entite, DEBLOCAGE).isEmpty()) {
                UUID regime = Collaterals.createPolicy(c, new Collaterals.PolicyDraft(
                    entite, new io.corebanking.loan.CollateralPolicy(
                        "HYPOTHEQUE", "Hypotheque", new BigDecimal("50"), 36),
                    DEBLOCAGE.minusYears(1), null, ACTOR));
                Collaterals.activatePolicy(c, regime, APPROVER);
            }
            UUID surete = Collaterals.register(c, new Collaterals.Draft(
                entite, null, actif, "HYPOTHEQUE", "Hypotheque " + actif, xof(valeur),
                xof(garanti), rang, expertise, ACTOR, APPROVER));
            Collaterals.allocate(c, surete, contrat, new BigDecimal(quotePart));
            return surete;
        });
    }

    // ------------------------------------------------------------------ outillage

    private static String classement(UUID contractId) {
        return lire(contractId, "bucket_code");
    }

    private static String raison(UUID contractId) {
        return lire(contractId, "reason");
    }

    private static String lire(UUID contractId, String colonne) {
        return database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "SELECT " + colonne + " FROM loan_classification"
                + " WHERE contract_id = ? AND status = 'ACTIVE'"
                + " ORDER BY classified_on DESC LIMIT 1")) {
                ps.setObject(1, contractId);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Lecture de la classification", e);
            }
        });
    }

    private static Money soldeDe(Account compte) {
        return database.inTransaction(c -> Balances.current(c, compte.id()));
    }
}
