package io.corebanking.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.money.Currencies;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.Entities;
import io.corebanking.ledger.store.SchemaMigrator;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Le dossier client : ce que la banque exige, ce qu'elle detient, ce qui manque — et ce que
 * l'incompletude empeche.
 */
class PartyFileIT {

    private static EmbeddedPostgres postgres;
    private static Database database;
    private static PartyService parties;

    private static final UUID ENTITY = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000ac");
    private static final UUID APPROVER = UUID.fromString("00000000-0000-0000-0000-0000000000af");
    private static final LocalDate JOUR = LocalDate.of(2026, 9, 14);

    @BeforeAll
    static void start() throws IOException {
        postgres = EmbeddedPostgres.builder().start();
        database = new Database(
            "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres", "postgres", "", 4);
        SchemaMigrator.migrate(database);
        database.inTransaction(c -> {
            Entities.insertCurrency(c, Currencies.XOF, "Franc CFA BCEAO");
            Entities.insertLegalEntity(c, ENTITY, "BANK-CI", "Banque de test", "CI",
                                       Currencies.XOF, JOUR);
            return null;
        });
        parties = new PartyService(database, Screening.NONE);
    }

    @AfterAll
    static void stop() throws IOException {
        if (database != null) database.close();
        if (postgres != null) postgres.close();
    }

    private static UUID personne(String reference) {
        UUID id = parties.create(new PartyService.Draft(ENTITY, reference,
            PartyKind.NATURAL_PERSON, "Personne " + reference, LocalDate.of(1985, 3, 2), "CI", null,
            List.of(PartyIdentifier.of(IdentifierKind.NATIONAL_ID, "CNI-" + reference)), ACTOR));
        parties.verifyKyc(id, RiskRating.MEDIUM, JOUR.minusMonths(1), ACTOR, APPROVER);
        return id;
    }

    private static UUID personneNonVerifiee(String reference) {
        return parties.create(new PartyService.Draft(ENTITY, reference, PartyKind.NATURAL_PERSON,
            "Personne " + reference, LocalDate.of(1985, 3, 2), "CI", null,
            List.of(PartyIdentifier.of(IdentifierKind.NATIONAL_ID, "CNI-" + reference)), ACTOR));
    }

    private static UUID societe(String reference) {
        UUID id = parties.create(new PartyService.Draft(ENTITY, reference, PartyKind.LEGAL_PERSON,
            "Societe " + reference, LocalDate.of(2010, 1, 1), "CI", null,
            List.of(PartyIdentifier.of(IdentifierKind.TRADE_REGISTRY, "RCCM-" + reference)), ACTOR));
        parties.verifyKyc(id, RiskRating.MEDIUM, JOUR.minusMonths(1), ACTOR, APPROVER);
        return id;
    }

    private static PartyDocuments.Document deposer(UUID partyId, DocumentKind kind,
                                                   LocalDate expire) {
        return database.inTransaction(c -> PartyDocuments.deposit(c, new PartyDocuments.Deposit(
            ENTITY, partyId, kind, "REF-" + kind, "ONECI", JOUR.minusYears(1), expire, JOUR,
            ACTOR)));
    }

    private static PartyFile.Completeness dossier(UUID partyId, LocalDate on) {
        return database.inTransaction(c -> PartyFile.completeness(c, partyId, on));
    }

