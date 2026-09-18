package io.corebanking.ledger.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.ledger.store.Numbering.CheckAlgorithm;
import io.corebanking.ledger.store.Numbering.Domain;
import io.corebanking.ledger.store.Numbering.Reset;
import io.corebanking.ledger.store.Numbering.Rule;
import io.corebanking.ledger.store.Numbering.Scope;
import io.corebanking.ledger.store.Numbering.Segment;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** La composition d'un numero : ce que le gabarit promet, sans base de donnees. */
class NumberingCompositionTest {

    private static final UUID ENTITE = UUID.randomUUID();
    private static final LocalDate JOUR = LocalDate.of(2026, 9, 18);

    private Rule regle(List<Segment> segments, Scope scope, Reset reset) {
        Numbering.validate(segments);
        return new Rule(UUID.randomUUID(), ENTITE, Domain.ACCOUNT, "test", segments, scope, reset,
                        1, "ACTIVE", UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    @DisplayName("un RIB porte la banque, le guichet, le numero et sa cle")
    void a_rib_carries_bank_branch_number_and_key() {
        Rule rule = regle(List.of(Segment.bankCode(5), Segment.branchCode(5),
                                  Segment.sequence(12),
                                  Segment.checkDigits(CheckAlgorithm.RIB_97)),
                          Scope.BRANCH, Reset.NEVER);

        String numero = Numbering.compose(rule, 42, "BF083", "00012", JOUR);

        assertThat(numero).hasSize(24).startsWith("BF08300012000000000042");
        assertThat(numero.substring(0, 22)).isEqualTo("BF08300012000000000042");
    }

    @Test
    @DisplayName("la cle rend le numero entier divisible par 97")
    void the_key_makes_the_whole_number_divisible_by_97() {
        Rule rule = regle(List.of(Segment.bankCode(5), Segment.branchCode(5),
                                  Segment.sequence(12),
                                  Segment.checkDigits(CheckAlgorithm.RIB_97)),
                          Scope.BRANCH, Reset.NEVER);

        for (long sequence : new long[] {1, 42, 97, 1234567, 999999999999L}) {
            String numero = Numbering.compose(rule, sequence, "10015", "00012", JOUR);
            assertThat(new BigInteger(numero).mod(BigInteger.valueOf(97)))
                .as("cle du numero %s", numero)
                .isEqualTo(BigInteger.ZERO);
        }
    }

    @Test
    @DisplayName("les lettres du numero sont transcodees pour le calcul de la cle")
    void letters_are_transcoded_for_the_key() {
        // A vaut 1 : deux numeros qui ne different que par cette transcription portent la meme
        // cle. C'est la table du RIB, et c'est elle qui permet des numeros alphanumeriques.
        assertThat(Numbering.checkDigits(CheckAlgorithm.RIB_97, "A0015"))
            .isEqualTo(Numbering.checkDigits(CheckAlgorithm.RIB_97, "10015"));
    }

    @Test
    @DisplayName("la cle tient sur deux chiffres, meme quand elle vaut moins de dix")
    void the_key_is_always_two_digits() {
        for (int i = 1; i < 200; i++) {
            assertThat(Numbering.checkDigits(CheckAlgorithm.RIB_97, Integer.toString(i)))
                .hasSize(2);
        }
    }

    @Test
    @DisplayName("la cle de Luhn ferme le numero modulo dix")
    void the_luhn_key_closes_the_number() {
        String cle = Numbering.checkDigits(CheckAlgorithm.LUHN, "7992739871");
        assertThat(cle).isEqualTo("3");
    }

    @Test
    @DisplayName("une reference client suit son gabarit, compteur cadre")
    void a_customer_reference_follows_its_template() {
        Rule rule = regle(List.of(Segment.literal("CLI-"), Segment.sequence(6)),
                          Scope.ENTITY, Reset.NEVER);

        assertThat(Numbering.compose(rule, 417, null, null, JOUR)).isEqualTo("CLI-000417");
    }

    @Test
    @DisplayName("la date du gabarit est la date comptable, pas celle de l'horloge")
    void the_date_segment_uses_the_business_date() {
        Rule rule = regle(List.of(Segment.literal("DC-"), Segment.date("yyyy"),
                                  Segment.literal("-"), Segment.sequence(4)),
                          Scope.ENTITY, Reset.YEAR);

        assertThat(Numbering.compose(rule, 142, null, null, LocalDate.of(2026, 1, 1)))
            .isEqualTo("DC-2026-0142");
        assertThat(Numbering.compose(rule, 1, null, null, LocalDate.of(2027, 12, 31)))
            .isEqualTo("DC-2027-0001");
    }

    @Test
    @DisplayName("un code trop long est refuse, jamais tronque")
    void a_code_that_does_not_fit_is_refused() {
        Rule rule = regle(List.of(Segment.branchCode(3), Segment.sequence(4)),
                          Scope.BRANCH, Reset.NEVER);

        assertThatThrownBy(() -> Numbering.compose(rule, 1, null, "00012", JOUR))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ne tient pas sur 3");
    }

    @Test
    @DisplayName("un compteur qui deborde son cadrage est refuse, pas rogne")
    void an_overflowing_counter_is_refused() {
        Rule rule = regle(List.of(Segment.literal("CLI-"), Segment.sequence(3)),
                          Scope.ENTITY, Reset.NEVER);

        assertThat(Numbering.compose(rule, 999, null, null, JOUR)).isEqualTo("CLI-999");
        assertThatThrownBy(() -> Numbering.compose(rule, 1000, null, null, JOUR))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("compteur");
    }

    @Test
    @DisplayName("un gabarit sans compteur est refuse a la redaction")
    void a_template_without_a_counter_is_refused() {
        assertThatThrownBy(() -> Numbering.validate(List.of(Segment.literal("CLI-"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exactement un segment de sequence");
    }

    @Test
    @DisplayName("un gabarit a deux compteurs est refuse")
    void a_template_with_two_counters_is_refused() {
        assertThatThrownBy(() -> Numbering.validate(
            List.of(Segment.sequence(3), Segment.literal("-"), Segment.sequence(3))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exactement un segment de sequence");
    }

    @Test
    @DisplayName("une cle de controle au milieu du gabarit est refusee")
    void a_key_in_the_middle_is_refused() {
        assertThatThrownBy(() -> Numbering.validate(
            List.of(Segment.sequence(4), Segment.checkDigits(CheckAlgorithm.RIB_97),
                    Segment.literal("X"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dernier segment");
    }

    @Test
    @DisplayName("un format de date inconnu est refuse a la redaction, pas a l'ouverture")
    void an_unknown_date_pattern_is_refused_when_drafted() {
        assertThatThrownBy(() -> Numbering.validate(
            List.of(Segment.date("ffff"), Segment.sequence(4))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Format de date inconnu");
    }

    @Test
    @DisplayName("le gabarit qui demande le code banque refuse de composer sans lui")
    void a_template_that_wants_the_bank_code_refuses_without_it() {
        Rule rule = regle(List.of(Segment.bankCode(5), Segment.sequence(4)),
                          Scope.ENTITY, Reset.NEVER);

        assertThatThrownBy(() -> Numbering.compose(rule, 1, null, null, JOUR))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("code banque");
    }

    @Test
    @DisplayName("la proposition du socle pour le compte est un RIB de la zone")
    void the_proposed_account_template_is_a_regional_rib() {
        var proposition = Numbering.proposal(ENTITE, Domain.ACCOUNT, UUID.randomUUID());

        assertThat(proposition.scope()).isEqualTo(Scope.BRANCH);
        assertThat(proposition.reset()).isEqualTo(Reset.NEVER);
        assertThat(proposition.segments()).extracting(Segment::kind)
            .containsExactly(Numbering.SegmentKind.BANK_CODE, Numbering.SegmentKind.BRANCH_CODE,
                             Numbering.SegmentKind.SEQUENCE, Numbering.SegmentKind.CHECK_DIGITS);
    }

    @Test
    @DisplayName("chaque proposition du socle est un gabarit valide")
    void every_proposal_is_a_valid_template() {
        for (Domain domain : Domain.values()) {
            var proposition = Numbering.proposal(ENTITE, domain, UUID.randomUUID());
            assertThat(proposition.segments()).as("gabarit de %s", domain).isNotEmpty();
        }
    }
}
