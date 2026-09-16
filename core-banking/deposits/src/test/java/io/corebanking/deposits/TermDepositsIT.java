package io.corebanking.deposits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.time.Periodicity;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.NormalBalance;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Les depots a terme : un taux fige au contrat, un capital bloque, un terme qui denoue, et une
 * rupture qui coute son prix.
 */
class TermDepositsIT extends DepositsTestBase {

    private TermDepositService service() {
        return new TermDepositService(database, postingService);
    }

    @Test
    @DisplayName("un depot a terme bloque son capital, court a son taux, et verse capital et interets nets au terme")
    void a_term_deposit_runs_to_its_maturity() {
        Decor decor = decor("DAT1");
        Bureau bureau = bureau(decor, "DAT1");
        produitDat(decor, bureau, "DAT1", "5", null);
        UUID client = client(decor.entityId(), "CLI-DAT1");
        UUID courant = ouvrir(decor, "DAT1-COURANT", "CC-DAT1", client);
        UUID depot = ouvrir(decor, "DAT1-DEPOT", "DAT-DAT1", client);
        verser(decor, courant, "10000000", "dat1-prov");

        TermDepositService.TermDeposit dat = service().subscribe(new TermDepositService.Draft(
            decor.entityId(), "DAT-2026-001", depot, courant, xof("5000000"), null, 3, null,
            TermDepositService.MaturityInstruction.PAY_OUT, ACTOR, APPROVER));
        assertThat(dat.annualRatePercent()).isEqualByComparingTo("5");
        assertThat(dat.valueDate()).isEqualTo(J);
        assertThat(dat.maturityDate()).isEqualTo(LocalDate.of(2026, 12, 15));
        assertThat(solde(depot)).isEqualTo(xof("5000000"));
        assertThat(solde(courant)).isEqualTo(xof("5000000"));

        // Le capital est bloque : aucun chemin de debit ne l'entame, pas meme le guichet.
        assertThatThrownBy(() -> retirer(decor, depot, "1", "dat1-retrait"))
            .hasMessageContaining("blocage DEBIT")
            .hasMessageContaining("DAT-2026-001");

        // Un second contrat sur le meme compte n'a pas de sens : le solde serait celui de deux
        // capitaux aux taux et aux termes differents.
        assertThatThrownBy(() -> service().subscribe(new TermDepositService.Draft(
                decor.entityId(), "DAT-2026-002", depot, courant, xof("1000000"), null, 12, null,
                TermDepositService.MaturityInstruction.PAY_OUT, ACTOR, APPROVER)))
            .isInstanceOf(TermDepositService.TermDepositRefusedException.class)
            .hasMessageContaining("solde");

        // Trente journees d'interets : 5 000 000 x 5 % x 30 / 365 = 20 547,94 -> 20 548.
        UUID run = UUID.randomUUID();
        TermDepositService.Accrued accrued = service().accrue(decor.entityId(), J.plusDays(29),
                                                               run, ACTOR);
        assertThat(accrued.deposits()).isEqualTo(1);
        assertThat(accrued.amount()).isEqualTo(xof("20548"));
        assertThat(solde(bureau.courus())).isEqualTo(xof("20548"));
        assertThat(solde(bureau.charges())).isEqualTo(xof("20548"));

        // Rattraper en une passe ou journee par journee donne la meme somme : le cumul est tenu
        // en precision entiere et l'impute est un ecart.
        service().accrue(decor.entityId(), J.plusDays(30), run, ACTOR);
        service().accrue(decor.entityId(), J.plusDays(31), run, ACTOR);
        assertThat(solde(bureau.courus())).isEqualTo(xof("21918"));

        // Au terme : les interets des 91 journees du trimestre, retenue de 10 %, capital rendu.
        dater(decor, LocalDate.of(2026, 12, 15));
        service().accrue(decor.entityId(), LocalDate.of(2026, 12, 15), run, ACTOR);
        TermDepositService.Payment payment = service().settle(dat.id(), run, ACTOR);
        assertThat(payment.matured()).isTrue();
        // 91 journees a 5 % sur 5 000 000 : 62 329 ; retenue 10 % : 6 233.
        assertThat(payment.interestGross()).isEqualTo(xof("62329"));
        assertThat(payment.withheld()).isEqualTo(xof("6233"));
        assertThat(payment.interestNet()).isEqualTo(xof("56096"));
        assertThat(payment.principalPaid()).isEqualTo(xof("5000000"));
        assertThat(payment.renewedAs()).isNull();
        assertThat(solde(depot).isZero()).as("le capital est sorti").isTrue();
        assertThat(solde(courant)).isEqualTo(xof("10056096"));
        assertThat(solde(bureau.courus()).isZero()).as("le sous-livre est solde").isTrue();
        assertThat(solde(bureau.irc())).isEqualTo(xof("6233"));

        TermDepositService.TermDeposit clos = lire(dat.id());
        assertThat(clos.status()).isEqualTo("MATURED");
        assertThat(clos.outstandingInterest().isZero()).isTrue();
        // Le blocage tombe avec le contrat : le compte redevient un compte.
        List<AccountLifecycle.ActiveBlock> blocages = database.inTransaction(
            c -> AccountLifecycle.activeBlocks(c, depot));
        assertThat(blocages).isEmpty();
    }

