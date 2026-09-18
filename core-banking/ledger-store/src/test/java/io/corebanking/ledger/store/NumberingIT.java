package io.corebanking.ledger.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.ledger.store.Numbering.CheckAlgorithm;
import io.corebanking.ledger.store.Numbering.Domain;
import io.corebanking.ledger.store.Numbering.Draft;
import io.corebanking.ledger.store.Numbering.Reset;
import io.corebanking.ledger.store.Numbering.Scope;
import io.corebanking.ledger.store.Numbering.Segment;
import java.math.BigInteger;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** La numerotation en base : le compteur, ses perimetres, et ce qu'elle refuse. */
class NumberingIT extends LedgerTestBase {

    private static final UUID AUTEUR = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    private static final UUID APPROBATEUR = UUID.fromString("00000000-0000-0000-0000-00000000a002");

    private UUID regleActive(Domain domain, List<Segment> segments, Scope scope, Reset reset) {
        return database.inTransaction(c -> {
            UUID id = Numbering.draft(c, new Draft(ENTITY, domain, "test " + domain, segments,
                                                   scope, reset, 1, AUTEUR));
            Numbering.activate(c, ENTITY, id, APPROBATEUR);
            return id;
        });
    }

    private UUID agence(String code, String nom) {
        var liaison = newGlAccount("LIAISON-" + code, io.corebanking.kernel.money.Currencies.XOF,
                                   io.corebanking.ledger.domain.account.NormalBalance.DEBIT, 1);
        return database.inTransaction(c -> Branches.create(
            c, ENTITY, code, nom, Branches.Kind.BRANCH, null, BUSINESS_DATE,
            java.util.Map.of(io.corebanking.kernel.money.Currencies.XOF, liaison.id())));
    }

    private String suivant(Domain domain, UUID branchId) {
        return database.inTransaction(c -> Numbering.next(
            c, domain, new Numbering.Context(ENTITY, branchId, BUSINESS_DATE)));
    }

    @Test
    @DisplayName("sans regle active, le socle refuse et dit que c'est un parametrage manquant")
    void without_an_active_rule_the_core_refuses_and_says_why() {
        // Une entite neuve : le socle ne seme aucune regle a la creation d'un etablissement. La
        // banque choisit son plan de numerotation, et tant qu'elle ne l'a pas choisi le refus le
        // dit — il ne se replie pas sur un gabarit improvise.
        UUID neuve = UUID.randomUUID();
        database.inTransaction(c -> {
            Entities.insertLegalEntity(c, neuve, "BANK-NEUVE", "Banque neuve", "CI",
                                       io.corebanking.kernel.money.Currencies.XOF, BUSINESS_DATE);
            return null;
        });

        assertThatThrownBy(() -> database.inTransaction(c -> Numbering.next(
            c, Domain.PARTY, new Numbering.Context(neuve, null, BUSINESS_DATE))))
            .isInstanceOf(Numbering.NoRuleException.class)
            .hasMessageContaining("parametrage manquant");
    }

    @Test
    @DisplayName("un numero fourni est repris tel quel, jamais recompose")
    void a_supplied_number_is_kept_as_it_is() {
        // Une reprise d'existant depend de cette garantie : recomposer couperait le lien avec les
        // archives et avec les cheques en circulation.
        String repris = database.inTransaction(c -> Numbering.orCompose(
            c, "  ANCIEN-00042  ", Domain.TERM_DEPOSIT,
            new Numbering.Context(ENTITY, null, BUSINESS_DATE)));

        assertThat(repris).isEqualTo("ANCIEN-00042");
    }

    @Test
    @DisplayName("le compteur avance d'un a chaque numero")
    void the_counter_advances_by_one() {
        regleActive(Domain.PARTY, List.of(Segment.literal("CLI-"), Segment.sequence(6)),
                    Scope.ENTITY, Reset.NEVER);

        assertThat(suivant(Domain.PARTY, null)).isEqualTo("CLI-000001");
        assertThat(suivant(Domain.PARTY, null)).isEqualTo("CLI-000002");
        assertThat(suivant(Domain.PARTY, null)).isEqualTo("CLI-000003");
    }

