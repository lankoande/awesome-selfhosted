package io.corebanking.regulatory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.domain.posting.PostingCommand;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.ledger.domain.posting.PostingResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La fiscalite : ce que la banque a preleve pour le compte de l'administration, et ce qu'elle lui
 * en dit.
 */
class TaxIT extends RegulatoryTestBase {

    private ReportingService service() {
        return new ReportingService(database);
    }

    @Test
    @DisplayName("la declaration fiscale porte ce qui est passe sur le compte de collecte, contre-passations comprises")
    void the_tax_return_carries_what_the_collection_account_received() {
        Account retenue = account("TAXE-IRC", AccountKind.GL, NormalBalance.CREDIT);
        Account charge = account("CHARGE-IRC", AccountKind.GL, NormalBalance.DEBIT);
        declarerTaxe("IRC", TaxRules.Basis.INTEREST_PAID, "10", retenue);

        // Deux prelevements dans la periode, un troisieme hors periode, et une contre-passation :
        // c'est le net de la periode qui est du.
        collecter(charge, retenue, "40000", FIN.minusDays(20), "irc-1");
        PostingResult annulee = collecter(charge, retenue, "15000", FIN.minusDays(10), "irc-2");
        collecter(charge, retenue, "9000", FIN.plusDays(2), "irc-hors");
        postingService.reverse(annulee.entryId(), FIN.minusDays(10), FIN.minusDays(5),
                               IdempotencyKey.of("irc-2-rev"), "commission annulee");

        UUID declaration = ReportingIT.declarer("FISCALE",
            RegulatoryDeclarations.Method.TAX_COLLECTION,
            RegulatoryDeclarations.Frequency.MONTHLY, 20, null);
        UUID etat = service().produce(ENTITY, declaration, FIN, FIN.plusDays(1), ACTOR);

        ReportFilings.Line ligne = lignes(etat, "IRC");
        assertThat(ligne.amount()).as("40 000 preleves, 15 000 rendus, le hors periode exclu")
            .isEqualTo(xof("40000"));
        assertThat(ligne.classification()).isEqualTo("INTEREST_PAID");
        assertThat(ligne.detail()).contains("10");
    }

    @Test
    @DisplayName("une taxe sans mouvement figure a zero : son absence serait lue comme un oubli de declaration")
    void a_tax_without_movement_is_declared_at_zero() {
        Account dormante = account("TAXE-DORMANTE", AccountKind.GL, NormalBalance.CREDIT);
        declarerTaxe("TAXE-SANS-FLUX", TaxRules.Basis.TRANSACTION, "1", dormante);

        UUID declaration = ReportingIT.declarer("FISCALE-ZERO",
            RegulatoryDeclarations.Method.TAX_COLLECTION,
            RegulatoryDeclarations.Frequency.MONTHLY, 20, null);
        UUID etat = service().produce(ENTITY, declaration, FIN, FIN.plusDays(1), ACTOR);

        assertThat(lignes(etat, "TAXE-SANS-FLUX").amount()).isEqualTo(xof("0"));
    }

    @Test
    @DisplayName("deux taxes en vigueur ne partagent pas un compte de collecte : ce qui y passe serait declare deux fois")
    void two_taxes_do_not_share_a_collection_account() {
        Account partage = account("TAXE-PARTAGE", AccountKind.GL, NormalBalance.CREDIT);
        declarerTaxe("TVA-1", TaxRules.Basis.FEES_CHARGED, "18", partage);

        assertThatThrownBy(() -> declarerTaxe("TVA-2", TaxRules.Basis.FEES_CHARGED, "18", partage))
            .isInstanceOf(TaxRules.TaxRuleRefusedException.class)
            .hasMessageContaining("declare deux fois");

        // La meme taxe ne se dedouble pas non plus sur la meme periode de validite.
        Account autre = account("TAXE-AUTRE", AccountKind.GL, NormalBalance.CREDIT);
        assertThatThrownBy(() -> declarerTaxe("TVA-1", TaxRules.Basis.FEES_CHARGED, "20", autre))
            .isInstanceOf(TaxRules.TaxRuleRefusedException.class)
            .hasMessageContaining("chevauche");
    }

    @Test
    @DisplayName("un taux de taxe se pose a deux, dans les bornes, et une declaration fiscale ne se seuille pas")
    void a_tax_rate_is_bounded_and_decided_by_two() {
        Account compte = account("TAXE-REFUS", AccountKind.GL, NormalBalance.CREDIT);
        assertThatThrownBy(() -> new TaxRules.Draft(ENTITY, "REFUS", "Refusee",
                TaxRules.Basis.FEES_CHARGED, new BigDecimal("120"), compte.id(),
                FIN.minusMonths(6), null, ACTOR, APPROVER))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("0 a 100");
        assertThatThrownBy(() -> new TaxRules.Draft(ENTITY, "REFUS", "Decidee seule",
                TaxRules.Basis.FEES_CHARGED, new BigDecimal("18"), compte.id(),
                FIN.minusMonths(6), null, ACTOR, ACTOR))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a deux");

        // Une taxe collectee se reverse en entier : la seuiller reviendrait a en garder une part.
        assertThatThrownBy(() -> new RegulatoryDeclarations.Draft(ENTITY, "FISCALE-SEUIL",
                "Seuillee", RegulatoryDeclarations.Recipient.TAX_AUTHORITY,
                RegulatoryDeclarations.Method.TAX_COLLECTION,
                RegulatoryDeclarations.Frequency.MONTHLY, 20, new BigDecimal("1000"),
                FIN.minusMonths(6), null, ACTOR, APPROVER))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("se reverse en entier");
    }

