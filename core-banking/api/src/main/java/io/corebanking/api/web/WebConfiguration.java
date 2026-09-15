package io.corebanking.api.web;

import io.corebanking.api.usecase.Paging;
import io.corebanking.kernel.id.IdempotencyKey;
import io.corebanking.ledger.store.Database;
import io.corebanking.security.Caller;
import io.corebanking.security.KeycloakCallerFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Ce que chaque requete recoit sans que le client le demande : l'appelant, derive du jeton, la
 * cle d'idempotence, derivee de l'en-tete {@code Idempotency-Key}, et la portee d'entite, posee
 * pour la duree de la requete a partir de l'entite de l'appelant.
 */
@Configuration
public class WebConfiguration implements WebMvcConfigurer {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private static final String CALLER_ATTRIBUTE = Caller.class.getName();
    private static final String SCOPE_ATTRIBUTE = Database.EntityScope.class.getName();

    private final KeycloakCallerFactory callerFactory;

    public WebConfiguration(KeycloakCallerFactory callerFactory) {
        this.callerFactory = callerFactory;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new EntityScopeInterceptor());
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CallerResolver());
        resolvers.add(new IdempotencyKeyResolver());
        resolvers.add(new PageRequestResolver());
    }

    /** {@link Paging.PageRequest} depuis {@code page} et {@code size} ; une taille au-dela du plafond est refusee. */
    private static final class PageRequestResolver implements HandlerMethodArgumentResolver {
        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return Paging.PageRequest.class.equals(parameter.getParameterType());
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                      NativeWebRequest request, WebDataBinderFactory binders) {
            return Paging.PageRequest.parse(request.getParameter("page"),
                                            request.getParameter("size"));
        }
    }

    /**
     * Pose l'entite de l'appelant pour la duree de la requete. Chaque transaction ouverte par les
     * cas d'usage la transmet a la base, qui ne montre rien d'autre : la Row Level Security fait
     * du cloisonnement une propriete de la connexion, pas une clause a ne pas oublier. Une
     * requete sans jeton n'a pas de portee — elle n'atteint d'ailleurs aucun cas d'usage. Une
     * ressource d'une autre entite n'existe donc pas pour l'appelant : elle est inconnue (404),
     * a moins que la politique ne tranche avant de la chercher (403).
     */
    private final class EntityScopeInterceptor implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                                 Object handler) {
            if (SecurityContextHolder.getContext().getAuthentication()
                instanceof JwtAuthenticationToken jwt) {
                Caller caller = callerFactory.from(jwt.getToken().getClaims());
                request.setAttribute(CALLER_ATTRIBUTE, caller);
                request.setAttribute(SCOPE_ATTRIBUTE, Database.enterEntity(caller.legalEntityId()));
            }
            return true;
        }

        @Override
        public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                    Object handler, Exception ex) {
            if (request.getAttribute(SCOPE_ATTRIBUTE) instanceof Database.EntityScope scope) {
                request.removeAttribute(SCOPE_ATTRIBUTE);
                scope.close();
            }
        }
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
            if (request.getAttribute(CALLER_ATTRIBUTE, NativeWebRequest.SCOPE_REQUEST)
                instanceof Caller caller) {
                return caller;
            }
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
