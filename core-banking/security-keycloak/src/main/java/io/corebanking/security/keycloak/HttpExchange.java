package io.corebanking.security.keycloak;

import java.util.Map;

/**
 * Couture HTTP de l'adaptateur.
 *
 * <p>Elle existe pour une raison precise : sans elle, la seule facon de verifier qu'un role est
 * cree avec les bons attributs, qu'un jeton expire est renouvele ou qu'un {@code 401} n'est pas
 * reessaye serait de demarrer un Keycloak. Ces regles-la n'ont rien de reseau — ce sont des
 * decisions — et elles doivent etre testables sans serveur.
 *
 * <p>L'implementation reelle {@link JdkHttpExchange} ne contient donc aucune logique : elle
 * transporte, et c'est tout.
 */
@FunctionalInterface
public interface HttpExchange {

    Response send(Request request);

    record Request(String method, String uri, Map<String, String> headers, String body) {

        public Request {
            headers = Map.copyOf(headers == null ? Map.of() : headers);
        }

        public static Request get(String uri, Map<String, String> headers) {
            return new Request("GET", uri, headers, null);
        }

        public static Request post(String uri, Map<String, String> headers, String body) {
            return new Request("POST", uri, headers, body);
        }

        public static Request put(String uri, Map<String, String> headers, String body) {
            return new Request("PUT", uri, headers, body);
        }
    }

    record Response(int status, String body) {

        public boolean isSuccess() {
            return status >= 200 && status < 300;
        }

        /** Defaillance passagere : seule categorie qui merite une nouvelle tentative. */
        public boolean isRetryable() {
            return status >= 500 || status == 429;
        }

        /**
         * Authentification ou autorisation refusee.
         *
         * <p>Jamais reessaye : reessayer ne corrigera pas un secret errone, et la repetition peut
         * declencher le verrouillage du compte de service — transformant une erreur de
         * configuration en indisponibilite.
         */
        public boolean isAuthFailure() {
            return status == 401 || status == 403;
        }
    }

    /** Defaillance de transport, distincte d'une reponse d'erreur. */
    class TransportException extends RuntimeException {
        public TransportException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