    @Test
    @DisplayName("un depot reconduit repart au taux du jour, pour la meme duree, sans que le capital soit jamais libre")
    void a_renewed_deposit_restarts_at_the_rate_of_the_day() {
        Decor decor = decor("DAT2");
        Bureau bureau = bureau(decor, "DAT2");
        produitDat(decor, bureau, "DAT2", "5", null);
        UUID client = client(decor.entityId(), "CLI-DAT2");
        UUID courant = ouvrir(decor, "DAT2-COURANT", "CC-DAT2", client);
        UUID depot = ouvrir(decor, "DAT2-DEPOT", "DAT-DAT2", client);
        verser(decor, courant, "10000000", "dat2-prov");

        TermDepositService.TermDeposit dat = service().subscribe(new TermDepositService.Draft(
            decor.entityId(), "DAT-2026-010", depot, courant, xof("4000000"), null, 3, null,
            TermDepositService.MaturityInstruction.RENEW_ALL, ACTOR, APPROVER));
        UUID run = UUID.randomUUID();
        dater(decor, LocalDate.of(2026, 12, 15));
        service().accrue(decor.entityId(), LocalDate.of(2026, 12, 15), run, ACTOR);

        // Entre-temps la banque a baisse son bareme : la reconduction en tient compte, le
        // contrat echu non.
        retarifer(decor, bureau, "DAT2", "3", LocalDate.of(2026, 12, 15));
        TermDepositService.Payment payment = service().settle(dat.id(), run, ACTOR);
        assertThat(payment.matured()).isTrue();
        assertThat(payment.renewedAs()).isNotNull();
        // 91 journees a 5 % sur 4 000 000 : 49 863 ; retenue 10 % : 4 986 ; net 44 877.
        assertThat(payment.interestGross()).isEqualTo(xof("49863"));
        assertThat(payment.interestNet()).isEqualTo(xof("44877"));
        assertThat(solde(courant)).as("rien n'est revenu au client").isEqualTo(xof("6000000"));
        assertThat(solde(depot)).as("le capital a grossi de ses interets nets")
            .isEqualTo(xof("4044877"));

        TermDepositService.TermDeposit suite = lire(payment.renewedAs());
        assertThat(suite.annualRatePercent()).as("le bareme du jour, pas celui d'hier")
            .isEqualByComparingTo("3");
        assertThat(suite.principal()).isEqualTo(xof("4044877"));
        assertThat(suite.valueDate()).isEqualTo(LocalDate.of(2026, 12, 15));
        assertThat(suite.termMonths()).isEqualTo(3);
        assertThat(suite.renewalOf()).isEqualTo(dat.id());
        assertThat(lire(dat.id()).renewedAs()).isEqualTo(suite.id());
        // Le blocage n'a pas ete leve : le capital n'a pas ete libre un instant.
        List<AccountLifecycle.ActiveBlock> blocages = database.inTransaction(
            c -> AccountLifecycle.activeBlocks(c, depot));
        assertThat(blocages).hasSize(1);
        assertThat(suite.blockId()).isEqualTo(blocages.getFirst().id());
    }

