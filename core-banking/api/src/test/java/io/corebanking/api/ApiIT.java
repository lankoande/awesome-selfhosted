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

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        try {
            postgres = EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        registry.add("corebanking.datasource.url",
                     () -> "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres");
        registry.add("corebanking.datasource.username", () -> "postgres");
        registry.add("corebanking.datasource.password", () -> "");
        registry.add("corebanking.datasource.pool-size", () -> "8");
        registry.add("corebanking.security.client-id", () -> CLIENT_ID);
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

    private final HttpClient http = HttpClient.newHttpClient();
    private UUID siege;
    private Account caisse;
    private String teller;
    private String officer;
    private String manager;
    private String operator;
    private UUID party;
    private UUID account;

    @BeforeAll
    void decor() {
        io.corebanking.ledger.store.SchemaMigrator.ensurePartitions(database, J.minusMonths(2),
                                                                     J.plusMonths(3));
        database.inTransaction(c -> {
            Entities.insertCurrency(c, Currencies.XOF, "Franc CFA BCEAO");
            Entities.insertLegalEntity(c, ENTITY, "API", "Banque API", "CI", Currencies.XOF, J);
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
            return null;
        });
        teller = token(UUID.randomUUID(), "guichetier", siege, Roles.TELLER);
        officer = token(UUID.randomUUID(), "charge.clientele", siege, Roles.CUSTOMER_OFFICER);
        manager = token(UUID.randomUUID(), "chef.agence", siege, Roles.BRANCH_MANAGER);
        operator = token(UUID.randomUUID(), "exploitant", null, Roles.OPERATOR);
    }

    @AfterAll
    static void stop() throws IOException {
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

        Reponse kyc = post(officer, "/parties/" + party + "/kyc-verifications", null, Map.of(
            "rating", "MEDIUM", "verifiedOn", J.toString(), "approverId", APPROVER.toString()));
        assertThat(kyc.status()).as(String.valueOf(kyc.body())).isEqualTo(200);
        assertThat(kyc.body().get("kycStatus")).isEqualTo("VERIFIED");

        Reponse ouverture = post(officer, "/accounts", null, Map.of(
            "code", "CLI-API-1", "holderPartyId", party.toString(), "productCode", "EP-API",
            "currency", "XOF", "approverId", APPROVER.toString()));
        assertThat(ouverture.status()).as(String.valueOf(ouverture.body())).isEqualTo(201);
        account = UUID.fromString((String) ouverture.body().get("id"));

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

        // Un guichetier n'ouvre pas de compte : la politique le dit, l'API le repete.
        Reponse interdit = post(teller, "/accounts", null, Map.of(
            "code", "CLI-API-2", "holderPartyId", party.toString(), "productCode", "EP-API",
            "currency", "XOF", "approverId", APPROVER.toString()));
        assertThat(interdit.status()).as(String.valueOf(interdit.body())).isEqualTo(403);
        assertThat((String) interdit.body().get("detail")).contains("roles");

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

    // ------------------------------------------------------------------ outillage

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
        Map<String, Object> body = response.body() == null || response.body().isBlank()
            ? Map.of() : json.readValue(response.body(), Map.class);
        return new Reponse(response.statusCode(), body);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port")
                          + "/v1/entities/" + ENTITY + path);
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
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(subject.toString())
                .issuer("https://keycloak.test/realms/core-banking")
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .claim("preferred_username", username)
                .claim("legal_entity", ENTITY.toString())
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
