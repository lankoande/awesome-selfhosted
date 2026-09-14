package io.corebanking.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.loan.DisbursementPlan;
import io.corebanking.loan.InvalidDisbursementPlanException;
import io.corebanking.loan.LoanTerms;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Deblocage echelonne, de bout en bout.
 *
 * <p>Le dossier de reference est un credit de construction de 10 000 000 XOF accorde le
 * 15 septembre 2026, mobilisable jusqu'au 15 fevrier 2027, amortissable en vingt-quatre
 * mensualites a compter du 15 mars 2027. Deux tranches : 4 000 000 a la signature, 6 000 000 au
 * constat des fondations le 20 novembre.
 */
class LoanMobilisationIT extends LoanTestBase {

    private static final LocalDate FONDATIONS = LocalDate.of(2026, 11, 20);
    private static final LocalDate LIMITE = LocalDate.of(2027, 2, 15);
    private static final LocalDate PREMIERE = LocalDate.of(2027, 3, 15);
    private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private static LoanTerms conditions() {
        return conditions("0");
    }

    private static LoanTerms conditions(String taxePercent) {
        return LoanTerms.of(xof("10000000")).ratePercent("12").instalments(24)
            .disbursedOn(DEBLOCAGE).firstDueDate(PREMIERE).taxOnInterestPercent(taxePercent)
            .build();
    }

    private static DisbursementPlan plan() {
        return DisbursementPlan.of(Currencies.XOF)
            .tranche(DEBLOCAGE, xof("4000000"), "signature")
            .tranche(FONDATIONS, xof("6000000"), "fondations achevees")
            .deadline(LIMITE)
            .build();
    }

    /** Dossier prepare : produit, contrat, plan ouvert. */
    private record Dossier(Decor decor, Account frais, UUID contrat) {}

    private static Dossier dossier(String code, Map<String, String> surcharges) {
        return dossier(code, surcharges, conditions(), xof("100000"));
    }

    private static Dossier dossier(String code, Map<String, String> surcharges, LoanTerms terms,
                                   Money fraisDeDossier) {
        Decor decor = decor(code);
        Account frais = account(decor.entityId(), code + "-FRAIS", AccountKind.GL,
                                NormalBalance.CREDIT);
        java.util.Map<String, String> parametres = new java.util.LinkedHashMap<>(surcharges);
        parametres.put(LoanCatalog.P_FEE_INCOME, frais.id().toString());
        product(decor, "CRED-" + code, parametres);
        UUID contrat = contract(decor, "REF-" + code, "CRED-" + code, "10000000");
        mobilisationService.open(contrat, plan(), terms, fraisDeDossier, ACTOR, APPROVER);
        return new Dossier(decor, frais, contrat);
    }

    private static LoanMobilisationService.Release debloquer(Dossier dossier, int rang,
                                                             String montant, LocalDate quand) {
        return mobilisationService.release(
            dossier.contrat(), rang, xof(montant), quand,
            IdempotencyKey.of("TR|" + dossier.contrat() + "|" + rang), ACTOR, APPROVER);
    }

    private static LoanMobilisationService.Outcome tfj(Dossier dossier, LocalDate date) {
        return tfj(dossier, date, RUN);
    }

    private static LoanMobilisationService.Outcome tfj(Dossier dossier, LocalDate date, UUID run) {
        return mobilisationService.process(dossier.decor().entityId(), date, ACTOR, run);
    }

    // ------------------------------------------------------------------ le cas nominal

    @Test
    @DisplayName("les interets ne courent que sur le capital mobilise, tranche par tranche")
    void mobilisationComplete() {
        Dossier dossier = dossier("M1", Map.of());
        // Les frais de dossier sont retenus sur la premiere mise a disposition, et sur elle seule :
        // les etaler ferait dependre leur montant du nombre de tranches reellement tirees.
        assertThat(debloquer(dossier, 1, "4000000", DEBLOCAGE).fees()).isEqualTo(xof("100000"));
        assertThat(debloquer(dossier, 2, "6000000", FONDATIONS).fees()).isEqualTo(xof("0"));

        // Un TFJ par date d'echeance intercalaire, comme en exploitation.
        for (LocalDate date : List.of(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 11, 15),
                                      LocalDate.of(2026, 12, 15), LocalDate.of(2027, 1, 15),
                                      LIMITE)) {
            tfj(dossier, date);
        }

        // 40 767 + 40 767 sur les 4 000 000 de la premiere tranche ; 90 740 sur la periode a cheval
        // sur le deblocage du 20 novembre ; 101 918 + 101 918 sur les 10 000 000 mobilises.
        assertThat(intercalaires(dossier.contrat()))
            .containsExactly(xof("40767"), xof("40767"), xof("90740"), xof("101918"),
                             xof("101918"));
        assertThat(solde(dossier.decor().produitsInterets())).isEqualTo(xof("376110"));

