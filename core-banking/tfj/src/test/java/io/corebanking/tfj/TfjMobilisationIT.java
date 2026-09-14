package io.corebanking.tfj;

import static org.assertj.core.api.Assertions.assertThat;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.store.Balances;
import io.corebanking.ledger.store.LedgerStoreException;
import io.corebanking.loan.DisbursementPlan;
import io.corebanking.loan.LoanTerms;
import io.corebanking.loan.service.LoanCatalog;
import io.corebanking.loan.service.LoanStore;
import io.corebanking.product.ProductCatalog;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Le deblocage par tranches dans la chaine complete du TFJ.
 *
 * <p>Credit de 1 000 000 XOF mobilise en deux tranches le jour de la signature, amortissable en
 * douze mensualites a compter du deuxieme mois. La periode de mobilisation s'acheve quatre jours
 * plus tard : les interets intercalaires de ces journees sont factures, puis l'echeancier
 * definitif est publie sur le capital reellement tire.
 */
class TfjMobilisationIT extends TfjTestBase {

    private static Money xof(String montant) {
        return Money.of(montant, Currencies.XOF);
    }

    private record Dossier(UUID contrat, LocalDate signature, LocalDate limite, Account pret,
                           Account courant, Account creances, Account produits) {}

    @Test
    @DisplayName("le TFJ facture les intercalaires, les preleve, puis arrete l'echeancier definitif")
    void mobilisationDansLeTfj() {
        Dossier dossier = dossier("T1", true);
        TfjRun cloture = jusquALaCloture(dossier);

        assertThat(cloture.steps()).extracting(TfjRun.StepExecution::name)
            .contains("LOAN_MOBILISATION");

        // Cinq journees a 12 % sur 1 000 000 : 1 644. Les intercalaires sont factures d'un bloc le
        // jour de la cloture, la periode intercalaire caleee sur la premiere echeance n'etant pas
        // encore echue.
        assertThat(intercalaires(dossier.contrat())).containsExactly(xof("1644"));
        assertThat(soldeDe(dossier.produits())).isEqualTo(xof("1644"));

        // Le prelevement de la journee les encaisse : la creance intercalaire est une creance
        // ordinaire, et l'etape des echeances la traite comme telle.
        assertThat(soldeDe(dossier.courant())).isEqualTo(xof("998356"));
        assertThat(soldeDe(dossier.creances()).isZero()).isTrue();
        assertThat(soldeDe(dossier.pret())).isEqualTo(xof("1000000"));

        // L'echeancier definitif prend effet le lendemain de la cloture, sur le capital tire.
        assertThat(echeancier(dossier.contrat()))
            .isEqualTo(new Version(1, "MOBILISATION", dossier.limite().plusDays(1)));
        assertThat(mobilisationClose(dossier.contrat())).isEqualTo(dossier.limite());
    }

    @Test
    @DisplayName("l'annulation du TFJ rouvre la mobilisation et retire l'echeancier qu'il a publie")
    void annulationRouvreLaMobilisation() {
        Dossier dossier = dossier("T2", false);
        TfjRun cloture = jusquALaCloture(dossier);
        assertThat(echeancier(dossier.contrat())).isNotNull();

        engine.cancel(cloture.id(), ACTOR, dossier.limite(), "erreur de parametrage");

        // Sans cette reouverture, les ecritures seraient contre-passees mais le credit resterait
        // amortissable sur un capital que plus aucune ecriture ne justifie — et la mobilisation,
        // close, ne pourrait plus recevoir la tranche qui restait a verser.
        assertThat(echeancier(dossier.contrat())).isNull();
        assertThat(mobilisationClose(dossier.contrat())).isNull();
        assertThat(intercalaires(dossier.contrat())).isEmpty();
        assertThat(curseurIntercalaire(dossier.contrat()))
            .isEqualTo(dossier.signature().minusDays(1));
        assertThat(soldeDe(dossier.produits()).isZero()).isTrue();

        // Rejoue, le traitement refait exactement ce qu'il avait fait : les journees redeviennent
        // facturables la ou elles l'etaient, et la periode intercalaire est la meme.
        TfjRun rejoue = engine.run(ENTITY, dossier.limite(), ACTOR, RunMode.REAL);
        assertThat(rejoue.isCompleted()).as(rejoue.summary()).isTrue();
        assertThat(intercalaires(dossier.contrat())).containsExactly(xof("1644"));
        assertThat(echeancier(dossier.contrat()))
            .isEqualTo(new Version(1, "MOBILISATION", dossier.limite().plusDays(1)));
    }

    // ------------------------------------------------------------------ outillage

