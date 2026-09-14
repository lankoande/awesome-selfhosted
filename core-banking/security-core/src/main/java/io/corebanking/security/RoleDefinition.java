package io.corebanking.security;

import java.util.Map;
import java.util.Objects;

/**
 * Definition declarative d'un role, telle qu'elle figure dans
 * {@code resources/security/roles.json}.
 *
 * @param code        identifiant technique, celui du role Keycloak et celui cite par
 *                    {@link SecurityConfig}
 * @param name        libelle lisible, affiche dans les ecrans d'habilitation
 * @param description ce que le role permet, en langage metier
 * @param isSystem    role livre avec le produit. Il est cree et tenu a jour au demarrage, et sa
 *                    disparition du royaume est une anomalie bloquante. Un role non systeme est
 *                    amorce une fois puis laisse a la banque.
 * @param category    regroupement pour la revue d'habilitations : RESEAU, SIEGE, PARAMETRAGE,
 *                    EXPLOITATION, CONTROLE
 * @param attributes  attributs libres, reportes tels quels en attributs de role Keycloak. Ils
 *                    portent ce qui est descriptif — periodicite de revue, exclusivite, exigence
 *                    d'un rattachement d'agence — jamais un droit.
 */
public record RoleDefinition(
    String code,
    String name,
    String description,
    boolean isSystem,
    String category,
    Map<String, String> attributes) {

    /** Attribut marquant un role qui ne se cumule avec aucun autre. */
    public static final String ATTRIBUTE_EXCLUSIVE = "exclusive";

    public RoleDefinition {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(category, "category");
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
        if (code.isBlank()) {
            throw new IllegalArgumentException("Code de role vide");
        }
        if (!code.equals(code.toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException(
                "Code de role « " + code + " » : convention en minuscules, comme dans Keycloak. "
                + "Une difference de casse cree deux roles distincts dont l'un n'ouvre rien.");
        }
    }

    /**
     * Role exclusif : son porteur ne peut detenir aucun autre role.
     *
     * <p>L'attribut est declaratif ; c'est {@link SecurityConfig} qui en tire la regle de
     * segregation, de facon qu'ajouter un profil exclusif reste du parametrage.
     */
    public boolean isExclusive() {
        return Boolean.parseBoolean(attributes.getOrDefault(ATTRIBUTE_EXCLUSIVE, "false"));
    }
}