        // Sur le montant accorde des la signature, la meme mobilisation aurait coute 506 302, soit
        // 130 192 de plus — pour des fonds que l'emprunteur n'avait pas recus. C'est le prix exact
        // du contournement qui consiste a tout debloquer sur un compte d'attente.
        assertThat(xof("506302").minus(xof("376110"))).isEqualTo(xof("130192"));

        // L'encours suit les versements, le client recoit le net des frais de dossier.
        assertThat(solde(dossier.decor().pret())).isEqualTo(xof("10000000"));
        assertThat(solde(dossier.decor().courant())).isEqualTo(xof("9900000"));
        assertThat(solde(dossier.frais())).isEqualTo(xof("100000"));
        assertThat(solde(dossier.decor().creances())).isEqualTo(xof("376110"));
    }

    @Test
    @DisplayName("l'echeancier definitif est arrete a la cloture, sur le capital reellement tire")
    void echeancierALaCloture() {
        Dossier dossier = dossier("M2", Map.of());
        debloquer(dossier, 1, "4000000", DEBLOCAGE);
        debloquer(dossier, 2, "6000000", FONDATIONS);

        // Avant la cloture, aucun echeancier : le capital a amortir n'est pas connu, et en publier
        // un reviendrait a reclamer l'amortissement d'un capital non verse.
        tfj(dossier, LocalDate.of(2026, 12, 15));
        assertThat(echeanciers(dossier.contrat())).isEmpty();

        LoanMobilisationService.Outcome outcome = tfj(dossier, LIMITE);

        assertThat(outcome.closed()).isEqualTo(1);
        assertThat(outcome.periodsBilled()).isEqualTo(2);
        assertThat(outcome.interimInterest()).isEqualTo(xof("203836"));
        assertThat(outcome.commitmentCancelled()).isNull();
        assertThat(echeanciers(dossier.contrat()))
            .containsExactly(new Version(1, "MOBILISATION", LIMITE.plusDays(1)));
        // Vingt-quatre mensualites de 470 735, la premiere le 15 mars 2027. La periode d'interets
        // s'ouvre le lendemain de la cloture : les mois precedents sont deja factures.
        assertThat(echeances(dossier.contrat())).hasSize(24);
        assertThat(premiereEcheance(dossier.contrat())).isEqualTo(xof("470735"));
        assertThat(mobilisation(dossier.contrat()).closedOn()).isEqualTo(LIMITE);
    }

    @Test
    @DisplayName("un TFJ de rattrapage facture les memes periodes qu'une serie de TFJ quotidiens")
    void rattrapage() {
        Dossier rattrape = dossier("M3", Map.of());
        debloquer(rattrape, 1, "4000000", DEBLOCAGE);
        debloquer(rattrape, 2, "6000000", FONDATIONS);
        // Une seule passe, le jour de la cloture : les cinq periodes echues sont facturees
        // separement, chacune sur son assiette. Les regrouper en une seule periode donnerait un
        // autre montant — l'assiette a change en cours de route.
        tfj(rattrape, LIMITE);

        assertThat(intercalaires(rattrape.contrat()))
            .containsExactly(xof("40767"), xof("40767"), xof("90740"), xof("101918"),
                             xof("101918"));
        assertThat(solde(rattrape.decor().produitsInterets())).isEqualTo(xof("376110"));
    }

    @Test
    @DisplayName("une periode intercalaire n'est facturee qu'une fois, meme si le TFJ est rejoue")
    void reprise() {
        Dossier dossier = dossier("M4", Map.of());
        debloquer(dossier, 1, "4000000", DEBLOCAGE);

        tfj(dossier, LocalDate.of(2026, 10, 15));
        tfj(dossier, LocalDate.of(2026, 10, 15));
        tfj(dossier, LocalDate.of(2026, 10, 15));

        assertThat(intercalaires(dossier.contrat())).containsExactly(xof("40767"));
        assertThat(solde(dossier.decor().produitsInterets())).isEqualTo(xof("40767"));
    }

    // ------------------------------------------------------------------ tirages incomplets

    @Test
    @DisplayName("une tranche versee pour moins que prevu reduit l'echeance, pas la duree")
    void tranchePartielle() {
        Dossier dossier = dossier("M5", Map.of());
        debloquer(dossier, 1, "4000000", DEBLOCAGE);
        LoanMobilisationService.Release seconde = debloquer(dossier, 2, "5000000", FONDATIONS);

        // Le reliquat tombe avec la tranche : le reporter sur une tranche ulterieure reviendrait a
        // modifier le plan sans decision.
        assertThat(seconde.shortfall()).isEqualTo(xof("1000000"));
        assertThat(seconde.drawn()).isEqualTo(xof("9000000"));
        assertThat(seconde.fees()).isEqualTo(xof("0"));
        assertThat(seconde.lastTranche()).isTrue();

        tfj(dossier, LIMITE);

        // 9 000 000 tires sur 10 000 000 accordes : le reliquat tombe avec la tranche.
        assertThat(solde(dossier.decor().pret())).isEqualTo(xof("9000000"));
        assertThat(intercalaires(dossier.contrat()))
            .containsExactly(xof("40767"), xof("40767"), xof("82192"), xof("91726"),
                             xof("91726"));
        // Meme terme — vingt-quatre mensualites au 15 mars 2027 — mais 423 661 au lieu de 470 735.
        assertThat(echeances(dossier.contrat())).hasSize(24);
        assertThat(premiereEcheance(dossier.contrat())).isEqualTo(xof("423661"));
    }

    @Test
    @DisplayName("une tranche non tiree a la date limite tombe, sans bloquer l'arrete")
    void trancheNonTiree() {
        Dossier dossier = dossier("M6", Map.of());
        debloquer(dossier, 1, "4000000", DEBLOCAGE);

        LoanMobilisationService.Outcome outcome = tfj(dossier, LIMITE);

        // Un chantier qui n'a pas avance est un fait de gestion, pas une anomalie : il est compte
        // et restitue, mais il n'arrete pas le traitement de fin de journee de la banque.
        assertThat(outcome.anomalies()).isEmpty();
        assertThat(outcome.commitmentCancelled()).isEqualTo(xof("6000000"));
        assertThat(tranches(dossier.contrat()))
            .containsExactly("RELEASED", "CANCELLED");
        assertThat(premiereEcheance(dossier.contrat())).isEqualTo(xof("188294"));
    }

    @Test
    @DisplayName("un credit dont aucune tranche n'est tiree est clos, sans echeancier")
    void aucunTirage() {
        Dossier dossier = dossier("M7", Map.of());

        tfj(dossier, LIMITE);

        // Publier un echeancier sur un capital nul ferait vivre un contrat vide dans tous les
        // etats de portefeuille, et il faudrait un jour expliquer pourquoi il ne rembourse rien.
        assertThat(echeanciers(dossier.contrat())).isEmpty();
        assertThat(statut(dossier.contrat())).isEqualTo("CLOSED");
        assertThat(solde(dossier.decor().pret()).isZero()).isTrue();
    }

    // ------------------------------------------------------------------ refus

    @Test
    @DisplayName("une tranche ne se debloque pas apres la date limite de mobilisation")
    void trancheHorsDelai() {
        Dossier dossier = dossier("M8", Map.of());
        debloquer(dossier, 1, "4000000", DEBLOCAGE);

        assertThatThrownBy(() -> debloquer(dossier, 2, "6000000", LIMITE.plusDays(1)))
            .isInstanceOf(LoanMobilisationService.DrawdownPeriodClosedException.class)
            .hasMessageContaining("periode de mobilisation s'est achevee");
        assertThat(solde(dossier.decor().pret())).isEqualTo(xof("4000000"));
    }

    @Test
    @DisplayName("les tranches se debloquent dans l'ordre, et une seule fois")
    void ordreDesTranches() {
        Dossier dossier = dossier("M9", Map.of());

        assertThatThrownBy(() -> debloquer(dossier, 2, "6000000", FONDATIONS))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("n'est pas debloquee");

        debloquer(dossier, 1, "4000000", DEBLOCAGE);
        assertThatThrownBy(() -> mobilisationService.release(
            dossier.contrat(), 1, xof("4000000"), DEBLOCAGE, IdempotencyKey.of("TR|bis"), ACTOR,
            APPROVER))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ne se versent pas deux fois");

        assertThat(solde(dossier.decor().pret())).isEqualTo(xof("4000000"));
    }

    @Test
    @DisplayName("verser plus que le montant prevu augmenterait l'engagement sans decision")
    void depassementDuPlan() {
        Dossier dossier = dossier("MA", Map.of());
        assertThatThrownBy(() -> debloquer(dossier, 1, "5000000", DEBLOCAGE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Le plan se modifie, il ne se depasse pas");
    }

    @Test
    @DisplayName("le plan doit totaliser le capital accorde")
    void planIncoherent() {
        Decor decor = decor("MB");
        product(decor, "CRED-MB", Map.of());
        UUID contrat = contract(decor, "REF-MB", "CRED-MB", "10000000");

        DisbursementPlan incomplet = DisbursementPlan.of(Currencies.XOF)
            .tranche(DEBLOCAGE, xof("4000000"))
            .tranche(FONDATIONS, xof("5000000"))
            .deadline(LIMITE).build();

        assertThatThrownBy(() -> mobilisationService.open(contrat, incomplet, conditions(),
                                                          xof("0"), ACTOR, APPROVER))
            .isInstanceOf(InvalidDisbursementPlanException.class);
        assertThat(statut(contrat)).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("le cout previsionnel de la mobilisation est confronte au plafond d'usure")
    void plafondDUsureALOuverture() {
        Decor decor = decor("MC");
        Account frais = account(decor.entityId(), "MC-FRAIS", AccountKind.GL,
                                NormalBalance.CREDIT);
        product(decor, "CRED-MC", Map.of(LoanCatalog.P_FEE_INCOME, frais.id().toString(),
                                          LoanCatalog.P_USURY_RATE, "14"));
        UUID contrat = contract(decor, "REF-MC", "CRED-MC", "10000000");

        // C'est le seul moment ou le refus protege l'emprunteur : apres le premier versement, le
        // depassement ne se corrige plus.
        assertThatThrownBy(() -> mobilisationService.open(contrat, plan(), conditions(),
                                                          xof("900000"), ACTOR, APPROVER))
            .isInstanceOf(LoanService.UsuryCeilingExceededException.class)
            .hasMessageContaining("plafond d'usure");
        assertThat(statut(contrat)).isEqualTo("DRAFT");
        assertThat(tranches(contrat)).isEmpty();
    }

    // ------------------------------------------------------------------ taxe

    @Test
    @DisplayName("la taxe sur les interets s'applique aux interets intercalaires")
    void taxeSurLesIntercalaires() {
        Dossier dossier = dossier("MD", Map.of(), conditions("18"), xof("0"));
        debloquer(dossier, 1, "4000000", DEBLOCAGE);
        tfj(dossier, LocalDate.of(2026, 10, 15));

        // 40 767 d'interets, 7 338 de taxe. Le client doit les deux, et la creance porte le total :
        // la taxe est collectee aupres de lui, pas prise sur le produit de la banque.
        assertThat(solde(dossier.decor().produitsInterets())).isEqualTo(xof("40767"));
        assertThat(solde(dossier.decor().taxe())).isEqualTo(xof("7338"));
        assertThat(creances(dossier.contrat()))
            .singleElement()
            .satisfies(creance -> {
                assertThat(creance.category()).isEqualTo("INTEREST");
                assertThat(creance.original()).isEqualTo(xof("48105"));
                assertThat(creance.dueDate()).isEqualTo(LocalDate.of(2026, 10, 15));
            });
    }

    // ------------------------------------------------------------------ outillage

    private static List<Money> intercalaires(UUID contractId) {
        return lire("SELECT interest FROM loan_interim_interest WHERE contract_id = ?"
                    + " AND status = 'ACTIVE' ORDER BY period_end", contractId,
                    rs -> Money.of(rs.getBigDecimal(1), Currencies.XOF));
    }

    private static List<String> tranches(UUID contractId) {
        return lire("SELECT status FROM loan_tranche WHERE contract_id = ? ORDER BY number",
                    contractId, rs -> rs.getString(1));
    }

    /** Version d'echeancier, telle que les assertions la lisent. */
    protected record Version(int version, String reason, LocalDate effectiveFrom) {}

    private static List<Version> echeanciers(UUID contractId) {
        return lire("SELECT version, reason, effective_from FROM loan_schedule"
                    + " WHERE contract_id = ? ORDER BY version", contractId,
                    rs -> new Version(rs.getInt(1), rs.getString(2),
                                      rs.getObject(3, LocalDate.class)));
    }

    private static List<Money> echeances(UUID contractId) {
        return lire("SELECT l.total FROM loan_schedule_line l JOIN loan_schedule s"
                    + " ON s.id = l.schedule_id WHERE s.contract_id = ? ORDER BY l.number",
                    contractId, rs -> Money.of(rs.getBigDecimal(1), Currencies.XOF));
    }

    private static Money premiereEcheance(UUID contractId) {
        return echeances(contractId).get(0);
    }

    private static String statut(UUID contractId) {
        return lire("SELECT status FROM loan_contract WHERE id = ?", contractId,
                    rs -> rs.getString(1)).get(0);
    }

    private static Tranches.MobilisationRow mobilisation(UUID contractId) {
        return database.inTransaction(
            c -> Tranches.mobilisationOf(c, contractId, Currencies.XOF).orElseThrow());
    }

    private interface RowReader<T> {
        T read(java.sql.ResultSet rs) throws SQLException;
    }

    private static <T> List<T> lire(String sql, UUID contractId, RowReader<T> reader) {
        return database.inTransaction(c -> {
            List<T> rows = new java.util.ArrayList<>();
            try (var ps = c.prepareStatement(sql)) {
                ps.setObject(1, contractId);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(reader.read(rs));
                    }
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Lecture : " + sql, e);
            }
            return rows;
        });
    }
}
