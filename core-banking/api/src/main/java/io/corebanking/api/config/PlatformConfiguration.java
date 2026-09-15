package io.corebanking.api.config;

import io.corebanking.deposits.AccountLifecycle;
import io.corebanking.deposits.OperationsService;
import io.corebanking.fee.service.FeeChargingService;
import io.corebanking.interest.service.BatchInterestAccrualService;
import io.corebanking.ledger.domain.posting.PostingService;
import io.corebanking.ledger.store.Database;
import io.corebanking.ledger.store.JdbcPostingService;
import io.corebanking.ledger.store.SchemaMigrator;
import io.corebanking.loan.service.LoanClassificationService;
import io.corebanking.loan.service.LoanLateChargesService;
import io.corebanking.loan.service.LoanMobilisationService;
import io.corebanking.loan.service.LoanService;
import io.corebanking.party.PartyService;
import io.corebanking.party.Screening;
import io.corebanking.security.AuthorizationAudit;
import io.corebanking.security.AuthorizationService;
import io.corebanking.security.KeycloakCallerFactory;
import io.corebanking.security.RoleStartupTask;
import io.corebanking.security.UseCaseExecutor;
import io.corebanking.security.keycloak.KeycloakAdminConfig;
import io.corebanking.security.keycloak.KeycloakAdminProvisioner;
import io.corebanking.security.store.JdbcAuthorizationAudit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assemblage du socle. Les services sont construits ici, une fois, et exposes aux cas d'usage ;
 * aucun d'eux ne connait Spring.
 */
@Configuration
public class PlatformConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformConfiguration.class);

    /**
     * Les roles applicatifs sont declares dans Keycloak au demarrage, depuis {@code roles.json} :
     * un role que la politique connait et que le royaume ignore est un droit que personne ne peut
     * recevoir. Un role orphelin dans le royaume est signale, jamais supprime.
     */
    @Bean
    ApplicationRunner roleProvisioning(PlatformProperties properties) {
        return args -> {
            PlatformProperties.Keycloak keycloak = properties.keycloak();
            if (keycloak == null || !keycloak.configured()) {
                LOG.warn("Keycloak : aucune API d'administration configuree, les roles de "
                         + "roles.json ne sont pas provisionnes au demarrage");
                return;
            }
            KeycloakAdminConfig config = KeycloakAdminConfig.of(
                keycloak.adminUrl(), keycloak.realm(), keycloak.serviceClientId(),
                keycloak::serviceClientSecret);
            RoleStartupTask.Report report = new RoleStartupTask(
                new KeycloakAdminProvisioner(config)).run();
            LOG.info("Keycloak : {}", report.summary());
        };
    }

    /**
     * Deux comptes de base, jamais un seul : le proprietaire du schema fait la montee de version
     * puis se retire ; l'application se connecte avec un role qui ne possede rien, et la base lui
     * applique ses politiques de cloisonnement (Row Level Security). Le classpath doit porter
     * toutes les versions : un module absent du deploiement se voit au demarrage, pas au premier
     * appel qui le demande.
     */
    @Bean(destroyMethod = "close")
    Database database(PlatformProperties properties) {
        PlatformProperties.Datasource ds = properties.datasource();
        PlatformProperties.Schema schema = properties.schema();
        if (schema.migrateOnStartup() && schema.ownerConfigured()) {
            try (Database owner = new Database(ds.url(), schema.username(), schema.password(), 1)) {
                SchemaMigrator.Report report = SchemaMigrator.migrate(owner,
                                                                      SchemaMigrator.Gaps.REFUSED);
                LOG.info("Schema : version {} sous le compte proprietaire {}, {} script(s) applique(s)",
                         report.highest(), schema.username(), report.applied().size());
            }
        }
        Database database = new Database(ds.url(), ds.username(), ds.password(), ds.poolSize());
        if (schema.migrateOnStartup() && !schema.ownerConfigured()) {
            LOG.warn("Schema : aucun compte proprietaire distinct (corebanking.schema.username) ; "
                     + "les migrations s'executent avec le compte applicatif, qui possede alors "
                     + "les tables et n'est soumis a aucun cloisonnement par la base. Acceptable "
                     + "en developpement, jamais en production.");
            SchemaMigrator.migrate(database, SchemaMigrator.Gaps.REFUSED);
        }
        return database;
    }

    @Bean
    PostingService postingService(Database database) {
        return new JdbcPostingService(database);
    }

    @Bean
    OperationsService operationsService(Database database, PostingService postingService) {
        return new OperationsService(database, postingService);
    }

    @Bean
    AccountLifecycle accountLifecycle(Database database, PostingService postingService) {
        return new AccountLifecycle(database, postingService);
    }

    @Bean
    PartyService partyService(Database database) {
        // Le filtrage (sanctions, PPE) est une interface : brancher ici l'editeur retenu.
        return new PartyService(database, Screening.NONE);
    }

    @Bean
    AuthorizationAudit authorizationAudit(Database database) {
        return new JdbcAuthorizationAudit(database);
    }

    @Bean
    AuthorizationService authorizationService(AuthorizationAudit audit) {
        return new AuthorizationService(audit);
    }

    @Bean
    UseCaseExecutor useCaseExecutor(AuthorizationService authorizationService) {
        return new UseCaseExecutor(authorizationService);
    }

    @Bean
    KeycloakCallerFactory callerFactory(PlatformProperties properties) {
        return new KeycloakCallerFactory(properties.security().clientId());
    }

    @Bean
    AccountDirectory accountDirectory(Database database) {
        return new AccountDirectory(database);
    }

    @Bean
    LoanService loanService(Database database, PostingService postingService) {
        return new LoanService(database, postingService);
    }

    @Bean
    io.corebanking.api.web.MakerChecker makerChecker(Database database,
                                                     AuthorizationService authorization,
                                                     tools.jackson.databind.ObjectMapper json,
                                                     PlatformProperties properties,
                                                     AccountLifecycle lifecycle,
                                                     PartyService parties,
                                                     AccountDirectory accounts,
                                                     LoanService loans) {
        int hours = properties.makerChecker() == null ? 48
                    : properties.makerChecker().expiryHoursOrDefault();
        return new io.corebanking.api.web.MakerChecker(
            database, authorization, json, java.time.Duration.ofHours(hours),
            io.corebanking.api.web.DualControlHandlers.all(database, lifecycle, parties, accounts,
                                                           loans));
    }

    @Bean
    EodEngines eodEngines(Database database, PostingService postingService,
                          LoanService loanService) {
        return new EodEngines(database, postingService,
                              new BatchInterestAccrualService(database, postingService),
                              new FeeChargingService(database, postingService), loanService,
                              new LoanMobilisationService(database, postingService),
                              new LoanLateChargesService(database, postingService),
                              new LoanClassificationService(database, postingService, loanService));
    }
}
