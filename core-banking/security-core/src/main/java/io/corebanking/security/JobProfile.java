package io.corebanking.security;

import java.util.Map;
import java.util.Set;

/**
 * Poste de travail : l'objet que la banque gere reellement.
 *
 * <h2>Les roles ne s'attribuent pas a des personnes</h2>
 *
 * <p>Un role est un regroupement technique de droits ; un poste est ce qu'occupe un agent. Les
 * roles sont donc portes par des <b>groupes Keycloak</b>, un par poste, et un utilisateur est
 * place dans un groupe — jamais gratifie d'un role directement.
 *
 * <p>La raison est operationnelle. L'attribution individuelle derive : au bout de deux ans, plus
 * personne ne sait pourquoi tel agent detient tel role, et la revue periodique des habilitations
 * — exigee par le controle interne — devient un inventaire de cas particuliers. Avec des groupes,
 * la revue porte sur huit postes au lieu de huit cents agents, et une anomalie se voit.
 *
 * <p><b>Aucun poste n'est decline par entite ou par agence.</b> L'entite et l'agence viennent des
 * revendications du jeton, pas du role. Les faire porter par le role produirait
 * {@code teller_CI}, {@code teller_SN}, {@code teller_CI_agence_007} — une explosion combinatoire
 * dont personne ne sort.
 */
public enum JobProfile {

    GUICHETIER("Guichetier", Set.of(Roles.TELLER)),

    CHEF_AGENCE("Chef d'agence", Set.of(Roles.TELLER, Roles.BRANCH_MANAGER)),

    CHARGE_CLIENTELE("Charge de clientele", Set.of(Roles.CUSTOMER_OFFICER)),
    CHARGE_CREDIT("Charge de credit", Set.of(Roles.CREDIT_OFFICER)),
    RESPONSABLE_ENGAGEMENTS("Responsable des engagements", Set.of(Roles.CREDIT_MANAGER)),

    COMPTABLE("Comptable", Set.of(Roles.ACCOUNTANT)),

    GESTIONNAIRE_PRODUIT("Gestionnaire de produits", Set.of(Roles.PRODUCT_MANAGER)),

    RESPONSABLE_RISQUES("Responsable des risques", Set.of(Roles.RISK_OFFICER)),

    EXPLOITANT("Exploitant", Set.of(Roles.OPERATOR)),

    /** Profil exclusif : un auditeur n'opere pas. Voir {@link SecurityConfig#segregationConflict}. */
    AUDITEUR("Auditeur", Set.of(Roles.AUDITOR));

    private final String label;
    private final Set<String> roles;

    JobProfile(String label, Set<String> roles) {
        this.label = label;
        this.roles = Set.copyOf(roles);
    }

    public String label() {
        return label;
    }

    public Set<String> roles() {
        return roles;
    }

    /** Nom du groupe Keycloak correspondant. */
    public String groupName() {
        return label;
    }

    public static Map<JobProfile, Set<String>> all() {
        Map<JobProfile, Set<String>> profiles = new java.util.EnumMap<>(JobProfile.class);
        for (JobProfile profile : values()) {
            profiles.put(profile, profile.roles());
        }
        return profiles;
    }
}
