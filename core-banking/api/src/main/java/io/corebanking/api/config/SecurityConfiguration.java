package io.corebanking.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

/**
 * Serveur de ressources OAuth2 : tout ce qui n'est pas la sante du service exige un jeton signe
 * par le royaume Keycloak. Le jeton dit qui appelle ; ce qu'il a le droit de faire est decide plus
 * loin, par {@code UseCaseExecutor} et {@code SecurityConfig}, jamais ici. Les refus de la chaine
 * elle-meme (401, 403) sortent dans l'enveloppe commune.
 */
@Configuration
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain api(HttpSecurity http, ObjectMapper json) throws Exception {
        var entryPoint = JsonSecurityResponses.entryPoint(json);
        var accessDenied = JsonSecurityResponses.accessDenied(json);
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                // Le contrat n'est pas une donnee : il se lit sans jeton.
                .requestMatchers(io.corebanking.api.openapi.OpenApiDocument.PATH).permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint)
                                     .accessDeniedHandler(accessDenied))
            .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults())
                                        .authenticationEntryPoint(entryPoint)
                                        .accessDeniedHandler(accessDenied))
            .build();
    }
}