    @Test
    @DisplayName("une rupture avant terme ramene les interets au taux de penalite et reprend ce qui a ete constate au-dela")
    void an_early_break_costs_its_price() {
        Decor decor = decor("DAT3");
        Bureau bureau = bureau(decor, "DAT3");
        produitDat(decor, bureau, "DAT3", "6", "1");
        UUID client = client(decor.entityId(), "CLI-DAT3");
        UUID courant = ouvrir(decor, "DAT3-COURANT", "CC-DAT3", client);
        UUID depot = ouvrir(decor, "DAT3-DEPOT", "DAT-DAT3", client);
        verser(decor, courant, "10000000", "dat3-prov");

        TermDepositService.TermDeposit dat = service().subscribe(new TermDepositService.Draft(
            decor.entityId(), "DAT-2026-020", depot, courant, xof("6000000"), null, 6, null,
            TermDepositService.MaturityInstruction.PAY_OUT, ACTOR, APPROVER));
        UUID run = UUID.randomUUID();
        // Soixante journees courues au taux du contrat : 6 000 000 x 6 % x 60 / 365 = 59 178.
        service().accrue(decor.entityId(), J.plusDays(59), run, ACTOR);
        assertThat(solde(bureau.courus())).isEqualTo(xof("59178"));

        // Le client reprend ses fonds au 61e jour : les interets sont ramenes a 1 %.
        dater(decor, J.plusDays(60));
        TermDepositService.Break rupture = service().breakEarly(dat.id(), "besoin de tresorerie",
                                                                 ACTOR, APPROVER);
        // 6 000 000 x 1 % x 60 / 365 = 9 863.
        assertThat(rupture.interestDue()).isEqualTo(xof("9863"));
        assertThat(rupture.clawedBack()).as("ce qui avait ete constate au-dela")
            .isEqualTo(xof("49315"));
        assertThat(rupture.withheld()).isEqualTo(xof("986"));
        assertThat(rupture.paidOut()).isEqualTo(xof("6008877"));
        assertThat(solde(depot).isZero()).isTrue();
        assertThat(solde(bureau.courus()).isZero()).as("le sous-livre est solde").isTrue();
        // La charge nette de la banque est ce que la rupture laisse, retenue comprise.
        assertThat(solde(bureau.charges())).isEqualTo(xof("9863"));
        assertThat(solde(courant)).isEqualTo(xof("10008877"));

        TermDepositService.TermDeposit rompu = lire(dat.id());
        assertThat(rompu.status()).isEqualTo("BROKEN");
        assertThat(rompu.breakReason()).isEqualTo("besoin de tresorerie");
        // Rompu, il ne se rompt plus, et le terme ne se denoue plus.
        assertThatThrownBy(() -> service().breakEarly(dat.id(), "encore", ACTOR, APPROVER))
            .isInstanceOf(TermDepositService.TermDepositRefusedException.class)
            .hasMessageContaining("BROKEN");
    }

