package io.corebanking.security.keycloak;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Transport HTTP, sur le client du JDK.
 *
 * <p>Aucune logique : ni reprise, ni interpretation de code de retour, ni journalisation du corps
 * des requetes — celui de la demande de jeton contient le secret du compte de service.
 *
 * <p>La verification TLS n'est jamais desactivee. Un adaptateur d'administration qui accepte
 * n'importe quel certificat ouvre une interception sur le canal par lequel transitent les
 * habilitations.
 */
public final class JdkHttpExchange implements HttpExchange {

    private final HttpClient client;
    private final Duration requestTimeout;

    public JdkHttpExchange(Duration connectTimeout, Duration requestTimeout) {
        this.client = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        this.requestTimeout = requestTimeout;
    }

    public JdkHttpExchange() {
        this(Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    @Override
    public Response send(Request request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(request.uri()))
            .timeout(requestTimeout);

        request.headers().forEach(builder::header);

        HttpRequest.BodyPublisher body = request.body() == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(request.body());
        builder.method(request.method(), body);

        try {
            HttpResponse<String> response =
                client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new TransportException(
                request.method() + " " + request.uri() + " : " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportException("Appel interrompu : " + request.uri(), e);
        }
    }
}
