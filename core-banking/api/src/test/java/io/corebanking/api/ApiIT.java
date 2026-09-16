package io.corebanking.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.corebanking.calendar.BusinessDayConvention;
import io.corebanking.calendar.Calendars;
import io.corebanking.calendar.OffsetUnit;
import io.corebanking.calendar.ValueDateRule;
import io.corebanking.deposits.DepositCatalog;
import io.corebanking.interest.accrual.AccrualSide;
import io.corebanking.kernel.money.Currencies;
import io.corebanking.ledger.domain.account.Account;
import io.corebanking.ledger.domain.account.AccountKind;
import io.corebanking.ledger.domain.account.AccountStatus;
import io.corebanking.ledger.domain.account.Direction;
import io.corebanking.ledger.domain.account.NormalBalance;
import io.corebanking.ledger.store.Accounts;
import io.corebanking.ledger.store.Branches;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.Entities;
import io.corebanking.loan.service.LoanCatalog;
import io.corebanking.product.ProductCatalog;
import io.corebanking.security.Roles;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/**
 * L'API de bout en bout : un vrai serveur, de vrais jetons signes, une vraie base. Le jeton dit
 * qui appelle ; {@code SecurityConfig} dit ce qu'il peut faire ; le socle fait le reste.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiIT {

    private static EmbeddedPostgres postgres;
    private static final KeyPair KEYS = rsa();
    private static final UUID ENTITY = UUID.randomUUID();
    private static final UUID APPROVER = UUID.randomUUID();
    private static final LocalDate J = LocalDate.of(2026, 9, 15);
    private static final String CLIENT_ID = "core-banking";
    private static final String APP_ROLE = "corebanking_app";
    private static final UUID AUTRE_ENTITE = UUID.randomUUID();
    /** Le sujet du guichetier : sa caisse lui est affectee, et resolue depuis son jeton. */
    private static final UUID GUICHETIER = UUID.randomUUID();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        try {
            postgres = EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        // Deux comptes, comme en production : le proprietaire migre, l'API se connecte avec un
        // role qui ne possede rien et auquel la base applique le cloisonnement par entite. Les
        // droits par defaut couvrent les tables que les migrations vont creer (ops/roles.sql).
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(url(), "postgres", "");
             java.sql.Statement s = c.createStatement()) {
            s.execute("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD 'app'");
            s.execute("GRANT USAGE ON SCHEMA public TO " + APP_ROLE);
            s.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE,"
                      + " DELETE ON TABLES TO " + APP_ROLE);
            s.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES"
                      + " TO " + APP_ROLE);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        registry.add("corebanking.datasource.url", ApiIT::url);
        registry.add("corebanking.datasource.username", () -> APP_ROLE);
        registry.add("corebanking.datasource.password", () -> "app");
        registry.add("corebanking.datasource.pool-size", () -> "8");
        registry.add("corebanking.schema.username", () -> "postgres");
        registry.add("corebanking.schema.password", () -> "");
        registry.add("corebanking.security.client-id", () -> CLIENT_ID);
    }

    private static String url() {
        return "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres";
    }

    /** Les jetons du test sont signes par une cle du test ; le serveur ne connait que la publique. */
    @TestConfiguration
    static class TestSecurity {
        @Bean
        JwtDecoder jwtDecoder() {
            return NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
        }
    }

    @Autowired private Environment environment;
    @Autowired private Database database;
    @Autowired private ObjectMapper json;

    /** Le decor est pose par le proprietaire du schema : c'est un acte d'exploitation, pas de l'API. */
    private Database owner;

    private final HttpClient http = HttpClient.newHttpClient();
    private UUID siege;
    private Account caisse;
    private String teller;
    private String teller2;
    private Account ecartsCaisse;
    private Account frais;
    private Account resultat;
    private Account reserves;
    private Account report;
    private Account reglementSortant;
    private Account nostro;
    private Account encaissement;
    private java.math.BigDecimal resultatNet;
    private String accountant2;
    private UUID caisseId;
    private String officer;
    private String manager;
    private String manager2;
    private String operator;
    private String creditOfficer;
    private String creditManager;
    private String productManager;
    private String riskOfficer;
    private String accountant;
    private Account pret;
    private Account courant;
    private Account liaison;
    private final Map<String, String> parametresCredit = new LinkedHashMap<>();
    private UUID party;
    private UUID account;
    private UUID pretId;

    @BeforeAll
    void decor() {
        // Les partitions, elles, sont creees par l'API avec son role applicatif : la fonction
        // s'execute avec les droits du proprietaire, comme le fera chaque bascule de journee.
        io.corebanking.ledger.store.SchemaMigrator.ensurePartitions(database, J.minusMonths(2),
                                                                     J.plusMonths(3));
        owner = new Database(url(), "postgres", "", 2);
        owner.inTransaction(c -> {
            Entities.insertCurrency(c, Currencies.XOF, "Franc CFA BCEAO");
            Entities.insertLegalEntity(c, ENTITY, "API", "Banque API", "CI", Currencies.XOF, J);
            Entities.insertLegalEntity(c, AUTRE_ENTITE, "AUTRE", "Autre banque", "SN",
                                       Currencies.XOF, J);
            Entities.openPeriod(c, ENTITY, J.minusMonths(2), J);
            UUID calendar = Calendars.createCalendar(c, "CI", "Cote d'Ivoire",
                Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), J.minusYears(1), J.plusYears(1));
            Calendars.attachToEntity(c, ENTITY, calendar);
            for (String type : List.of("CASH_DEPOSIT", "TRANSFER")) {
                Calendars.addRule(c, ENTITY, new ValueDateRule(type, null, Direction.CREDIT, 0,
                    OffsetUnit.CALENDAR_DAYS, BusinessDayConvention.UNADJUSTED, J.minusYears(1),
                    null), APPROVER, UUID.randomUUID());
            }
            for (String type : List.of("CASH_WITHDRAWAL", "TRANSFER", "PAYMENT_ORDER",
                                       "CHEQUE_PAYMENT")) {
                Calendars.addRule(c, ENTITY, new ValueDateRule(type, null, Direction.DEBIT, 0,
                    OffsetUnit.CALENDAR_DAYS, BusinessDayConvention.UNADJUSTED, J.minusYears(1),
                    null), APPROVER, UUID.randomUUID());
            }
            siege = Branches.headOffice(c, ENTITY);
            caisse = compte(c, "CAISSE", AccountKind.INTERNAL, NormalBalance.DEBIT, siege);
            Account charges = compteDeResultat(c, "CHARGES", NormalBalance.DEBIT);
            Account courus = compte(c, "COURUS", AccountKind.GL, NormalBalance.CREDIT, null);
            frais = compteDeResultat(c, "FRAIS", NormalBalance.CREDIT);
            resultat = compte(c, "RESULTAT", AccountKind.GL, NormalBalance.CREDIT, null);
            reserves = compte(c, "RESERVES", AccountKind.GL, NormalBalance.CREDIT, null);
            report = compte(c, "REPORT", AccountKind.GL, NormalBalance.CREDIT, null);
            Account taxe = compte(c, "TAXE", AccountKind.GL, NormalBalance.CREDIT, null);
            Map<String, String> parametres = new LinkedHashMap<>();
            parametres.put(ProductCatalog.P_RATE, "3");
            parametres.put(ProductCatalog.P_DAY_COUNT, "ACT_365");
            parametres.put(ProductCatalog.P_SIDE, AccrualSide.CREDITOR.name());
            parametres.put(ProductCatalog.P_CAPITALISATION, "QUARTERLY");
            parametres.put(ProductCatalog.P_DEBIT_ACCOUNT, charges.id().toString());
            parametres.put(ProductCatalog.P_CREDIT_ACCOUNT, courus.id().toString());
            parametres.put(DepositCatalog.P_WITHDRAWAL_FEE, "500");
            parametres.put(DepositCatalog.P_FEE_INCOME, frais.id().toString());
            parametres.put(DepositCatalog.P_TAX_RATE, "18");
            parametres.put(DepositCatalog.P_TAX_ACCOUNT, taxe.id().toString());
            reglementSortant = compte(c, "REGLEMENT-SORTANT", AccountKind.GL, NormalBalance.CREDIT, null);
            nostro = compte(c, "NOSTRO", AccountKind.NOSTRO, NormalBalance.DEBIT, null);
            parametres.put(DepositCatalog.P_PAYMENT_FEE, "1000");
            parametres.put(DepositCatalog.P_PAYMENT_CLEARING, reglementSortant.id().toString());
            encaissement = compte(c, "ENCAISSEMENT", AccountKind.GL, NormalBalance.DEBIT, null);
            parametres.put(DepositCatalog.P_CHEQUE_BOOK_FEE, "2000");
            parametres.put(DepositCatalog.P_CHEQUE_COLLECTION, encaissement.id().toString());
            UUID version = ProductCatalog.createDraft(c, new ProductCatalog.Draft(
                ENTITY, "EP-API", "SAVINGS_ACCOUNT", "Epargne", "XOF", J.minusMonths(1), null,
                parametres, List.of(), APPROVER));
            ProductCatalog.activate(c, version, UUID.randomUUID());

            // Le decor du credit : comptes du client et comptes generaux que le produit citera.
            pret = compte(c, "PRET", AccountKind.CUSTOMER, NormalBalance.DEBIT, siege);
            courant = compte(c, "COURANT", AccountKind.CUSTOMER, NormalBalance.CREDIT, siege);
            liaison = compte(c, "LIAISON-XOF", AccountKind.GL, NormalBalance.DEBIT, null);
            ecartsCaisse = compteDeResultat(c, "ECARTS-CAISSE", NormalBalance.DEBIT);
            Account creances = compte(c, "CREANCES", AccountKind.GL, NormalBalance.DEBIT, null);
            Account produits = compteDeResultat(c, "PRODUITS-CREDIT", NormalBalance.CREDIT);
            Account retard = compteDeResultat(c, "RETARD", NormalBalance.CREDIT);
            Account icne = compte(c, "ICNE", AccountKind.GL, NormalBalance.DEBIT, null);
            parametresCredit.put(LoanCatalog.P_ACCRUED, creances.id().toString());
            parametresCredit.put(LoanCatalog.P_ACCRUED_INTEREST, icne.id().toString());
            parametresCredit.put(LoanCatalog.P_INTEREST_INCOME, produits.id().toString());
            parametresCredit.put(LoanCatalog.P_TAX_ACCOUNT, taxe.id().toString());
            parametresCredit.put(LoanCatalog.P_DIRECT_DEBIT, "true");
            parametresCredit.put(LoanCatalog.P_LATE_RATE, "18");
            parametresCredit.put(LoanCatalog.P_LATE_INCOME, retard.id().toString());
            return null;
        });
        teller = token(GUICHETIER, "guichetier", siege, Roles.TELLER);
        teller2 = token(UUID.randomUUID(), "guichetier.2", siege, Roles.TELLER);
        officer = token(UUID.randomUUID(), "charge.clientele", siege, Roles.CUSTOMER_OFFICER);
        manager = token(UUID.randomUUID(), "chef.agence", siege, Roles.BRANCH_MANAGER);
        manager2 = token(UUID.randomUUID(), "chef.agence.adjoint", siege, Roles.BRANCH_MANAGER);
        operator = token(UUID.randomUUID(), "exploitant", null, Roles.OPERATOR);
        creditOfficer = token(UUID.randomUUID(), "charge.credit", siege, Roles.CREDIT_OFFICER);
        creditManager = token(UUID.randomUUID(), "resp.credit", null, Roles.CREDIT_MANAGER);
        productManager = token(UUID.randomUUID(), "resp.produits", null, Roles.PRODUCT_MANAGER);
        riskOfficer = token(UUID.randomUUID(), "risques", null, Roles.RISK_OFFICER);
        accountant = token(UUID.randomUUID(), "comptable", null, Roles.ACCOUNTANT);
        accountant2 = token(UUID.randomUUID(), "chef.comptable", null, Roles.ACCOUNTANT);
    }

    @AfterAll
    void stop() throws IOException {
        if (owner != null) owner.close();
        if (postgres != null) postgres.close();
    }

    @Test
    @Order(0)
    @DisplayName("la caisse du guichetier : demandee par un chef d'agence, validee par un autre ; sans caisse, pas d'operation de guichet")
    void caisse() throws Exception {
        Map<String, Object> demande = Map.of(
            "code", "C-01", "cashAccountId", caisse.id().toString(),
            "tellerSubjectId", GUICHETIER.toString(),
            "differenceAccountId", ecartsCaisse.id().toString());
        // Un guichetier ne cree pas de caisse ; sans caisse, il ne sert pas non plus.
        assertThat(post(teller, "/tills", null, demande).status()).isEqualTo(403);
        UUID compteQuelconque = caisse.id();
        Reponse sansCaisse = post(teller, "/accounts/" + compteQuelconque + "/deposits", "dep-0",
                                  Map.of("amount", "100", "currency", "XOF"));
        assertThat(sansCaisse.status()).as(String.valueOf(sansCaisse.body())).isEqualTo(409);
        assertThat((String) sansCaisse.body().get("detail")).contains("Aucune caisse");

        Reponse soumise = post(manager, "/tills", null, demande);
        assertThat(soumise.status()).as(String.valueOf(soumise.body())).isEqualTo(202);
        assertThat(post(manager, "/pending-operations/" + attente(soumise) + "/approve", null,
                        Map.of()).status()).isEqualTo(403);
        Reponse creee = post(manager2, "/pending-operations/" + attente(soumise) + "/approve",
                             null, Map.of());
        assertThat(creee.status()).as(String.valueOf(creee.body())).isEqualTo(200);
        caisseId = UUID.fromString((String) resultat(creee.body()).get("id"));
    }

    @Test
    @Order(1)
    @DisplayName("du tiers au retrait : chaque etape passe par le jeton, la cle d'idempotence et la politique")
    void parcours_client() throws Exception {
        Reponse creation = post(officer, "/parties", null, Map.of(
            "reference", "T-API-1", "kind", "NATURAL_PERSON", "displayName", "Awa Diop",
            "countryCode", "CI",
            "identifiers", List.of(Map.of("kind", "NATIONAL_ID", "value", "CI-123456"))));
        assertThat(creation.status()).as(String.valueOf(creation.body())).isEqualTo(201);
        party = UUID.fromString((String) creation.body().get("id"));

        // La verification de la connaissance client se fait a deux : soumise par le charge de
        // clientele, approuvee par le chef d'agence — l'approbateur est le sujet de son jeton.
        Reponse kyc = post(officer, "/parties/" + party + "/kyc-verifications", null, Map.of(
            "rating", "MEDIUM", "verifiedOn", J.toString()));
        assertThat(kyc.status()).as(String.valueOf(kyc.body())).isEqualTo(202);
        assertThat(kyc.body().get("status")).isEqualTo("PENDING");
        UUID attenteKyc = UUID.fromString((String) kyc.body().get("id"));
        // Le maker ne valide pas sa propre operation : la politique le refuse.
        Reponse soiMeme = post(officer, "/pending-operations/" + attenteKyc + "/approve", null,
                               Map.of());
        assertThat(soiMeme.status()).as(String.valueOf(soiMeme.body())).isEqualTo(403);
        assertThat((String) soiMeme.body().get("detail")).contains("separation des taches");
        Reponse approbation = post(manager, "/pending-operations/" + attenteKyc + "/approve", null,
                                   Map.of());
        assertThat(approbation.status()).as(String.valueOf(approbation.body())).isEqualTo(200);
        assertThat(approbation.body().get("status")).isEqualTo("EXECUTED");
        assertThat(resultat(approbation.body()).get("kycStatus")).isEqualTo("VERIFIED");
        assertThat(get(officer, "/parties/" + party).body().get("kycStatus"))
            .isEqualTo("VERIFIED");

        Reponse ouverture = post(officer, "/accounts", null, Map.of(
            "code", "CLI-API-1", "holderPartyId", party.toString(), "productCode", "EP-API",
            "currency", "XOF"));
        assertThat(ouverture.status()).as(String.valueOf(ouverture.body())).isEqualTo(202);
        UUID attenteOuverture = UUID.fromString((String) ouverture.body().get("id"));
        // Un guichetier n'est pas habilite a ouvrir un compte : il ne l'approuve pas non plus.
        assertThat(post(teller, "/pending-operations/" + attenteOuverture + "/approve", null,
                        Map.of()).status()).isEqualTo(403);
        Reponse ouvert = post(manager, "/pending-operations/" + attenteOuverture + "/approve", null,
                              Map.of());
        assertThat(ouvert.status()).as(String.valueOf(ouvert.body())).isEqualTo(200);
        account = UUID.fromString((String) resultat(ouvert.body()).get("id"));
        // Une operation decidee ne se decide pas deux fois.
        assertThat(post(manager2, "/pending-operations/" + attenteOuverture + "/approve", null,
                        Map.of()).status()).isEqualTo(409);

        Reponse versement = post(teller, "/accounts/" + account + "/deposits", "dep-1", Map.of(
            "amount", "100000", "currency", "XOF",
            "channel", "GUICHET"));
        assertThat(versement.status()).as(String.valueOf(versement.body())).isEqualTo(201);
        assertThat(montant(versement.body(), "balanceAfter")).isEqualTo("100000");
        assertThat(versement.body().get("replayed")).isEqualTo(false);

        Reponse retrait = post(teller, "/accounts/" + account + "/withdrawals", "ret-1", Map.of(
            "amount", "20000", "currency", "XOF"));
        assertThat(retrait.status()).as(String.valueOf(retrait.body())).isEqualTo(201);
        assertThat(montant(retrait.body(), "fee")).isEqualTo("500");
        assertThat(montant(retrait.body(), "tax")).isEqualTo("90");
        assertThat(montant(retrait.body(), "balanceAfter")).isEqualTo("79410");
        assertThat(retrait.body().get("remote")).isEqualTo(false);

        // Le client a appuye deux fois : meme cle, meme resultat, rien de plus comptabilise.
        Reponse rejeu = post(teller, "/accounts/" + account + "/withdrawals", "ret-1", Map.of(
            "amount", "20000", "currency", "XOF"));
        assertThat(rejeu.status()).as(String.valueOf(rejeu.body())).isEqualTo(200);
        assertThat(rejeu.body().get("replayed")).isEqualTo(true);
        assertThat(rejeu.body().get("entryId")).isEqualTo(retrait.body().get("entryId"));

        Reponse solde = get(teller, "/accounts/" + account + "/balance");
        assertThat(solde.status()).as(String.valueOf(solde.body())).isEqualTo(200);
        assertThat(montant(solde.body(), "current")).isEqualTo("79410");
        assertThat(montant(solde.body(), "available")).isEqualTo("79410");
        assertThat(solde.body().get("asOf")).isEqualTo(J.toString());
    }

    @Test
    @Order(2)
    @DisplayName("les refus sont des reponses nommees : 401, 400, 403, 404, 409, 422")
    void refus() throws Exception {
        Reponse anonyme = get(null, "/accounts/" + account + "/balance");
        assertThat(anonyme.status()).isEqualTo(401);
        assertThat(anonyme.body().get("status")).isEqualTo(401);
        assertThat(anonyme.body().get("title")).isEqualTo("Non authentifie");

        Reponse sansCle = post(teller, "/accounts/" + account + "/withdrawals", null, Map.of(
            "amount", "1000", "currency", "XOF"));
        assertThat(sansCle.status()).as(String.valueOf(sansCle.body())).isEqualTo(400);
        assertThat(sansCle.body().get("title")).isEqualTo("Cle d'idempotence absente");

        // Un guichetier n'ouvre pas de compte : la politique le dit des la soumission.
        Reponse interdit = post(teller, "/accounts", null, Map.of(
            "code", "CLI-API-2", "holderPartyId", party.toString(), "productCode", "EP-API",
            "currency", "XOF"));
        assertThat(interdit.status()).as(String.valueOf(interdit.body())).isEqualTo(403);
        assertThat((String) interdit.body().get("detail")).contains("roles");

        // Un blocage soumis puis rejete, avec motif : rien ne bloque le compte.
        Reponse blocage = post(manager, "/accounts/" + account + "/blocks", null, Map.of(
            "kind", "DEBIT", "reason", "opposition"));
        assertThat(blocage.status()).as(String.valueOf(blocage.body())).isEqualTo(202);
        UUID attenteBlocage = UUID.fromString((String) blocage.body().get("id"));
        assertThat(post(manager2, "/pending-operations/" + attenteBlocage + "/reject", null,
                        Map.of()).status()).isEqualTo(422);          // un rejet se motive
        Reponse rejet = post(manager2, "/pending-operations/" + attenteBlocage + "/reject", null,
                             Map.of("reason", "opposition levee par le tribunal"));
        assertThat(rejet.status()).as(String.valueOf(rejet.body())).isEqualTo(200);
        assertThat(rejet.body().get("status")).isEqualTo("REJECTED");
        assertThat(get(manager, "/pending-operations/" + attenteBlocage).body().get("decisionReason"))
            .isEqualTo("opposition levee par le tribunal");
        Reponse attentes = get(manager, "/pending-operations");
        assertThat(attentes.status()).isEqualTo(200);
        assertThat((List<?>) attentes.body().get("items")).isNotNull();
        assertThat(post(teller, "/accounts/" + account + "/withdrawals", "ret-apres-rejet",
                        Map.of("amount", "1000", "currency", "XOF")).status()).isEqualTo(201);
        assertThat(post(teller, "/accounts/" + account + "/deposits", "dep-apres-rejet",
                        Map.of("amount", "1590", "currency", "XOF")).status()).isEqualTo(201);

        // Au-dela du plafond du role, meme avec la caisse et le compte de son agence.
        Reponse plafond = post(teller, "/accounts/" + account + "/withdrawals", "ret-plafond",
            Map.of("amount", "3000000", "currency", "XOF"));
        assertThat(plafond.status()).as(String.valueOf(plafond.body())).isEqualTo(403);
        assertThat((String) plafond.body().get("detail")).contains("plafond");

        Reponse insuffisant = post(teller, "/accounts/" + account + "/withdrawals", "ret-trop",
            Map.of("amount", "1000000", "currency", "XOF"));
        assertThat(insuffisant.status()).as(String.valueOf(insuffisant.body())).isEqualTo(409);

        Reponse inconnu = get(teller, "/accounts/" + UUID.randomUUID() + "/balance");
        assertThat(inconnu.status()).as(String.valueOf(inconnu.body())).isEqualTo(404);

        // L'entite est celle du jeton, et la base ne montre rien d'autre : pour un porteur d'une
        // autre entite, le compte n'existe pas — meme role, meme adresse, meme identifiant.
        String etranger = tokenOf(UUID.randomUUID(), "chef.autre.banque", AUTRE_ENTITE, null,
                                  Roles.BRANCH_MANAGER);
        Reponse ailleurs = get(etranger, "/accounts/" + account + "/balance");
        assertThat(ailleurs.status()).as(String.valueOf(ailleurs.body())).isEqualTo(404);
        Reponse ailleursAussi = post(etranger, "/accounts/" + account + "/blocks", null, Map.of(
            "kind", "DEBIT", "reason", "tentative transverse"));
        assertThat(ailleursAussi.status()).as(String.valueOf(ailleursAussi.body())).isEqualTo(404);
        assertThat(get(etranger, "/pending-operations").body().get("items"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).isEmpty();

        Reponse devise = post(teller, "/accounts/" + account + "/withdrawals", "ret-eur", Map.of(
            "amount", "10", "currency", "EUR"));
        assertThat(devise.status()).as(String.valueOf(devise.body())).isEqualTo(422);

        // Le solde n'a pas bouge.
        assertThat(montant(get(manager, "/accounts/" + account + "/balance").body(), "current"))
            .isEqualTo("79410");
    }

    @Test
    @Order(3)
    @SuppressWarnings("unchecked")
    @DisplayName("l'arrete de caisse precede l'arrete de la banque ; l'exploitant lance la journee, et la consulte")
    void arrete() throws Exception {
        // La caisse a servi et n'est pas arretee : les controles prealables refusent la journee.
        Reponse lancement = post(operator, "/eod/runs", null, Map.of("businessDate", J.toString()));
        assertThat(lancement.status()).as(String.valueOf(lancement.body())).isEqualTo(201);
        assertThat(lancement.body().get("status")).isEqualTo("FAILED");
        UUID runId = UUID.fromString((String) lancement.body().get("id"));
        Map<String, Object> controles = ((List<Map<String, Object>>) lancement.body().get("steps"))
            .get(0);
        assertThat(controles.get("name")).isEqualTo("PRE_CHECKS");
        assertThat(String.valueOf(controles.get("anomalies"))).contains("C-01");

        // Le guichetier compte sa caisse — juste ; un autre guichetier n'arrete pas la sienne.
        String livre = montant(get(manager, "/accounts/" + caisse.id() + "/balance").body(),
                               "current");
        assertThat(post(teller2, "/tills/" + caisseId + "/closure", null,
                        Map.of("counted", livre, "currency", "XOF")).status()).isEqualTo(403);
        Reponse comptage = post(teller, "/tills/" + caisseId + "/closure", null,
                                Map.of("counted", livre, "currency", "XOF"));
        assertThat(comptage.status()).as(String.valueOf(comptage.body())).isEqualTo(201);
        assertThat(montant(comptage.body(), "difference")).isEqualTo("0");
        assertThat(comptage.body().get("entryId")).isNull();
        // La journee de caisse est close : ni second arrete, ni operation.
        assertThat(post(teller, "/tills/" + caisseId + "/closure", null,
                        Map.of("counted", livre, "currency", "XOF")).status()).isEqualTo(409);
        assertThat(post(teller, "/accounts/" + account + "/deposits", "dep-clos",
                        Map.of("amount", "100", "currency", "XOF")).status()).isEqualTo(409);

        // La reprise passe les controles et arrete la journee.
        Reponse reprise = post(operator, "/eod/runs/" + runId + "/resume", null, Map.of());
        assertThat(reprise.status()).as(String.valueOf(reprise.body())).isEqualTo(200);
        assertThat(reprise.body().get("status")).isEqualTo("COMPLETED");

        Reponse lecture = get(operator, "/eod/runs/" + runId);
        assertThat(lecture.status()).as(String.valueOf(lecture.body())).isEqualTo(200);
        assertThat(lecture.body().get("businessDate")).isEqualTo(J.toString());

        // Le guichetier ne lance pas l'arrete.
        assertThat(post(teller, "/eod/runs", null, Map.of("businessDate", J.plusDays(1).toString()))
                       .status()).isEqualTo(403);
        // La journee a bascule : le disponible se lit au lendemain.
        assertThat(get(teller, "/accounts/" + account + "/balance").body().get("asOf"))
            .isEqualTo(J.plusDays(1).toString());
    }

    @Test
    @Order(4)
    @DisplayName("le credit : produit active a deux, contrat, deblocage a deux, reglement, remboursement anticipe a deux, lecture")
    void credit() throws Exception {
        // Le produit de credit : redige par le responsable produits, active par le risque.
        Reponse brouillon = post(productManager, "/products", null, Map.of(
            "code", "CRED-API", "productType", "TERM_LOAN", "label", "Credit amortissable",
            "currency", "XOF", "validFrom", J.minusMonths(1).toString(),
            "parameters", parametresCredit));
        assertThat(brouillon.status()).as(String.valueOf(brouillon.body())).isEqualTo(201);
        UUID version = UUID.fromString((String) brouillon.body().get("id"));
        Reponse activation = post(productManager, "/products/" + version + "/activation", null,
                                  Map.of());
        assertThat(activation.status()).as(String.valueOf(activation.body())).isEqualTo(202);
        // Le redacteur ne valide pas sa propre version.
        assertThat(post(productManager, "/pending-operations/" + attente(activation) + "/approve",
                        null, Map.of()).status()).isEqualTo(403);
        Reponse active = post(riskOfficer, "/pending-operations/" + attente(activation) + "/approve",
                              null, Map.of());
        assertThat(active.status()).as(String.valueOf(active.body())).isEqualTo(200);
        assertThat(resultat(active.body()).get("status")).isEqualTo("ACTIVE");

        // Le contrat, par le charge de credit, rattache au client ; jamais par un guichetier.
        Map<String, Object> contrat = new LinkedHashMap<>();
        contrat.put("reference", "CRED-API-1");
        contrat.put("productCode", "CRED-API");
        contrat.put("currency", "XOF");
        contrat.put("loanAccountId", pret.id().toString());
        contrat.put("settlementAccountId", courant.id().toString());
        contrat.put("principal", "1000000");
        contrat.put("disbursedOn", J.plusDays(1).toString());
        contrat.put("customerPartyId", party.toString());
        assertThat(post(teller, "/loans", null, contrat).status()).isEqualTo(403);
        Reponse cree = post(creditOfficer, "/loans", null, contrat);
        assertThat(cree.status()).as(String.valueOf(cree.body())).isEqualTo(201);
        pretId = UUID.fromString((String) cree.body().get("id"));

        // Le deblocage : conditions proposees par le chef d'agence, approuvees par le
        // responsable credit — l'argent sort, le plafond porte sur le capital.
        Reponse deblocage = post(manager, "/loans/" + pretId + "/disbursement", null, Map.of(
            "annualRatePercent", "12", "instalments", 12,
            "firstDueDate", J.plusDays(1).plusMonths(1).toString()));
        assertThat(deblocage.status()).as(String.valueOf(deblocage.body())).isEqualTo(202);
        Reponse debloque = post(creditManager, "/pending-operations/" + attente(deblocage)
                                + "/approve", null, Map.of());
        assertThat(debloque.status()).as(String.valueOf(debloque.body())).isEqualTo(200);
        assertThat((List<?>) resultat(debloque.body()).get("schedule")).hasSize(12);
        assertThat(montant(get(manager, "/accounts/" + courant.id() + "/balance").body(),
                           "current")).isEqualTo("1000000");

        // Le dossier tel que l'agent le lit.
        Reponse dossier = get(creditOfficer, "/loans/" + pretId);
        assertThat(dossier.status()).as(String.valueOf(dossier.body())).isEqualTo(200);
        assertThat(dossier.body().get("status")).isEqualTo("ACTIVE");
        assertThat(montant(dossier.body(), "principal")).isEqualTo("1000000");
        assertThat((List<?>) dossier.body().get("schedule")).hasSize(12);
        assertThat((List<?>) dossier.body().get("receivables")).isEmpty();
        assertThat(dossier.body().get("daysPastDue")).isEqualTo(0);

        // Un reglement au guichet sans echeance echue n'affecte rien : l'excedent est rendu.
        Reponse reglement = post(teller, "/loans/" + pretId + "/repayments", "rmb-1", Map.of(
            "amount", "50000", "currency", "XOF"));
        assertThat(reglement.status()).as(String.valueOf(reglement.body())).isEqualTo(201);
        assertThat(montant(reglement.body(), "allocated")).isEqualTo("0");
        assertThat(montant(reglement.body(), "unallocated")).isEqualTo("50000");

        // Le remboursement anticipe : enregistre par le charge de credit, approuve par le chef
        // d'agence ; l'echeancier est refait, en duree.
        Reponse anticipe = post(creditOfficer, "/loans/" + pretId + "/prepayments", "rap-1",
                                Map.of("amount", "200000", "currency", "XOF",
                                       "mode", "SHORTEN_TERM"));
        assertThat(anticipe.status()).as(String.valueOf(anticipe.body())).isEqualTo(202);
        Reponse rembourse = post(manager, "/pending-operations/" + attente(anticipe) + "/approve",
                                 null, Map.of());
        assertThat(rembourse.status()).as(String.valueOf(rembourse.body())).isEqualTo(200);
        assertThat(montant(resultat(rembourse.body()), "principalRepaid")).isEqualTo("200000");
        assertThat((List<?>) get(creditOfficer, "/loans/" + pretId).body().get("schedule"))
            .hasSizeLessThan(12);

        // Un contrat inconnu — ou d'une autre entite — n'existe pas.
        assertThat(get(creditOfficer, "/loans/" + UUID.randomUUID()).status()).isEqualTo(404);
    }

    @Test
    @Order(5)
    @DisplayName("conditions de banque et reseau : regle de date de valeur, ferie, agence — chacun a deux")
    void parametrage() throws Exception {
        Reponse regle = post(operator, "/calendar/value-date-rules", null, Map.of(
            "operationType", "CHEQUE_DEPOSIT", "direction", "CREDIT", "offset", 2,
            "unit", "BUSINESS_DAYS", "convention", "FOLLOWING", "validFrom", J.toString()));
        assertThat(regle.status()).as(String.valueOf(regle.body())).isEqualTo(202);
        Reponse regleActive = post(productManager, "/pending-operations/" + attente(regle)
                                   + "/approve", null, Map.of());
        assertThat(regleActive.status()).as(String.valueOf(regleActive.body())).isEqualTo(200);
        assertThat(resultat(regleActive.body()).get("id")).isNotNull();

        Reponse ferie = post(operator, "/calendar/holidays", null, Map.of(
            "date", J.plusMonths(2).toString(), "label", "Fete nationale"));
        assertThat(ferie.status()).as(String.valueOf(ferie.body())).isEqualTo(202);
        Reponse ferieActif = post(productManager, "/pending-operations/" + attente(ferie)
                                  + "/approve", null, Map.of());
        assertThat(ferieActif.status()).as(String.valueOf(ferieActif.body())).isEqualTo(200);
        assertThat(resultat(ferieActif.body()).get("date")).isEqualTo(J.plusMonths(2).toString());

        // Une agence : demandee par l'exploitant, validee par la comptabilite — jamais par un
        // guichetier, ni par l'exploitant lui-meme.
        Map<String, Object> agence = Map.of(
            "code", "AG-002", "name", "Agence Plateau", "kind", "BRANCH",
            "openedOn", J.toString(), "liaisonAccounts", Map.of("XOF", liaison.id().toString()));
        assertThat(post(teller, "/branches", null, agence).status()).isEqualTo(403);
        Reponse demande = post(operator, "/branches", null, agence);
        assertThat(demande.status()).as(String.valueOf(demande.body())).isEqualTo(202);
        assertThat(post(operator, "/pending-operations/" + attente(demande) + "/approve", null,
                        Map.of()).status()).isEqualTo(403);
        Reponse creee = post(accountant, "/pending-operations/" + attente(demande) + "/approve",
                             null, Map.of());
        assertThat(creee.status()).as(String.valueOf(creee.body())).isEqualTo(200);
        UUID agenceId = UUID.fromString((String) resultat(creee.body()).get("id"));
        List<Branches.Branch> reseau = owner.inTransaction(c -> Branches.ofEntity(c, ENTITY));
        assertThat(reseau).extracting(Branches.Branch::code).contains("SIEGE", "AG-002");
        assertThat(reseau).extracting(Branches.Branch::id).contains(agenceId);
    }

    @Test
    @Order(6)
    @DisplayName("l'enveloppe et la pagination : une seule forme, des pages bornees a ordre total, le curseur pour le grand livre et le journal, la balance a six colonnes, l'identifiant de requete repris")
    void enveloppe_et_pagination() throws Exception {
        // L'identifiant de requete fourni est repris dans l'enveloppe et dans l'en-tete.
        Reponse identifiee = get(manager, "/accounts/" + account + "/balance",
                                 Map.of("X-Request-Id", "support-123"));
        assertThat(identifiee.status()).isEqualTo(200);
        assertThat(identifiee.meta().get("requestId")).isEqualTo("support-123");
        assertThat(identifiee.envelope().get("page")).isNull();
        assertThat(identifiee.envelope().get("error")).isNull();

        // Le releve du compte : les mouvements par pages, dans l'ordre du journal.
        Reponse releve = get(manager, "/accounts/" + account + "/journal?from=" + J + "&to="
                             + J.plusDays(1) + "&page=0&size=2");
        assertThat(releve.status()).as(String.valueOf(releve.envelope())).isEqualTo(200);
        assertThat(releve.items()).hasSize(2);
        assertThat(releve.page().get("size")).isEqualTo(2);
        assertThat(((Number) releve.page().get("totalElements")).longValue()).isGreaterThan(2);
        assertThat(releve.page().get("hasNext")).isEqualTo(true);
        assertThat(releve.items().get(0).get("bookingDate")).isEqualTo(J.toString());
        assertThat(releve.items().get(0).get("direction")).isEqualTo("CREDIT");
        Reponse suite = get(manager, "/accounts/" + account + "/journal?from=" + J + "&to="
                            + J.plusDays(1) + "&page=1&size=2");
        assertThat(suite.page().get("hasPrevious")).isEqualTo(true);
        assertThat(suite.items().get(0).get("entryId"))
            .isNotEqualTo(releve.items().get(0).get("entryId"));
        // Une page au-dela du plafond est refusee, pas ramenee au plafond en silence.
        Reponse trop = get(manager, "/accounts/" + account + "/journal?size=500");
        assertThat(trop.status()).isEqualTo(400);
        assertThat((String) trop.body().get("detail")).contains("200");
        // Un guichetier ne lit pas le journal d'un compte.
        assertThat(get(teller, "/accounts/" + account + "/journal").status()).isEqualTo(403);

        // Le grand livre du compte, par curseur : les memes lignes, la page suivante reprend
        // apres la precedente, et un curseur qui ne se relit pas est une requete invalide.
        Reponse livre = get(manager, "/accounts/" + account + "/ledger?from=" + J + "&to="
                            + J.plusDays(1) + "&size=2");
        assertThat(livre.status()).as(String.valueOf(livre.envelope())).isEqualTo(200);
        assertThat(livre.items()).hasSize(2);
        assertThat(livre.items().get(0).get("entryId"))
            .isEqualTo(releve.items().get(0).get("entryId"));
        assertThat(livre.items().get(0)).containsKeys("accountCode", "lineNumber", "knowledgeTime");
        assertThat(livre.page().get("hasNext")).isEqualTo(true);
        assertThat(livre.page().get("hasPrevious")).isEqualTo(false);
        assertThat(livre.page().get("totalElements")).isNull();
        String curseur = (String) livre.page().get("nextCursor");
        assertThat(curseur).isNotBlank();
        Reponse livreSuite = get(manager, "/accounts/" + account + "/ledger?from=" + J + "&to="
                                 + J.plusDays(1) + "&size=2&after=" + curseur);
        assertThat(livreSuite.status()).as(String.valueOf(livreSuite.envelope())).isEqualTo(200);
        assertThat(livreSuite.items().get(0).get("entryId"))
            .isEqualTo(suite.items().get(0).get("entryId"));
        assertThat(livreSuite.page().get("hasPrevious")).isEqualTo(true);
        Reponse curseurFaux = get(manager, "/accounts/" + account + "/ledger?after=n-importe-quoi");
        assertThat(curseurFaux.status()).isEqualTo(400);
        assertThat((String) curseurFaux.body().get("detail")).contains("Curseur");

        // Le journal de l'entite et la balance : la comptabilite et l'audit, pas l'agence.
        assertThat(get(manager, "/ledger/journal").status()).isEqualTo(403);
        assertThat(get(manager, "/ledger/trial-balance").status()).isEqualTo(403);
        Reponse journal = get(accountant, "/ledger/journal?from=" + J + "&to=" + J + "&size=3");
        assertThat(journal.status()).as(String.valueOf(journal.envelope())).isEqualTo(200);
        assertThat(journal.items()).hasSize(3);
        assertThat(journal.page().get("nextCursor")).isNotNull();
        assertThat(journal.items()).extracting(l -> l.get("bookingDate")).containsOnly(J.toString());
        Reponse balance = get(accountant, "/ledger/trial-balance?from=" + J.minusMonths(1) + "&to="
                              + J + "&size=100");
        assertThat(balance.status()).as(String.valueOf(balance.envelope())).isEqualTo(200);
        assertThat(balance.items()).extracting(l -> l.get("code")).contains("CAISSE", "FRAIS");
        Map<String, Object> ligneCaisse = balance.items().stream()
            .filter(l -> "CAISSE".equals(l.get("code"))).findFirst().orElseThrow();
        assertThat(ligneCaisse.get("kind")).isEqualTo("INTERNAL");
        assertThat(new java.math.BigDecimal(montant(ligneCaisse, "closingDebit"))).isPositive();
        assertThat(montant(ligneCaisse, "closingCredit")).isEqualTo("0");
        Reponse totaux = get(accountant, "/ledger/trial-balance/totals?from=" + J.minusMonths(1)
                             + "&to=" + J);
        assertThat(totaux.status()).as(String.valueOf(totaux.envelope())).isEqualTo(200);
        assertThat(totaux.items()).hasSize(1);
        assertThat(totaux.items().get(0).get("currency")).isEqualTo("XOF");
        assertThat(totaux.items().get(0).get("balanced")).isEqualTo(true);
        assertThat(montant(totaux.items().get(0), "closingDebit"))
            .isEqualTo(montant(totaux.items().get(0), "closingCredit"));
        Reponse clients = get(accountant, "/ledger/trial-balance?kind=CUSTOMER&to=" + J);
        assertThat(clients.status()).isEqualTo(200);
        assertThat(clients.items()).isNotEmpty();
        assertThat(clients.items()).extracting(l -> l.get("kind")).containsOnly("CUSTOMER");
        assertThat(get(accountant, "/ledger/trial-balance?kind=NIMPORTE").status()).isEqualTo(400);

        // Les operations en attente : une page, apres le filtre d'habilitation.
        Reponse blocage = post(manager, "/accounts/" + account + "/blocks", null,
                               Map.of("kind", "DEBIT", "reason", "verification"));
        assertThat(blocage.status()).isEqualTo(202);
        Reponse attentes = get(manager, "/pending-operations?page=0&size=1");
        assertThat(attentes.status()).isEqualTo(200);
        assertThat(attentes.items()).hasSize(1);
        assertThat(((Number) attentes.page().get("totalElements")).longValue())
            .isGreaterThanOrEqualTo(1);
        assertThat(post(manager2, "/pending-operations/" + attente(blocage) + "/reject", null,
                        Map.of("reason", "sans objet")).status()).isEqualTo(200);

        // Les contrats et les tiers, par pages.
        Reponse credits = get(creditOfficer, "/loans?status=ACTIVE&page=0&size=10");
        assertThat(credits.status()).as(String.valueOf(credits.envelope())).isEqualTo(200);
        assertThat(credits.items()).extracting(c -> c.get("reference")).contains("CRED-API-1");
        Reponse tiers = get(officer, "/parties?q=awa");
        assertThat(tiers.status()).as(String.valueOf(tiers.envelope())).isEqualTo(200);
        assertThat(tiers.items()).extracting(t -> t.get("displayName")).containsExactly("Awa Diop");
        assertThat(get(officer, "/parties?q=personne").items()).isEmpty();

        // Les refus de la chaine et du routage portent la meme enveloppe.
        Reponse nullePart = get(manager, "/nulle-part");
        assertThat(nullePart.status()).isEqualTo(404);
        assertThat(nullePart.body().get("status")).isEqualTo(404);
        assertThat(nullePart.meta().get("requestId")).isNotNull();
        Reponse malForme = get(manager, "/accounts/pas-un-uuid/balance");
        assertThat(malForme.status()).isEqualTo(400);
        assertThat((String) malForme.body().get("detail")).contains("accountId");
    }

    @Test
    @Order(7)
    @DisplayName("le rechelonnement : propose par le chef d'agence, approuve par le responsable credit, sur le capital non echu")
    void reechelonnement() throws Exception {
        // Sans motif, rien n'est soumis.
        assertThat(post(manager, "/loans/" + pretId + "/rescheduling", null, Map.of(
            "instalments", 6, "firstDueDate", J.plusDays(3).plusMonths(1).toString(),
            "effectiveFrom", J.plusDays(3).toString())).status()).isEqualTo(422);

        Reponse demande = post(manager, "/loans/" + pretId + "/rescheduling", null, Map.of(
            "instalments", 6, "firstDueDate", J.plusDays(3).plusMonths(1).toString(),
            "effectiveFrom", J.plusDays(3).toString(), "reason", "difficultes passageres"));
        assertThat(demande.status()).as(String.valueOf(demande.envelope())).isEqualTo(202);
        Reponse replanifie = post(creditManager, "/pending-operations/" + attente(demande)
                                  + "/approve", null, Map.of());
        assertThat(replanifie.status()).as(String.valueOf(replanifie.envelope())).isEqualTo(200);
        assertThat(replanifie.body().get("status")).isEqualTo("EXECUTED");
        assertThat(montant(resultat(replanifie.body()), "remaining")).isEqualTo("800000");
        assertThat((List<?>) resultat(replanifie.body()).get("schedule")).hasSize(6);

        Reponse dossier = get(creditOfficer, "/loans/" + pretId);
        assertThat((List<?>) dossier.body().get("schedule")).hasSize(6);
        assertThat(((Map<?, ?>) ((List<?>) dossier.body().get("schedule")).get(0)).get("dueDate"))
            .isEqualTo(J.plusDays(3).plusMonths(1).toString());
    }

    @Test
    @Order(8)
    @DisplayName("la cloture annuelle par l'API : exercice ouvert a deux, arrete mensuel refuse sur le dernier mois, cloture a deux, annulation a deux, resultat affecte a deux et cloture alors retenue")
    void cloture() throws Exception {
        // L'exercice : ouvert par la comptabilite, valide par une seconde.
        Reponse exercice = post(accountant, "/fiscal-years", null, Map.of(
            "start", J.minusYears(1).plusDays(1).toString(), "end", J.toString(),
            "resultAccountId", resultat.id().toString()));
        assertThat(exercice.status()).as(String.valueOf(exercice.envelope())).isEqualTo(202);
        assertThat(post(accountant, "/pending-operations/" + attente(exercice) + "/approve", null,
                        Map.of()).status()).isEqualTo(403);
        Reponse ouvert = post(accountant2, "/pending-operations/" + attente(exercice) + "/approve",
                              null, Map.of());
        assertThat(ouvert.status()).as(String.valueOf(ouvert.envelope())).isEqualTo(200);
        Reponse exercices = get(accountant, "/fiscal-years");
        assertThat(exercices.status()).isEqualTo(200);
        assertThat(exercices.items()).hasSize(1);
        assertThat(exercices.items().get(0).get("status")).isEqualTo("OPEN");

        // Le dernier mois d'un exercice ne se clot pas par un arrete mensuel : l'approbation
        // execute, le moteur refuse, et le refus est la reponse.
        Reponse mois = post(accountant, "/eom/runs", null, Map.of("businessDate", J.toString()));
        assertThat(mois.status()).as(String.valueOf(mois.envelope())).isEqualTo(202);
        Reponse refus = post(accountant2, "/pending-operations/" + attente(mois) + "/approve", null,
                             Map.of());
        assertThat(refus.status()).as(String.valueOf(refus.envelope())).isEqualTo(409);
        assertThat((String) refus.body().get("detail")).contains("cloture annuelle");

        // La cloture annuelle : le resultat determine, le mois et l'exercice clos.
        String fraisAvant = montant(get(manager, "/accounts/" + frais.id() + "/balance").body(),
                                    "current");
        assertThat(new java.math.BigDecimal(fraisAvant)).isPositive();
        Reponse annee = post(accountant, "/eoy/runs", null, Map.of("businessDate", J.toString()));
        assertThat(annee.status()).as(String.valueOf(annee.envelope())).isEqualTo(202);
        Reponse clos = post(accountant2, "/pending-operations/" + attente(annee) + "/approve", null,
                            Map.of());
        assertThat(clos.status()).as(String.valueOf(clos.envelope())).isEqualTo(200);
        assertThat(resultat(clos.body()).get("status")).isEqualTo("COMPLETED");
        UUID runId = UUID.fromString((String) resultat(clos.body()).get("id"));
        Reponse lecture = get(accountant, "/eoy/runs/" + runId);
        assertThat(lecture.status()).as(String.valueOf(lecture.envelope())).isEqualTo(200);
        assertThat(String.valueOf(lecture.body().get("steps"))).contains("RESULT_DETERMINATION");
        assertThat(montant(get(manager, "/accounts/" + frais.id() + "/balance").body(), "current"))
            .isEqualTo("0");
        assertThat(get(accountant, "/fiscal-years").items().get(0).get("status"))
            .isEqualTo("CLOSED");

        // L'annulation, a deux, datee de la fin d'exercice : tout est defait.
        Reponse annulation = post(accountant, "/eoy/runs/" + runId + "/cancel", null, Map.of(
            "reversalBookingDate", J.toString(), "reason", "produit oublie"));
        assertThat(annulation.status()).as(String.valueOf(annulation.envelope())).isEqualTo(202);
        Reponse annule = post(accountant2, "/pending-operations/" + attente(annulation)
                              + "/approve", null, Map.of());
        assertThat(annule.status()).as(String.valueOf(annule.envelope())).isEqualTo(200);
        assertThat(resultat(annule.body()).get("status")).isEqualTo("CANCELLED");
        assertThat(montant(get(manager, "/accounts/" + frais.id() + "/balance").body(), "current"))
            .isEqualTo(fraisAvant);
        assertThat(get(accountant, "/fiscal-years").items().get(0).get("status"))
            .isEqualTo("REOPENED");

        // La cloture rejouee, puis le resultat affecte a deux — en totalite, au report a
        // nouveau — ; affecte, il retient la cloture : l'annulation est refusee.
        Reponse rejouee = post(accountant, "/eoy/runs", null, Map.of("businessDate", J.toString()));
        Reponse reclos = post(accountant2, "/pending-operations/" + attente(rejouee) + "/approve",
                              null, Map.of());
        assertThat(reclos.status()).as(String.valueOf(reclos.envelope())).isEqualTo(200);
        assertThat(resultat(reclos.body()).get("status")).isEqualTo("COMPLETED");
        UUID rejoueeId = UUID.fromString((String) resultat(reclos.body()).get("id"));
        UUID exerciceId = UUID.fromString(
            (String) get(accountant, "/fiscal-years").items().get(0).get("id"));
        Reponse fiche = get(accountant, "/fiscal-years/" + exerciceId);
        assertThat(fiche.status()).as(String.valueOf(fiche.envelope())).isEqualTo(200);
        assertThat(fiche.body().get("status")).isEqualTo("CLOSED");
        assertThat(fiche.body().get("appropriation")).isNull();
        java.math.BigDecimal net = new java.math.BigDecimal(montant(fiche.body(), "netResult"));
        assertThat(net).isNotZero();
        resultatNet = net;
        assertThat(montant(get(manager, "/accounts/" + resultat.id() + "/balance").body(),
                           "current")).isEqualTo(net.toPlainString());
        assertThat(get(accountant, "/fiscal-years/" + UUID.randomUUID()).status()).isEqualTo(404);

        // Une demande sans piece ni destination n'est pas soumise.
        assertThat(post(accountant, "/fiscal-years/" + exerciceId + "/appropriation", null,
            Map.of("bookingDate", J.plusDays(1).toString(), "decidedOn", J.plusDays(1).toString(),
                   "allocations", List.of(Map.of("accountId", report.id().toString(),
                                                  "amount", "1", "currency", "XOF"))))
            .status()).isEqualTo(422);
        assertThat(post(accountant, "/fiscal-years/" + exerciceId + "/appropriation", null,
            Map.of("bookingDate", J.plusDays(1).toString(), "decidedOn", J.plusDays(1).toString(),
                   "reference", "AGO", "allocations", List.of())).status()).isEqualTo(422);

        // Ni plus ni moins que le resultat : l'approbation execute, et le refus est la reponse.
        Reponse partielle = post(accountant, "/fiscal-years/" + exerciceId + "/appropriation", null,
            Map.of("bookingDate", J.plusDays(1).toString(), "decidedOn", J.plusDays(1).toString(),
                   "reference", "AGO", "allocations", List.of(Map.of(
                       "accountId", report.id().toString(), "amount", "1", "currency", "XOF"))));
        assertThat(partielle.status()).as(String.valueOf(partielle.envelope())).isEqualTo(202);
        Reponse refusPartiel = post(accountant2, "/pending-operations/" + attente(partielle)
                                    + "/approve", null, Map.of());
        assertThat(refusPartiel.status()).as(String.valueOf(refusPartiel.envelope())).isEqualTo(422);
        assertThat((String) refusPartiel.body().get("detail")).contains("ni plus ni moins");

        Reponse decision = post(accountant, "/fiscal-years/" + exerciceId + "/appropriation", null,
            Map.of("bookingDate", J.plusDays(1).toString(), "decidedOn", J.plusDays(1).toString(),
                   "reference", "AGO du " + J.plusDays(1), "allocations", List.of(Map.of(
                       "accountId", report.id().toString(), "amount", net.abs().toPlainString(),
                       "currency", "XOF"))));
        assertThat(decision.status()).as(String.valueOf(decision.envelope())).isEqualTo(202);
        assertThat(post(accountant, "/pending-operations/" + attente(decision) + "/approve", null,
                        Map.of()).status()).isEqualTo(403);
        Reponse affecte = post(accountant2, "/pending-operations/" + attente(decision) + "/approve",
                               null, Map.of());
        assertThat(affecte.status()).as(String.valueOf(affecte.envelope())).isEqualTo(200);
        assertThat(affecte.body().get("status")).isEqualTo("EXECUTED");
        assertThat(montant(resultat(affecte.body()), "netResult")).isEqualTo(net.toPlainString());
        assertThat(montant(get(manager, "/accounts/" + resultat.id() + "/balance").body(),
                           "current")).isEqualTo("0");
        assertThat(montant(get(manager, "/accounts/" + report.id() + "/balance").body(),
                           "current")).isEqualTo(net.toPlainString());
        fiche = get(accountant, "/fiscal-years/" + exerciceId);
        assertThat(((Map<?, ?>) fiche.body().get("appropriation")).get("reference"))
            .isEqualTo("AGO du " + J.plusDays(1));
        assertThat((List<?>) fiche.body().get("appropriations")).hasSize(1);

        Reponse reannulation = post(accountant, "/eoy/runs/" + rejoueeId + "/cancel", null, Map.of(
            "reversalBookingDate", J.toString(), "reason", "essai"));
        assertThat(reannulation.status()).as(String.valueOf(reannulation.envelope())).isEqualTo(202);
        Reponse retenue = post(accountant2, "/pending-operations/" + attente(reannulation)
                               + "/approve", null, Map.of());
        assertThat(retenue.status()).as(String.valueOf(retenue.envelope())).isEqualTo(409);
        assertThat((String) retenue.body().get("detail")).contains("affecte");
        assertThat(get(accountant, "/fiscal-years/" + exerciceId).body().get("status"))
            .isEqualTo("CLOSED");
    }

    @Test
    @Order(9)
    @DisplayName("regime de surete, grille de risque et schema comptable rediges puis actives a deux ; une surete prise, affectee, levee")
    void parametrage_du_risque_et_suretes() throws Exception {
        Reponse regime = post(riskOfficer, "/collateral-policies", null, Map.of(
            "kind", "HYPOTHEQUE", "label", "Hypotheque de premier rang",
            "eligibleRatePercent", "50", "maxValuationAgeMonths", 24,
            "validFrom", J.minusMonths(1).toString()));
        assertThat(regime.status()).as(String.valueOf(regime.envelope())).isEqualTo(201);
        Reponse activation = post(riskOfficer, "/collateral-policies/"
                                  + regime.body().get("id") + "/activation", null, Map.of());
        assertThat(activation.status()).isEqualTo(202);
        Reponse actif = post(accountant, "/pending-operations/" + attente(activation) + "/approve",
                             null, Map.of());
        assertThat(actif.status()).as(String.valueOf(actif.envelope())).isEqualTo(200);
        assertThat(resultat(actif.body()).get("status")).isEqualTo("ACTIVE");

        // Une grille lacunaire est refusee des la redaction ; une grille contigue est acceptee.
        Map<String, Object> sain = Map.of("ordinal", 0, "code", "SAIN", "label", "Sain",
            "fromDays", 0, "toDays", 89, "provisionRatePercent", "0", "performing", true);
        Map<String, Object> douteux = Map.of("ordinal", 1, "code", "DOUTEUX", "label", "Douteux",
            "fromDays", 90, "provisionRatePercent", "20", "performing", false);
        Map<String, Object> lacunaire = Map.of("ordinal", 1, "code", "DOUTEUX", "label", "Douteux",
            "fromDays", 120, "provisionRatePercent", "20", "performing", false);
        assertThat(post(riskOfficer, "/risk-profiles", null, Map.of(
            "label", "Grille lacunaire", "validFrom", J.minusMonths(1).toString(),
            "grid", Map.of("code", "GRILLE-LACUNE", "contagion", "NONE",
                           "buckets", List.of(sain, lacunaire)))).status()).isEqualTo(422);
        Reponse grille = post(riskOfficer, "/risk-profiles", null, Map.of(
            "label", "Grille BCEAO", "validFrom", J.minusMonths(1).toString(),
            "grid", Map.of("code", "GRILLE-API", "contagion", "NONE",
                           "buckets", List.of(sain, douteux))));
        assertThat(grille.status()).as(String.valueOf(grille.envelope())).isEqualTo(201);
        Reponse grilleActivation = post(riskOfficer, "/risk-profiles/" + grille.body().get("id")
                                        + "/activation", null, Map.of());
        assertThat(post(accountant, "/pending-operations/" + attente(grilleActivation)
                        + "/approve", null, Map.of()).status()).isEqualTo(200);

        // Le schema comptable : valide avant d'entrer en base, active par une seconde main.
        Map<String, Object> evenement = Map.of(
            "eventType", "MAINTENANCE_FEE", "derivations", Map.of("total", "round(base, 0)"),
            "lines", List.of(
                Map.of("account", "CONTRACT", "direction", "DEBIT", "amount", "total",
                       "label", "Frais de tenue de compte"),
                Map.of("account", "GL:" + frais.code(), "direction", "CREDIT", "amount", "total",
                       "label", "Commissions percues")));
        Reponse schema = post(accountant, "/accounting-schemas", null, Map.of(
            "code", "FRAIS-API", "label", "Frais de tenue", "currency", "XOF",
            "validFrom", J.minusMonths(1).toString(), "version", 1,
            "events", List.of(evenement)));
        assertThat(schema.status()).as(String.valueOf(schema.envelope())).isEqualTo(201);
        Reponse schemaActivation = post(accountant, "/accounting-schemas/"
                                        + schema.body().get("id") + "/activation", null, Map.of());
        assertThat(schemaActivation.status()).isEqualTo(202);
        // Le redacteur ne l'active pas, meme par une seconde requete.
        assertThat(post(accountant, "/pending-operations/" + attente(schemaActivation)
                        + "/approve", null, Map.of()).status()).isEqualTo(403);
        assertThat(post(accountant2, "/pending-operations/" + attente(schemaActivation)
                        + "/approve", null, Map.of()).status()).isEqualTo(200);

        // La surete : prise par le charge de credit, validee par le responsable credit,
        // affectee au contrat, puis levee — a deux a chaque fois.
        Reponse surete = post(creditOfficer, "/collaterals", null, Map.of(
            "customerPartyId", party.toString(), "assetReference", "TF-1234",
            "kind", "HYPOTHEQUE", "label", "Villa Cocody", "assetValue", "50000000",
            "securedAmount", "20000000", "currency", "XOF", "rank", 1,
            "valuedOn", J.toString()));
        assertThat(surete.status()).as(String.valueOf(surete.envelope())).isEqualTo(202);
        Reponse prise = post(creditManager, "/pending-operations/" + attente(surete) + "/approve",
                             null, Map.of());
        assertThat(prise.status()).as(String.valueOf(prise.envelope())).isEqualTo(200);
        UUID sureteId = UUID.fromString((String) resultat(prise.body()).get("id"));
        Reponse affectation = post(creditOfficer, "/collaterals/" + sureteId + "/allocations", null,
                                   Map.of("contractId", pretId.toString(), "sharePercent", "100"));
        assertThat(affectation.status()).as(String.valueOf(affectation.envelope())).isEqualTo(202);
        assertThat(post(creditManager, "/pending-operations/" + attente(affectation) + "/approve",
                        null, Map.of()).status()).isEqualTo(200);
        Reponse mainlevee = post(creditOfficer, "/collaterals/" + sureteId + "/release", null,
                                 Map.of("on", J.plusDays(1).toString()));
        assertThat(mainlevee.status()).isEqualTo(202);
        Reponse levee = post(creditManager, "/pending-operations/" + attente(mainlevee) + "/approve",
                             null, Map.of());
        assertThat(levee.status()).as(String.valueOf(levee.envelope())).isEqualTo(200);
        assertThat(resultat(levee.body()).get("status")).isEqualTo("RELEASED");
        // Une surete inconnue n'existe pas.
        assertThat(post(creditOfficer, "/collaterals/" + UUID.randomUUID() + "/release", null,
                        Map.of("on", J.toString())).status()).isEqualTo(404);
    }

    @Test
    @Order(10)
    @DisplayName("les etats financiers : maquettes redigees et activees a deux, bilan equilibre, compte de resultat egal au resultat affecte")
    void etats_financiers() throws Exception {
        List<Map<String, Object>> rubriques = List.of(
            Map.of("ordinal", 1, "code", "A1", "label", "Caisse", "level", 1, "kind", "DETAIL",
                   "side", "DEBIT"),
            Map.of("ordinal", 2, "code", "A2", "label", "Credits et comptes debiteurs",
                   "level", 1, "kind", "DETAIL", "side", "DEBIT"),
            Map.of("ordinal", 3, "code", "A3", "label", "Autres actifs", "level", 1,
                   "kind", "DETAIL", "side", "DEBIT"),
            Map.of("ordinal", 4, "code", "TA", "label", "Total actif", "level", 0,
                   "kind", "TOTAL", "side", "DEBIT", "plus", List.of("A1", "A2", "A3")),
            Map.of("ordinal", 5, "code", "P1", "label", "Depots de la clientele", "level", 1,
                   "kind", "DETAIL", "side", "CREDIT"),
            Map.of("ordinal", 6, "code", "P2", "label", "Autres passifs et fonds propres",
                   "level", 1, "kind", "DETAIL", "side", "CREDIT"),
            Map.of("ordinal", 7, "code", "PR", "label", "Resultat de l'exercice", "level", 1,
                   "kind", "PROFIT_OR_LOSS", "side", "CREDIT"),
            Map.of("ordinal", 8, "code", "TP", "label", "Total passif", "level", 0,
                   "kind", "TOTAL", "side", "CREDIT", "plus", List.of("P1", "P2", "PR")));
        List<Map<String, Object>> regles = List.of(
            Map.of("ordinal", 1, "lineCode", "A1", "accountKind", "INTERNAL"),
            Map.of("ordinal", 2, "lineCode", "A2", "accountKind", "CUSTOMER", "balanceSide", "DEBIT"),
            Map.of("ordinal", 3, "lineCode", "P1", "accountKind", "CUSTOMER", "balanceSide", "CREDIT"),
            Map.of("ordinal", 4, "lineCode", "A3", "balanceSide", "DEBIT"),
            Map.of("ordinal", 5, "lineCode", "P2", "balanceSide", "CREDIT"));

        // Une maquette fausse n'est pas enregistree ; un guichetier n'en redige pas.
        Reponse fausse = post(accountant, "/statement-layouts", null, Map.of(
            "kind", "BALANCE_SHEET", "code", "BILAN-FAUX", "label", "Bilan",
            "validFrom", J.minusYears(1).toString(),
            "lines", List.of(Map.of("ordinal", 1, "code", "T", "label", "Total", "kind", "TOTAL",
                                    "side", "DEBIT", "plus", List.of("ZZ"))),
            "rules", regles));
        assertThat(fausse.status()).as(String.valueOf(fausse.envelope())).isEqualTo(422);
        assertThat((String) fausse.body().get("detail")).contains("Maquette invalide");
        assertThat(post(teller, "/statement-layouts", null, Map.of("kind", "BALANCE_SHEET"))
            .status()).isEqualTo(403);

        // Le bilan et le compte de resultat : rediges par la comptabilite, actives par une seconde.
        Reponse maquette = post(accountant, "/statement-layouts", null, Map.of(
            "kind", "BALANCE_SHEET", "code", "BILAN-API", "label", "Bilan",
            "validFrom", J.minusYears(1).toString(), "lines", rubriques, "rules", regles));
        assertThat(maquette.status()).as(String.valueOf(maquette.envelope())).isEqualTo(201);
        UUID bilanId = UUID.fromString((String) maquette.body().get("id"));
        Reponse activation = post(accountant, "/statement-layouts/" + bilanId + "/activation",
                                  null, Map.of());
        assertThat(activation.status()).as(String.valueOf(activation.envelope())).isEqualTo(202);
        assertThat(post(accountant, "/pending-operations/" + attente(activation) + "/approve",
                        null, Map.of()).status()).isEqualTo(403);
        Reponse active = post(accountant2, "/pending-operations/" + attente(activation)
                              + "/approve", null, Map.of());
        assertThat(active.status()).as(String.valueOf(active.envelope())).isEqualTo(200);
        assertThat(resultat(active.body()).get("status")).isEqualTo("ACTIVE");
        Reponse compteDeResultat = post(accountant, "/statement-layouts", null, Map.of(
            "kind", "INCOME_STATEMENT", "code", "CR-API", "label", "Compte de resultat",
            "validFrom", J.minusYears(1).toString(),
            "lines", List.of(
                Map.of("ordinal", 1, "code", "C1", "label", "Charges", "kind", "DETAIL",
                       "side", "DEBIT"),
                Map.of("ordinal", 2, "code", "R1", "label", "Produits", "kind", "DETAIL",
                       "side", "CREDIT"),
                Map.of("ordinal", 3, "code", "RES", "label", "Resultat", "kind", "TOTAL",
                       "side", "CREDIT", "plus", List.of("R1"), "minus", List.of("C1"))),
            "rules", List.of(
                Map.of("ordinal", 1, "lineCode", "C1", "balanceSide", "DEBIT"),
                Map.of("ordinal", 2, "lineCode", "R1", "balanceSide", "CREDIT"))));
        assertThat(compteDeResultat.status()).isEqualTo(201);
        Reponse activationCr = post(accountant, "/statement-layouts/"
                                    + compteDeResultat.body().get("id") + "/activation", null,
                                    Map.of());
        assertThat(post(accountant2, "/pending-operations/" + attente(activationCr) + "/approve",
                        null, Map.of()).status()).isEqualTo(200);

        // La maquette se relit ; une maquette inconnue n'existe pas.
        Reponse relue = get(accountant, "/statement-layouts/" + bilanId);
        assertThat(relue.status()).isEqualTo(200);
        assertThat((List<?>) relue.body().get("lines")).hasSize(8);
        assertThat((List<?>) relue.body().get("rules")).hasSize(5);
        assertThat(get(accountant, "/statement-layouts/" + UUID.randomUUID()).status())
            .isEqualTo(404);

        // Le bilan a la date de cloture : tout compte affecte, actif egal au passif ; le
        // resultat de l'exercice clos est au compte de resultat, la rubrique en cours a zero.
        Reponse bilan = get(accountant, "/statements/balance-sheet?asOf=" + J);
        assertThat(bilan.status()).as(String.valueOf(bilan.envelope())).isEqualTo(200);
        assertThat(bilan.body().get("consistent")).as(String.valueOf(bilan.body().get("anomalies")))
            .isEqualTo(true);
        assertThat(montant(bilan.body(), "net")).isEqualTo("0");
        Map<String, String> lignes = new java.util.HashMap<>();
        for (Object ligne : (List<?>) bilan.body().get("lines")) {
            Map<?, ?> l = (Map<?, ?>) ligne;
            lignes.put((String) l.get("code"), (String) ((Map<?, ?>) l.get("amount")).get("amount"));
        }
        assertThat(lignes.get("TA")).isEqualTo(lignes.get("TP"));
        assertThat(new java.math.BigDecimal(lignes.get("TA"))).isPositive();
        assertThat(lignes.get("PR")).isEqualTo("0");

        // Le compte de resultat de l'exercice, hors ecritures de cloture : le resultat affecte.
        Reponse cr = get(accountant, "/statements/income-statement?to=" + J);
        assertThat(cr.status()).as(String.valueOf(cr.envelope())).isEqualTo(200);
        assertThat(cr.body().get("consistent")).isEqualTo(true);
        assertThat(cr.body().get("from")).isEqualTo(J.minusYears(1).plusDays(1).toString());
        assertThat(new java.math.BigDecimal(montant(cr.body(), "net")))
            .isEqualByComparingTo(resultatNet);

        // Sans maquette de hors bilan, pas de hors bilan ; et le guichet ne lit pas les etats.
        Reponse horsBilan = get(accountant, "/statements/off-balance-sheet");
        assertThat(horsBilan.status()).isEqualTo(409);
        assertThat((String) horsBilan.body().get("detail")).contains("maquette");
        assertThat(get(teller, "/statements/balance-sheet").status()).isEqualTo(403);
    }

    @Test
    @Order(11)
    @DisplayName("le contrat OpenAPI est publie par le service, sans jeton, tel qu'il est verse, hors enveloppe")
    void contrat_openapi() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(
                "http://localhost:" + environment.getProperty("local.server.port")
                + "/v1/openapi.json")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
            .contains("application/json");
        @SuppressWarnings("unchecked")
        Map<String, Object> contrat = json.readValue(response.body(), Map.class);
        assertThat(contrat.get("openapi")).isEqualTo("3.1.0");
        assertThat(contrat).doesNotContainKey("meta");
        @SuppressWarnings("unchecked")
        Map<String, Object> chemins = (Map<String, Object>) contrat.get("paths");
        assertThat(chemins).containsKeys(
            "/v1/entities/{legalEntityId}/accounts/{accountId}/balance",
            "/v1/entities/{legalEntityId}/statements/balance-sheet",
            "/v1/entities/{legalEntityId}/fiscal-years/{fiscalYearId}/appropriation");
        @SuppressWarnings("unchecked")
        Map<String, Object> retrait = (Map<String, Object>) ((Map<?, ?>) chemins.get(
            "/v1/entities/{legalEntityId}/accounts/{accountId}/withdrawals")).get("post");
        @SuppressWarnings("unchecked")
        Map<String, Object> reponses = (Map<String, Object>) retrait.get("responses");
        assertThat(reponses).containsKeys("200", "201", "400", "401", "403", "404", "409", "422");
        assertThat(String.valueOf(retrait.get("parameters"))).contains("IdempotencyKey");
    }

    @Test
    @Order(12)
    @DisplayName("paiements sortants et plafonds : plafond pose a deux, ordre debite puis envoye, regle et retourne ; un autre annule avant envoi")
    void paiements_sortants() throws Exception {
        Map<String, Object> ordre = Map.of("amount", "30000", "currency", "XOF",
            "beneficiaryName", "Fournisseur SA", "beneficiaryBank", "BK-CI-001",
            "beneficiaryAccount", "CI93CI0010001234567890123456", "reference", "Facture 42");
        // Un guichetier n'ordonne pas de paiement sortant ; sans cle, rien ne part.
        assertThat(post(teller, "/accounts/" + account + "/payment-orders", "pay-1", ordre).status())
            .isEqualTo(403);
        assertThat(post(officer, "/accounts/" + account + "/payment-orders", null, ordre).status())
            .isEqualTo(400);

        // Le plafond par operation, pose a deux dans l'agence du compte.
        Reponse plafond = post(manager, "/accounts/" + account + "/limits", null, Map.of(
            "kind", "TRANSACTION", "amount", "40000", "currency", "XOF",
            "validFrom", J.toString()));
        assertThat(plafond.status()).as(String.valueOf(plafond.envelope())).isEqualTo(202);
        assertThat(post(manager2, "/pending-operations/" + attente(plafond) + "/approve", null,
                        Map.of()).status()).isEqualTo(200);
        Reponse plafonds = get(manager, "/accounts/" + account + "/limits");
        assertThat(plafonds.status()).as(String.valueOf(plafonds.envelope())).isEqualTo(200);
        assertThat(plafonds.items()).hasSize(1);
        assertThat(plafonds.items().get(0).get("kind")).isEqualTo("TRANSACTION");
        Reponse tropGros = post(officer, "/accounts/" + account + "/payment-orders", "pay-gros",
            Map.of("amount", "40001", "currency", "XOF", "beneficiaryName", "X",
                   "beneficiaryBank", "Y", "beneficiaryAccount", "Z"));
        assertThat(tropGros.status()).as(String.valueOf(tropGros.envelope())).isEqualTo(409);
        assertThat((String) tropGros.body().get("detail")).contains("plafond");

        // L'ordre : le client est debite, montant, frais et taxe ; un rejeu rend le meme ordre.
        String avant = montant(get(manager, "/accounts/" + account + "/balance").body(), "current");
        Reponse emis = post(officer, "/accounts/" + account + "/payment-orders", "pay-1", ordre);
        assertThat(emis.status()).as(String.valueOf(emis.envelope())).isEqualTo(201);
        assertThat(emis.body().get("status")).isEqualTo("ORDERED");
        assertThat(montant(emis.body(), "fee")).isEqualTo("1000");
        UUID ordreId = UUID.fromString((String) emis.body().get("id"));
        Reponse rejeu = post(officer, "/accounts/" + account + "/payment-orders", "pay-1", ordre);
        assertThat(rejeu.status()).isEqualTo(200);
        assertThat(rejeu.body().get("id")).isEqualTo(ordreId.toString());
        String apres = montant(get(manager, "/accounts/" + account + "/balance").body(), "current");
        assertThat(new java.math.BigDecimal(avant).subtract(new java.math.BigDecimal(apres)))
            .isEqualByComparingTo("31180");

        // Le suivi est un acte de back-office : l'officier ne regle pas, l'exploitant si.
        assertThat(post(officer, "/payment-orders/" + ordreId + "/send", null, Map.of()).status())
            .isEqualTo(403);
        Reponse tropTot = post(operator, "/payment-orders/" + ordreId + "/settlement", null,
                               Map.of("nostroAccountId", nostro.id().toString()));
        assertThat(tropTot.status()).isEqualTo(409);
        assertThat(post(operator, "/payment-orders/" + ordreId + "/send", null, Map.of())
            .body().get("status")).isEqualTo("SENT");
        Reponse regle = post(operator, "/payment-orders/" + ordreId + "/settlement", null,
                             Map.of("nostroAccountId", nostro.id().toString()));
        assertThat(regle.status()).as(String.valueOf(regle.envelope())).isEqualTo(200);
        assertThat(regle.body().get("status")).isEqualTo("SETTLED");
        Reponse retour = post(operator, "/payment-orders/" + ordreId + "/return", null,
                              Map.of("reason", "compte beneficiaire clos"));
        assertThat(retour.status()).as(String.valueOf(retour.envelope())).isEqualTo(200);
        assertThat(retour.body().get("status")).isEqualTo("RETURNED");
        String rendu = montant(get(manager, "/accounts/" + account + "/balance").body(), "current");
        assertThat(new java.math.BigDecimal(avant).subtract(new java.math.BigDecimal(rendu)))
            .as("les frais restent acquis").isEqualByComparingTo("1180");

        // Un second ordre, annule avant envoi : tout revient, frais compris.
        Reponse second = post(officer, "/accounts/" + account + "/payment-orders", "pay-2", ordre);
        assertThat(second.status()).isEqualTo(201);
        UUID secondId = UUID.fromString((String) second.body().get("id"));
        Reponse annule = post(operator, "/payment-orders/" + secondId + "/cancellation", null,
                              Map.of("reason", "erreur de saisie"));
        assertThat(annule.status()).as(String.valueOf(annule.envelope())).isEqualTo(200);
        assertThat(annule.body().get("status")).isEqualTo("CANCELLED");
        assertThat(montant(get(manager, "/accounts/" + account + "/balance").body(), "current"))
            .isEqualTo(rendu);

        // La lecture, tracee ; la liste par statut ; un ordre inconnu n'existe pas.
        Reponse liste = get(accountant, "/payment-orders?status=returned");
        assertThat(liste.status()).as(String.valueOf(liste.envelope())).isEqualTo(200);
        assertThat(liste.items()).extracting(o -> o.get("id")).containsExactly(ordreId.toString());
        assertThat(get(officer, "/payment-orders/" + secondId).body().get("cancelReason"))
            .isEqualTo("erreur de saisie");
        assertThat(get(officer, "/payment-orders/" + UUID.randomUUID()).status()).isEqualTo(404);
        assertThat(get(teller, "/payment-orders").status()).isEqualTo(403);
    }

    @Test
    @Order(13)
    @DisplayName("cheques : chequier delivre a deux, cheque paye au guichet une fois, opposition, rejet sans provision avec incident, remise creditee sauf bonne fin puis reglee, une autre impayee")
    @SuppressWarnings("unchecked")
    void cheques() throws Exception {
        // Le chequier se delivre a deux, dans l'agence du compte ; un guichetier ne le demande pas.
        assertThat(post(teller, "/accounts/" + account + "/cheque-books", null, Map.of("count", 25))
                       .status()).isEqualTo(403);
        Reponse demande = post(officer, "/accounts/" + account + "/cheque-books", null,
                               Map.of("count", 25));
        assertThat(demande.status()).as(String.valueOf(demande.envelope())).isEqualTo(202);
        Reponse delivre = post(manager, "/pending-operations/" + attente(demande) + "/approve", null,
                               Map.of());
        assertThat(delivre.status()).as(String.valueOf(delivre.envelope())).isEqualTo(200);
        Map<String, Object> chequier = resultat(delivre.body());
        assertThat(chequier.get("firstNumber")).isEqualTo(1);
        assertThat(chequier.get("lastNumber")).isEqualTo(25);
        assertThat(montant(chequier, "fee")).as("frais 2000 et taxe 18 %").isEqualTo("2360");
        Reponse chequiers = get(officer, "/accounts/" + account + "/cheque-books");
        assertThat(chequiers.status()).as(String.valueOf(chequiers.envelope())).isEqualTo(200);
        assertThat(chequiers.items()).extracting(b -> b.get("id"))
            .containsExactly(chequier.get("id"));
        assertThat(get(creditOfficer, "/accounts/" + account + "/cheque-books").status())
            .isEqualTo(403);

        // Au guichet : le guichetier paie sur sa caisse ; rejoue, la meme ecriture ; sous une
        // autre cle, le cheque est deja paye ; sans cle, rien ne part.
        Map<String, Object> paiement = Map.of("amount", "20000", "currency", "XOF",
                                              "beneficiary", "Porteur");
        String avant = montant(get(manager, "/accounts/" + account + "/balance").body(), "current");
        Reponse paye = post(teller, "/accounts/" + account + "/cheques/1/payment", "chq-1", paiement);
        assertThat(paye.status()).as(String.valueOf(paye.envelope())).isEqualTo(201);
        assertThat(((Map<String, Object>) paye.body().get("cheque")).get("status")).isEqualTo("PAID");
        assertThat(((Map<String, Object>) paye.body().get("receipt")).get("valueDate"))
            .isEqualTo(J.plusDays(1).toString());
        Reponse rejeu = post(teller, "/accounts/" + account + "/cheques/1/payment", "chq-1", paiement);
        assertThat(rejeu.status()).isEqualTo(200);
        assertThat(((Map<String, Object>) rejeu.body().get("receipt")).get("entryId"))
            .isEqualTo(((Map<String, Object>) paye.body().get("receipt")).get("entryId"));
        assertThat(post(teller, "/accounts/" + account + "/cheques/1/payment", "chq-2", paiement)
                       .status()).isEqualTo(409);
        String apres = montant(get(manager, "/accounts/" + account + "/balance").body(), "current");
        assertThat(new java.math.BigDecimal(avant).subtract(new java.math.BigDecimal(apres)))
            .isEqualByComparingTo("20000");
        assertThat(post(teller, "/accounts/" + account + "/cheques/2/payment", null, paiement)
                       .status()).isEqualTo(400);
        // Un cheque inconnu n'existe pas ; au-dela du plafond du guichetier, refuse ; par
        // compensation, un nostro est requis, et le chef d'agence n'a pas de caisse.
        assertThat(post(teller, "/accounts/" + account + "/cheques/99/payment", "chq-3", paiement)
                       .status()).isEqualTo(404);
        assertThat(post(teller, "/accounts/" + account + "/cheques/2/payment", "chq-4",
                        Map.of("amount", "2000001", "currency", "XOF")).status()).isEqualTo(403);
        assertThat(post(operator, "/accounts/" + account + "/cheques/2/payment", "chq-5",
                        Map.of("amount", "1000", "currency", "XOF", "mode", "CLEARING")).status())
            .isEqualTo(422);
        assertThat(post(manager, "/accounts/" + account + "/cheques/2/payment", "chq-6", paiement)
                       .status()).isEqualTo(409);
        Reponse compense = post(operator, "/accounts/" + account + "/cheques/4/payment", "chq-7",
            Map.of("amount", "1000", "currency", "XOF", "mode", "CLEARING",
                   "nostroAccountId", nostro.id().toString(), "beneficiary", "Banque X"));
        assertThat(compense.status()).as(String.valueOf(compense.envelope())).isEqualTo(201);

        // L'opposition, motivee, par le charge de clientele : le cheque ne se paie plus.
        assertThat(post(teller, "/accounts/" + account + "/cheques/2/stop", null,
                        Map.of("reason", "LOSS")).status()).isEqualTo(403);
        assertThat(post(officer, "/accounts/" + account + "/cheques/2/stop", null,
                        Map.of("reason", "PARCE_QUE")).status()).isEqualTo(422);
        Reponse oppose = post(officer, "/accounts/" + account + "/cheques/2/stop", null,
                              Map.of("reason", "LOSS"));
        assertThat(oppose.status()).as(String.valueOf(oppose.envelope())).isEqualTo(200);
        assertThat(oppose.body().get("status")).isEqualTo("STOPPED");
        assertThat(oppose.body().get("stopReason")).isEqualTo("LOSS");
        Reponse refuse = post(teller, "/accounts/" + account + "/cheques/2/payment", "chq-8", paiement);
        assertThat(refuse.status()).isEqualTo(409);
        assertThat((String) refuse.body().get("detail")).contains("opposition");

        // Sans provision : rejete, l'incident est enregistre ; le cheque peut etre represente.
        assertThat(new java.math.BigDecimal(apres)).as("le decor tient sous le plafond du guichetier")
            .isLessThan(new java.math.BigDecimal("1900000"));
        Reponse rejete = post(teller, "/accounts/" + account + "/cheques/3/payment", "chq-9",
            Map.of("amount", "1900000", "currency", "XOF", "beneficiary", "Porteur"));
        assertThat(rejete.status()).as(String.valueOf(rejete.envelope())).isEqualTo(409);
        assertThat((String) rejete.body().get("detail")).contains("incident");
        Reponse incidents = get(officer, "/accounts/" + account + "/cheque-incidents");
        assertThat(incidents.status()).as(String.valueOf(incidents.envelope())).isEqualTo(200);
        assertThat(incidents.items()).singleElement()
            .satisfies(i -> assertThat(i.get("reason")).isEqualTo("SANS_PROVISION"));
        Reponse rejetes = get(teller, "/accounts/" + account + "/cheques?status=rejected");
        assertThat(rejetes.items()).extracting(q -> q.get("number")).containsExactly(3);
        assertThat(montant(get(manager, "/accounts/" + account + "/balance").body(), "current"))
            .isEqualTo(new java.math.BigDecimal(apres).subtract(new java.math.BigDecimal("1000"))
                           .toPlainString());

        // La remise credite le client sauf bonne fin, a deux jours ouvres de valeur, bloquee
        // jusqu'au reglement ; rejouee, la meme remise.
        Map<String, Object> soldeAvant = get(manager, "/accounts/" + account + "/balance").body();
        java.math.BigDecimal bloqueAvant = new java.math.BigDecimal(montant(soldeAvant, "current"))
            .subtract(new java.math.BigDecimal(montant(soldeAvant, "available")));
        Map<String, Object> remise = Map.of("amount", "50000", "currency", "XOF",
            "draweeBank", "BK-CI-002", "chequeNumber", "0001234", "drawerName", "Tireur SARL");
        Reponse deposee = post(teller, "/accounts/" + account + "/cheque-deposits", "rem-1", remise);
        assertThat(deposee.status()).as(String.valueOf(deposee.envelope())).isEqualTo(201);
        assertThat(deposee.body().get("status")).isEqualTo("DEPOSITED");
        assertThat(deposee.body().get("valueDate")).as("mercredi + 2 jours ouvres")
            .isEqualTo(J.plusDays(3).toString());
        UUID remiseId = UUID.fromString((String) deposee.body().get("id"));
        assertThat(post(teller, "/accounts/" + account + "/cheque-deposits", "rem-1", remise)
                       .status()).isEqualTo(200);
        Map<String, Object> soldeRemise = get(manager, "/accounts/" + account + "/balance").body();
        assertThat(new java.math.BigDecimal(montant(soldeRemise, "current"))
                       .subtract(new java.math.BigDecimal(montant(soldeAvant, "current"))))
            .isEqualByComparingTo("50000");
        assertThat(new java.math.BigDecimal(montant(soldeRemise, "current"))
                       .subtract(new java.math.BigDecimal(montant(soldeRemise, "available"))))
            .as("le montant remis est bloque").isEqualByComparingTo(bloqueAvant.add(
                new java.math.BigDecimal("50000")));

        // Le reglement est un acte de back-office, sur un nostro : le blocage tombe.
        assertThat(post(teller, "/cheque-deposits/" + remiseId + "/settlement", null,
                        Map.of("nostroAccountId", nostro.id().toString())).status()).isEqualTo(403);
        assertThat(post(operator, "/cheque-deposits/" + remiseId + "/settlement", null,
                        Map.of("nostroAccountId", caisse.id().toString())).status()).isEqualTo(422);
        Reponse reglee = post(operator, "/cheque-deposits/" + remiseId + "/settlement", null,
                              Map.of("nostroAccountId", nostro.id().toString()));
        assertThat(reglee.status()).as(String.valueOf(reglee.envelope())).isEqualTo(200);
        assertThat(reglee.body().get("status")).isEqualTo("SETTLED");
        Map<String, Object> soldeRegle = get(manager, "/accounts/" + account + "/balance").body();
        assertThat(new java.math.BigDecimal(montant(soldeRegle, "current"))
                       .subtract(new java.math.BigDecimal(montant(soldeRegle, "available"))))
            .isEqualByComparingTo(bloqueAvant);
        assertThat(post(operator, "/cheque-deposits/" + remiseId + "/return", null,
                        Map.of("reason", "trop tard")).status()).isEqualTo(409);

        // Une seconde remise revient impayee : le credit est contre-passe, le blocage avec lui.
        Reponse seconde = post(teller, "/accounts/" + account + "/cheque-deposits", "rem-2",
            Map.of("amount", "30000", "currency", "XOF", "draweeBank", "BK-CI-002",
                   "chequeNumber", "0001235", "drawerName", "Tireur SARL"));
        assertThat(seconde.status()).as(String.valueOf(seconde.envelope())).isEqualTo(201);
        UUID secondeId = UUID.fromString((String) seconde.body().get("id"));
        Reponse impayee = post(operator, "/cheque-deposits/" + secondeId + "/return", null,
                               Map.of("reason", "provision insuffisante"));
        assertThat(impayee.status()).as(String.valueOf(impayee.envelope())).isEqualTo(200);
        assertThat(impayee.body().get("status")).isEqualTo("RETURNED");
        assertThat(impayee.body().get("returnReason")).isEqualTo("provision insuffisante");
        Map<String, Object> soldeFinal = get(manager, "/accounts/" + account + "/balance").body();
        assertThat(montant(soldeFinal, "current")).isEqualTo(montant(soldeRegle, "current"));
        assertThat(montant(soldeFinal, "available")).isEqualTo(montant(soldeRegle, "available"));

        // La lecture, tracee ; la liste par statut ; une remise inconnue n'existe pas.
        Reponse liste = get(accountant, "/cheque-deposits?status=settled");
        assertThat(liste.status()).as(String.valueOf(liste.envelope())).isEqualTo(200);
        assertThat(liste.items()).extracting(d -> d.get("id")).containsExactly(remiseId.toString());
        assertThat(get(teller, "/cheque-deposits/" + remiseId).body().get("settlementAccountId"))
            .isEqualTo(nostro.id().toString());
        assertThat(get(teller, "/cheque-deposits/" + UUID.randomUUID()).status()).isEqualTo(404);
        assertThat(get(creditOfficer, "/cheque-deposits").status()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ outillage

    private static UUID attente(Reponse reponse) {
        return UUID.fromString((String) reponse.body().get("id"));
    }

    /**
     * @param body     la donnee (ou l'erreur) telle que les assertions la lisent ; une liste est
     *                 rendue sous {@code items}
     * @param envelope l'enveloppe entiere : data, page, error, meta
     */
    private record Reponse(int status, Map<String, Object> body, Map<String, Object> envelope) {
        @SuppressWarnings("unchecked")
        Map<String, Object> page() {
            return (Map<String, Object>) envelope.get("page");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> meta() {
            return (Map<String, Object>) envelope.get("meta");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items() {
            return (List<Map<String, Object>>) body.get("items");
        }
    }

    private Reponse post(String token, String path, String idempotencyKey, Map<String, Object> body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return send(request.build());
    }

    private Reponse get(String token, String path) throws Exception {
        return get(token, path, Map.of());
    }

    private Reponse get(String token, String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        headers.forEach(request::header);
        return send(request.build());
    }

    @SuppressWarnings("unchecked")
    private Reponse send(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        String text = response.body();
        Map<String, Object> envelope = text == null || text.isBlank()
            ? Map.of() : json.readValue(text, Map.class);
        // Toute reponse porte l'enveloppe, et l'identifiant de requete en en-tete.
        if (!envelope.isEmpty()) {
            assertThat(envelope).containsKey("meta");
            assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
        }
        Object data = envelope.get("data");
        Object error = envelope.get("error");
        Map<String, Object> body;
        if (error instanceof Map<?, ?> refus) {
            body = (Map<String, Object>) refus;
        } else if (data instanceof List<?> list) {
            body = Map.of("items", list);
        } else if (data instanceof Map<?, ?> donnee) {
            body = (Map<String, Object>) donnee;
        } else {
            body = Map.of();
        }
        return new Reponse(response.statusCode(), body, envelope);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port")
                          + "/v1/entities/" + ENTITY + path);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resultat(Map<String, Object> body) {
        return (Map<String, Object>) body.get("result");
    }

    @SuppressWarnings("unchecked")
    private static String montant(Map<String, Object> body, String field) {
        return (String) ((Map<String, Object>) body.get(field)).get("amount");
    }

    /** Un compte general de charges ou de produits : ce que la cloture annuelle solde. */
    private static Account compteDeResultat(java.sql.Connection c, String code,
                                            NormalBalance normal) {
        Account account = new Account(UUID.randomUUID(), ENTITY, code, AccountKind.GL, normal,
                                      Currencies.XOF, true, false, 1, AccountStatus.ACTIVE, null)
            .withNature(io.corebanking.ledger.domain.account.AccountNature.PROFIT_AND_LOSS);
        Accounts.create(c, account, J.minusMonths(1));
        return account;
    }

    private static Account compte(java.sql.Connection c, String code, AccountKind kind,
                                  NormalBalance normal, UUID branch) {
        Account account = new Account(UUID.randomUUID(), ENTITY, code, kind, normal, Currencies.XOF,
                                      true, false, 1, AccountStatus.ACTIVE, branch);
        Accounts.create(c, account, J.minusMonths(1));
        return account;
    }

    private static String token(UUID subject, String username, UUID branch, String... roles) {
        return tokenOf(subject, username, ENTITY, branch, roles);
    }

    private static String tokenOf(UUID subject, String username, UUID entity, UUID branch,
                                  String... roles) {
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(subject.toString())
                .issuer("https://keycloak.test/realms/core-banking")
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .claim("preferred_username", username)
                .claim("legal_entity", entity.toString())
                .claim("resource_access", Map.of(CLIENT_ID, Map.of("roles", List.of(roles))));
            if (branch != null) {
                claims.claim("branch", branch.toString());
            }
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
            jwt.sign(new RSASSASigner(KEYS.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
