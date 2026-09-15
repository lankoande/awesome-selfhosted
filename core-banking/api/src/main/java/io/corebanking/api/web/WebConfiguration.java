package io.corebanking.api.web;

import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.security.Caller;
import io.corebanking.security.KeycloakCallerFactory;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Deux parametres que les controleurs recoivent sans les demander au client : l'appelant, derive
 * du jeton, et la cle d'idempotence, derivee de l'en-tete {@code Idempotency-Key}.
 */
@Configuration
public class WebConfiguration implements WebMvcConfigurer {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final KeycloakCallerFactory callerFactory;

    public WebConfiguration(KeycloakCallerFactory callerFactory) {
        this.callerFactory = callerFactory;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CallerResolver());
        resolvers.add(new IdempotencyKeyResolver());
    }

    /** {@link Caller} depuis les revendications du jeton — jamais depuis un parametre. */
    private final class CallerResolver implements HandlerMethodArgumentResolver {
        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return Caller.class.equals(parameter.getParameterType());
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                      NativeWebRequest request, WebDataBinderFactory binders) {
            if (!(SecurityContextHolder.getContext().getAuthentication()
                  instanceof JwtAuthenticationToken jwt)) {
                throw new KeycloakCallerFactory.MissingClaimException("jeton");
            }
            return callerFactory.from(jwt.getToken().getClaims());
        }
    }

    /** {@link IdempotencyKey} depuis l'en-tete, obligatoire : un client qui appuie deux fois n'est pas debite deux fois. */
    private static final class IdempotencyKeyResolver implements HandlerMethodArgumentResolver {
        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return IdempotencyKey.class.equals(parameter.getParameterType());
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                      NativeWebRequest request, WebDataBinderFactory binders) {
            String value = request.getHeader(IDEMPOTENCY_HEADER);
            if (value == null || value.isBlank()) {
                throw new MissingIdempotencyKeyException();
            }
            return IdempotencyKey.of(value.trim());
        }
    }

    public static class MissingIdempotencyKeyException extends RuntimeException {
        public MissingIdempotencyKeyException() {
            super("En-tete " + IDEMPOTENCY_HEADER + " obligatoire : c'est la cle qui rend un "
                  + "rejeu inoffensif.");
        }
    }
}
