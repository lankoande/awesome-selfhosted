package io.corebanking.regulatory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Le retard declaratif se constate, il ne se decouvre pas.
 *
 * <p>Une declaration oubliee pendant trois mois est une sanction ; la meme, vue le lendemain de
 * l'echeance, est un rattrapage. C'est toute la difference que fait cette surveillance.
 */
class DeadlineWatchIT extends RegulatoryTestBase {

    private ReportingService service() {
        return new ReportingService(database);
    }

    @Test
    @DisplayName("une echeance depassee sans etat transmis est nommee, avec sa periode et son retard")
    void an_overdue_deadline_is_named_with_its_period() {
        UUID declaration = ReportingIT.declarer("VEILLE-MENSUELLE",
            RegulatoryDeclarations.Method.ACCOUNTING_SITUATION,
            RegulatoryDeclarations.Frequency.MONTHLY, 15, null);

        // Le 10 du mois suivant : l'echeance du mois clos n'est pas encore passee.
        LocalDate avant = LocalDate.of(2026, 10, 10);
        assertThat(retards(service().watch(ENTITY, avant), "VEILLE-MENSUELLE"))
            .as("l'echeance du 15 n'est pas encore passee")
            .noneMatch(o -> o.periodEnd().equals(FIN));

        // Le 20 : elle l'est, et rien n'a ete transmis.
        LocalDate apres = LocalDate.of(2026, 10, 20);
        List<ReportingService.Overdue> retards = retards(service().watch(ENTITY, apres),
                                                         "VEILLE-MENSUELLE").stream()
            .filter(o -> o.periodEnd().equals(FIN)).toList();
        assertThat(retards).hasSize(1);
        ReportingService.Overdue retard = retards.getFirst();
        assertThat(retard.periodEnd()).isEqualTo(FIN);
        assertThat(retard.dueOn()).isEqualTo(FIN.plusDays(15));
        assertThat(retard.produced()).isFalse();
        assertThat(retard.describe(apres)).contains("depassee de 5 jours")
            .contains("aucun etat n'est produit");

        // Produit mais non transmis : le retard demeure, et il le dit autrement — ce n'est pas
        // le meme manquement, ni le meme rattrapage.
        UUID etat = service().produce(ENTITY, declaration, FIN, FIN.plusDays(1), ACTOR);
        ReportingService.Overdue produit = retards(service().watch(ENTITY, apres),
                                                   "VEILLE-MENSUELLE").stream()
            .filter(o -> o.periodEnd().equals(FIN)).findFirst().orElseThrow();
        assertThat(produit.produced()).isTrue();
        assertThat(produit.describe(apres)).contains("n'est pas transmis");

        // Transmis, il disparait de la veille.
        database.inEntity(ENTITY, c -> ReportFilings.transmit(c, etat, FIN.plusDays(10),
                                                              "BCEAO-VEILLE", ACTOR, APPROVER));
        assertThat(retards(service().watch(ENTITY, apres), "VEILLE-MENSUELLE"))
            .noneMatch(o -> o.periodEnd().equals(FIN));
    }

    @Test
    @DisplayName("la veille ne reclame rien avant l'entree en vigueur de la declaration ni apres sa fin")
    void the_watch_respects_the_validity_of_the_declaration() {
        // En vigueur a partir du 1er septembre seulement : les periodes anterieures ne sont pas
        // en retard, elles n'etaient pas dues.
        UUID declaration = database.inEntity(ENTITY, c -> RegulatoryDeclarations.declare(c,
            new RegulatoryDeclarations.Draft(ENTITY, "VEILLE-RECENTE", "Recente",
                RegulatoryDeclarations.Recipient.BANKING_COMMISSION,
                RegulatoryDeclarations.Method.ACCOUNTING_SITUATION,
                RegulatoryDeclarations.Frequency.MONTHLY, 15, null,
                LocalDate.of(2026, 9, 1), null, ACTOR, APPROVER)));
        assertThat(declaration).isNotNull();

        List<ReportingService.Overdue> retards = retards(
            service().watch(ENTITY, LocalDate.of(2026, 10, 20)), "VEILLE-RECENTE");
        assertThat(retards).extracting(ReportingService.Overdue::periodEnd)
            .as("les periodes anterieures a l'entree en vigueur ne sont pas dues")
            .containsExactly(FIN);
    }

    @Test
    @DisplayName("une declaration trimestrielle se surveille par trimestre, pas par mois")
    void a_quarterly_declaration_is_watched_by_quarter() {
        ReportingIT.declarer("VEILLE-TRIMESTRE", RegulatoryDeclarations.Method.ACCOUNTING_SITUATION,
                             RegulatoryDeclarations.Frequency.QUARTERLY, 30, null);

        // Au 20 octobre, le trimestre clos le 30 septembre a une echeance au 30 octobre : rien
        // n'est du. Le trimestre precedent, clos le 30 juin, est en retard depuis le 30 juillet.
        List<ReportingService.Overdue> retards = retards(
            service().watch(ENTITY, LocalDate.of(2026, 10, 20)), "VEILLE-TRIMESTRE");
        assertThat(retards).extracting(ReportingService.Overdue::periodEnd)
            .contains(LocalDate.of(2026, 6, 30))
            .doesNotContain(LocalDate.of(2026, 9, 30));
    }

    private static List<ReportingService.Overdue> retards(ReportingService.Watch watch,
                                                          String code) {
        return watch.overdue().stream()
            .filter(o -> o.declarationCode().equals(code)).toList();
    }
}
