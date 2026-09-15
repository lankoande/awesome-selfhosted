package io.corebanking.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Point d'entree de l'API. Un seul executable : Tomcat embarque, serveur de ressources OAuth2,
 * le socle et ses migrations de schema.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CoreBankingApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreBankingApplication.class, args);
    }
}
