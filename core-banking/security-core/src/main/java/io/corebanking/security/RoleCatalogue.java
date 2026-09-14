package io.corebanking.security;

import io.corebanking.security.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Catalogue des roles, charge depuis {@code resources/security/roles.json}.
 *
 * <h2>Deux sources, deux roles, une coherence exigee</h2>
 *
 * <p>Le catalogue est <b>descriptif</b> : libelle, description, categorie, attributs — tout ce
 * qu'une regle d'habilitation ne porte pas et dont les ecrans de revue ont besoin.
 * {@link SecurityConfig} reste <b>normatif</b> : seule une regle ouvre une operation.
 *
 * <p>Un role decrit ici mais cite par aucune regle n'ouvre rien ; un role cite par une regle mais
 * absent d'ici ne sera jamais provisionne, et l'operation correspondante devient inaccessible a
 * tous sans qu'aucune erreur ne le signale. {@link #validateAgainstPolicy()} interdit les deux, et
 * s'execute au demarrage : l'application ne sert pas avec un catalogue incoherent.
 *
 * <p>Le fichier est une <b>ressource du produit</b>, versionnee avec le code et revue comme lui.
 * Un role n'y est pas ajoute par une console d'administration.
 */
public final class RoleCatalogue {

    private static final String RESOURCE = "/security/roles.json";

    private static final Catalogue CATALOGUE = load();

    private record Catalogue(String clientId, Map<String, RoleDefinition> byCode) {}

    private RoleCatalogue() {}

    // ------------------------------------------------------------------ chargement

    private static Catalogue load() {
        String source;
        try (InputStream in = RoleCatalogue.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new CatalogueException(
                    "Catalogue de roles introuvable : " + RESOURCE
                    + ". Sans lui, aucune habilitation ne peut etre provisionnee.");
            }
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CatalogueException("Lecture de " + RESOURCE + " impossible : " + e.getMessage());
        }

        Map<String, Object> document = Json.parseObject(source);
        String clientId = text(document, "client");
        Object rawRoles = document.get("roles");
        if (!(rawRoles instanceof List<?> list) || list.isEmpty()) {
            throw new CatalogueException("Le catalogue ne declare aucun role.");
        }

        Map<String, RoleDefinition> byCode = new LinkedHashMap<>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> raw)) {
                throw new CatalogueException("Entree de role invalide : objet attendu.");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> role = (Map<String, Object>) raw;
            RoleDefinition definition = new RoleDefinition(
                text(role, "code"),
                text(role, "name"),
                optionalText(role, "description"),
                Boolean.TRUE.equals(role.get("isSystem")),
                text(role, "category"),
                attributes(role.get("attributes")));

            if (byCode.put(definition.code(), definition) != null) {
                throw new CatalogueException(
                    "Role « " + definition.code() + " » declare deux fois dans le catalogue.");
            }
        }
        // Ordre de declaration preserve : le fichier de provisionnement engendre doit sortir
        // identique d'une execution a l'autre, sans quoi chaque livraison produit un diff bruite
        // que plus personne ne relit.
        return new Catalogue(clientId, java.util.Collections.unmodifiableMap(byCode));
    }

    private static Map<String, String> attributes(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new CatalogueException("« attributes » doit etre un objet.");
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        map.forEach((key, value) -> attributes.put(String.valueOf(key), String.valueOf(value)));
        return attributes;
    }

    private static String text(Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new CatalogueException(
                "Champ « " + field + " » absent ou vide dans " + object);
        }
        return text;
    }

    private static String optionalText(Map<String, Object> object, String field) {
        Object value = object.get(field);
        return value instanceof String text ? text : "";
    }

    /** Catalogue illisible ou incoherent. Bloque le demarrage. */
    public static class CatalogueException extends RuntimeException {
        public CatalogueException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ lecture

    /** Client Keycloak porteur des roles, declare en tete du catalogue. */
    public static String clientId() {
        return CATALOGUE.clientId();
    }

    public static Map<String, RoleDefinition> definitions() {
        return CATALOGUE.byCode();
    }

    public static RoleDefinition require(String code) {
        RoleDefinition definition = CATALOGUE.byCode().get(code);
        if (definition == null) {
            throw new CatalogueException("Role « " + code + " » absent du catalogue.");
        }
        return definition;
    }

    /** Codes declares par le catalogue. */
    public static Set<String> declared() {
        return new TreeSet<>(CATALOGUE.byCode().keySet());
    }

    /** Roles livres avec le produit : crees et tenus a jour a chaque demarrage. */
    public static List<RoleDefinition> systemRoles() {
        return CATALOGUE.byCode().values().stream().filter(RoleDefinition::isSystem).toList();
    }

    /** Roles marques exclusifs : leur porteur ne peut detenir aucun autre role. */
    public static Set<String> exclusiveRoles() {
        return CATALOGUE.byCode().values().stream()
            .filter(RoleDefinition::isExclusive)
            .map(RoleDefinition::code)
            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
    }

    /** Regroupement par categorie, pour les ecrans de revue d'habilitations. */
    public static Map<String, List<RoleDefinition>> byCategory() {
        Map<String, List<RoleDefinition>> grouped = new TreeMap<>();
        CATALOGUE.byCode().values().forEach(definition ->
            grouped.computeIfAbsent(definition.category(), key -> new ArrayList<>()).add(definition));
        return grouped;
    }

    // ------------------------------------------------------------------ coherence

    /** Roles reellement cites par au moins une regle d'habilitation. */
    public static Set<String> usedByPolicy() {
        Set<String> used = new TreeSet<>();
        SecurityConfig.policy().values().forEach(rule -> used.addAll(rule.roles()));
        return used;
    }

    /** Operations qu'un role permet de realiser, pour la revue d'habilitations. */
    public static Set<Operation> operationsOf(String role) {
        Set<Operation> operations = new LinkedHashSet<>();
        SecurityConfig.policy().forEach((operation, rule) -> {
            if (rule.roles().contains(role)) {
                operations.add(operation);
            }
        });
        return operations;
    }

    /**
     * Verifie que catalogue et politique se recouvrent exactement. Appelee au demarrage : un ecart
     * empeche l'application de servir.
     */
    public static void validateAgainstPolicy() {
        Set<String> declared = declared();
        Set<String> used = usedByPolicy();

        Set<String> missingFromCatalogue = new TreeSet<>(used);
        missingFromCatalogue.removeAll(declared);

        Set<String> grantingNothing = new TreeSet<>(declared);
        grantingNothing.removeAll(used);

        if (!missingFromCatalogue.isEmpty() || !grantingNothing.isEmpty()) {
            StringBuilder message = new StringBuilder("Catalogue de roles incoherent avec la politique.");
            if (!missingFromCatalogue.isEmpty()) {
                message.append("\n  Cites par une regle et absents du catalogue : ")
                       .append(missingFromCatalogue)
                       .append("\n    -> ils ne seront jamais provisionnes ; les operations "
                               + "correspondantes seront inaccessibles a tous, sans message.");
            }
            if (!grantingNothing.isEmpty()) {
                message.append("\n  Declares au catalogue et cites par aucune regle : ")
                       .append(grantingNothing)
                       .append("\n    -> attribuables et sans effet : ils font croire a un droit "
                               + "qui n'existe pas.");
            }
            throw new CatalogueException(message.toString());
        }
    }
}