    @Test
    @DisplayName("rompu apres avoir deja percu des interets, le client rend le trop-percu et l'ecriture reste equilibree")
    void a_break_after_interest_was_paid_claws_it_back() {
        Decor decor = decor("DAT5");
        Bureau bureau = bureau(decor, "DAT5");
        produitDat(decor, bureau, "DAT5", "6", "1");
        UUID client = client(decor.entityId(), "CLI-DAT5");
        UUID courant = ouvrir(decor, "DAT5-COURANT", "CC-DAT5", client);
        UUID depot = ouvrir(decor, "DAT5-DEPOT", "DAT-DAT5", client);
        verser(decor, courant, "10000000", "dat5-prov");

        // Un DAT de six mois qui sert ses interets chaque mois.
        TermDepositService.TermDeposit dat = service().subscribe(new TermDepositService.Draft(
            decor.entityId(), "DAT-2026-050", depot, courant, xof("6000000"), null, 6,
            Periodicity.MONTHLY, TermDepositService.MaturityInstruction.PAY_OUT, ACTOR, APPROVER));
        assertThat(dat.nextPaymentDate()).isEqualTo(LocalDate.of(2026, 10, 15));

        // Premiere echeance : les interets du mois sont verses au client.
        UUID run = UUID.randomUUID();
        dater(decor, LocalDate.of(2026, 10, 15));
        service().accrue(decor.entityId(), LocalDate.of(2026, 10, 15), run, ACTOR);
        TermDepositService.Payment servi = service().settle(dat.id(), run, ACTOR);
        assertThat(servi.matured()).isFalse();
        // Les 31 journees courues jusqu'a l'echeance comprise : 6 000 000 x 6 % x 31 / 365 =
        // 30 575 ; retenue 10 % : 3 058 ; net 27 517. La periode suivante repart le lendemain :
        // le jour d'une echeance appartient a la periode qu'elle ferme, une fois pour toutes.
        assertThat(servi.interestGross()).isEqualTo(xof("30575"));
        assertThat(servi.interestNet()).isEqualTo(xof("27517"));
        assertThat(solde(courant)).isEqualTo(xof("4027517"));
        assertThat(solde(bureau.courus()).isZero()).as("la periode est soldee").isTrue();

        // Le client rompt le lendemain : au taux de penalite, il n'avait droit qu'a 5 096 sur
        // les 31 journees courues — il a percu 30 575 bruts, il rend la difference.
        dater(decor, LocalDate.of(2026, 10, 16));
        service().accrue(decor.entityId(), LocalDate.of(2026, 10, 16), run, ACTOR);
        TermDepositService.Break rupture = service().breakEarly(dat.id(), "achat immobilier",
                                                                 ACTOR, APPROVER);
        assertThat(rupture.interestDue()).isEqualTo(xof("5096"));
        assertThat(rupture.paidOut()).as("le capital seul : le trop-percu est repris")
            .isEqualTo(xof("6000000"));
        assertThat(solde(depot).isZero()).isTrue();
        assertThat(solde(bureau.courus()).isZero()).as("le sous-livre est solde").isTrue();
        // La charge nette de la banque est ce que la rupture laisse.
        assertThat(solde(bureau.charges())).isEqualTo(xof("5096"));
        // 4 027 517 + 6 000 000 − 25 479 rendus : le client garde ce que la rupture lui laisse,
        // la retenue deja prelevee restant acquise au Tresor.
        assertThat(solde(courant)).isEqualTo(xof("10002038"));

        TermDepositService.TermDeposit rompu = lire(dat.id());
        assertThat(rompu.status()).isEqualTo("BROKEN");
        assertThat(rompu.outstandingInterest().isZero()).isTrue();
    }

