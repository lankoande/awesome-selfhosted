package io.corebanking.security;

import static io.corebanking.security.Roles.ACCOUNTANT;
import static io.corebanking.security.Roles.AUDITOR;
import static io.corebanking.security.Roles.BRANCH_MANAGER;
import static io.corebanking.security.Roles.CUSTOMER_OFFICER;
import static io.corebanking.security.Roles.OPERATOR;
import static io.corebanking.security.Roles.PRODUCT_MANAGER;
import static io.corebanking.security.Roles.RISK_OFFICER;
import static io.corebanking.security.Roles.TELLER;

import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Politique d'habilitation de l'application. <b>Source unique de verite.</b>
 *
 * <h2>Pourquoi ici, et nulle part ailleurs</h2>
 *
 * <p>Aucune annotation d'habilitation n'existe dans le code — ni {@code @PreAuthorize}, ni
 * {@code @Secured}, ni {@code @RolesAllowed}. Une regle ArchUnit casse la compilation si l'une
 * d'elles reapparait. Trois raisons :
 *
 * <ul>
 *   <li><b>Auditabilite.</b> Un controleur interne, un commissaire aux comptes ou un inspecteur
 *       demande la matrice des habilitations. Ici, elle s'imprime — {@link #describe()}. Dispersee
 *       en annotations, elle se reconstitue a la main, et cette reconstitution est fausse des la
 *       livraison suivante.</li>
 *   <li><b>Coherence.</b> Deux operations equivalentes finissent toujours par diverger quand leurs
 *       regles sont ecrites a deux endroits, a six mois d'intervalle, par deux personnes.</li>
 *   <li><b>Revue.</b> Un changement d'habilitation apparait dans un diff d'un seul fichier, que
 *       l'on peut exiger de faire relire par le controle interne.</li>
 * </ul>
 *
 * <h2>Le risque que cette centralisation doit neutraliser</h2>
 *
 * <p>Une annotation oubliee laisse une methode ouverte. Une table centrale incomplete fait
 * exactement la meme chose, en moins visible : la methode n'apparait nulle part, donc personne ne
 * la cherche. Deux garde-fous repondent a ce risque, et ils sont la raison pour laquelle cette
 * approche est plus sure que les annotations, et non l'inverse :
 *
 * <ol>
 *   <li>{@link Operation} est une enumeration. Le bloc statique ci-dessous <b>refuse de charger la
 *       classe</b> si une seule valeur n'a pas de regle : l'application ne demarre pas. Une
 *       operation ajoutee sans habilitation ne peut donc pas atteindre la production.</li>
 *   <li>Le refus est le defaut. Une operation absente de la table serait refusee, jamais autorisee.</li>
 * </ol>
 *
 * <h2>Ce que cette table ne contient pas</h2>
 *
 * <p>Aucune URL, aucun nom de methode, aucun detail de transport. La politique porte sur des
 * operations metier. Le point d'entree HTTP se contente d'associer une requete a une operation ;
 * changer une route ne change aucune habilitation.
 */
public final class SecurityConfig {

    private static final CurrencyRef XOF = io.corebanking.kernel.money.Currencies.XOF;

    private static final Map<Operation, AccessRule> POLICY = buildPolicy();

    static {
        // Verification d'exhaustivite au chargement de la classe : une operation sans regle
        // empeche l'application de demarrer, plutot que de creer un trou silencieux.
        List<Operation> orphelines = java.util.Arrays.stream(Operation.values())
            .filter(operation -> !POLICY.containsKey(operation))
            .toList();
        if (!orphelines.isEmpty()) {
            throw new ExceptionInInitializerError(
                "Operations sans regle d'habilitation : " + orphelines
                + ". Toute operation doit figurer dans SecurityConfig avant d'etre exposee.");
        }
    }

    private SecurityConfig() {}