    @Test
    @DisplayName("une transaction annulee rend le numero : la serie n'a pas de trou")
    void a_rolled_back_transaction_gives_the_number_back() {
        regleActive(Domain.LOAN_CONTRACT, List.of(Segment.literal("CRD-"), Segment.sequence(4)),
                    Scope.ENTITY, Reset.NEVER);

        assertThat(suivant(Domain.LOAN_CONTRACT, null)).isEqualTo("CRD-0001");

        assertThatThrownBy(() -> database.inTransaction(c -> {
            Numbering.next(c, Domain.LOAN_CONTRACT,
                           new Numbering.Context(ENTITY, null, BUSINESS_DATE));
            throw new IllegalStateException("l'ouverture echoue apres le numero");
        })).hasMessageContaining("l'ouverture echoue");

        // Le numero 2 n'a pas ete consomme : c'est ce qu'une sequence PostgreSQL ne saurait pas
        // faire, et c'est pour cela que le compteur est une ligne de table.
        assertThat(suivant(Domain.LOAN_CONTRACT, null)).isEqualTo("CRD-0002");
    }

    @Test
    @DisplayName("une serie par agence : deux guichets ne se marchent pas dessus")
    void a_series_per_branch() {
        UUID agenceA = agence("00021", "Agence A");
        UUID agenceB = agence("00022", "Agence B");
        regleActive(Domain.ACCOUNT, List.of(Segment.branchCode(5), Segment.sequence(6)),
                    Scope.BRANCH, Reset.NEVER);

        assertThat(suivant(Domain.ACCOUNT, agenceA)).isEqualTo("00021000001");
        assertThat(suivant(Domain.ACCOUNT, agenceA)).isEqualTo("00021000002");
        // L'agence B repart a un : son compteur est le sien.
        assertThat(suivant(Domain.ACCOUNT, agenceB)).isEqualTo("00022000001");
    }

    @Test
    @DisplayName("le RIB compose porte le code banque de l'etablissement, et sa cle ferme")
    void the_composed_rib_carries_the_bank_code_and_closes() {
        database.inTransaction(c -> {
            Entities.updateEstablishment(c, ENTITY, new Entities.EstablishmentUpdate(
                null, "10015", null, null, null, null, null, null, null));
            return null;
        });
        UUID agence = agence("00031", "Agence RIB");
        regleActive(Domain.TERM_DEPOSIT,
                    List.of(Segment.bankCode(5), Segment.branchCode(5), Segment.sequence(12),
                            Segment.checkDigits(CheckAlgorithm.RIB_97)),
                    Scope.BRANCH, Reset.NEVER);

        String rib = suivant(Domain.TERM_DEPOSIT, agence);

        assertThat(rib).hasSize(24).startsWith("1001500031");
        assertThat(new BigInteger(rib).mod(BigInteger.valueOf(97))).isEqualTo(BigInteger.ZERO);
    }