    @Test
    @DisplayName("un compte que vise un depot a terme vivant ne se clot pas, pas meme celui qui le regle")
    void an_account_bound_to_a_live_deposit_does_not_close() {
        Decor decor = decor("DAT6");
        Bureau bureau = bureau(decor, "DAT6");
        produitDat(decor, bureau, "DAT6", "5", null);
        UUID client = client(decor.entityId(), "CLI-DAT6");
        UUID courant = ouvrir(decor, "DAT6-COURANT", "CC-DAT6", client);
        UUID depot = ouvrir(decor, "DAT6-DEPOT", "DAT-DAT6", client);
        verser(decor, courant, "5000000", "dat6-prov");
        service().subscribe(new TermDepositService.Draft(
            decor.entityId(), "DAT-2026-060", depot, courant, xof("2000000"), null, 3, null,
            TermDepositService.MaturityInstruction.PAY_OUT, ACTOR, APPROVER));

        // Le compte de depot est tenu par son blocage ; le compte de reglement, lui, ne l'est
        // par rien d'autre que cette regle.
        assertThatThrownBy(() -> lifecycle.close(new AccountLifecycle.Closing(
                courant, decor.caisse().id(), ACTOR, APPROVER)))
            .isInstanceOf(AccountLifecycle.ClosureRefusedException.class)
            .hasMessageContaining("DAT-2026-060");
        assertThatThrownBy(() -> lifecycle.close(new AccountLifecycle.Closing(
                depot, decor.caisse().id(), ACTOR, APPROVER)))
            .isInstanceOf(AccountLifecycle.ClosureRefusedException.class);
    }

    @Test
    @DisplayName("le produit borne ce qu'une agence peut consentir : montant, duree et taux")
    void the_product_bounds_what_a_branch_may_grant() {
        Decor decor = decor("DAT4");
        Bureau bureau = bureau(decor, "DAT4");
        produitDat(decor, bureau, "DAT4", "5", null);
        UUID client = client(decor.entityId(), "CLI-DAT4");
        UUID courant = ouvrir(decor, "DAT4-COURANT", "CC-DAT4", client);
        UUID depot = ouvrir(decor, "DAT4-DEPOT", "DAT-DAT4", client);
        verser(decor, courant, "10000000", "dat4-prov");

        // Sous le minimum du produit.
        assertThatThrownBy(() -> service().subscribe(new TermDepositService.Draft(
                decor.entityId(), "DAT-2026-030", depot, courant, xof("500000"), null, 6, null,
                TermDepositService.MaturityInstruction.PAY_OUT, ACTOR, APPROVER)))
            .isInstanceOf(TermDepositService.TermDepositRefusedException.class)
            .hasMessageContaining("au moins");

        // Hors des durees admises.
        assertThatThrownBy(() -> service().subscribe(new TermDepositService.Draft(
                decor.entityId(), "DAT-2026-031", depot, courant, xof("2000000"), null, 1, null,
                TermDepositService.MaturityInstruction.PAY_OUT, ACTOR, APPROVER)))
            .isInstanceOf(TermDepositService.TermDepositRefusedException.class)
            .hasMessageContaining("durees de 3 a 60 mois");

        // Au-dela du plafond de taux : le prix de la ressource ne se fixe pas en agence.
        assertThatThrownBy(() -> service().subscribe(new TermDepositService.Draft(
                decor.entityId(), "DAT-2026-032", depot, courant, xof("2000000"),
                new java.math.BigDecimal("9"), 6, null,
                TermDepositService.MaturityInstruction.PAY_OUT, ACTOR, APPROVER)))
            .isInstanceOf(TermDepositService.TermDepositRefusedException.class)
            .hasMessageContaining("plafond");

        // Une souscription se decide a deux.
        assertThatThrownBy(() -> new TermDepositService.Draft(
                decor.entityId(), "DAT-2026-033", depot, courant, xof("2000000"), null, 6, null,
                TermDepositService.MaturityInstruction.PAY_OUT, ACTOR, ACTOR))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a deux");

        // Dans les bornes, le taux consenti est celui du contrat.
        TermDepositService.TermDeposit dat = service().subscribe(new TermDepositService.Draft(
            decor.entityId(), "DAT-2026-034", depot, courant, xof("2000000"),
            new java.math.BigDecimal("6.5"), 6, null,
            TermDepositService.MaturityInstruction.PAY_OUT, ACTOR, APPROVER));
        assertThat(dat.annualRatePercent()).isEqualByComparingTo("6.5");
    }

    // ------------------------------------------------------------------ outillage