    private static Map<Operation, AccessRule> buildPolicy() {
        Map<Operation, AccessRule> policy = new EnumMap<>(Operation.class);

        // ------------------------------------------------------------------ consultation
        // Tracees en lecture : un agent habilite qui consulte des comptes sans motif est le cas de
        // fraude interne le plus frequent, et il est invisible d'un journal limite aux modifications.
        policy.put(Operation.ACCOUNT_BALANCE_READ,
            AccessRule.allow(TELLER, CUSTOMER_OFFICER, BRANCH_MANAGER, ACCOUNTANT, AUDITOR)
                .within(Scope.OWN_BRANCH).tracedOnRead().build());

        policy.put(Operation.ACCOUNT_JOURNAL_READ,
            AccessRule.allow(CUSTOMER_OFFICER, BRANCH_MANAGER, ACCOUNTANT, AUDITOR)
                .within(Scope.OWN_ENTITY).tracedOnRead().build());

        policy.put(Operation.PARTY_READ,
            AccessRule.allow(TELLER, CUSTOMER_OFFICER, BRANCH_MANAGER, AUDITOR)
                .within(Scope.OWN_BRANCH).tracedOnRead().build());

        // ------------------------------------------------------------------ operations
        // Les plafonds vivent ici, pas dans le jeton : un attribut Keycloak mal renseigne ne doit
        // pas pouvoir elever un plafond sans passer par une revue de code.
        policy.put(Operation.CASH_OPERATION,
            AccessRule.allow(TELLER, BRANCH_MANAGER)
                .within(Scope.OWN_BRANCH)
                .upTo(Map.of(TELLER,         Money.of("2000000", XOF),
                             BRANCH_MANAGER, Money.of("25000000", XOF)))
                .build());

        policy.put(Operation.TRANSFER,
            AccessRule.allow(TELLER, CUSTOMER_OFFICER, BRANCH_MANAGER)
                .within(Scope.OWN_ENTITY)
                .upTo(Map.of(TELLER,           Money.of("5000000", XOF),
                             CUSTOMER_OFFICER, Money.of("50000000", XOF),
                             BRANCH_MANAGER,   Money.of("100000000", XOF)))
                .build());

        // Une correction se valide par un tiers : c'est le geste par lequel une fraude se dissimule.
        policy.put(Operation.ENTRY_REVERSAL,
            AccessRule.allow(BRANCH_MANAGER, ACCOUNTANT)
                .within(Scope.OWN_ENTITY).requiringSecondPerson().build());

        policy.put(Operation.ACCOUNT_HOLD,
            AccessRule.allow(BRANCH_MANAGER, RISK_OFFICER)
                .within(Scope.OWN_ENTITY).requiringSecondPerson().build());

        // ------------------------------------------------------------------ referentiel
        policy.put(Operation.ACCOUNT_OPEN,
            AccessRule.allow(CUSTOMER_OFFICER, BRANCH_MANAGER)
                .within(Scope.OWN_BRANCH).requiringSecondPerson().build());

        policy.put(Operation.ACCOUNT_CLOSE,
            AccessRule.allow(BRANCH_MANAGER)
                .within(Scope.OWN_BRANCH).requiringSecondPerson().build());

        // ------------------------------------------------------------------ parametrage
        // Un parametrage produit des montants sur des comptes clients : meme regime qu'une operation.
        policy.put(Operation.PRODUCT_DRAFT,
            AccessRule.allow(PRODUCT_MANAGER).within(Scope.OWN_ENTITY).build());

        policy.put(Operation.PRODUCT_ACTIVATE,
            AccessRule.allow(PRODUCT_MANAGER, RISK_OFFICER)
                .within(Scope.OWN_ENTITY).requiringSecondPerson().build());

        // ------------------------------------------------------------------ exploitation
        policy.put(Operation.TFJ_RUN,
            AccessRule.allow(OPERATOR).within(Scope.OWN_ENTITY).build());

        // Annuler un TFJ contre-passe des millions d'ecritures : double validation, sans exception.
        policy.put(Operation.TFJ_CANCEL,
            AccessRule.allow(OPERATOR, ACCOUNTANT)
                .within(Scope.OWN_ENTITY).requiringSecondPerson().build());

        policy.put(Operation.PERIOD_REOPEN,
            AccessRule.allow(ACCOUNTANT)
                .within(Scope.OWN_ENTITY).requiringSecondPerson().build());

        // ------------------------------------------------------------------ audit
        // Seul acces legitimement transverse aux entites, et le seul.
        policy.put(Operation.AUDIT_READ,
            AccessRule.allow(AUDITOR).within(Scope.ANY_ENTITY).tracedOnRead().build());

        return Map.copyOf(policy);
    }

    /**
     * Regle applicable a une operation. Ne renvoie jamais {@code null} : l'exhaustivite est
     * garantie au chargement de la classe.
     */
    public static AccessRule ruleFor(Operation operation) {
        AccessRule rule = POLICY.get(operation);
        return rule == null ? AccessRule.denyAll() : rule;
    }

    public static Map<Operation, AccessRule> policy() {
        return POLICY;
    }

    /**
     * Matrice des habilitations sous forme lisible. C'est l'artefact remis au controle interne et a
     * l'inspection ; il est genere depuis la politique reellement appliquee, et ne peut donc pas
     * diverger d'elle.
     */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("Operation | Roles | Perimetre | Plafonds | Double validation | Lecture tracee\n");
        sb.append("---|---|---|---|---|---\n");
        for (Operation operation : Operation.values()) {
            AccessRule rule = POLICY.get(operation);
            sb.append(operation).append(" | ")
              .append(rule.roles().stream().sorted().toList()).append(" | ")
              .append(rule.scope()).append(" | ")
              .append(rule.ceilings().isEmpty() ? "-" : new java.util.TreeMap<>(rule.ceilings()))
              .append(" | ")
              .append(rule.dualControl() ? "oui" : "non").append(" | ")
              .append(rule.auditEvenOnSuccess() ? "oui" : "non").append('\n');
        }
        return sb.toString();
    }
}
