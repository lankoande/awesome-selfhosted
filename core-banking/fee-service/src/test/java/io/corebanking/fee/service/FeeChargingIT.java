package io.corebanking.fee.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.store.LedgerStoreException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FeeChargingIT extends FeeTestBase {

    private static Account produitCommissions;
    private static Account taxeCollectee;
    private static Account caisse;

    private static final LocalDate FIN_SEPTEMBRE = LocalDate.of(2026, 9, 30);

    @BeforeAll
    static void comptesGeneraux() {
        produitCommissions = gl("70611-COMMISSIONS");
        taxeCollectee = gl("44571-TOB");
        caisse = glDebit("57-CAISSE");
    }

    private static Money xof(String montant) {
        return Money.of(montant, Currencies.XOF);
    }

    /** Parametrage de base : frais de tenue de compte mensuels a terme echu, 2 000 XOF, TOB 18 %. */
    private static Map<String, String> tenueDeCompte(Map<String, String> surcharges) {
        return tenueDeCompte(produitCommissions, taxeCollectee, surcharges);
    }

    private static Map<String, String> tenueDeCompte(Account produit, Account taxe,
                                                     Map<String, String> surcharges) {
        Map<String, String> parametres = new LinkedHashMap<>();
        parametres.put(FeeCatalog.P_FEE_CODES, "TENUE");
        parametres.put("fee.TENUE.label", "Frais de tenue de compte");
        parametres.put("fee.TENUE.frequency", "MONTHLY");
        parametres.put("fee.TENUE.anchor", "2026-09-01");
        parametres.put("fee.TENUE.timing", "IN_ARREARS");
        parametres.put("fee.TENUE.basis", "FLAT");
        parametres.put("fee.TENUE.amount", "2000");
        parametres.put("fee.TENUE.tax_rate", "18");
        parametres.put("fee.TENUE.income_account", produit.id().toString());
        parametres.put("fee.TENUE.tax_account", taxe.id().toString());
        parametres.putAll(surcharges);
        return parametres;
    }

    private static FeeChargingService.Outcome percevoir(Account compte, LocalDate date) {
        return feeService.chargeDue(ENTITY, List.of(compte.id()), date, ACTOR, UUID.randomUUID());
    }

    // ------------------------------------------------------------------ perception nominale

    @Test
    @DisplayName("la commission est prelevee a l'echeance, taxe comprise, et le client est debite du total")
    void perceptionNominale() {
        // Comptes generaux dedies : les autres cas partagent les leurs, et une assertion sur un
        // cumul partage dependrait de l'ordre d'execution.
        Account produit = gl("70611-NOMINAL");
        Account taxe = gl("44571-NOMINAL");
        product("P-NOMINAL", tenueDeCompte(produit, taxe, Map.of()), OUVERTURE);
        Account compte = client("C-NOMINAL", OUVERTURE);
        assign(compte, "P-NOMINAL", OUVERTURE);
        credit(compte, caisse, "100000", OUVERTURE, "dep-nominal");

        FeeChargingService.Outcome bilan = percevoir(compte, FIN_SEPTEMBRE);

        assertThat(bilan.collected()).isEqualTo(1);
        assertThat(bilan.collectedAmount()).isEqualTo(xof("2360"));

        List<Charged> liquidations = charges(compte.id());
        assertThat(liquidations).hasSize(1);
        Charged tenue = liquidations.get(0);
        assertThat(tenue.net()).isEqualTo(xof("2000"));
        assertThat(tenue.tax()).isEqualTo(xof("360"));
        assertThat(tenue.total()).isEqualTo(xof("2360"));
        assertThat(tenue.outcome()).isEqualTo(FeeOutcome.COLLECTED);
        assertThat(tenue.entryId()).isNotNull();
        assertThat(tenue.periodStart()).isEqualTo("2026-09-01");
        assertThat(tenue.periodEnd()).isEqualTo(FIN_SEPTEMBRE);

        // 2 360 debites au client, ventiles a l'unite pres entre produit et taxe.
        assertThat(balance(compte)).isEqualTo(xof("97640"));
        assertThat(balance(produit)).isEqualTo(xof("2000"));
        assertThat(balance(taxe)).isEqualTo(xof("360"));
    }

    @Test
    @DisplayName("rejouer le traitement ne facture pas une seconde fois")
    void reprise() {
        product("P-REPRISE", tenueDeCompte(Map.of()), OUVERTURE);
        Account compte = client("C-REPRISE", OUVERTURE);
        assign(compte, "P-REPRISE", OUVERTURE);
        credit(compte, caisse, "100000", OUVERTURE, "dep-reprise");

        percevoir(compte, FIN_SEPTEMBRE);
        Money apresPremier = balance(compte);

        // Sous un identifiant de traitement different : c'est le cas qu'une cle derivee du run
        // laisserait passer.
        FeeChargingService.Outcome second = percevoir(compte, FIN_SEPTEMBRE);

        assertThat(second.charged()).isZero();
        assertThat(charges(compte.id())).hasSize(1);
        assertThat(balance(compte)).isEqualTo(apresPremier);
    }

    @Test
    @DisplayName("deux liquidations couvrant un meme jour sont refusees par la base elle-meme")
    void chevauchementRefuse() {
        product("P-CHEVAUCHE", tenueDeCompte(Map.of()), OUVERTURE);
        Account compte = client("C-CHEVAUCHE", OUVERTURE);
        assign(compte, "P-CHEVAUCHE", OUVERTURE);
        credit(compte, caisse, "100000", OUVERTURE, "dep-chevauche");
        percevoir(compte, FIN_SEPTEMBRE);

        // Une insertion directe, comme le ferait une correction manuelle ou un traitement
        // parallele mal isole. Le verrou applicatif ne la verrait pas ; la contrainte, si.
        assertThatThrownBy(() -> database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "INSERT INTO fee_charge(id, legal_entity_id, account_id, fee_code, period_index,"
                + " period_start, period_end, charge_date, basis_amount, gross_amount, net_amount,"
                + " tax_amount, total_amount, tax_rate_percent, charged_days, period_days, outcome)"
                + " VALUES (?,?,?,'TENUE',0,'2026-09-15','2026-10-15','2026-10-15',0,0,0,0,0,0,"
                + " 30,30,'REJECTED')")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, ENTITY);
                ps.setObject(3, compte.id());
                ps.executeUpdate();
                return null;
            } catch (SQLException e) {
                throw new LedgerStoreException("insertion", e);
            }
        })).hasStackTraceContaining("ex_fee_no_overlap");
    }

    // ------------------------------------------------------------------ rattrapage

    @Test
    @DisplayName("trois mois non traites sont rattrapes, chacun value a sa propre echeance")
    void rattrapage() {
        product("P-RATTRAPAGE", tenueDeCompte(Map.of()), OUVERTURE);
        Account compte = client("C-RATTRAPAGE", OUVERTURE);
        assign(compte, "P-RATTRAPAGE", OUVERTURE);
        credit(compte, caisse, "100000", OUVERTURE, "dep-rattrapage");

        FeeChargingService.Outcome bilan = percevoir(compte, LocalDate.of(2026, 11, 30));

        assertThat(bilan.collected()).isEqualTo(3);
        List<Charged> liquidations = charges(compte.id());
        assertThat(liquidations).extracting(Charged::periodEnd)
            .containsExactly(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 31),
                             LocalDate.of(2026, 11, 30));
        // Chaque commission porte la date de valeur de son echeance, pas celle du rattrapage.
        assertThat(liquidations).extracting(Charged::chargeDate)
            .containsExactly(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 31),
                             LocalDate.of(2026, 11, 30));
        assertThat(valueDates(compte.id()))
            .containsExactly(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 31),
                             LocalDate.of(2026, 11, 30));
        assertThat(balance(compte)).isEqualTo(xof("92920"));   // 100 000 - 3 x 2 360
    }

    @Test
    @DisplayName("un compte ouvert apres l'ancrage n'est pas facture des periodes anterieures a son existence")
    void compteOuvertApresAncrage() {
        product("P-TARDIF", tenueDeCompte(Map.of("fee.TENUE.anchor", "2026-01-01")), OUVERTURE);
        Account compte = client("C-TARDIF", LocalDate.of(2026, 9, 16));
        assign(compte, "P-TARDIF", LocalDate.of(2026, 9, 16));
        credit(compte, caisse, "100000", LocalDate.of(2026, 9, 16), "dep-tardif");

        percevoir(compte, FIN_SEPTEMBRE);

        List<Charged> liquidations = charges(compte.id());
        assertThat(liquidations).hasSize(1);
        assertThat(liquidations.get(0).periodStart()).isEqualTo("2026-09-01");
    }

    @Test
    @DisplayName("le prorata reduit la commission du compte ouvert en cours de periode")
    void prorata() {
        product("P-PRORATA",
                tenueDeCompte(Map.of("fee.TENUE.proration", "ACTUAL_DAYS",
                                     "fee.TENUE.amount", "3000",
                                     "fee.TENUE.tax_rate", "0")),
                OUVERTURE);
        Account compte = client("C-PRORATA", LocalDate.of(2026, 9, 16));
        assign(compte, "P-PRORATA", LocalDate.of(2026, 9, 16));
        credit(compte, caisse, "100000", LocalDate.of(2026, 9, 16), "dep-prorata");

        percevoir(compte, FIN_SEPTEMBRE);

        Charged tenue = charges(compte.id()).get(0);
        assertThat(tenue.chargedDays()).isEqualTo(15);         // du 16 au 30 inclus
        assertThat(tenue.net()).isEqualTo(xof("1500"));        // 3 000 x 15 / 30
    }

    // ------------------------------------------------------------------ provision insuffisante

    @Test
    @DisplayName("provision insuffisante, politique REJECT : rien n'est comptabilise, tout est trace")
    void provisionInsuffisanteRejet() {
        product("P-REJET", tenueDeCompte(Map.of("fee.TENUE.on_insufficient_funds", "REJECT")),
                OUVERTURE);
        Account compte = client("C-REJET", OUVERTURE);
        assign(compte, "P-REJET", OUVERTURE);
        credit(compte, caisse, "1000", OUVERTURE, "dep-rejet");

        FeeChargingService.Outcome bilan = percevoir(compte, FIN_SEPTEMBRE);

        assertThat(bilan.rejected()).isEqualTo(1);
        assertThat(bilan.charged()).isZero();
        assertThat(balance(compte)).isEqualTo(xof("1000"));

        // Le manque a gagner est chiffre, ce qu'un simple « ne rien faire » rendrait impossible.
        Charged tenue = charges(compte.id()).get(0);
        assertThat(tenue.outcome()).isEqualTo(FeeOutcome.REJECTED);
        assertThat(tenue.total()).isEqualTo(xof("2360"));
        assertThat(tenue.entryId()).isNull();
    }

    @Test
    @DisplayName("politique FORCE : le prelevement passe et le compte devient debiteur")
    void provisionInsuffisanteForcee() {
        product("P-FORCE", tenueDeCompte(Map.of("fee.TENUE.on_insufficient_funds", "FORCE")),
                OUVERTURE);
        Account compte = client("C-FORCE", OUVERTURE);
        assign(compte, "P-FORCE", OUVERTURE);
        credit(compte, caisse, "1000", OUVERTURE, "dep-force");

        FeeChargingService.Outcome bilan = percevoir(compte, FIN_SEPTEMBRE);

        assertThat(bilan.forced()).isEqualTo(1);
        assertThat(charges(compte.id()).get(0).outcome()).isEqualTo(FeeOutcome.FORCED);
        assertThat(balance(compte)).isEqualTo(xof("-1360"));
    }

    @Test
    @DisplayName("le decouvert autorise fait partie du disponible")
    void decouvertAutorise() {
        Map<String, String> parametres = tenueDeCompte(Map.of());
        parametres.put(FeeCatalog.P_OVERDRAFT_LIMIT, "50000");
        product("P-DECOUVERT", parametres, OUVERTURE);
        Account compte = client("C-DECOUVERT", OUVERTURE);
        assign(compte, "P-DECOUVERT", OUVERTURE);
        credit(compte, caisse, "1000", OUVERTURE, "dep-decouvert");

        FeeChargingService.Outcome bilan = percevoir(compte, FIN_SEPTEMBRE);

        assertThat(bilan.collected()).isEqualTo(1);
        assertThat(balance(compte)).isEqualTo(xof("-1360"));
    }

    @Test
    @DisplayName("politique DEFER : la commission devient une creance, encaissee des que le compte est provisionne")
    void reportPuisRecouvrement() {
        product("P-REPORT",
                tenueDeCompte(Map.of("fee.TENUE.on_insufficient_funds", "DEFER",
                                     "fee.TENUE.arrear_max_age_days", "90")),
                OUVERTURE);
        Account compte = client("C-REPORT", OUVERTURE);
        assign(compte, "P-REPORT", OUVERTURE);
        credit(compte, caisse, "500", OUVERTURE, "dep-report-1");

        FeeChargingService.Outcome premier = percevoir(compte, FIN_SEPTEMBRE);
        assertThat(premier.deferred()).isEqualTo(1);
        assertThat(charges(compte.id()).get(0).outcome()).isEqualTo(FeeOutcome.DEFERRED);
        assertThat(charges(compte.id()).get(0).entryId()).isNull();

        credit(compte, caisse, "10000", LocalDate.of(2026, 10, 5), "dep-report-2");
        FeeChargingService.Outcome second = percevoir(compte, LocalDate.of(2026, 10, 5));

        assertThat(second.recovered()).isEqualTo(1);
        Charged tenue = charges(compte.id()).get(0);
        assertThat(tenue.outcome()).isEqualTo(FeeOutcome.COLLECTED);
        assertThat(tenue.entryId()).isNotNull();
        assertThat(tenue.attempts()).isEqualTo(2);
        // Le montant encaisse est celui liquide en septembre, pas un montant recalcule en octobre.
        assertThat(tenue.total()).isEqualTo(xof("2360"));
        assertThat(balance(compte)).isEqualTo(xof("8140"));    // 500 + 10 000 - 2 360
    }

    @Test
    @DisplayName("une creance trop ancienne est abandonnee, et l'abandon est date")
    void abandonApresDelai() {
        product("P-ABANDON",
                tenueDeCompte(Map.of("fee.TENUE.on_insufficient_funds", "DEFER",
                                     "fee.TENUE.arrear_max_age_days", "10")),
                OUVERTURE);
        Account compte = client("C-ABANDON", OUVERTURE);
        assign(compte, "P-ABANDON", OUVERTURE);
        credit(compte, caisse, "500", OUVERTURE, "dep-abandon");

        percevoir(compte, FIN_SEPTEMBRE);
        FeeChargingService.Outcome tardif = percevoir(compte, LocalDate.of(2026, 10, 15));

        assertThat(tardif.writtenOff()).isEqualTo(1);
        assertThat(charges(compte.id()).get(0).outcome()).isEqualTo(FeeOutcome.WRITTEN_OFF);
    }

    @Test
    @DisplayName("la dette la plus ancienne est soldee avant la commission du jour")
    void impayeAvantCommissionDuJour() {
        product("P-ORDRE",
                tenueDeCompte(Map.of("fee.TENUE.on_insufficient_funds", "DEFER",
                                     "fee.TENUE.arrear_max_age_days", "90")),
                OUVERTURE);
        Account compte = client("C-ORDRE", OUVERTURE);
        assign(compte, "P-ORDRE", OUVERTURE);
        credit(compte, caisse, "500", OUVERTURE, "dep-ordre-1");

        percevoir(compte, FIN_SEPTEMBRE);                      // septembre reportee

        // De quoi solder exactement une commission, pas deux.
        credit(compte, caisse, "2000", LocalDate.of(2026, 10, 31), "dep-ordre-2");
        FeeChargingService.Outcome octobre = percevoir(compte, LocalDate.of(2026, 10, 31));

        assertThat(octobre.recovered()).isEqualTo(1);          // septembre, la plus ancienne
        assertThat(octobre.deferred()).isEqualTo(1);           // octobre attend a son tour

        List<Charged> liquidations = charges(compte.id());
        assertThat(liquidations.get(0).outcome()).isEqualTo(FeeOutcome.COLLECTED);
        assertThat(liquidations.get(1).outcome()).isEqualTo(FeeOutcome.DEFERRED);
    }

    // ------------------------------------------------------------------ exoneration

    @Test
    @DisplayName("une exoneration suspend le prelevement mais conserve le montant : le geste commercial est chiffre")
    void exoneration() {
        product("P-EXO", tenueDeCompte(Map.of()), OUVERTURE);
        Account compte = client("C-EXO", OUVERTURE);
        assign(compte, "P-EXO", OUVERTURE);
        credit(compte, caisse, "100000", OUVERTURE, "dep-exo");
        database.inTransaction(c -> FeeLedger.grantExemption(
            c, compte.id(), "TENUE", OUVERTURE, LocalDate.of(2026, 12, 31),
            "Offre de bienvenue trois mois", ACTOR, APPROVER));

        FeeChargingService.Outcome bilan = percevoir(compte, FIN_SEPTEMBRE);

        assertThat(bilan.waived()).isEqualTo(1);
        Charged tenue = charges(compte.id()).get(0);
        assertThat(tenue.outcome()).isEqualTo(FeeOutcome.WAIVED);
        assertThat(tenue.total()).isEqualTo(xof("2360"));
        assertThat(tenue.entryId()).isNull();
        assertThat(balance(compte)).isEqualTo(xof("100000"));
    }

    @Test
    @DisplayName("une exoneration ne peut pas etre accordee et validee par la meme personne")
    void exonerationSansSeparationDesTaches() {
        Account compte = client("C-EXO-SOLO", OUVERTURE);

        assertThatThrownBy(() -> database.inTransaction(c -> FeeLedger.grantExemption(
            c, compte.id(), "TENUE", OUVERTURE, null, "complaisance", ACTOR, ACTOR)))
            .hasStackTraceContaining("ck_exemption_approval");
    }

    // ------------------------------------------------------------------ plus fort decouvert

    @Test
    @DisplayName("la commission du plus fort decouvert retient le pic en date de valeur, pas le solde de fin de mois")
    void plusFortDecouvert() {
        Map<String, String> parametres = new LinkedHashMap<>();
        parametres.put(FeeCatalog.P_FEE_CODES, "CPFD");
        parametres.put("fee.CPFD.label", "Commission du plus fort decouvert");
        parametres.put("fee.CPFD.frequency", "MONTHLY");
        parametres.put("fee.CPFD.anchor", "2026-09-01");
        parametres.put("fee.CPFD.basis", "RATE_ON_HIGHEST_DEBIT_BALANCE");
        parametres.put("fee.CPFD.rate", "0.05");
        parametres.put("fee.CPFD.income_account", produitCommissions.id().toString());
        parametres.put("fee.CPFD.on_insufficient_funds", "FORCE");
        product("P-CPFD", parametres, OUVERTURE);

        Account compte = client("C-CPFD", OUVERTURE);
        assign(compte, "P-CPFD", OUVERTURE);
        credit(compte, caisse, "1000000", OUVERTURE, "cpfd-1");
        debit(compte, caisse, "5000000", LocalDate.of(2026, 9, 10), "cpfd-2");   // -4 000 000
        credit(compte, caisse, "6000000", LocalDate.of(2026, 9, 20), "cpfd-3");  // +2 000 000

        percevoir(compte, FIN_SEPTEMBRE);

        Charged cpfd = charges(compte.id()).get(0);
        // Le pic est de 4 000 000 entre le 10 et le 19, alors que le compte finit le mois
        // largement crediteur. Retenir le solde de fin de periode ne facturerait rien.
        assertThat(cpfd.basis()).isEqualTo(xof("4000000"));
        assertThat(cpfd.net()).isEqualTo(xof("2000"));         // 0,05 % de 4 000 000
    }

    // ------------------------------------------------------------------ annulation

    @Test
    @DisplayName("l'annulation d'un traitement rend la periode a nouveau exigible, et la refacturation aboutit")
    void annulationPuisRefacturation() {
        product("P-ANNULE", tenueDeCompte(Map.of()), OUVERTURE);
        Account compte = client("C-ANNULE", OUVERTURE);
        assign(compte, "P-ANNULE", OUVERTURE);
        credit(compte, caisse, "100000", OUVERTURE, "dep-annule");

        UUID run = UUID.randomUUID();
        feeService.chargeDue(ENTITY, List.of(compte.id()), FIN_SEPTEMBRE, ACTOR, run);
        assertThat(charges(compte.id()).get(0).generation()).isZero();

        database.inTransaction(c -> FeeLedger.cancelRun(c, run));

        FeeChargingService.Outcome refacturation = percevoir(compte, FIN_SEPTEMBRE);

        // Le point du test : sans la generation dans la cle d'idempotence, l'ecriture retomberait
        // sur celle du traitement annule et la commission disparaitrait sans trace.
        assertThat(refacturation.collected()).isEqualTo(1);
        List<Charged> liquidations = charges(compte.id());
        assertThat(liquidations).hasSize(2);
        assertThat(liquidations).extracting(Charged::outcome)
            .containsExactlyInAnyOrder(FeeOutcome.CANCELLED, FeeOutcome.COLLECTED);
        Charged refaite = liquidations.stream()
            .filter(charge -> charge.outcome() == FeeOutcome.COLLECTED).findFirst().orElseThrow();
        assertThat(refaite.generation()).isEqualTo(1);
        assertThat(refaite.entryId()).isNotNull();
    }

    @Test
    @DisplayName("les montants d'une liquidation sont figes : seul son denouement evolue")
    void liquidationImmuable() {
        product("P-FIGE", tenueDeCompte(Map.of()), OUVERTURE);
        Account compte = client("C-FIGE", OUVERTURE);
        assign(compte, "P-FIGE", OUVERTURE);
        credit(compte, caisse, "100000", OUVERTURE, "dep-fige");
        percevoir(compte, FIN_SEPTEMBRE);

        assertThatThrownBy(() -> database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "UPDATE fee_charge SET net_amount = 1, total_amount = 1 WHERE account_id = ?")) {
                ps.setObject(1, compte.id());
                ps.executeUpdate();
                return null;
            } catch (SQLException e) {
                throw new LedgerStoreException("mise a jour", e);
            }
        })).hasStackTraceContaining("est figee");
    }

    @Test
    @DisplayName("une liquidation ne se supprime pas")
    void liquidationIndelebile() {
        product("P-INDELEBILE", tenueDeCompte(Map.of()), OUVERTURE);
        Account compte = client("C-INDELEBILE", OUVERTURE);
        assign(compte, "P-INDELEBILE", OUVERTURE);
        credit(compte, caisse, "100000", OUVERTURE, "dep-indelebile");
        percevoir(compte, FIN_SEPTEMBRE);

        assertThatThrownBy(() -> database.inTransaction(c -> {
            try (var ps = c.prepareStatement("DELETE FROM fee_charge WHERE account_id = ?")) {
                ps.setObject(1, compte.id());
                ps.executeUpdate();
                return null;
            } catch (SQLException e) {
                throw new LedgerStoreException("suppression", e);
            }
        })).hasStackTraceContaining("ne se supprime pas");
    }

    // ------------------------------------------------------------------ parametrage

    @Test
    @DisplayName("un compte sans commission parametree n'est pas facture et ne produit aucune anomalie")
    void produitSansCommission() {
        product("P-SANS-FRAIS", Map.of("interest.rate", "3"), OUVERTURE);
        Account compte = client("C-SANS-FRAIS", OUVERTURE);
        assign(compte, "P-SANS-FRAIS", OUVERTURE);

        FeeChargingService.Outcome bilan = percevoir(compte, FIN_SEPTEMBRE);

        assertThat(bilan.charged()).isZero();
        assertThat(bilan.anomalies()).isEmpty();
        assertThat(charges(compte.id())).isEmpty();
    }

    @Test
    @DisplayName("un parametrage incomplet est signale par compte, sans interrompre les autres")
    void parametrageIncomplet() {
        Map<String, String> parametres = new LinkedHashMap<>();
        parametres.put(FeeCatalog.P_FEE_CODES, "TENUE");
        parametres.put("fee.TENUE.basis", "FLAT");
        parametres.put("fee.TENUE.income_account", produitCommissions.id().toString());
        // Le montant du forfait manque.
        product("P-INCOMPLET", parametres, OUVERTURE);
        Account compte = client("C-INCOMPLET", OUVERTURE);
        assign(compte, "P-INCOMPLET", OUVERTURE);

        FeeChargingService.Outcome bilan = percevoir(compte, FIN_SEPTEMBRE);

        assertThat(bilan.anomalies()).hasSize(1);
        assertThat(bilan.anomalies().get(0)).contains("fee.TENUE.amount");
        assertThat(charges(compte.id())).isEmpty();
    }

    // ------------------------------------------------------------------ outillage

    private static List<LocalDate> valueDates(UUID accountId) {
        return database.inTransaction(c -> {
            List<LocalDate> dates = new java.util.ArrayList<>();
            try (var ps = c.prepareStatement(
                "SELECT l.value_date FROM journal_line l JOIN journal_entry e"
                + "   ON e.id = l.entry_id AND e.booking_date = l.booking_date"
                + " WHERE l.account_id = ? AND e.transaction_type = 'FEE_CHARGE'"
                + " ORDER BY l.value_date")) {
                ps.setObject(1, accountId);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        dates.add(rs.getObject(1, LocalDate.class));
                    }
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Lecture des dates de valeur", e);
            }
            return dates;
        });
    }
}