    /** Les comptes generaux d'un produit de depot a terme. */
    protected record Bureau(io.corebanking.ledger.domain.account.Account courus,
                            io.corebanking.ledger.domain.account.Account charges,
                            io.corebanking.ledger.domain.account.Account irc) {}

    private static Bureau bureau(Decor decor, String code) {
        return new Bureau(
            account(decor.entityId(), "COURUS-" + code, AccountKind.GL, NormalBalance.CREDIT),
            account(decor.entityId(), "CHARGES-" + code, AccountKind.GL, NormalBalance.DEBIT),
            account(decor.entityId(), "IRC-" + code, AccountKind.GL, NormalBalance.CREDIT));
    }

    /** Un produit de depot a terme, et le compte courant qui lui sert de contrepartie. */
    private static void produitDat(Decor decor, Bureau bureau, String suffixe, String taux,
                                   String penalite) {
        String code = "DAT-" + suffixe;
        produit(decor, "CC-" + suffixe, "CURRENT_ACCOUNT", frais(decor));
        Map<String, String> parametres = parametresDat(bureau, taux, penalite);
        database.inTransaction(c -> {
            UUID version = io.corebanking.product.ProductCatalog.createDraft(
                c, new io.corebanking.product.ProductCatalog.Draft(
                    decor.entityId(), code, "TERM_DEPOSIT", "Produit " + code, "XOF",
                    J.minusMonths(14), null, parametres, List.of(), ACTOR));
            io.corebanking.product.ProductCatalog.activate(c, version, APPROVER);
            return null;
        });
    }

    /** Une nouvelle version du produit, a compter d'une date : le bareme a change. */
    private static void retarifer(Decor decor, Bureau bureau, String suffixe, String taux,
                                  LocalDate from) {
        String code = "DAT-" + suffixe;
        database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "UPDATE product_version SET valid_to = ? WHERE code = ?"
                + "   AND legal_entity_id = ? AND valid_to IS NULL")) {
                ps.setObject(1, from.minusDays(1));
                ps.setString(2, code);
                ps.setObject(3, decor.entityId());
                ps.executeUpdate();
            } catch (java.sql.SQLException e) {
                throw new io.corebanking.ledger.store.LedgerStoreException("Cloture de version", e);
            }
            UUID version = io.corebanking.product.ProductCatalog.createDraft(
                c, new io.corebanking.product.ProductCatalog.Draft(
                    decor.entityId(), code, "TERM_DEPOSIT", "Produit " + code, "XOF", from, null,
                    parametresDat(bureau, taux, null), List.of(), ACTOR));
            io.corebanking.product.ProductCatalog.activate(c, version, APPROVER);
            return null;
        });
    }

    private static Map<String, String> parametresDat(Bureau bureau, String taux,
                                                     String penalite) {
        Map<String, String> parametres = new LinkedHashMap<>();
        parametres.put(TermDepositCatalog.P_RATE, taux);
        parametres.put(TermDepositCatalog.P_MAX_RATE, "7");
        parametres.put(TermDepositCatalog.P_DAY_COUNT, "ACT_365");
        parametres.put(TermDepositCatalog.P_MIN_AMOUNT, "1000000");
        parametres.put(TermDepositCatalog.P_MIN_MONTHS, "3");
        parametres.put(TermDepositCatalog.P_MAX_MONTHS, "60");
        parametres.put(TermDepositCatalog.P_ACCRUED_INTEREST, bureau.courus().id().toString());
        parametres.put(TermDepositCatalog.P_INTEREST_EXPENSE, bureau.charges().id().toString());
        parametres.put(TermDepositCatalog.P_WITHHOLDING_RATE, "10");
        parametres.put(TermDepositCatalog.P_WITHHOLDING_ACCOUNT, bureau.irc().id().toString());
        if (penalite != null) {
            parametres.put(TermDepositCatalog.P_PENALTY_RATE, penalite);
        }
        return parametres;
    }

    private static TermDepositService.TermDeposit lire(UUID id) {
        return database.inTransaction(c -> TermDepositService.require(c, id));
    }
}
