package io.corebanking.product;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Famille de produit : ce qu'un type de produit doit porter pour etre exploitable.
 *
 * <h2>Le defaut que cette classe ferme</h2>
 *
 * <p>Le parametrage etait jusqu'ici un sac de couples cle/valeur, type a la lecture. Un produit de
 * credit sans compte de creances rattachees s'activait sans rien dire ; l'erreur ne se decouvrait
 * qu'au premier traitement de fin de journee qui en avait besoin — c'est-a-dire la nuit, sur une
 * etape bloquante, avec un arrete a reprendre. Le commentaire de la migration du parametrage
 * l'annoncait pourtant : « un parametre absent est une erreur de deploiement du parametrage, pas
 * une exception au milieu du TFJ. » Il etait ecrit, il n'etait pas tenu.
 *
 * <p>La famille est le contrat manquant. Elle declare ce qui est exige, ce qui est admis, et ce qui
 * devient exige <b>en fonction d'un autre parametre</b>. {@link #validate} s'execute a l'activation
 * d'une version : le refus intervient au deploiement, devant celui qui parametre, et non la nuit
 * devant celui qui exploite.
 *
 * <h2>Pourquoi les parametres inconnus sont refuses</h2>
 *
 * <p>Rien n'empechait un produit d'epargne de porter {@code loan.penalty_rate}. Le parametre
 * n'etait jamais lu : il donnait a son auteur la certitude d'avoir parametre une penalite qui ne
 * s'appliquerait jamais. Un parametre non declare par la famille est donc refuse, et c'est le seul
 * moyen de distinguer une valeur inutile d'une valeur mal nommee.
 *
 * @param requireOneOf exigences alternatives : au moins un des elements doit etre present. Une
 *                     entree prefixee {@code tier:} designe un bareme par tranches et non un
 *                     parametre.
 * @param groups       blocs repetes, indexes par une liste declaree ailleurs — les commissions,
 *                     dont le nombre varie d'un produit a l'autre
 */
