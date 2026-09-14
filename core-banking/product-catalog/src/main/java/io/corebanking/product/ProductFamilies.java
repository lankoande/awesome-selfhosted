package io.corebanking.product;

import io.corebanking.kernel.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Catalogue des familles de produit, charge depuis {@code resources/product/families.json}.
 *
 * <h2>Pourquoi un fichier, et pourquoi au chargement des classes</h2>
 *
 * <p>Meme dispositif que le catalogue des roles : une ressource versionnee avec le code, revue
 * comme lui, lue une fois et validee avant que quoi que ce soit ne s'execute. Un fichier incoherent
 * — condition sans declencheur, parametre a la fois obligatoire et facultatif, bloc repete sans
 * marqueur — fait echouer le chargement, pas le premier deploiement de parametrage.
 *
 * <p>Le fichier vit dans {@code product-catalog} alors que les noms de parametres sont declares
 * dans les modules de service, qui en dependent. L'accord entre les deux est verifie par un test
 * de chaque module de service : {@link #declaredParameters} rend ce que la famille admet, et le
 * test confronte les constantes du module a cette liste. Un parametre lu par le code mais absent
 * du fichier serait refuse a l'activation ; un parametre declare et lu par personne serait un
 * parametre mort.
 */
public final class ProductFamilies {

    private static final String RESOURCE = "/product/families.json";

    private static final Map<String, ProductFamily> BY_CODE = load();

    private ProductFamilies() {}

    // ------------------------------------------------------------------ acces

    /** Famille d'un type de produit, exigee. */
    public static ProductFamily require(String type) {
        ProductFamily family = BY_CODE.get(type);
        if (family == null) {
            throw new UnknownFamilyException(type, BY_CODE.keySet());
        }
        return family;
    }

    public static boolean isKnown(String type) {
        return BY_CODE.containsKey(type);
    }

    public static Set<String> codes() {
        return BY_CODE.keySet();
    }

    /**
     * Tout ce qu'une famille admet, marqueurs de blocs repetes compris.
     *
     * <p>Sert aux tests d'accord des modules de service : une constante de parametre absente d'ici
     * designe un parametre que le code lit et que l'activation refusera.
     */
    public static Set<String> declaredParameters(String type) {
        ProductFamily family = require(type);
        Set<String> names = new TreeSet<>(family.required());
        names.addAll(family.optional());
        for (ProductFamily.OneOf alternative : family.requireOneOf()) {
            alternative.of().stream()
                .filter(entry -> !entry.startsWith(ProductFamily.TIER))
                .forEach(names::add);
        }
        collectConditions(family.conditions(), names);
        for (ProductFamily.Group group : family.groups()) {
            names.add(group.listParameter());
            names.addAll(group.required());
            names.addAll(group.optional());
            collectConditions(group.conditions(), names);
        }
        return names;
    }

    private static void collectConditions(List<ProductFamily.Condition> conditions,
                                          Set<String> names) {
        for (ProductFamily.Condition condition : conditions) {
            names.add(condition.when());
            names.addAll(condition.require());
        }
    }

    // ------------------------------------------------------------------ chargement

    private static Map<String, ProductFamily> load() {
        return parse(read());
    }

    /**
     * Analyse et valide le document.
     *
     * <p>Separee de la lecture de la ressource pour que les regles de coherence du fichier —
     * condition sans declencheur, parametre a la fois obligatoire et facultatif, marqueur hors bloc
     * repete — soient eprouvees sur des documents ecrits pour cela, et non seulement sur le seul
     * fichier livre.
     */
    static Map<String, ProductFamily> parse(String source) {
        Map<String, Object> document = Json.parseObject(source);
        Map<String, Block> blocks = blocks(document.get("blocks"));

        Object rawFamilies = document.get("families");
        if (!(rawFamilies instanceof List<?> list) || list.isEmpty()) {
            throw new CatalogueException("Le catalogue ne declare aucune famille de produit.");
        }
        Map<String, ProductFamily> byCode = new LinkedHashMap<>();
        for (Object entry : list) {
            ProductFamily family = family(object(entry, "famille"), blocks);
            if (byCode.put(family.code(), family) != null) {
                throw new CatalogueException("Famille declaree deux fois : " + family.code());
            }
        }
        return Map.copyOf(byCode);
    }

    private static String read() {
        try (InputStream in = ProductFamilies.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new CatalogueException(
                    "Catalogue des familles de produit introuvable : " + RESOURCE
                    + ". Sans lui, aucun parametrage ne peut etre valide avant activation.");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CatalogueException("Lecture de " + RESOURCE + " impossible : "
                                         + e.getMessage());
        }
    }

    /** Fragment reutilisable, agrege dans les familles qui l'incluent. */
    private record Block(Set<String> required, Set<String> optional,
                         List<ProductFamily.OneOf> requireOneOf,
                         List<ProductFamily.Condition> conditions,
                         List<ProductFamily.Group> groups) {}

    private static Map<String, Block> blocks(Object raw) {
        Map<String, Block> blocks = new LinkedHashMap<>();
        if (raw == null) {
            return blocks;
        }
        for (Map.Entry<String, Object> entry : object(raw, "blocs").entrySet()) {
            Map<String, Object> body = object(entry.getValue(), "bloc " + entry.getKey());
            blocks.put(entry.getKey(), new Block(
                names(body.get("required"), entry.getKey()),
                names(body.get("optional"), entry.getKey()),
                oneOfs(body.get("requireOneOf"), entry.getKey()),
                conditions(body.get("conditions"), entry.getKey(), false),
                groups(body.get("groups"), entry.getKey())));
        }
        return blocks;
    }

    private static ProductFamily family(Map<String, Object> raw, Map<String, Block> blocks) {
        String code = text(raw, "code");
        Set<String> required = new LinkedHashSet<>(names(raw.get("required"), code));
        Set<String> optional = new LinkedHashSet<>(names(raw.get("optional"), code));
        List<ProductFamily.OneOf> oneOfs = new ArrayList<>(oneOfs(raw.get("requireOneOf"), code));
        List<ProductFamily.Condition> conditions =
            new ArrayList<>(conditions(raw.get("conditions"), code, false));
        List<ProductFamily.Group> groups = new ArrayList<>(groups(raw.get("groups"), code));

        for (String included : names(raw.get("includes"), code)) {
            Block block = blocks.get(included);
            if (block == null) {
                throw new CatalogueException(
                    "Famille " + code + " : bloc inconnu « " + included + " ».");
            }
            required.addAll(block.required());
            optional.addAll(block.optional());
            oneOfs.addAll(block.requireOneOf());
            conditions.addAll(block.conditions());
            groups.addAll(block.groups());
        }

        required.forEach(name -> requireNoPlaceholder(name, "Famille " + code));
        optional.forEach(name -> requireNoPlaceholder(name, "Famille " + code));
        oneOfs.forEach(alternative -> alternative.of()
            .forEach(entry -> requireNoPlaceholder(entry, "Famille " + code)));

        Set<String> both = new TreeSet<>(required);
        both.retainAll(optional);
        if (!both.isEmpty()) {
            throw new CatalogueException(
                "Famille " + code + " : " + both + " a la fois obligatoire et facultatif.");
        }
        return new ProductFamily(code, text(raw, "label"), required, optional, oneOfs, conditions,
                                 groups);
    }

    private static List<ProductFamily.Group> groups(Object raw, String context) {
        if (raw == null) {
            return List.of();
        }
        List<ProductFamily.Group> groups = new ArrayList<>();
        for (Object entry : array(raw, context + " : groups")) {
            Map<String, Object> body = object(entry, context + " : groupe");
            String list = text(body, "listParameter");
            Set<String> required = names(body.get("required"), context);
            Set<String> optional = names(body.get("optional"), context);
            if (required.isEmpty() && optional.isEmpty()) {
                throw new CatalogueException(
                    context + " : le bloc repete « " + list + " » ne declare aucun parametre.");
            }
            List<ProductFamily.Condition> conditions =
                conditions(body.get("conditions"), context, true);
            for (String name : required) {
                requirePlaceholder(name, context);
            }
            for (String name : optional) {
                requirePlaceholder(name, context);
            }
            groups.add(new ProductFamily.Group(list, required, optional, conditions));
        }
        return groups;
    }

    private static List<ProductFamily.Condition> conditions(Object raw, String context,
                                                            boolean repeated) {
        if (raw == null) {
            return List.of();
        }
        List<ProductFamily.Condition> conditions = new ArrayList<>();
        for (Object entry : array(raw, context + " : conditions")) {
            Map<String, Object> body = object(entry, context + " : condition");
            String when = text(body, "when");
            Set<String> in = names(body.get("in"), context);
            boolean presence = Boolean.TRUE.equals(body.get("present"));
            if (in.isEmpty() == !presence) {
                throw new CatalogueException(
                    context + ", condition sur « " + when + " » : indiquer soit « in », soit "
                    + "« present », et un seul des deux — sinon elle ne se declenche jamais, "
                    + "ou toujours.");
            }
            Set<String> require = names(body.get("require"), context);
            String requireTier = optionalText(body, "requireTier");
            if (require.isEmpty() && requireTier == null) {
                throw new CatalogueException(
                    context + ", condition sur « " + when + " » : elle n'exige rien.");
            }
            String because = text(body, "because");
            if (!repeated) {
                requireNoPlaceholder(when, context);
                require.forEach(name -> requireNoPlaceholder(name, context));
            }
            conditions.add(new ProductFamily.Condition(when, optionalText(body, "fallback"), in,
                                                        presence, require, requireTier, because));
        }
        return conditions;
    }

    private static List<ProductFamily.OneOf> oneOfs(Object raw, String context) {
        if (raw == null) {
            return List.of();
        }
        List<ProductFamily.OneOf> alternatives = new ArrayList<>();
        for (Object entry : array(raw, context + " : requireOneOf")) {
            Map<String, Object> body = object(entry, context + " : alternative");
            List<String> of = List.copyOf(names(body.get("of"), context));
            if (of.size() < 2) {
                throw new CatalogueException(
                    context + " : une alternative a moins de deux termes n'est pas une "
                    + "alternative, c'est une exigence.");
            }
            alternatives.add(new ProductFamily.OneOf(of, text(body, "because")));
        }
        return alternatives;
    }

    private static void requirePlaceholder(String name, String context) {
        if (!name.contains(ProductFamily.PLACEHOLDER)) {
            throw new CatalogueException(
                context + " : « " + name + " » figure dans un bloc repete sans porter le "
                + "marqueur " + ProductFamily.PLACEHOLDER + ", il designerait le meme parametre "
                + "pour tous les elements.");
        }
    }

    private static void requireNoPlaceholder(String name, String context) {
        if (name.contains(ProductFamily.PLACEHOLDER)) {
            throw new CatalogueException(
                context + " : « " + name + " » porte le marqueur " + ProductFamily.PLACEHOLDER
                + " hors d'un bloc repete, rien ne le remplacerait.");
        }
    }

    // ------------------------------------------------------------------ lecture du document

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object raw, String context) {
        if (!(raw instanceof Map)) {
            throw new CatalogueException(context + " : objet attendu.");
        }
        return (Map<String, Object>) raw;
    }

    private static List<?> array(Object raw, String context) {
        if (!(raw instanceof List<?> list)) {
            throw new CatalogueException(context + " : tableau attendu.");
        }
        return list;
    }

    private static Set<String> names(Object raw, String context) {
        if (raw == null) {
            return new LinkedHashSet<>();
        }
        Set<String> names = new LinkedHashSet<>();
        for (Object entry : array(raw, context)) {
            if (!(entry instanceof String name) || name.isBlank()) {
                throw new CatalogueException(context + " : nom de parametre vide ou non textuel.");
            }
            if (!names.add(name)) {
                throw new CatalogueException(context + " : « " + name + " » cite deux fois.");
            }
        }
        return names;
    }

    private static String text(Map<String, Object> raw, String field) {
        Object value = raw.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new CatalogueException("Champ « " + field + " » absent ou vide.");
        }
        return text;
    }

    private static String optionalText(Map<String, Object> raw, String field) {
        Object value = raw.get(field);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    /** Type de produit absent du catalogue des familles. */
    public static class UnknownFamilyException extends RuntimeException {
        public UnknownFamilyException(String type, Set<String> known) {
            super("Famille de produit inconnue : « " + type + " ». Familles declarees : " + known
                  + ". Un type libre laisserait un produit sans contrat de parametrage, et ses "
                  + "manques ne se decouvriraient qu'au premier traitement qui les demande.");
        }
    }

    /** Catalogue des familles incoherent : le chargement echoue, l'application ne sert pas. */
    public static class CatalogueException extends RuntimeException {
        public CatalogueException(String detail) {
            super("Catalogue des familles de produit : " + detail);
        }
    }
}
