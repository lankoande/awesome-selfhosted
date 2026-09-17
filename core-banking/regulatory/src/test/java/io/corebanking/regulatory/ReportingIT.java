package io.corebanking.regulatory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.ledger.domain.account.Account;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ce que la banque doit au superviseur : un etat qu'elle peut lui reproduire, sur une periode
 * qu'il attend, transmis avant l'echeance — et le retard, constate par la banque avant lui.
 */
class ReportingIT extends RegulatoryTestBase {

    private ReportingService service() {
        return new ReportingService(database);
    }

    @Test
    @DisplayName("la centrale des risques recense par client, bilan et hors bilan, au-dela du seuil")
    void the_credit_registry_aggregates_by_customer_above_the_threshold() {
        UUID gros = client("REG-GROS");
        UUID petit = client("REG-PETIT");
        // Deux concours pour le meme client : c'est leur somme qui est recensee, pas chacun.
        credit("CR-GROS-1", gros, "6000000", FIN.minusMonths(2));
        credit("CR-GROS-2", gros, "5000000", FIN.minusMonths(1));
        credit("CR-PETIT", petit, "400000", FIN.minusMonths(1));

        UUID declaration = declarer("CENTRALE", RegulatoryDeclarations.Method.CREDIT_REGISTRY,
                                    RegulatoryDeclarations.Frequency.MONTHLY, 15,
                                    new BigDecimal("1000000"));
        UUID etat = service().produce(ENTITY, declaration, FIN, FIN.plusDays(2), ACTOR);

        ReportFilings.Filing filing = lire(etat);
        assertThat(filing.status()).isEqualTo(ReportFilings.Status.PRODUCED);
        assertThat(filing.periodStart()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(filing.dueOn()).isEqualTo(FIN.plusDays(15));
        assertThat(filing.thresholdUsed()).isEqualByComparingTo("1000000");

        List<ReportFilings.Line> lignes = filing.lines();
        assertThat(lignes).extracting(ReportFilings.Line::subjectReference)
            .as("le client sous le seuil n'est pas recense").doesNotContain("REG-PETIT");
        ReportFilings.Line ligne = lignes.stream()
            .filter(l -> "REG-GROS".equals(l.subjectReference())).findFirst().orElseThrow();
        assertThat(ligne.amount()).as("les deux concours agreges").isEqualTo(xof("11000000"));
        assertThat(filing.lineCount()).isEqualTo(lignes.size());
    }

    @Test
    @DisplayName("un etat ne se produit que sur une periode que le superviseur attend, et une seule fois")
    void a_filing_covers_a_period_and_only_one_exists() {
        UUID declaration = declarer("SITUATION",
                                    RegulatoryDeclarations.Method.ACCOUNTING_SITUATION,
                                    RegulatoryDeclarations.Frequency.MONTHLY, 15, null);

        // Une date qui ne ferme pas de mois n'est pas une periode.
        assertThatThrownBy(() -> service().produce(ENTITY, declaration, FIN.minusDays(3),
                                                   FIN, ACTOR))
            .isInstanceOf(ReportFilings.FilingRefusedException.class)
            .hasMessageContaining("ne ferme pas de periode");

        // Produire avant la fin de la periode couverte n'a pas de sens.
        assertThatThrownBy(() -> service().produce(ENTITY, declaration, FIN, FIN.minusDays(1),
                                                   ACTOR))
            .isInstanceOf(ReportFilings.FilingRefusedException.class)
            .hasMessageContaining("apres la fin de la periode");

        UUID premier = service().produce(ENTITY, declaration, FIN, FIN.plusDays(1), ACTOR);
        assertThat(lire(premier).lines()).as("la balance est exhaustive").isNotEmpty();

        assertThatThrownBy(() -> service().produce(ENTITY, declaration, FIN, FIN.plusDays(2),
                                                   ACTOR))
            .isInstanceOf(ReportFilings.FilingRefusedException.class)
            .hasMessageContaining("deux etats pour une periode seraient deux verites");

        // Annule et motive, il laisse la place a un etat repris.
        database.inEntity(ENTITY, c -> ReportFilings.cancel(c, premier, FIN.plusDays(2),
                                                            "erreur d'imputation corrigee"));
        UUID repris = service().produce(ENTITY, declaration, FIN, FIN.plusDays(2), ACTOR);
        assertThat(lire(repris).status()).isEqualTo(ReportFilings.Status.PRODUCED);
        assertThat(lire(premier).status()).isEqualTo(ReportFilings.Status.CANCELLED);
    }

    @Test
    @DisplayName("la transmission se fait a deux, porte la reference rendue, et ce qui est parti ne s'annule pas")
    void transmission_takes_two_and_what_is_filed_stays_filed() {
        UUID declaration = declarer("INCIDENTS", RegulatoryDeclarations.Method.PAYMENT_INCIDENTS,
                                    RegulatoryDeclarations.Frequency.MONTHLY, 10, null);
        Account compte = compte("CLI-INC", client("REG-INC"));
        incident(compte, 1001, "250000", FIN.minusDays(5));
        incident(compte, 1002, "180000", FIN.minusDays(2));
        // Hors periode : il appartient au mois suivant.
        incident(compte, 1003, "90000", FIN.plusDays(1));

        UUID etat = service().produce(ENTITY, declaration, FIN, FIN.plusDays(1), ACTOR);
        ReportFilings.Line ligne = lire(etat).lines().getFirst();
        assertThat(ligne.occurrences()).as("deux incidents dans la periode").isEqualTo(2);
        assertThat(ligne.amount()).isEqualTo(xof("430000"));
        assertThat(ligne.label()).contains("REG-INC");

        assertThatThrownBy(() -> database.inEntity(ENTITY, c -> ReportFilings.transmit(
                c, etat, FIN.plusDays(2), "BCEAO-1", ACTOR, ACTOR)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a deux");
        assertThatThrownBy(() -> database.inEntity(ENTITY, c -> ReportFilings.transmit(
                c, etat, FIN.plusDays(2), " ", ACTOR, APPROVER)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reference");

        ReportFilings.Filing transmis = database.inEntity(ENTITY, c -> ReportFilings.transmit(
            c, etat, FIN.plusDays(2), "BCEAO-2026-09-0042", ACTOR, APPROVER));
        assertThat(transmis.transmitted()).isTrue();
        assertThat(transmis.transmissionReference()).isEqualTo("BCEAO-2026-09-0042");

        assertThatThrownBy(() -> database.inEntity(ENTITY, c -> ReportFilings.transmit(
                c, etat, FIN.plusDays(3), "BCEAO-BIS", ACTOR, APPROVER)))
            .isInstanceOf(ReportFilings.FilingRefusedException.class)
            .hasMessageContaining("ne se transmet pas deux fois");
        assertThatThrownBy(() -> database.inEntity(ENTITY, c -> ReportFilings.cancel(
                c, etat, FIN.plusDays(3), "on s'est trompe")))
            .isInstanceOf(ReportFilings.FilingRefusedException.class)
            .hasMessageContaining("ne s'annule pas");
    }

    @Test
    @DisplayName("un etat regenere est confronte au contenu transmis : l'ecart est nomme, ligne par ligne")
    void a_regenerated_filing_is_compared_to_what_was_filed() {
        UUID client = client("REG-REPRO");
        credit("CR-REPRO", client, "3000000", FIN.minusMonths(1));
        UUID declaration = declarer("CENTRALE-REPRO",
                                    RegulatoryDeclarations.Method.CREDIT_REGISTRY,
                                    RegulatoryDeclarations.Frequency.MONTHLY, 15,
                                    new BigDecimal("1000000"));
        UUID etat = service().produce(ENTITY, declaration, FIN, FIN.plusDays(1), ACTOR);
        database.inEntity(ENTITY, c -> ReportFilings.transmit(c, etat, FIN.plusDays(2),
                                                              "BCEAO-REPRO", ACTOR, APPROVER));

        assertThat(service().differences(ENTITY, etat))
            .as("rien n'a bouge : l'etat se reproduit a l'identique").isEmpty();

        // Une ecriture ajoutee apres coup sur une date couverte : l'etat ne se reproduit plus,
        // et c'est exactement ce que l'inspection cherche a voir.
        UUID pret = database.inEntity(ENTITY, c -> {
            try (var ps = c.prepareStatement(
                "SELECT loan_account_id FROM loan_contract WHERE reference = 'CR-REPRO'")) {
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getObject(1, UUID.class);
                }
            } catch (java.sql.SQLException e) {
                throw new io.corebanking.ledger.store.LedgerStoreException("compte de pret", e);
            }
        });
        postingService.post(io.corebanking.ledger.domain.posting.PostingCommand.online(
            io.corebanking.kernel.id.IdempotencyKey.of("repro-extra"), ENTITY, FIN.minusDays(1),
            "LOAN_DISBURSEMENT", ACTOR,
            List.of(io.corebanking.ledger.domain.posting.PostingLine.debit(
                        pret, xof("500000"), FIN.minusDays(1), null),
                    io.corebanking.ledger.domain.posting.PostingLine.credit(
                        caisse.id(), xof("500000"), FIN.minusDays(1), null))));

        List<String> ecarts = service().differences(ENTITY, etat);
        assertThat(ecarts).hasSize(1);
        assertThat(ecarts.getFirst()).contains("REG-REPRO").contains("3000000")
            .contains("3500000");
    }

    @Test
    @DisplayName("rien ne sort vers le bureau du credit sans consentement, et la revocation vaut pour la suite")
    void nothing_reaches_the_credit_bureau_without_consent() {
        UUID consentant = client("REG-OUI");
        UUID refusant = client("REG-NON");
        credit("CR-OUI", consentant, "2000000", FIN.minusMonths(2));
        credit("CR-NON", refusant, "2500000", FIN.minusMonths(2));
        UUID declaration = declarer("BIC", RegulatoryDeclarations.Method.CREDIT_BUREAU,
                                    RegulatoryDeclarations.Frequency.MONTHLY, 20, null);

        UUID sansPersonne = service().produce(ENTITY, declaration, FIN, FIN.plusDays(1), ACTOR);
        assertThat(lire(sansPersonne).lines())
            .as("sans consentement, l'historique ne sort pas").noneMatch(
                l -> l.subjectId().equals(consentant) || l.subjectId().equals(refusant));

        service().recordConsent(ENTITY, consentant, true, FIN.minusMonths(1), ACTOR);
        database.inEntity(ENTITY, c -> ReportFilings.cancel(c, sansPersonne, FIN.plusDays(1),
                                                            "reprise apres consentement"));
        UUID avec = service().produce(ENTITY, declaration, FIN, FIN.plusDays(1), ACTOR);
        assertThat(lire(avec).lines()).extracting(ReportFilings.Line::subjectReference)
            .containsExactly("REG-OUI");

        // La revocation ne reecrit pas le passe : elle vaut pour les etats suivants.
        service().recordConsent(ENTITY, consentant, false, FIN.plusDays(2), ACTOR);
        LocalDate moisSuivant = LocalDate.of(2026, 10, 31);
        UUID apres = service().produce(ENTITY, declaration, moisSuivant, moisSuivant.plusDays(1),
                                       ACTOR);
        assertThat(lire(apres).lines()).as("le consentement revoque ne declare plus").isEmpty();
        assertThat(lire(avec).lines()).as("l'etat deja produit garde son contenu").hasSize(1);
    }

    @Test
    @DisplayName("une declaration incomplete ou mal seuillee est refusee a la declaration, et se decide a deux")
    void an_ill_formed_declaration_is_refused() {
        assertThatThrownBy(() -> new RegulatoryDeclarations.Draft(
                ENTITY, "SANS-DELAI", "Sans delai",
                RegulatoryDeclarations.Recipient.CENTRAL_BANK,
                RegulatoryDeclarations.Method.ACCOUNTING_SITUATION,
                RegulatoryDeclarations.Frequency.MONTHLY, null, null, FIN.minusMonths(6), null,
                ACTOR, APPROVER))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("delai");

        // Une situation comptable seuillee serait fausse : la balance est exhaustive.
        assertThatThrownBy(() -> new RegulatoryDeclarations.Draft(
                ENTITY, "SEUILLEE", "Balance seuillee",
                RegulatoryDeclarations.Recipient.CENTRAL_BANK,
                RegulatoryDeclarations.Method.ACCOUNTING_SITUATION,
                RegulatoryDeclarations.Frequency.MONTHLY, 15, new BigDecimal("1000"),
                FIN.minusMonths(6), null, ACTOR, APPROVER))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ne se seuille pas");

        assertThatThrownBy(() -> new RegulatoryDeclarations.Draft(
                ENTITY, "SEUL", "Decidee seule", RegulatoryDeclarations.Recipient.CENTRAL_BANK,
                RegulatoryDeclarations.Method.CREDIT_REGISTRY,
                RegulatoryDeclarations.Frequency.MONTHLY, 15, new BigDecimal("1000"),
                FIN.minusMonths(6), null, ACTOR, ACTOR))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a deux");
    }

    @Test
    @DisplayName("la situation comptable dit le meme solde que le grand livre, contre-passations comprises")
    void the_accounting_situation_agrees_with_the_ledger() {
        io.corebanking.ledger.domain.account.Account compte = account("CTRL-PASSE",
            io.corebanking.ledger.domain.account.AccountKind.GL,
            io.corebanking.ledger.domain.account.NormalBalance.DEBIT);
        // Une ecriture, puis sa contre-passation : le solde du compte revient a zero. Un etat
        // qui ecarterait la contre-passation laisserait l'operation annulee dans la balance
        // transmise — et l'etat dirait autre chose que le grand livre de la banque.
        io.corebanking.ledger.domain.posting.PostingResult ecriture = postingService.post(
            io.corebanking.ledger.domain.posting.PostingCommand.online(
                io.corebanking.kernel.id.IdempotencyKey.of("ctrl-1"), ENTITY, FIN.minusDays(3),
                "TRANSFER", ACTOR,
                List.of(io.corebanking.ledger.domain.posting.PostingLine.debit(
                            compte.id(), xof("700000"), FIN.minusDays(3), null),
                        io.corebanking.ledger.domain.posting.PostingLine.credit(
                            caisse.id(), xof("700000"), FIN.minusDays(3), null))));
        postingService.reverse(ecriture.entryId(), FIN.minusDays(3), FIN.minusDays(2),
                               io.corebanking.kernel.id.IdempotencyKey.of("ctrl-1-rev"),
                               "erreur de saisie");

        UUID declaration = declarer("SITUATION-CTRL",
                                    RegulatoryDeclarations.Method.ACCOUNTING_SITUATION,
                                    RegulatoryDeclarations.Frequency.MONTHLY, 15, null);
        UUID etat = service().produce(ENTITY, declaration, FIN, FIN.plusDays(1), ACTOR);

        ReportFilings.Line ligne = lire(etat).lines().stream()
            .filter(l -> "CTRL-PASSE".equals(l.subjectReference())).findFirst().orElseThrow();
        io.corebanking.kernel.money.Money grandLivre = database.inEntity(ENTITY,
            c -> io.corebanking.ledger.store.Balances.replayAsOfBookingDate(c, compte.id(), FIN));
        assertThat(ligne.amount()).as("l'etat dit ce que dit le grand livre")
            .isEqualTo(grandLivre);
        assertThat(ligne.amount()).isEqualTo(xof("0"));
    }

    @Test
    @DisplayName("le recensement lit l'etat du jour de la periode, pas celui d'aujourd'hui : un engagement debloque depuis reste un engagement ce jour-la")
    void the_registry_reads_the_state_as_of_the_period_end() {
        UUID client = client("REG-TRANCHE");
        // Deux millions accordes, un million deux cent mille verses : le reste est un engagement
        // de financement que la banque tiendra a la demande du client.
        UUID contrat = credit("CR-TRANCHE", client, "2000000", "1200000", FIN.minusMonths(2));
        mobilisation(contrat, FIN.plusMonths(2));
        // Le plan se pose d'un bloc : la base verifie que les tranches totalisent le capital
        // accorde, et ce controle n'a de sens qu'une fois le plan complet.
        UUID tranche = database.inTransaction(c -> {
            tranche(c, contrat, 1, "1200000", FIN.minusMonths(2), FIN.minusMonths(2));
            return tranche(c, contrat, 2, "800000", FIN.minusDays(10), null);
        });

        UUID declaration = declarer("CENTRALE-TRANCHE",
                                    RegulatoryDeclarations.Method.CREDIT_REGISTRY,
                                    RegulatoryDeclarations.Frequency.MONTHLY, 15,
                                    new BigDecimal("1000000"));
        UUID etat = service().produce(ENTITY, declaration, FIN, FIN.plusDays(1), ACTOR);
        ReportFilings.Line ligne = lire(etat).lines().stream()
            .filter(l -> "REG-TRANCHE".equals(l.subjectReference())).findFirst().orElseThrow();
        assertThat(ligne.amount()).as("l'encours porte").isEqualTo(xof("1200000"));
        assertThat(ligne.offBalance()).as("l'engagement non verse").isEqualTo(xof("800000"));

        database.inEntity(ENTITY, c -> ReportFilings.transmit(c, etat, FIN.plusDays(2),
                                                              "BCEAO-TRANCHE", ACTOR, APPROVER));

        // La tranche est debloquee apres la fin de periode. Elle etait un engagement ce jour-la,
        // et l'etat regenere doit le redire a l'identique — sinon la reproductibilite dirait un
        // ecart de donnees la ou il n'y a que le temps qui passe.
        debloquer(tranche, FIN.plusDays(5));
        assertThat(service().differences(ENTITY, etat))
            .as("le passe ne bouge pas parce qu'on est demain").isEmpty();
    }

    @Test
    @DisplayName("la classe retenue est la derniere connue a la fin de periode, pas la pire jamais atteinte")
    void the_registry_keeps_the_latest_classification_not_the_worst_ever() {
        UUID client = client("REG-CLASSE");
        UUID contrat = credit("CR-CLASSE", client, "4000000", FIN.minusMonths(3));
        // Le credit a ete douteux, puis il est redevenu sain : c'est le sain qui se declare.
        classer(contrat, FIN.minusMonths(2), 3, 120);
        classer(contrat, FIN.minusDays(5), 1, 0);

        UUID declaration = declarer("CENTRALE-CLASSE",
                                    RegulatoryDeclarations.Method.CREDIT_REGISTRY,
                                    RegulatoryDeclarations.Frequency.MONTHLY, 15,
                                    new BigDecimal("1000000"));
        UUID etat = service().produce(ENTITY, declaration, FIN, FIN.plusDays(1), ACTOR);
        ReportFilings.Line ligne = lire(etat).lines().stream()
            .filter(l -> "REG-CLASSE".equals(l.subjectReference())).findFirst().orElseThrow();
        assertThat(ligne.daysPastDue()).as("le retard de la derniere classification").isZero();
        assertThat(ligne.classification()).isNotNull();
    }

    // ------------------------------------------------------------------ outillage

    static UUID declarer(String code, RegulatoryDeclarations.Method method,
                         RegulatoryDeclarations.Frequency frequency, int deadlineDays,
                         BigDecimal threshold) {
        return database.inEntity(ENTITY, c -> RegulatoryDeclarations.declare(c,
            new RegulatoryDeclarations.Draft(ENTITY, code, code,
                RegulatoryDeclarations.Recipient.CENTRAL_BANK, method, frequency, deadlineDays,
                threshold, FIN.minusMonths(6), null, ACTOR, APPROVER)));
    }

    static ReportFilings.Filing lire(UUID filingId) {
        return database.inEntity(ENTITY, c -> ReportFilings.require(c, filingId));
    }
}