    @Test
    @DisplayName("sans politique de diligence, rien n'est exige et la completude le dit ; declaree, elle nomme ce qui manque et ce qui a expire")
    void completeness() {
        UUID client = personne("DOC-001");
        PartyFile.Completeness sansPolitique = dossier(client, JOUR);
        assertThat(sansPolitique.policyDeclared()).isFalse();
        assertThat(sansPolitique.complete()).isTrue();
        assertThat(sansPolitique.summary()).isEqualTo("dossier complet");

        database.inTransaction(c -> KycPolicies.declare(c, new KycPolicies.Draft(
            ENTITY, PartyKind.NATURAL_PERSON, KycLevel.STANDARD,
            Set.of(DocumentKind.IDENTITY, DocumentKind.ADDRESS_PROOF), false, null, ACTOR,
            APPROVER)));
        PartyFile.Completeness vide = dossier(client, JOUR);
        assertThat(vide.policyDeclared()).isTrue();
        assertThat(vide.complete()).isFalse();
        assertThat(vide.missing()).containsExactly(DocumentKind.IDENTITY,
                                                   DocumentKind.ADDRESS_PROOF);
        assertThat(vide.summary()).contains("pieces manquantes");

        deposer(client, DocumentKind.IDENTITY, JOUR.plusYears(2));
        deposer(client, DocumentKind.ADDRESS_PROOF, null);
        assertThat(dossier(client, JOUR).complete()).isTrue();

        // Une piece qui expire manque : le dossier redevient incomplet le jour ou elle expire.
        assertThat(dossier(client, JOUR.plusYears(2)).complete()).as("le dernier jour, elle vaut")
            .isTrue();
        PartyFile.Completeness apres = dossier(client, JOUR.plusYears(2).plusDays(1));
        assertThat(apres.expired()).containsExactly(DocumentKind.IDENTITY);
        assertThat(apres.summary()).contains("pieces expirees");

        // Une piece renouvelee remplace la precedente sans l'effacer.
        deposer(client, DocumentKind.IDENTITY, JOUR.plusYears(5));
        assertThat(dossier(client, JOUR.plusYears(3)).complete()).isTrue();
        List<PartyDocuments.Document> toutes = database.inTransaction(
            c -> PartyDocuments.of(c, client, true));
        assertThat(toutes).hasSize(3);
        List<PartyDocuments.Document> courantes = database.inTransaction(
            c -> PartyDocuments.of(c, client, false));
        assertThat(courantes).hasSize(2);
        assertThat(courantes).filteredOn(d -> d.kind() == DocumentKind.IDENTITY).singleElement()
            .satisfies(d -> assertThat(d.expiresOn()).isEqualTo(JOUR.plusYears(5)));

        // Une piece n'expire pas avant d'etre emise.
        assertThatThrownBy(() -> new PartyDocuments.Deposit(ENTITY, client, DocumentKind.IDENTITY,
                null, null, JOUR, JOUR.minusDays(1), JOUR, ACTOR))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("avant d'etre emise");
    }

    @Test
    @DisplayName("un dossier incomplet n'ouvre plus rien, mais ses comptes continuent de fonctionner")
    void an_incomplete_file_stops_onboarding_not_operations() {
        UUID client = personne("DOC-002");
        database.inTransaction(c -> KycPolicies.replace(c, new KycPolicies.Draft(
            ENTITY, PartyKind.NATURAL_PERSON, KycLevel.STANDARD, Set.of(DocumentKind.IDENTITY),
            false, null, ACTOR, APPROVER)));

        assertThatThrownBy(() -> database.inTransaction(
                c -> PartyService.requireOnboardable(c, client)))
            .isInstanceOf(PartyService.PartyNotOperableException.class)
            .hasMessageContaining("dossier incomplet")
            .hasMessageContaining("IDENTITY")
            .hasMessageContaining("comptes existants continuent de fonctionner");
        Party operable = database.inTransaction(c -> PartyService.requireOperable(c, client));
        assertThat(operable.reference()).as("ses comptes fonctionnent").isEqualTo("DOC-002");

        deposer(client, DocumentKind.IDENTITY, JOUR.plusYears(1));
        Party ouvrable = database.inTransaction(c -> PartyService.requireOnboardable(c, client));
        assertThat(ouvrable.reference()).isEqualTo("DOC-002");
    }