    @Test
    @DisplayName("un taux qui change en cours de periode ne fait pas declarer deux fois ce qui est passe apres la bascule")
    void a_rate_change_within_the_period_is_not_declared_twice() {
        Account collecte = account("TAXE-BASCULE", AccountKind.GL, NormalBalance.CREDIT);
        Account charge = account("CHARGE-BASCULE", AccountKind.GL, NormalBalance.DEBIT);
        LocalDate bascule = FIN.minusDays(14);
        // L'ancien taux cesse la veille de la bascule, le nouveau prend le meme compte de
        // collecte a partir de ce jour-la : c'est le cas ordinaire d'un changement de loi.
        database.inEntity(ENTITY, c -> TaxRules.declare(c, new TaxRules.Draft(
            ENTITY, "TVA-AVANT", "TVA 18 %", TaxRules.Basis.FEES_CHARGED, new BigDecimal("18"),
            collecte.id(), FIN.minusMonths(6), bascule.minusDays(1), ACTOR, APPROVER)));
        database.inEntity(ENTITY, c -> TaxRules.declare(c, new TaxRules.Draft(
            ENTITY, "TVA-APRES", "TVA 20 %", TaxRules.Basis.FEES_CHARGED, new BigDecimal("20"),
            collecte.id(), bascule, null, ACTOR, APPROVER)));

        collecter(charge, collecte, "18000", FIN.minusDays(20), "bascule-avant");
        collecter(charge, collecte, "20000", FIN.minusDays(5), "bascule-apres");

        UUID declaration = ReportingIT.declarer("FISCALE-BASCULE",
            RegulatoryDeclarations.Method.TAX_COLLECTION,
            RegulatoryDeclarations.Frequency.MONTHLY, 20, null);
        UUID etat = service().produce(ENTITY, declaration, FIN, FIN.plusDays(1), ACTOR);

        assertThat(lignes(etat, "TVA-AVANT").amount()).as("ce qui est passe sous l'ancien taux")
            .isEqualTo(xof("18000"));
        assertThat(lignes(etat, "TVA-APRES").amount()).as("ce qui est passe sous le nouveau")
            .isEqualTo(xof("20000"));
    }

    @Test
    @DisplayName("un compte de collecte d'une autre entite, ou un compte client, est refuse par le service et pas seulement par l'API")
    void the_collection_account_is_checked_by_the_service() {
        Account client = compte("CLI-TAXE", client("REG-TAXE"));
        assertThatThrownBy(() -> database.inEntity(ENTITY, c -> TaxRules.declare(c,
                new TaxRules.Draft(ENTITY, "TAXE-CLIENT", "Sur compte client",
                    TaxRules.Basis.FEES_CHARGED, new BigDecimal("18"), client.id(),
                    FIN.minusMonths(6), null, ACTOR, APPROVER))))
            .isInstanceOf(TaxRules.TaxRuleRefusedException.class)
            .hasMessageContaining("n'appartient a personne");

        assertThatThrownBy(() -> database.inEntity(ENTITY, c -> TaxRules.declare(c,
                new TaxRules.Draft(ENTITY, "TAXE-FANTOME", "Compte inexistant",
                    TaxRules.Basis.FEES_CHARGED, new BigDecimal("18"), UUID.randomUUID(),
                    FIN.minusMonths(6), null, ACTOR, APPROVER))))
            .isInstanceOf(TaxRules.TaxRuleRefusedException.class)
            .hasMessageContaining("inconnu");
    }

    // ------------------------------------------------------------------ outillage

    private static void declarerTaxe(String code, TaxRules.Basis basis, String taux,
                                     Account collecte) {
        database.inEntity(ENTITY, c -> TaxRules.declare(c, new TaxRules.Draft(
            ENTITY, code, code, basis, new BigDecimal(taux), collecte.id(), FIN.minusMonths(6),
            null, ACTOR, APPROVER)));
    }

    /** Un prelevement de taxe : la charge du client, la collecte de la banque. */
    private static PostingResult collecter(Account charge, Account collecte, String montant,
                                           LocalDate on, String key) {
        return postingService.post(PostingCommand.online(
            IdempotencyKey.of(key), ENTITY, on, "FEE_CHARGE", ACTOR,
            List.of(PostingLine.debit(charge.id(), xof(montant), on, null),
                    PostingLine.credit(collecte.id(), xof(montant), on, null))));
    }

    private static ReportFilings.Line lignes(UUID filingId, String code) {
        return ReportingIT.lire(filingId).lines().stream()
            .filter(l -> code.equals(l.subjectReference())).findFirst().orElseThrow();
    }
}