    @Test
    @DisplayName("le gabarit qui reclame le code banque refuse tant que l'etablissement se tait")
    void a_rib_template_refuses_while_the_bank_code_is_missing() {
        UUID autre = UUID.randomUUID();
        database.inTransaction(c -> {
            Entities.insertLegalEntity(c, autre, "BANK-MUET", "Banque sans code", "CI",
                                       io.corebanking.kernel.money.Currencies.XOF, BUSINESS_DATE);
            UUID id = Numbering.draft(c, new Draft(
                autre, Domain.ACCOUNT, "RIB",
                List.of(Segment.bankCode(5), Segment.sequence(6)), Scope.ENTITY, Reset.NEVER, 1,
                AUTEUR));
            Numbering.activate(c, autre, id, APPROBATEUR);
            return null;
        });

        assertThatThrownBy(() -> database.inTransaction(c -> Numbering.next(
            c, Domain.ACCOUNT, new Numbering.Context(autre, null, BUSINESS_DATE))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("code banque");
    }

    @Test
    @DisplayName("une regle s'active a deux, jamais par son redacteur")
    void a_rule_is_activated_by_a_second_person() {
        UUID id = database.inTransaction(c -> Numbering.draft(c, new Draft(
            ENTITY, Domain.LOAN_APPLICATION, "brouillon",
            List.of(Segment.literal("DC-"), Segment.sequence(4)), Scope.ENTITY, Reset.NEVER, 1,
            AUTEUR)));

        assertThatThrownBy(() -> database.inTransaction(c -> {
            Numbering.activate(c, ENTITY, id, AUTEUR);
            return null;
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("a deux");

        database.inTransaction(c -> {
            Numbering.activate(c, ENTITY, id, APPROBATEUR);
            return null;
        });
        java.util.Optional<Numbering.Rule> active =
            database.inTransaction(c -> Numbering.active(c, ENTITY, Domain.LOAN_APPLICATION));
        assertThat(active).isPresent();
        assertThat(active.orElseThrow().label()).isEqualTo("brouillon");
    }

    @Test
    @DisplayName("activer une regle retire celle qui numerotait : jamais deux series en parallele")
    void activating_a_rule_withdraws_the_previous_one() {
        UUID premiere = regleActive(Domain.STANDING_ORDER,
                                    List.of(Segment.literal("OP-"), Segment.sequence(5)),
                                    Scope.ENTITY, Reset.NEVER);
        assertThat(suivant(Domain.STANDING_ORDER, null)).isEqualTo("OP-00001");

        UUID seconde = regleActive(Domain.STANDING_ORDER,
                                   List.of(Segment.literal("ORD/"), Segment.sequence(6)),
                                   Scope.ENTITY, Reset.NEVER);

        // Le compteur de la nouvelle regle est le sien : il repart a un. La serie precedente
        // reste explicable — la regle retiree n'est pas supprimee.
        assertThat(suivant(Domain.STANDING_ORDER, null)).isEqualTo("ORD/000001");
        String statutPrecedent =
            database.inTransaction(c -> Numbering.require(c, ENTITY, premiere).status());
        boolean secondeActive =
            database.inTransaction(c -> Numbering.require(c, ENTITY, seconde).active());
        assertThat(statutPrecedent).isEqualTo("WITHDRAWN");
        assertThat(secondeActive).isTrue();
    }

    @Test
    @DisplayName("l'apercu montre le prochain numero sans consommer le compteur")
    void the_preview_does_not_consume_the_counter() {
        UUID id = regleActive(Domain.LOAN_CONTRACT,
                              List.of(Segment.literal("APERCU-"), Segment.sequence(3)),
                              Scope.ENTITY, Reset.NEVER);

        String apercu = database.inTransaction(
            c -> Numbering.preview(c, ENTITY, id, null, BUSINESS_DATE));
        String reel = suivant(Domain.LOAN_CONTRACT, null);

        assertThat(apercu).isEqualTo("APERCU-001").isEqualTo(reel);
    }

    @Test
    @DisplayName("le code banque ne se change plus une fois qu'il a numerote des comptes")
    void the_bank_code_is_frozen_once_it_has_numbered_accounts() {
        UUID entite = UUID.randomUUID();
        database.inTransaction(c -> {
            Entities.insertLegalEntity(c, entite, "BANK-FIG", "Banque figee", "CI",
                                       io.corebanking.kernel.money.Currencies.XOF, BUSINESS_DATE);
            Entities.updateEstablishment(c, entite, new Entities.EstablishmentUpdate(
                null, "10022", null, null, null, null, null, null, null));
            UUID id = Numbering.draft(c, new Draft(
                entite, Domain.ACCOUNT, "RIB", List.of(Segment.bankCode(5), Segment.sequence(6)),
                Scope.ENTITY, Reset.NEVER, 1, AUTEUR));
            Numbering.activate(c, entite, id, APPROBATEUR);
            Numbering.next(c, Domain.ACCOUNT, new Numbering.Context(entite, null, BUSINESS_DATE));
            return null;
        });

        assertThatThrownBy(() -> database.inTransaction(c -> {
            Entities.updateEstablishment(c, entite, new Entities.EstablishmentUpdate(
                null, "10099", null, null, null, null, null, null, null));
            return null;
        })).isInstanceOf(IllegalStateException.class)
           .hasMessageContaining("deja numerote des comptes");
    }

    @Test
    @DisplayName("sous concurrence, chaque appelant recoit un numero distinct")
    void concurrent_callers_never_share_a_number() throws Exception {
        regleActive(Domain.PARTY, List.of(Segment.literal("CONC-"), Segment.sequence(5)),
                    Scope.ENTITY, Reset.NEVER);
        int concurrents = 8;
        ExecutorService pool = Executors.newFixedThreadPool(concurrents);
        try {
            List<Callable<String>> taches = java.util.stream.IntStream.range(0, concurrents)
                .<Callable<String>>mapToObj(i -> () -> suivant(Domain.PARTY, null))
                .toList();
            List<String> numeros = pool.invokeAll(taches).stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).collect(Collectors.toList());

            assertThat(numeros).doesNotHaveDuplicates().hasSize(concurrents);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("un numero deja compose ne se recompose pas : la base le refuse")
    void a_number_is_never_composed_twice() throws SQLException {
        regleActive(Domain.TERM_DEPOSIT, List.of(Segment.literal("DAT-"), Segment.sequence(1)),
                    Scope.ENTITY, Reset.NEVER);
        for (int i = 1; i <= 9; i++) {
            assertThat(suivant(Domain.TERM_DEPOSIT, null)).isEqualTo("DAT-" + i);
        }
        // Le dixieme deborde le cadrage : le gabarit est trop court, et c'est un refus clair,
        // jamais un numero tronque qui doublonnerait le premier.
        assertThatThrownBy(() -> suivant(Domain.TERM_DEPOSIT, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("compteur");
    }
}