    /** Enchaine les TFJ jusqu'au premier jour ouvre qui atteint la date limite de mobilisation. */
    private static TfjRun jusquALaCloture(Dossier dossier) {
        while (true) {
            LocalDate date = businessDate();
            TfjRun run = engine.run(ENTITY, date, ACTOR, RunMode.REAL);
            assertThat(run.isCompleted()).as(run.summary()).isTrue();
            if (!date.isBefore(dossier.limite())) {
                return run;
            }
        }
    }

    private static Dossier dossier(String code, boolean prelevementAutomatique) {
        LocalDate signature = businessDate();
        LocalDate limite = signature.plusDays(4);
        LocalDate premiere = signature.plusMonths(2);

        Account pret = account(code + "-PRET", AccountKind.CUSTOMER, NormalBalance.DEBIT);
        Account courant = account(code + "-COURANT", AccountKind.CUSTOMER, NormalBalance.CREDIT);
        Account creances = account(code + "-CREANCES", AccountKind.GL, NormalBalance.DEBIT);
        Account produits = account(code + "-PRODUITS", AccountKind.GL, NormalBalance.CREDIT);
        Account taxe = account(code + "-TAXE", AccountKind.GL, NormalBalance.CREDIT);

        Map<String, String> parametres = new LinkedHashMap<>();
        parametres.put(LoanCatalog.P_ACCRUED, creances.id().toString());
        parametres.put(LoanCatalog.P_INTEREST_INCOME, produits.id().toString());
        parametres.put(LoanCatalog.P_TAX_ACCOUNT, taxe.id().toString());
        parametres.put(LoanCatalog.P_DIRECT_DEBIT, String.valueOf(prelevementAutomatique));

        UUID contrat = database.inTransaction(c -> {
            UUID version = ProductCatalog.createDraft(c, new ProductCatalog.Draft(
                ENTITY, "CRED-" + code, "TERM_LOAN", "Credit de construction", "XOF",
                signature.minusDays(1), null, parametres, List.of(), ACTOR));
            ProductCatalog.activate(c, version, APPROVER);
            return LoanStore.createContract(c, new LoanStore.ContractDraft(
                ENTITY, "REF-" + code, "CRED-" + code, Currencies.XOF, pret.id(), courant.id(),
                xof("1000000"), signature, ACTOR));
        });

        LoanTerms conditions = LoanTerms.of(xof("1000000")).ratePercent("12").instalments(12)
            .disbursedOn(signature).firstDueDate(premiere).build();
        DisbursementPlan plan = DisbursementPlan.of(Currencies.XOF)
            .tranche(signature, xof("600000"), "signature")
            .tranche(signature, xof("400000"), "ouverture du chantier")
            .deadline(limite).build();

        mobilisationService.open(contrat, plan, conditions, null, ACTOR, APPROVER);
        mobilisationService.release(contrat, 1, xof("600000"), signature,
                                    IdempotencyKey.of("TR1|" + code), ACTOR, APPROVER);
        mobilisationService.release(contrat, 2, xof("400000"), signature,
                                    IdempotencyKey.of("TR2|" + code), ACTOR, APPROVER);
        return new Dossier(contrat, signature, limite, pret, courant, creances, produits);
    }

    private record Version(int version, String reason, LocalDate effectiveFrom) {}

    private static Version echeancier(UUID contractId) {
        return database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "SELECT version, reason, effective_from FROM loan_schedule WHERE contract_id = ?")) {
                ps.setObject(1, contractId);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? new Version(rs.getInt(1), rs.getString(2),
                                                   rs.getObject(3, LocalDate.class)) : null;
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Lecture de l'echeancier", e);
            }
        });
    }

    private static List<Money> intercalaires(UUID contractId) {
        return database.inTransaction(c -> {
            List<Money> montants = new java.util.ArrayList<>();
            try (var ps = c.prepareStatement(
                "SELECT interest FROM loan_interim_interest WHERE contract_id = ?"
                + " AND status = 'ACTIVE' ORDER BY period_end")) {
                ps.setObject(1, contractId);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        montants.add(Money.of(rs.getBigDecimal(1), Currencies.XOF));
                    }
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Lecture des intercalaires", e);
            }
            return montants;
        });
    }

    private static LocalDate mobilisationClose(UUID contractId) {
        return colonneDate(contractId, "closed_on");
    }

    private static LocalDate curseurIntercalaire(UUID contractId) {
        return colonneDate(contractId, "interim_billed_through");
    }

    private static LocalDate colonneDate(UUID contractId, String colonne) {
        return database.inTransaction(c -> {
            try (var ps = c.prepareStatement(
                "SELECT " + colonne + " FROM loan_mobilisation WHERE contract_id = ?")) {
                ps.setObject(1, contractId);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? rs.getObject(1, LocalDate.class) : null;
                }
            } catch (SQLException e) {
                throw new LedgerStoreException("Lecture de " + colonne, e);
            }
        });
    }

    private static Money soldeDe(Account compte) {
        return database.inTransaction(c -> Balances.current(c, compte.id()));
    }
}
