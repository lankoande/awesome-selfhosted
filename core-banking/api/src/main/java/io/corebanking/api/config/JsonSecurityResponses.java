package io.corebanking.api.config;

import io.corebanking.api.web.ApiResponse;
import io.corebanking.api.web.RequestIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * Les refus prononces par la chaine de securite — avant tout controleur — sortent dans la meme
 * enveloppe que le reste : un client qui recoit 401 lit la meme forme qu'en 403 ou 409.
 */
final class JsonSecurityResponses {

    private JsonSecurityResponses() {}

    static AuthenticationEntryPoint entryPoint(ObjectMapper json) {
        return (request, response, exception) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            write(json, request, response, HttpStatus.UNAUTHORIZED, "Non authentifie",
                  detail(exception));
        };
    }

    static AccessDeniedHandler accessDenied(ObjectMapper json) {
        return (request, response, exception) -> write(
            json, request, response, HttpStatus.FORBIDDEN, "Acces refuse",
            "Le jeton ne permet pas d'atteindre cette ressource.");
    }

    private static String detail(AuthenticationException exception) {
        return exception instanceof InvalidBearerTokenException
            ? "Jeton invalide ou expire." : "Un jeton signe par le royaume est requis.";
    }

    private static void write(ObjectMapper json, HttpServletRequest request,
                              HttpServletResponse response, HttpStatus status, String title,
                              String detail) throws IOException {
        String requestId = RequestIds.of(request);
        response.setStatus(status.value());
        response.setHeader(RequestIds.HEADER, requestId);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(ApiResponse.error(
            status.value(), title, detail, request.getRequestURI(), requestId)));
        response.getWriter().flush();
    }
}