    @Test
    @DisplayName("les beneficiaires effectifs d'une personne morale : personnes physiques, parts plafonnees a 100 %, connus veut dire dossier verifie")
    void beneficial_owners() {
        UUID societe = societe("SOC-001");
        UUID dirigeant = personne("BO-001");
        UUID associe = personneNonVerifiee("BO-002");
        database.inTransaction(c -> KycPolicies.declare(c, new KycPolicies.Draft(
            ENTITY, PartyKind.LEGAL_PERSON, KycLevel.STANDARD, Set.of(DocumentKind.ARTICLES),
            true, new BigDecimal("25"), ACTOR, APPROVER)));
        deposer(societe, DocumentKind.ARTICLES, null);

        // Sans beneficiaire declare, le dossier d'une personne morale est incomplet.
        PartyFile.Completeness sansBeneficiaire = dossier(societe, JOUR);
        assertThat(sansBeneficiaire.beneficialOwnersMissing()).isTrue();
        assertThat(sansBeneficiaire.summary()).contains("aucun beneficiaire effectif declare");

        database.inTransaction(c -> BeneficialOwners.declare(c, new BeneficialOwners.Declaration(
            ENTITY, societe, dirigeant, new BigDecimal("60"), JOUR, ACTOR, APPROVER)));
        assertThat(dossier(societe, JOUR).complete()).isTrue();

        // Un beneficiaire au-dela du seuil doit avoir son dossier verifie : le connaitre, c'est
        // avoir son dossier, pas son nom.
        database.inTransaction(c -> BeneficialOwners.declare(c, new BeneficialOwners.Declaration(
            ENTITY, societe, associe, new BigDecimal("30"), JOUR, ACTOR, APPROVER)));
        PartyFile.Completeness avecNonVerifie = dossier(societe, JOUR);
        assertThat(avecNonVerifie.unverifiedOwners()).containsExactly("BO-002");
        assertThat(avecNonVerifie.complete()).isFalse();
        parties.verifyKyc(associe, RiskRating.LOW, JOUR, ACTOR, APPROVER);
        assertThat(dossier(societe, JOUR).complete()).isTrue();

        // Les parts ne depassent pas 100 %, et un beneficiaire est une personne physique.
        UUID troisieme = personne("BO-003");
        assertThatThrownBy(() -> database.inTransaction(
                c -> BeneficialOwners.declare(c, new BeneficialOwners.Declaration(
                    ENTITY, societe, troisieme, new BigDecimal("20"), JOUR, ACTOR, APPROVER))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("totaliseraient 110");
        UUID autreSociete = societe("SOC-002");
        assertThatThrownBy(() -> database.inTransaction(
                c -> BeneficialOwners.declare(c, new BeneficialOwners.Declaration(
                    ENTITY, societe, autreSociete, new BigDecimal("5"), JOUR, ACTOR, APPROVER))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("remonter la chaine de detention");
        assertThatThrownBy(() -> database.inTransaction(
                c -> BeneficialOwners.declare(c, new BeneficialOwners.Declaration(
                    ENTITY, dirigeant, troisieme, new BigDecimal("5"), JOUR, ACTOR, APPROVER))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Seule une personne morale");
        assertThatThrownBy(() -> new BeneficialOwners.Declaration(ENTITY, societe, dirigeant,
                new BigDecimal("10"), JOUR, ACTOR, ACTOR))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a deux");

        // Une part qui change de main : la declaration prend fin, l'historique reste.
        BeneficialOwners.Owner sortant = database.inTransaction(c -> BeneficialOwners.current(
            c, societe)).stream().filter(o -> o.ownerReference().equals("BO-002")).findFirst()
            .orElseThrow();
        database.inTransaction(c -> {
            BeneficialOwners.end(c, sortant.id(), JOUR, ACTOR, APPROVER);
            return null;
        });
        List<BeneficialOwners.Owner> restants = database.inTransaction(
            c -> BeneficialOwners.current(c, societe));
        assertThat(restants).extracting(BeneficialOwners.Owner::ownerReference)
            .containsExactly("BO-001");
        // La part liberee peut etre redeclaree.
        database.inTransaction(c -> BeneficialOwners.declare(c, new BeneficialOwners.Declaration(
            ENTITY, societe, troisieme, new BigDecimal("40"), JOUR, ACTOR, APPROVER)));
        List<BeneficialOwners.Owner> apresRedeclaration = database.inTransaction(
            c -> BeneficialOwners.current(c, societe));
        assertThat(apresRedeclaration).hasSize(2);
    }

    @Test
    @DisplayName("les relations entre tiers : le representant legal est une personne physique, la detention ne boucle pas, et le groupe se lit en chaine")
    void relationships() {
        UUID mere = societe("GRP-MERE");
        UUID fille = societe("GRP-FILLE");
        UUID petiteFille = societe("GRP-PETITE");
        UUID gerant = personne("GRP-GERANT");

        database.inTransaction(c -> Relationships.declare(c, new Relationships.Draft(
            ENTITY, fille, mere, RelationshipKind.PARENT_COMPANY, JOUR, ACTOR, APPROVER)));
        database.inTransaction(c -> Relationships.declare(c, new Relationships.Draft(
            ENTITY, petiteFille, fille, RelationshipKind.PARENT_COMPANY, JOUR, ACTOR, APPROVER)));
        Relationships.Relationship mandat = database.inTransaction(
            c -> Relationships.declare(c, new Relationships.Draft(
                ENTITY, fille, gerant, RelationshipKind.LEGAL_REPRESENTATIVE, JOUR, ACTOR,
                APPROVER)));
        assertThat(mandat.toReference()).isEqualTo("GRP-GERANT");

        // La detention ne boucle pas, meme au deuxieme rang.
        assertThatThrownBy(() -> database.inTransaction(
                c -> Relationships.declare(c, new Relationships.Draft(
                    ENTITY, mere, petiteFille, RelationshipKind.PARENT_COMPANY, JOUR, ACTOR,
                    APPROVER))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("formerait un cycle");
        assertThatThrownBy(() -> database.inTransaction(
                c -> Relationships.declare(c, new Relationships.Draft(
                    ENTITY, fille, gerant, RelationshipKind.PARENT_COMPANY, JOUR, ACTOR, APPROVER))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("deux personnes morales");
        assertThatThrownBy(() -> database.inTransaction(
                c -> Relationships.declare(c, new Relationships.Draft(
                    ENTITY, fille, mere, RelationshipKind.LEGAL_REPRESENTATIVE, JOUR, ACTOR,
                    APPROVER))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("representant legal est une personne physique");
        assertThatThrownBy(() -> new Relationships.Draft(ENTITY, fille, fille,
                RelationshipKind.GROUP_MEMBER, JOUR, ACTOR, APPROVER))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lui-meme");
        assertThatThrownBy(() -> database.inTransaction(
                c -> Relationships.declare(c, new Relationships.Draft(
                    ENTITY, fille, mere, RelationshipKind.PARENT_COMPANY, JOUR, ACTOR, APPROVER))))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("deja en vigueur");

        // Le groupe se lit en chaine, dans les deux sens.
        List<UUID> groupe = database.inTransaction(c -> Relationships.group(c, mere));
        assertThat(groupe).containsExactlyInAnyOrder(mere, fille, petiteFille);
        List<UUID> depuisLaPetite = database.inTransaction(c -> Relationships.group(c, petiteFille));
        assertThat(depuisLaPetite).containsExactlyInAnyOrder(mere, fille, petiteFille);
        List<UUID> seul = database.inTransaction(c -> Relationships.group(c, gerant));
        assertThat(seul).containsExactly(gerant);

        // Les relations d'un tiers, portees comme subies ; leur fin se decide a deux.
        List<Relationships.Relationship> deLaFille = database.inTransaction(
            c -> Relationships.of(c, fille));
        assertThat(deLaFille).hasSize(3);
        assertThatThrownBy(() -> database.inTransaction(c -> {
            Relationships.end(c, mandat.id(), JOUR, ACTOR, ACTOR);
            return null;
        })).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("a deux");
        database.inTransaction(c -> {
            Relationships.end(c, mandat.id(), JOUR, ACTOR, APPROVER);
            return null;
        });
        List<Relationships.Relationship> duGerant = database.inTransaction(
            c -> Relationships.of(c, gerant));
        assertThat(duGerant).isEmpty();
        assertThatThrownBy(() -> database.inTransaction(c -> {
            Relationships.end(c, mandat.id(), JOUR, ACTOR, APPROVER);
            return null;
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("deja terminee");
    }

    @Test
    @DisplayName("les dossiers incomplets se recensent, et une politique remplacee ne laisse pas ses anciennes exigences")
    void incomplete_files_and_policy_replacement() {
        UUID client = personne("REC-001");
        database.inTransaction(c -> KycPolicies.replace(c, new KycPolicies.Draft(
            ENTITY, PartyKind.NATURAL_PERSON, KycLevel.STANDARD,
            Set.of(DocumentKind.IDENTITY, DocumentKind.INCOME_PROOF), false, null, ACTOR,
            APPROVER)));
        deposer(client, DocumentKind.IDENTITY, JOUR.plusYears(1));
        List<PartyFile.Completeness> incomplets = database.inTransaction(
            c -> PartyFile.incomplete(c, ENTITY, JOUR));
        assertThat(incomplets).extracting(PartyFile.Completeness::reference).contains("REC-001");

        // Une piece perimee compte comme manquante dans la liste de travail, et un dossier
        // complet n'y figure pas : la base designe les memes dossiers que la confrontation.
        UUID perime = personne("REC-002");
        deposer(perime, DocumentKind.IDENTITY, JOUR.minusDays(1));
        deposer(perime, DocumentKind.INCOME_PROOF, null);
        UUID complet = personne("REC-003");
        deposer(complet, DocumentKind.IDENTITY, JOUR.plusYears(1));
        deposer(complet, DocumentKind.INCOME_PROOF, null);
        List<PartyFile.Completeness> avecPerimes = database.inTransaction(
            c -> PartyFile.incomplete(c, ENTITY, JOUR));
        assertThat(avecPerimes).extracting(PartyFile.Completeness::reference)
            .contains("REC-001", "REC-002").doesNotContain("REC-003");
        assertThat(avecPerimes).filteredOn(d -> d.reference().equals("REC-002")).singleElement()
            .satisfies(d -> assertThat(d.expired()).containsExactly(DocumentKind.IDENTITY));

        // Une personne morale dont les beneficiaires effectifs sont exiges et inconnus y figure
        // aussi — la deuxieme branche de la meme requete.
        UUID societe = societe("REC-SA");
        database.inTransaction(c -> KycPolicies.replace(c, new KycPolicies.Draft(
            ENTITY, PartyKind.LEGAL_PERSON, KycLevel.STANDARD, Set.of(), true, null, ACTOR,
            APPROVER)));
        List<PartyFile.Completeness> avecSociete = database.inTransaction(
            c -> PartyFile.incomplete(c, ENTITY, JOUR));
        assertThat(avecSociete).filteredOn(d -> d.reference().equals("REC-SA")).singleElement()
            .satisfies(d -> assertThat(d.beneficialOwnersMissing()).isTrue());
        database.inTransaction(c -> BeneficialOwners.declare(c, new BeneficialOwners.Declaration(
            ENTITY, societe, personne("REC-BO"), new BigDecimal("100"), JOUR, ACTOR, APPROVER)));
        List<PartyFile.Completeness> apresDeclaration = database.inTransaction(
            c -> PartyFile.incomplete(c, ENTITY, JOUR));
        assertThat(apresDeclaration).extracting(PartyFile.Completeness::reference)
            .doesNotContain("REC-SA");

        // La politique se remplace : l'exigence retiree ne manque plus.
        database.inTransaction(c -> KycPolicies.replace(c, new KycPolicies.Draft(
            ENTITY, PartyKind.NATURAL_PERSON, KycLevel.STANDARD, Set.of(DocumentKind.IDENTITY),
            false, null, ACTOR, APPROVER)));
        assertThat(dossier(client, JOUR).complete()).isTrue();
        List<KycPolicies.Policy> politiques = database.inTransaction(c -> KycPolicies.all(c, ENTITY));
        assertThat(politiques).filteredOn(p -> p.partyKind() == PartyKind.NATURAL_PERSON
                                               && p.kycLevel() == KycLevel.STANDARD)
            .singleElement()
            .satisfies(p -> assertThat(p.requiredDocuments()).containsExactly(DocumentKind.IDENTITY));
        assertThatThrownBy(() -> database.inTransaction(c -> KycPolicies.declare(c,
                new KycPolicies.Draft(ENTITY, PartyKind.NATURAL_PERSON, KycLevel.STANDARD,
                                      Set.of(), false, null, ACTOR, APPROVER))))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("existe deja");
        assertThatThrownBy(() -> new KycPolicies.Draft(ENTITY, PartyKind.NATURAL_PERSON,
                KycLevel.STANDARD, Set.of(), true, null, ACTOR, APPROVER))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exigence des personnes morales");
    }

    @Test
    @DisplayName("a deux en meme temps : une seule piece en vigueur par nature, des parts qui ne depassent pas cent pour cent, et une detention qui ne boucle pas")
    void concurrency() throws Exception {
        UUID client = personne("CONC-001");
        UUID mere = societe("CONC-MERE");
        UUID fille = societe("CONC-FILLE");
        UUID premier = personne("CONC-BO-1");
        UUID second = personne("CONC-BO-2");
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            // Deux depots de la meme nature, en meme temps : une seule piece reste en vigueur,
            // l'autre est chainee — sans l'index unique, les deux resteraient courantes.
            List<Object> depots = enMemeTemps(executor,
                () -> deposer(client, DocumentKind.IDENTITY, JOUR.plusYears(5)),
                () -> deposer(client, DocumentKind.IDENTITY, JOUR.plusYears(6)));
            assertThat(depots).allMatch(o -> o instanceof PartyDocuments.Document);
            List<PartyDocuments.Document> courantes = database.inTransaction(
                c -> PartyDocuments.of(c, client, false));
            assertThat(courantes).hasSize(1);
            List<PartyDocuments.Document> toutes = database.inTransaction(
                c -> PartyDocuments.of(c, client, true));
            assertThat(toutes).hasSize(2);

            // Soixante et soixante : la seconde declaration voit la premiere et se refuse.
            List<Object> parts = enMemeTemps(executor,
                () -> database.inTransaction(c -> BeneficialOwners.declare(c,
                    new BeneficialOwners.Declaration(ENTITY, mere, premier, new BigDecimal("60"),
                                                     JOUR, ACTOR, APPROVER))),
                () -> database.inTransaction(c -> BeneficialOwners.declare(c,
                    new BeneficialOwners.Declaration(ENTITY, mere, second, new BigDecimal("60"),
                                                     JOUR, ACTOR, APPROVER))));
            assertThat(parts).filteredOn(o -> o instanceof BeneficialOwners.Owner).hasSize(1);
            assertThat(parts).filteredOn(o -> o instanceof IllegalArgumentException).hasSize(1);
            List<BeneficialOwners.Owner> detenteurs = database.inTransaction(
                c -> BeneficialOwners.current(c, mere));
            assertThat(detenteurs).hasSize(1);

            // Les deux sens d'une meme detention : aucune des deux declarations, seule, ne ferme
            // un cycle ; ensemble, elles le feraient. La seconde doit donc attendre la premiere,
            // et la voir. Le scenario est deterministe : la premiere transaction declare puis
            // retient sa validation jusqu'a ce que la seconde soit lancee.
            var declaree = new java.util.concurrent.CountDownLatch(1);
            var valider = new java.util.concurrent.CountDownLatch(1);
            var premiere = executor.submit(() -> database.inTransaction(c -> {
                Relationships.Relationship r = Relationships.declare(c, new Relationships.Draft(
                    ENTITY, fille, mere, RelationshipKind.PARENT_COMPANY, JOUR, ACTOR, APPROVER));
                declaree.countDown();
                try {
                    valider.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return r;
            }));
            declaree.await();
            var seconde = executor.submit(() -> {
                try {
                    return database.inTransaction(c -> Relationships.declare(c,
                        new Relationships.Draft(ENTITY, mere, fille,
                                                RelationshipKind.PARENT_COMPANY, JOUR, ACTOR,
                                                APPROVER)));
                } catch (RuntimeException e) {
                    return e;
                }
            });
            Thread.sleep(300);                       // le temps que la seconde bute sur le verrou
            valider.countDown();
            assertThat(premiere.get()).isInstanceOf(Relationships.Relationship.class);
            assertThat(seconde.get()).isInstanceOf(IllegalArgumentException.class)
                .asString().contains("cycle");
            List<UUID> groupe = database.inTransaction(c -> Relationships.group(c, mere));
            assertThat(groupe).containsExactlyInAnyOrder(mere, fille);
        } finally {
            executor.shutdown();
        }
    }

    private static List<Object> enMemeTemps(java.util.concurrent.ExecutorService executor,
                                            java.util.concurrent.Callable<?> premier,
                                            java.util.concurrent.Callable<?> second)
            throws Exception {
        var depart = new java.util.concurrent.CountDownLatch(1);
        java.util.function.Function<java.util.concurrent.Callable<?>,
                                    java.util.concurrent.Callable<Object>>
            tentative = acte -> () -> {
                depart.await();
                try {
                    return acte.call();
                } catch (RuntimeException e) {
                    return e;
                }
            };
        var a = executor.submit(tentative.apply(premier));
        var b = executor.submit(tentative.apply(second));
        depart.countDown();
        return List.of(a.get(), b.get());
    }
}
