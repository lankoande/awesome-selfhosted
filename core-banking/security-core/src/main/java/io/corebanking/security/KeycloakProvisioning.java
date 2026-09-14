package io.corebanking.security;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Provisionnement Keycloak engendre depuis la politique.
 *
 * <h2>Le sens de la fleche</h2>
 *
 * <p>La politique est la source ; Keycloak en est le reflet. Le fichier produit ici s'applique par
 * import partiel et se rejoue a chaque livraison : les roles du client et les groupes de postes
 * sont donc toujours ceux que le code attend.
 *
 * <p>Creer les roles a la main dans la console inverse la fleche, et cette inversion coute cher
 * dans les deux sens :
 *
 * <ul>
 *   <li>vers le trop : un role saisi et jamais cite par une regle n'ouvre rien, mais il est
 *       attribue, il apparait dans les jetons, et il donne l'illusion d'un droit ;</li>
 *   <li>vers le trop peu : un role attendu par la politique et jamais cree rend l'operation
 *       inaccessible a tous, sans qu'aucune erreur ne le dise.</li>
 * </ul>
 *
 * <p>Le second cas est le plus perfide : tout est coherent, les refus sont reguliers, et
 * l'operation est simplement morte. C'est pourquoi {@link #drift} existe et doit tourner au
 * deploiement.
 *
 * <h2>Ce que ce fichier ne contient pas</h2>
 *
 * <p>Aucun utilisateur, aucune affectation. Le provisionnement cree les roles et les groupes ; le
 * rattachement d'un agent a un groupe releve de la securite operationnelle, avec double validation
 * et revue periodique. Melanger les deux ferait passer une decision d'habilitation nominative dans
 * un pipeline de livraison, ou elle echapperait au controle interne.
 */
public final class KeycloakProvisioning {

    private KeycloakProvisioning() {}

    /**
     * Import partiel Keycloak : roles du client et groupes de postes.
     *
     * <p>A appliquer sur le royaume avec {@code Partial import}, ou via
     * {@code kcadm.sh create partialImport}.
     */
    public static String partialImport(String clientId) {
        Objects.requireNonNull(clientId, "clientId");
        StringBuilder json = new StringBuilder();

        json.append("{\n  \"ifResourceExists\": \"OVERWRITE\",\n");
        json.append("  \"roles\": {\n    \"client\": {\n      ")
            .append(quote(clientId)).append(": [\n");

        List<String> roles = new ArrayList<>(RoleCatalogue.declared());
        for (int i = 0; i < roles.size(); i++) {
            String role = roles.get(i);
            json.append("        { \"name\": ").append(quote(role))
                .append(", \"description\": ").append(quote(describe(role))).append(" }")
                .append(i < roles.size() - 1 ? "," : "").append('\n');
        }
        json.append("      ]\n    }\n  },\n");

        json.append("  \"groups\": [\n");
        JobProfile[] profiles = JobProfile.values();
        for (int i = 0; i < profiles.length; i++) {
            JobProfile profile = profiles[i];
            json.append("    {\n      \"name\": ").append(quote(profile.groupName()))
                .append(",\n      \"clientRoles\": { ").append(quote(clientId)).append(": [");
            List<String> profileRoles = new ArrayList<>(new TreeSet<>(profile.roles()));
            for (int r = 0; r < profileRoles.size(); r++) {
                json.append(quote(profileRoles.get(r)))
                    .append(r < profileRoles.size() - 1 ? ", " : "");
            }
            json.append("] }\n    }").append(i < profiles.length - 1 ? "," : "").append('\n');
        }
        json.append("  ]\n}\n");
        return json.toString();
    }

    /** Description engendree : les operations que le role ouvre effectivement. */
    static String describe(String role) {
        Set<Operation> operations = RoleCatalogue.operationsOf(role);
        return operations.isEmpty()
            ? "Role sans operation associee — anomalie de politique"
            : "Ouvre : " + operations.stream().map(Enum::name).sorted().toList();
    }

    /**
     * Ecart entre les roles attendus par la politique et ceux reellement presents dans Keycloak.
     *
     * @param observed roles du client backend releves sur le royaume
     */
    public static Drift drift(Set<String> observed) {
        Set<String> expected = RoleCatalogue.declared();

        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(observed);

        Set<String> unexpected = new TreeSet<>(observed);
        unexpected.removeAll(expected);

        return new Drift(missing, unexpected);
    }

    /**
     * @param missing    attendus par la politique, absents du royaume : les operations
     *                   correspondantes sont inaccessibles a tous, en silence
     * @param unexpected presents dans le royaume, cites par aucune regle : attribuables et sans
     *                   effet, ils font croire a un droit qui n'existe pas
     */
    public record Drift(Set<String> missing, Set<String> unexpected) {

        public Drift {
            missing = Set.copyOf(missing);
            unexpected = Set.copyOf(unexpected);
        }

        public boolean isClean() {
            return missing.isEmpty() && unexpected.isEmpty();
        }

        /** Message d'exploitation, vide si aucun ecart. */
        public String report() {
            if (isClean()) {
                return "";
            }
            StringBuilder sb = new StringBuilder("Ecart de provisionnement Keycloak.");
            if (!missing.isEmpty()) {
                sb.append("\n  Absents du royaume, attendus par la politique : ")
                  .append(new TreeSet<>(missing))
                  .append("\n    -> les operations correspondantes sont inaccessibles a tous, "
                          + "sans message d'erreur.");
            }
            if (!unexpected.isEmpty()) {
                sb.append("\n  Presents dans le royaume, cites par aucune regle : ")
                  .append(new TreeSet<>(unexpected))
                  .append("\n    -> attribuables et sans effet : ils font croire a un droit "
                          + "qui n'existe pas.");
            }
            return sb.toString();
        }
    }

    /** Roles references par les postes mais inconnus de la politique : incoherence de catalogue. */
    public static Set<String> profilesReferencingUnknownRoles() {
        Set<String> declared = RoleCatalogue.declared();
        Set<String> unknown = new LinkedHashSet<>();
        for (JobProfile profile : JobProfile.values()) {
            profile.roles().stream().filter(role -> !declared.contains(role)).forEach(unknown::add);
        }
        return unknown;
    }

    /**
     * Point d'entree d'exploitation : engendre le fichier d'import partiel.
     *
     * <p>Appele par le pipeline de deploiement, en amont de la livraison applicative — les roles
     * doivent exister avant que la politique qui les cite ne soit en service.
     *
     * <pre>java -cp core-banking.jar io.corebanking.security.KeycloakProvisioning core-banking</pre>
     */
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage : KeycloakProvisioning <client-backend>");
            System.exit(2);
            return;
        }
        System.out.print(partialImport(args[0]));
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
