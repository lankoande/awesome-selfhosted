package io.corebanking.tfj;

import static io.corebanking.kernel.money.Currencies.XOF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.store.Balances;
import io.corebanking.ledger.store.Reconciliation;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TfjEngineIT extends TfjTestBase {

    private Account cash() {
        return account("GL-CAISSE-" + UUID.randomUUID(), AccountKind.GL, NormalBalance.DEBIT);
    }

    private Account gl(NormalBalance sens) {
        return account("GL-" + UUID.randomUUID(), AccountKind.GL, sens);
    }

    /**
     * Rattache le compte a un produit dont la seule version est echue : le rattachement est
     * legitime — le produit existe, dans la bonne devise —, mais il ne se resout pas a la journee
     * traitee. C'est le seul chemin qui reste vers un compte sans parametrage : rattacher un compte
     * a un produit qui n'existe pas est refuse a la saisie.
     */
    private void bindToExpiredProduct(Account account, String productCode,
                                      java.time.LocalDate jour) {
        Account charges = gl(NormalBalance.DEBIT);
        Account courus = gl(NormalBalance.CREDIT);
        database.inTransaction(c -> {
            UUID version = io.corebanking.product.ProductCatalog.createDraft(
                c, new io.corebanking.product.ProductCatalog.Draft(
                    ENTITY, productCode, "SAVINGS_ACCOUNT", "Epargne echue", "XOF",
                    J1.minusMonths(3), J1.minusMonths(2),
                    java.util.Map.of(io.corebanking.product.ProductCatalog.P_RATE, "6",
                                     io.corebanking.product.ProductCatalog.P_DAY_COUNT, "ACT_365",
                                     io.corebanking.product.ProductCatalog.P_SIDE, "CREDITOR",
                                     io.corebanking.product.ProductCatalog.P_CAPITALISATION,
                                     "QUARTERLY",
                                     io.corebanking.product.ProductCatalog.P_DEBIT_ACCOUNT,
                                     charges.id().toString(),
                                     io.corebanking.product.ProductCatalog.P_CREDIT_ACCOUNT,
                                     courus.id().toString()),
                    java.util.List.of(), ACTOR));
            io.corebanking.product.ProductCatalog.activate(c, version, APPROVER);
            io.corebanking.product.ProductCatalog.assignProduct(c, account.id(), productCode,
                                                                 jour.minusDays(1), null);
            return null;
        });
    }

    @Test
    @DisplayName("un TFJ complet remunere, arrete les soldes, controle, puis bascule la journee")
    void a_complete_run_accrues_snapshots_checks_then_rolls_the_day() {
        Account charges = gl(NormalBalance.DEBIT);
        Account courus = gl(NormalBalance.CREDIT);
        Account caisse = cash();
        var jour = businessDate();
        Account client = savingsAccount("CLI-700", charges, courus, "EP-700");
        deposit(client, caisse, "10000000", jour, "dep-700");

        TfjRun run = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);

        assertThat(run.isCompleted()).as(run.summary()).isTrue();
        assertThat(run.steps()).extracting(TfjRun.StepExecution::name)
            .containsExactly("PRE_CHECKS", "FX_RATES", "HOLD_EXPIRY", "DIRECT_DEBITS", "FEE_CHARGING",
                             "LOAN_MOBILISATION",
                             "LOAN_SCHEDULE", "LOAN_INTEREST_ACCRUAL", "LOAN_LATE_CHARGES",
                             "LOAN_CLASSIFICATION", "LOAN_CLOSURE", "INTEREST_ACCRUAL",
                             "INTEREST_SETTLEMENT", "FX_REVALUATION", "DORMANCY", "KYC_REVIEW",
                             "DOCUMENT_EXPIRY", "OFFER_EXPIRY", "SUSPENSE_REVIEW",
                             "BALANCE_SNAPSHOT",
                             "RECONCILIATION", "OPEN_NEXT_DAY");
        assertThat(run.steps()).allMatch(
            step -> step.status() == TfjRun.StepExecution.Status.COMPLETED);

        // 10 000 000 a 6 % sur une journee en ACT/365 : 1 643,835 XOF, arrondi a 1 644.
        database.inTransaction(c -> {
            assertThat(Balances.current(c, courus.id())).isEqualTo(Money.of("1644", XOF));
            assertThat(Reconciliation.allBlockingChecks(c, ENTITY)).isEmpty();
            return null;
        });

        // La journee a bascule : c'est la derniere etape, et elle seule y est autorisee.
        assertThat(businessDate()).isEqualTo(calendar.nextBusinessDay(jour));
    }

    @Test
    @DisplayName("relancer le TFJ d'une journee deja arretee renvoie son rapport, sans rien rejouer")
    void re_running_a_closed_day_returns_the_existing_result() {
        Account charges = gl(NormalBalance.DEBIT);
        Account courus = gl(NormalBalance.CREDIT);
        var jour = businessDate();
        Account client = savingsAccount("CLI-701", charges, courus, "EP-701");
        deposit(client, cash(), "1000000", jour, "dep-701");

        TfjRun premier = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);
        assertThat(premier.isCompleted()).isTrue();
        Money apres = database.inTransaction(c -> Balances.current(c, courus.id()));

        TfjRun second = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);

        // Meme traitement, meme rapport : la relance est une lecture, pas une reexecution.
        assertThat(second.id()).isEqualTo(premier.id());
        assertThat(second.isCompleted()).isTrue();
        database.inTransaction(c ->
            assertThat(Balances.current(c, courus.id())).isEqualTo(apres));
    }

    @Test
    @DisplayName("sauter une journee est impossible : le TFJ ne traite que la date courante")
    void skipping_a_day_is_impossible() {
        var jour = businessDate();

        assertThatThrownBy(() -> engine.run(ENTITY, jour.plusDays(1), ACTOR, RunMode.REAL))
            .isInstanceOf(TfjEngine.TfjRefusedException.class)
            .hasMessageContaining("jamais en fusionnant des journees");
    }

    @Test
    @DisplayName("un TFJ a blanc produit le rapport complet et ne laisse aucune trace comptable")
    void a_dry_run_reports_everything_and_leaves_nothing() {
        Account charges = gl(NormalBalance.DEBIT);
        Account courus = gl(NormalBalance.CREDIT);
        Account client = savingsAccount("CLI-702", charges, courus, "EP-702");
        var jour = businessDate();
        deposit(client, cash(), "5000000", jour, "dep-702");

        Money avant = database.inTransaction(c -> Balances.current(c, courus.id()));

        TfjRun blanc = engine.run(ENTITY, jour, ACTOR, RunMode.DRY_RUN);

        // Le rapport existe et toutes les etapes ont reellement tourne...
        assertThat(blanc.isCompleted()).as(blanc.summary()).isTrue();
        assertThat(blanc.steps()).allMatch(
            step -> step.status() == TfjRun.StepExecution.Status.COMPLETED);
        assertThat(step(blanc, "INTEREST_ACCRUAL").written()).isPositive();

        // ... et pourtant rien n'a ete conserve : ni ecriture, ni bascule de journee.
        database.inTransaction(c ->
            assertThat(Balances.current(c, courus.id())).isEqualTo(avant));
        assertThat(businessDate()).isEqualTo(jour);
        assertThat(countEntries(blanc.id())).isZero();
    }

    private static TfjRun.StepExecution step(TfjRun run, String name) {
        return run.steps().stream().filter(step -> step.name().equals(name)).findFirst()
            .orElseThrow(() -> new AssertionError("Etape " + name + " absente du rapport"));
    }

    @Test
    @DisplayName("un TFJ a blanc n'empeche pas le TFJ reel de la meme journee")
    void a_dry_run_does_not_block_the_real_run() {
        var jour = businessDate();
        engine.run(ENTITY, jour, ACTOR, RunMode.DRY_RUN);

        TfjRun reel = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);

        assertThat(reel.isCompleted()).isTrue();
        assertThat(businessDate()).isEqualTo(calendar.nextBusinessDay(jour));
    }

    @Test
    @DisplayName("un compte dont le parametrage ne se resout pas arrete le TFJ, en nommant le compte")
    void an_account_without_resolvable_product_stops_the_run() {
        Account orphelin = account("CLI-703", AccountKind.CUSTOMER, NormalBalance.CREDIT);
        var jour = businessDate();
        bindToExpiredProduct(orphelin, "PRODUIT-ECHU-703", jour);

        TfjRun run = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);

        assertThat(run.status()).isEqualTo(TfjRun.Status.FAILED);
        var etape = run.failedStep().orElseThrow();
        // La premiere etape qui resout le parametrage du compte est celle qui bute dessus.
        assertThat(etape.name()).isEqualTo("FEE_CHARGING");
        assertThat(etape.anomalies().toString()).contains(orphelin.id().toString());

        // La journee n'a pas bascule : une etape bloquante en echec arrete la chaine.
        assertThat(businessDate()).isEqualTo(jour);
    }

    @Test
    @DisplayName("un TFJ en echec se reprend a l'etape fautive, les etapes franchies ne sont pas rejouees")
    void a_failed_run_resumes_at_the_failing_step() {
        Account orphelin = account("CLI-704", AccountKind.CUSTOMER, NormalBalance.CREDIT);
        var jour = businessDate();
        bindToExpiredProduct(orphelin, "PRODUIT-ECHU-704", jour);

        TfjRun echoue = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);
        assertThat(echoue.status()).isEqualTo(TfjRun.Status.FAILED);

        // Lancer un second TFJ est refuse : on reprend, on ne duplique pas.
        assertThatThrownBy(() -> engine.run(ENTITY, jour, ACTOR, RunMode.REAL))
            .isInstanceOf(TfjEngine.TfjRefusedException.class)
            .hasMessageContaining("Le reprendre");

        // Correction du parametrage : le rattachement fautif est clos.
        database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "DELETE FROM account_product WHERE account_id = ?")) {
                ps.setObject(1, orphelin.id());
                ps.executeUpdate();
                return null;
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        });

        TfjRun repris = engine.resume(echoue.id(), ACTOR);

        assertThat(repris.isCompleted()).as(repris.summary()).isTrue();
        assertThat(businessDate()).isEqualTo(calendar.nextBusinessDay(jour));
    }

    @Test
    @DisplayName("annuler un TFJ contre-passe ses ecritures et restaure la date comptable")
    void cancelling_a_run_reverses_its_entries_and_restores_the_date() {
        Account charges = gl(NormalBalance.DEBIT);
        Account courus = gl(NormalBalance.CREDIT);
        Account client = savingsAccount("CLI-705", charges, courus, "EP-705");
        var jour = businessDate();
        deposit(client, cash(), "20000000", jour, "dep-705");

        Money avant = database.inTransaction(c -> Balances.current(c, courus.id()));
        TfjRun run = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);
        assertThat(run.isCompleted()).isTrue();
        assertThat(countEntries(run.id())).isPositive();

        TfjRun annule = engine.cancel(run.id(), ACTOR, jour.plusDays(1),
                                      "Bareme errone applique a l'arrete");

        assertThat(annule.status()).isEqualTo(TfjRun.Status.CANCELLED);
        database.inTransaction(c -> {
            // Le solde revient exactement a son etat d'avant l'arrete...
            assertThat(Balances.current(c, courus.id())).isEqualTo(avant);
            // ... et le journal reste equilibre : l'annulation passe par des extournes, jamais
            // par une suppression.
            assertThat(Reconciliation.allBlockingChecks(c, ENTITY)).isEmpty();
            return null;
        });
        assertThat(businessDate()).isEqualTo(jour);
    }

    @Test
    @DisplayName("apres annulation, la journee peut etre arretee de nouveau et les interets recalcules")
    void after_cancellation_the_day_can_be_closed_again() {
        Account charges = gl(NormalBalance.DEBIT);
        Account courus = gl(NormalBalance.CREDIT);
        Account client = savingsAccount("CLI-706", charges, courus, "EP-706");
        var jour = businessDate();
        deposit(client, cash(), "10000000", jour, "dep-706");

        TfjRun premier = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);
        Money apresPremier = database.inTransaction(c -> Balances.current(c, courus.id()));
        engine.cancel(premier.id(), ACTOR, jour, "Reprise de l'arrete");

        // Les journees calculees par le TFJ annule ont ete neutralisees : sans cela, le moteur les
        // croirait deja remunerees et elles ne le seraient plus jamais.
        TfjRun second = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);

        assertThat(second.isCompleted()).as(second.summary()).isTrue();
        database.inTransaction(c -> {
            assertThat(Balances.current(c, courus.id())).isEqualTo(apresPremier);
            assertThat(Reconciliation.allBlockingChecks(c, ENTITY)).isEmpty();
            return null;
        });
    }

    @Test
    @DisplayName("une journee ne s'annule pas tant qu'une journee suivante est arretee")
    void cancelling_a_day_behind_a_later_run_is_refused() {
        var jour = businessDate();
        TfjRun premier = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);
        var lendemain = businessDate();
        TfjRun second = engine.run(ENTITY, lendemain, ACTOR, RunMode.REAL);
        assertThat(second.isCompleted()).as(second.summary()).isTrue();

        // Restaurer la date a J alors que J+1 a tourne laisserait J+1 tenue pour faite sur un etat
        // que ses ecritures ne decrivent plus, et la date comptable bloquee sur J+1.
        assertThatThrownBy(() -> engine.cancel(premier.id(), ACTOR, lendemain, "erreur"))
            .isInstanceOf(TfjEngine.TfjRefusedException.class)
            .hasMessageContaining(lendemain.toString())
            .hasMessageContaining("du plus recent au plus ancien");
        assertThat(businessDate()).isAfter(lendemain);   // rien n'a bouge

        // Dans l'ordre, les deux annulations passent et la date revient a J.
        engine.cancel(second.id(), ACTOR, lendemain, "erreur");
        engine.cancel(premier.id(), ACTOR, lendemain, "erreur");
        assertThat(businessDate()).isEqualTo(jour);
    }

    @Test
    @DisplayName("un TFJ a blanc ne s'annule pas : il n'a rien laisse")
    void a_dry_run_cannot_be_cancelled() {
        var jour = businessDate();
        TfjRun blanc = engine.run(ENTITY, jour, ACTOR, RunMode.DRY_RUN);

        assertThatThrownBy(() -> engine.cancel(blanc.id(), ACTOR, jour, "motif"))
            .isInstanceOf(TfjEngine.TfjRefusedException.class)
            .hasMessageContaining("rien a annuler");
    }

    @Test
    @DisplayName("une annulation sans motif est refusee")
    void a_cancellation_without_reason_is_refused() {
        var jour = businessDate();
        TfjRun run = engine.run(ENTITY, jour, ACTOR, RunMode.REAL);

        assertThatThrownBy(() -> engine.cancel(run.id(), ACTOR, jour, "  "))
            .isInstanceOf(TfjEngine.TfjRefusedException.class)
            .hasMessageContaining("Motif d'annulation obligatoire");
    }

    @Test
    @DisplayName("la journee bascule au jour ouvre suivant, et le lundi remunere tout le week-end")
    void the_day_rolls_to_the_next_business_day_and_monday_pays_the_weekend() {
        Account charges = gl(NormalBalance.DEBIT);
        Account courus = gl(NormalBalance.CREDIT);

        // On se place un vendredi : il faut arreter les journees jusque-la.
        while (businessDate().getDayOfWeek() != java.time.DayOfWeek.FRIDAY) {
            engine.run(ENTITY, businessDate(), ACTOR, RunMode.REAL);
        }
        var vendredi = businessDate();

        Account client = savingsAccount("CLI-710", charges, courus, "EP-710");
        deposit(client, cash(), "10000000", vendredi, "dep-710");

        engine.run(ENTITY, vendredi, ACTOR, RunMode.REAL);
        Money apresVendredi = database.inTransaction(c -> Balances.current(c, courus.id()));

        // Ni samedi ni dimanche ne sont arretes : la journee saute au lundi.
        var lundi = businessDate();
        assertThat(lundi).isEqualTo(vendredi.plusDays(3));
        assertThat(lundi.getDayOfWeek()).isEqualTo(java.time.DayOfWeek.MONDAY);

        engine.run(ENTITY, lundi, ACTOR, RunMode.REAL);
        Money apresLundi = database.inTransaction(c -> Balances.current(c, courus.id()));

        // Le TFJ du lundi remunere samedi, dimanche et lundi : trois journees en une fois.
        // 10 000 000 a 6 % en ACT/365 valent 1 643,8356 par jour ; le cumul exact de quatre
        // journees est 6 575,34, arrondi a 6 575, contre 1 644 apres la seule journee du vendredi.
        assertThat(apresVendredi).isEqualTo(Money.of("1644", XOF));
        assertThat(apresLundi).isEqualTo(Money.of("6575", XOF));
    }
}
