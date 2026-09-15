package io.corebanking.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.corebanking.kernel.money.Currencies;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountStatus;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.Entities;
import io.corebanking.ledger.store.SchemaMigrator;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Le referentiel client : un tiers par personne, rien ne s'ouvre sur un dossier non verifie. */
class PartyIT {

    private static EmbeddedPostgres postgres;
    private static Database database;
    private static PartyService parties;

    private static final UUID ENTITY = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID AUTRE = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000ac");
    private static final UUID APPROVER = UUID.fromString("00000000-0000-0000-0000-0000000000af");
    private static final LocalDate JOUR = LocalDate.of(2026, 9, 14);

    @BeforeAll
    static void start() throws IOException {
        postgres = EmbeddedPostgres.builder().start();
        database = new Database(
            "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres", "postgres", "", 4);
        SchemaMigrator.migrate(database, SchemaMigrator.Gaps.TOLERATED);
        database.inTransaction(c -> {
            Entities.insertCurrency(c, Currencies.XOF, "Franc CFA BCEAO");
            Entities.insertLegalEntity(c, ENTITY, "BANK-CI", "Banque de test", "CI",
                                       Currencies.XOF, JOUR);
            Entities.insertLegalEntity(c, AUTRE, "BANK-SN", "Autre banque", "SN",
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

    private static PartyService.Draft personne(String reference, String cni) {
        return new PartyService.Draft(ENTITY, reference, PartyKind.NATURAL_PERSON,
            "Awa Diallo", LocalDate.of(1985, 3, 2), "CI", "PARTICULIER",
            List.of(new PartyIdentifier(IdentifierKind.NATIONAL_ID, cni, LocalDate.of(2020, 1, 1),
                                        LocalDate.of(2030, 1, 1), "ONECI"),
                    PartyIdentifier.of(IdentifierKind.PHONE, "+2250700000000")),
            ACTOR);
    }

    @Test
    @DisplayName("un identifiant officiel deja connu refuse la creation en nommant le dossier existant")
    void dedoublonnage() {
        UUID premier = parties.create(personne("CLI-001", "CI-1234567"));
        assertThat(parties.require(premier).kycStatus()).isEqualTo(KycStatus.PENDING);

        assertThatThrownBy(() -> parties.create(personne("CLI-002", "CI-1234567")))
            .isInstanceOf(PartyService.DuplicatePartyException.class)
            .hasMessageContaining("CLI-001")
            .hasMessageContaining("NATIONAL_ID CI-1234567");

        // Le meme numero dans une autre entite est un autre client : pas de fuite entre banques.
        UUID ailleurs = parties.create(new PartyService.Draft(AUTRE, "SN-001",
            PartyKind.NATURAL_PERSON, "Awa Diallo", null, "SN", null,
            List.of(PartyIdentifier.of(IdentifierKind.NATIONAL_ID, "CI-1234567")), ACTOR));
        assertThat(ailleurs).isNotEqualTo(premier);

        // Sans identifiant officiel, pas de dossier : rien ne distinguerait deux homonymes.
        assertThatThrownBy(() -> new PartyService.Draft(ENTITY, "CLI-003",
            PartyKind.NATURAL_PERSON, "Homonyme", null, "CI", null,
            List.of(PartyIdentifier.of(IdentifierKind.PHONE, "+2250100000000")), ACTOR))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("identifiant officiel");
    }

    @Test
    @DisplayName("la verification a deux fixe le niveau de diligence et l'echeance de revue selon le risque")
    void verification() {
        UUID id = parties.create(personne("CLI-010", "CI-0000010"));
        database.inTransaction(c -> {
            assertThatThrownBy(() -> PartyService.requireOnboardable(c, id))
                .isInstanceOf(PartyService.PartyNotOperableException.class)
                .hasMessageContaining("PENDING");
            // Un dossier en attente fonctionne ; il ne s'etend pas.
            assertThat(PartyService.requireOperable(c, id).reference()).isEqualTo("CLI-010");
            return null;
        });

        assertThatThrownBy(() -> parties.verifyKyc(id, RiskRating.MEDIUM, JOUR, ACTOR, ACTOR))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("a deux");

        parties.verifyKyc(id, RiskRating.MEDIUM, JOUR, ACTOR, APPROVER);
        Party verifie = parties.require(id);
        assertThat(verifie.kycStatus()).isEqualTo(KycStatus.VERIFIED);
        assertThat(verifie.kycLevel()).isEqualTo(KycLevel.STANDARD);
        assertThat(verifie.kycReviewDue()).isEqualTo(JOUR.plusMonths(24));
        assertThat(verifie.onboardable()).isTrue();

        parties.verifyKyc(id, RiskRating.HIGH, JOUR, ACTOR, APPROVER);
        assertThat(parties.require(id).kycLevel()).isEqualTo(KycLevel.ENHANCED);
        assertThat(parties.require(id).kycReviewDue()).isEqualTo(JOUR.plusMonths(12));
    }

    @Test
    @DisplayName("la revue depassee expire le dossier : ses comptes fonctionnent, rien de neuf ne s'ouvre ; l'annulation le defait")
    void revuePeriodique() {
        UUID id = parties.create(personne("CLI-020", "CI-0000020"));
        parties.verifyKyc(id, RiskRating.HIGH, JOUR.minusMonths(13), ACTOR, APPROVER);
        UUID run = UUID.randomUUID();

        List<String> expires = database.inTransaction(
            c -> KycReviews.expire(c, ENTITY, JOUR, run, ACTOR));
        assertThat(expires).contains("CLI-020");
        Party expire = parties.require(id);
        assertThat(expire.kycStatus()).isEqualTo(KycStatus.EXPIRED);
        assertThat(expire.operable()).isTrue();
        assertThat(expire.onboardable()).isFalse();

        int restaures = database.inTransaction(c -> KycReviews.cancelRun(c, run));
        assertThat(restaures).isEqualTo(1);
        assertThat(parties.require(id).kycStatus()).isEqualTo(KycStatus.VERIFIED);
    }

    @Test
    @DisplayName("une correspondance au filtrage cree le dossier bloque, en attente de levee de doute")
    void filtrage() {
        PartyService filtre = new PartyService(database, subject ->
            subject.displayName().contains("Sanctionne")
                ? Optional.of(new Screening.Match("UE", "EU-2024-17", "homonymie forte"))
                : Optional.empty());
        UUID id = filtre.create(new PartyService.Draft(ENTITY, "CLI-030", PartyKind.LEGAL_PERSON,
            "Societe Sanctionnee SA", LocalDate.of(2001, 5, 5), "CI", "PME",
            List.of(PartyIdentifier.of(IdentifierKind.TRADE_REGISTRY, "CI-ABJ-2001-B-1234")),
            ACTOR));

        Party bloque = filtre.require(id);
        assertThat(bloque.status()).isEqualTo(PartyStatus.BLOCKED);
        assertThat(bloque.statusReason()).contains("EU-2024-17");
        database.inTransaction(c -> {
            assertThatThrownBy(() -> PartyService.requireOperable(c, id))
                .isInstanceOf(PartyService.PartyNotOperableException.class)
                .hasMessageContaining("BLOCKED");
            return null;
        });

        // Levee de doute, a deux : le dossier redevient un dossier ordinaire, en attente de KYC.
        filtre.unblock(id, "faux positif, piece justificative au dossier", JOUR, ACTOR, APPROVER);
        assertThat(filtre.require(id).status()).isEqualTo(PartyStatus.ACTIVE);
    }

    @Test
    @DisplayName("un compte a des titulaires de son entite, et un titulaire bloque bloque le compte")
    void titulaires() {
        UUID titulaire = parties.create(personne("CLI-040", "CI-0000040"));
        UUID mandataire = parties.create(personne("CLI-041", "CI-0000041"));
        Account compte = new Account(UUID.randomUUID(), ENTITY, "CC-040", AccountKind.CUSTOMER,
                                     NormalBalance.CREDIT, Currencies.XOF, true, true, 1,
                                     AccountStatus.ACTIVE);
        database.inTransaction(c -> {
            Accounts.create(c, compte, JOUR);
            assertThatThrownBy(() -> AccountHolders.requireOperableHolders(c, compte.id(), JOUR))
                .hasMessageContaining("aucun titulaire");
            AccountHolders.attach(c, compte.id(), titulaire, HolderRole.HOLDER, JOUR, ACTOR);
            AccountHolders.attach(c, compte.id(), mandataire, HolderRole.MANDATE, JOUR, ACTOR);
            assertThat(AccountHolders.requireOperableHolders(c, compte.id(), JOUR)).hasSize(2);
            assertThat(AccountHolders.accountsOf(c, titulaire, JOUR)).containsExactly(compte.id());
            assertThat(AccountHolders.accountsOf(c, mandataire, JOUR)).isEmpty();
            return null;
        });

        // Un tiers d'une autre entite ne peut pas etre titulaire ici.
        UUID etranger = parties.create(new PartyService.Draft(AUTRE, "SN-040",
            PartyKind.NATURAL_PERSON, "Moussa Sow", null, "SN", null,
            List.of(PartyIdentifier.of(IdentifierKind.PASSPORT, "SN-P-040")), ACTOR));
        database.inTransaction(c -> {
            assertThatThrownBy(() -> AccountHolders.attach(c, compte.id(), etranger,
                                                           HolderRole.JOINT_HOLDER, JOUR, ACTOR))
                .hasMessageContaining("autre entite");
            return null;
        });

        // Le mandataire bloque n'empeche rien ; le titulaire bloque empeche tout.
        parties.block(mandataire, "opposition", JOUR, ACTOR, APPROVER);
        database.inTransaction(c -> {
            assertThat(AccountHolders.requireOperableHolders(c, compte.id(), JOUR)).hasSize(2);
            return null;
        });
        parties.block(titulaire, "saisie", JOUR, ACTOR, APPROVER);
        database.inTransaction(c -> {
            assertThatThrownBy(() -> AccountHolders.requireOperableHolders(c, compte.id(), JOUR))
                .isInstanceOf(PartyService.PartyNotOperableException.class)
                .hasMessageContaining("saisie");
            return null;
        });
    }
}
