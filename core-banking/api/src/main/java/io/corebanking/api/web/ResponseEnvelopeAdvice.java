package io.corebanking.api.web;

import io.corebanking.api.usecase.Paging;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Toute reponse des controleurs de l'API sort dans {@link ApiResponse}. Un controleur rend sa
 * donnee — ou une {@link Paging.Paged page}, ou une {@link Paging.Slice tranche} — et rien d'autre : l'enveloppe n'est jamais
 * construite a la main, elle ne peut donc pas manquer. Les reponses hors de ce paquetage (sante
 * du service) ne sont pas touchees.
 */
@RestControllerAdvice(basePackages = "io.corebanking.api")
public class ResponseEnvelopeAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        // La seule reponse hors enveloppe : le contrat OpenAPI, marque comme tel.
        return !returnType.hasMethodAnnotation(io.corebanking.api.openapi.Raw.class);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        String requestId = request instanceof ServletServerHttpRequest servlet
            ? RequestIds.of(servlet.getServletRequest()) : "n/a";
        response.getHeaders().set(RequestIds.HEADER, requestId);
        if (body instanceof ApiResponse<?> enveloped) {
            return enveloped;
        }
        if (body instanceof Paging.Paged<?> paged) {
            return ApiResponse.of(paged, requestId);
        }
        if (body instanceof Paging.Slice<?> slice) {
            return ApiResponse.of(slice, requestId);
        }
        return ApiResponse.of(body, requestId);
    }
}
