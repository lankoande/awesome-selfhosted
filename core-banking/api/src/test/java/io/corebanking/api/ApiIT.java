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
            Entities.openPeriod(c, ENTITY, J.minusMonths(2), J.plusMonths(3));
            UUID calendar = Calendars.createCalendar(c, "CI", "Cote d'Ivoire",
                Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), J.minusYears(1), J.plusYears(1));
            Calendars.attachToEntity(c, ENTITY, calendar);
            for (String type : List.of("CASH_DEPOSIT", "TRANSFER")) {
                Calendars.addRule(c, ENTITY, new ValueDateRule(type, null, Direction.CREDIT, 0,
                    OffsetUnit.CALENDAR_DAYS, BusinessDayConvention.UNADJUSTED, J.minusYears(1),
                    null), APPROVER, UUID.randomUUID());
            }
            for (String type : List.of("CASH_WITHDRAWAL", "TRANSFER")) {
                Calendars.addRule(c, ENTITY, new ValueDateRule(type, null, Direction.DEBIT, 0,
                    OffsetUnit.CALENDAR_DAYS, BusinessDayConvention.UNADJUSTED, J.minusYears(1),
                    null), APPROVER, UUID.randomUUID());
            }
            siege = Branches.headOffice(c, ENTITY);
            caisse = compte(c, "CAISSE", AccountKind.INTERNAL, NormalBalance.DEBIT, siege);
            Account charges = compte(c, "CHARGES", AccountKind.GL, NormalBalance.DEBIT, null);
            Account courus = compte(c, "COURUS", AccountKind.GL, NormalBalance.CREDIT, null);
            Account frais = compte(c, "FRAIS", AccountKind.GL, NormalBalance.CREDIT, null);
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
            UUID version = ProductCatalog.createDraft(c, new ProductCatalog.Draft(
                ENTITY, "EP-API", "SAVINGS_ACCOUNT", "Epargne", "XOF", J.minusMonths(1), null,
                parametres, List.of(), APPROVER));
            ProductCatalog.activate(c, version, UUID.randomUUID());

            // Le decor du credit : comptes du client et comptes generaux que le produit citera.
            pret = compte(c, "PRET", AccountKind.CUSTOMER, NormalBalance.DEBIT, siege);
            courant = compte(c, "COURANT", AccountKind.CUSTOMER, NormalBalance.CREDIT, siege);
            liaison = compte(c, "LIAISON-XOF", AccountKind.GL, NormalBalance.DEBIT, null);
            Account creances = compte(c, "CREANCES", AccountKind.GL, NormalBalance.DEBIT, null);
            Account produits = compte(c, "PRODUITS-CREDIT", AccountKind.GL, NormalBalance.CREDIT,
                                      null);
            Account retard = compte(c, "RETARD", AccountKind.GL, NormalBalance.CREDIT, null);
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
        teller = token(UUID.randomUUID(), "guichetier", siege, Roles.TELLER);
        officer = token(UUID.randomUUID(), "charge.clientele", siege, Roles.CUSTOMER_OFFICER);
        manager = token(UUID.randomUUID(), "chef.agence", siege, Roles.BRANCH_MANAGER);
        manager2 = token(UUID.randomUUID(), "chef.agence.adjoint", siege, Roles.BRANCH_MANAGER);
        operator = token(UUID.randomUUID(), "exploitant", null, Roles.OPERATOR);
        creditOfficer = token(UUID.randomUUID(), "charge.credit", siege, Roles.CREDIT_OFFICER);
        creditManager = token(UUID.randomUUID(), "resp.credit", null, Roles.CREDIT_MANAGER);
        productManager = token(UUID.randomUUID(), "resp.produits", null, Roles.PRODUCT_MANAGER);
        riskOfficer = token(UUID.randomUUID(), "risques", null, Roles.RISK_OFFICER);
        accountant = token(UUID.randomUUID(), "comptable", null, Roles.ACCOUNTANT);
    }

    @AfterAll
    void stop() throws IOException {
        if (owner != null) owner.close();
        if (postgres != null) postgres.close();
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
            "amount", "100000", "currency", "XOF", "cashAccountId", caisse.id().toString(),
            "channel", "GUICHET"));
        assertThat(versement.status()).as(String.valueOf(versement.body())).isEqualTo(201);
        assertThat(montant(versement.body(), "balanceAfter")).isEqualTo("100000");
        assertThat(versement.body().get("replayed")).isEqualTo(false);

        Reponse retrait = post(teller, "/accounts/" + account + "/withdrawals", "ret-1", Map.of(
            "amount", "20000", "currency", "XOF", "cashAccountId", caisse.id().toString()));
        assertThat(retrait.status()).as(String.valueOf(retrait.body())).isEqualTo(201);
        assertThat(montant(retrait.body(), "fee")).isEqualTo("500");
        assertThat(montant(retrait.body(), "tax")).isEqualTo("90");
        assertThat(montant(retrait.body(), "balanceAfter")).isEqualTo("79410");
        assertThat(retrait.body().get("remote")).isEqualTo(false);

        // Le client a appuye deux fois : meme cle, meme resultat, rien de plus comptabilise.
        Reponse rejeu = post(teller, "/accounts/" + account + "/withdrawals", "ret-1", Map.of(
            "amount", "20000", "currency", "XOF", "cashAccountId", caisse.id().toString()));
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
        assertThat(get(null, "/accounts/" + account + "/balance").status()).isEqualTo(401);

        Reponse sansCle = post(teller, "/accounts/" + account + "/withdrawals", null, Map.of(
            "amount", "1000", "currency", "XOF", "cashAccountId", caisse.id().toString()));
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
                        Map.of("amount", "1000", "currency", "XOF",
                               "cashAccountId", caisse.id().toString())).status()).isEqualTo(201);
        assertThat(post(teller, "/accounts/" + account + "/deposits", "dep-apres-rejet",
                        Map.of("amount", "1590", "currency", "XOF",
                               "cashAccountId", caisse.id().toString())).status()).isEqualTo(201);

        // Au-dela du plafond du role, meme avec la caisse et le compte de son agence.
        Reponse plafond = post(teller, "/accounts/" + account + "/withdrawals", "ret-plafond",
            Map.of("amount", "3000000", "currency", "XOF",
                   "cashAccountId", caisse.id().toString()));
        assertThat(plafond.status()).as(String.valueOf(plafond.body())).isEqualTo(403);
        assertThat((String) plafond.body().get("detail")).contains("plafond");

        Reponse insuffisant = post(teller, "/accounts/" + account + "/withdrawals", "ret-trop",
            Map.of("amount", "1000000", "currency", "XOF",
                   "cashAccountId", caisse.id().toString()));
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
            "amount", "10", "currency", "EUR", "cashAccountId", caisse.id().toString()));
        assertThat(devise.status()).as(String.valueOf(devise.body())).isEqualTo(422);

        // Le solde n'a pas bouge.
        assertThat(montant(get(manager, "/accounts/" + account + "/balance").body(), "current"))
            .isEqualTo("79410");
    }

    @Test
    @Order(3)
    @DisplayName("l'exploitant lance l'arrete de la journee, et le consulte")
    void arrete() throws Exception {
        Reponse lancement = post(operator, "/eod/runs", null, Map.of("businessDate", J.toString()));
        assertThat(lancement.status()).as(String.valueOf(lancement.body())).isEqualTo(201);
        assertThat(lancement.body().get("status")).isEqualTo("COMPLETED");
        UUID runId = UUID.fromString((String) lancement.body().get("id"));

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
        UUID pretId = UUID.fromString((String) cree.body().get("id"));

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

    // ------------------------------------------------------------------ outillage

    private static UUID attente(Reponse reponse) {
        return UUID.fromString((String) reponse.body().get("id"));
    }

    private record Reponse(int status, Map<String, Object> body) {}

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
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return send(request.build());
    }

    @SuppressWarnings("unchecked")
    private Reponse send(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        String text = response.body();
        Map<String, Object> body;
        if (text == null || text.isBlank()) {
            body = Map.of();
        } else if (text.trim().startsWith("[")) {
            body = Map.of("items", json.readValue(text, List.class));
        } else {
            body = json.readValue(text, Map.class);
        }
        return new Reponse(response.statusCode(), body);
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