public record ProductFamily(
    String code,
    String label,
    Set<String> required,
    Set<String> optional,
    List<OneOf> requireOneOf,
    List<Condition> conditions,
    List<Group> groups) {

    /** Prefixe designant un bareme par tranches plutot qu'un parametre. */
    public static final String TIER = "tier:";

    /** Marqueur remplace par le code de l'element dans un bloc repete. */
    public static final String PLACEHOLDER = "{code}";

    public ProductFamily {
        Objects.requireNonNull(code, "code");
        required = Set.copyOf(required == null ? Set.of() : required);
        optional = Set.copyOf(optional == null ? Set.of() : optional);
        requireOneOf = List.copyOf(requireOneOf == null ? List.of() : requireOneOf);
        conditions = List.copyOf(conditions == null ? List.of() : conditions);
        groups = List.copyOf(groups == null ? List.of() : groups);
    }

    /**
     * Exigence alternative.
     *
     * @param because consequence de l'absence, restituee telle quelle a celui qui parametre
     */
    public record OneOf(List<String> of, String because) {
        public OneOf {
            of = List.copyOf(of);
        }
    }

    /**
     * Exigence conditionnelle : ce qu'un parametre rend obligatoire.
     *
     * @param when         parametre examine
     * @param fallback     valeur prise par le parametre lorsqu'il est absent, telle que le code
     *                     appelant la retient. Sans elle, une exigence attachee a la valeur par
     *                     defaut ne se declencherait jamais — et c'est precisement le cas le plus
     *                     frequent, puisqu'un parametre absent est un parametre qu'on a oublie.
     * @param in           valeurs declenchantes ; exclusif de {@code presence}
     * @param presence     vrai lorsque la seule presence du parametre declenche l'exigence
     * @param requireTier  bareme par tranches alors exige
     */
    public record Condition(String when, String fallback, Set<String> in, boolean presence,
                            Set<String> require, String requireTier, String because) {

        public Condition {
            Objects.requireNonNull(when, "when");
            in = Set.copyOf(in == null ? Set.of() : in);
            require = Set.copyOf(require == null ? Set.of() : require);
        }

        boolean triggeredBy(Map<String, String> parameters) {
            String value = parameters.get(when);
            if (presence) {
                return value != null;
            }
            // Parametre absent et sans valeur par defaut declaree : la condition ne se declenche
            // pas. Interroger l'ensemble avec une valeur nulle leverait une exception — les
            // ensembles immuables refusent le null — et le controle tomberait sur le produit le
            // plus banal, celui qui ne parametre rien.
            String effective = value == null ? fallback : value;
            return effective != null && in.contains(effective);
        }

        Condition substitute(String element) {
            return new Condition(
                ProductFamily.substitute(when, element), fallback, in, presence,
                substituteAll(require, element),
                requireTier == null ? null : ProductFamily.substitute(requireTier, element),
                because);
        }
    }

    /**
     * Bloc repete : un jeu de parametres par element d'une liste.
     *
     * <p>Les commissions d'un produit sont declarees par {@code fee.codes} et chacune decrite par
     * ses propres parametres. Une commission citee dans la liste mais sans compte de produit est la
     * faute de parametrage type : elle se percevrait au premier arrete, et l'ecriture n'aurait
     * nulle part ou aller.
     *
     * @param listParameter parametre portant la liste des elements, separes par des virgules
     */
    public record Group(String listParameter, Set<String> required, Set<String> optional,
                        List<Condition> conditions) {

        public Group {
            Objects.requireNonNull(listParameter, "listParameter");
            required = Set.copyOf(required == null ? Set.of() : required);
            optional = Set.copyOf(optional == null ? Set.of() : optional);
            conditions = List.copyOf(conditions == null ? List.of() : conditions);
        }

        List<String> elementsIn(Map<String, String> parameters) {
            String declared = parameters.get(listParameter);
            if (declared == null || declared.isBlank()) {
                return List.of();
            }
            List<String> elements = new ArrayList<>();
            for (String element : declared.split(",")) {
                String trimmed = element.trim();
                if (!trimmed.isEmpty()) {
                    elements.add(trimmed);
                }
            }
            return elements;
        }
    }

    // ------------------------------------------------------------------ validation

    /**
     * Confronte un parametrage a la famille.
     *
     * <p>Tous les manques sont collectes avant d'echouer. S'arreter au premier obligerait celui qui
     * parametre a redeployer autant de fois qu'il manque de lignes, et il finirait par desactiver
     * le controle.
     *
     * @param tierPurposes discriminants des baremes par tranches portes par la version
     */
    public void validate(String productCode, Map<String, String> parameters,
                         Set<String> tierPurposes) {
        List<String> problems = new ArrayList<>();
        Set<String> known = new LinkedHashSet<>(required);
        known.addAll(optional);

        for (String name : required) {
            if (!parameters.containsKey(name)) {
                problems.add("parametre obligatoire absent : " + name);
            }
        }
        for (OneOf alternative : requireOneOf) {
            alternative.of().stream().filter(entry -> !entry.startsWith(TIER)).forEach(known::add);
            if (alternative.of().stream().noneMatch(entry -> satisfied(entry, parameters,
                                                                      tierPurposes))) {
                problems.add("aucun de " + alternative.of() + " n'est renseigne — "
                             + alternative.because());
            }
        }
        checkConditions(conditions, parameters, tierPurposes, known, problems);

        for (Group group : groups) {
            known.add(group.listParameter());
            for (String element : group.elementsIn(parameters)) {
                for (String name : group.required()) {
                    String resolved = substitute(name, element);
                    known.add(resolved);
                    if (!parameters.containsKey(resolved)) {
                        problems.add("parametre obligatoire absent : " + resolved);
                    }
                }
                known.addAll(substituteAll(group.optional(), element));
                List<Condition> resolved = group.conditions().stream()
                    .map(condition -> condition.substitute(element)).toList();
                checkConditions(resolved, parameters, tierPurposes, known, problems);
            }
        }

        for (String name : parameters.keySet()) {
            if (!known.contains(name)) {
                problems.add("parametre inconnu de la famille " + code + " : " + name);
            }
        }
        if (!problems.isEmpty()) {
            throw new IncompleteProductException(productCode, code, problems);
        }
    }

    private void checkConditions(List<Condition> toCheck, Map<String, String> parameters,
                                 Set<String> tierPurposes, Set<String> known,
                                 List<String> problems) {
        for (Condition condition : toCheck) {
            known.add(condition.when());
            known.addAll(condition.require());
            if (!condition.triggeredBy(parameters)) {
                continue;
            }
            for (String name : condition.require()) {
                if (!parameters.containsKey(name)) {
                    problems.add(name + " est obligatoire des lors que " + condition.when()
                                 + " vaut " + value(condition, parameters) + " — "
                                 + condition.because());
                }
            }
            String tier = condition.requireTier();
            if (tier != null && !tierPurposes.contains(tier)) {
                problems.add("aucun bareme par tranches " + tier
                             + " alors que " + condition.when() + " vaut "
                             + value(condition, parameters) + " — " + condition.because());
            }
        }
    }

    private static String value(Condition condition, Map<String, String> parameters) {
        String raw = parameters.get(condition.when());
        return raw == null ? condition.fallback() + " par defaut" : raw;
    }

    private static boolean satisfied(String entry, Map<String, String> parameters,
                                     Set<String> tierPurposes) {
        return entry.startsWith(TIER) ? tierPurposes.contains(entry.substring(TIER.length()))
                                      : parameters.containsKey(entry);
    }

    static String substitute(String name, String element) {
        return name.replace(PLACEHOLDER, element);
    }

    private static Set<String> substituteAll(Set<String> names, String element) {
        Set<String> resolved = new LinkedHashSet<>(names.size());
        for (String name : names) {
            resolved.add(substitute(name, element));
        }
        return resolved;
    }

    /** Parametrage refuse au deploiement, avec la liste complete de ce qui manque. */
    public static class IncompleteProductException extends RuntimeException {

        private final List<String> problems;

        public IncompleteProductException(String productCode, String family,
                                          List<String> problems) {
            super("Produit " + productCode + " (famille " + family + ") : "
                  + String.join(" | ", problems)
                  + ". Une version activee sans ces elements echouerait au premier traitement qui "
                  + "les demande, de nuit et sur une etape bloquante.");
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }
}
