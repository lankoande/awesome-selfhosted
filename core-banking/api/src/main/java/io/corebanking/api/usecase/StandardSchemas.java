package io.corebanking.api.usecase;

import io.corebanking.deposits.OperationSchemas;
import io.corebanking.fee.service.FeeSchemas;
import io.corebanking.kernel.json.Json;
import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.loan.service.LoanSchemas;
import io.corebanking.product.SchemaCatalog;
import io.corebanking.schema.AccountRef;
import io.corebanking.schema.EventTemplate;
import io.corebanking.schema.TemplateLine;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Le catalogue de ce que le socle comptabilise, et de ce qui se parametre.
 *
 * <h2>Le piege que ce catalogue ferme</h2>
 *
 * <p>L'API laissait rediger puis activer un schema sous <b>n'importe quel code</b> et pour
 * n'importe quel type d'evenement. Or un seul schema est aujourd'hui resolu depuis le catalogue :
 * celui de la commission, quand une commission nomme un code autre que
 * {@link FeeSchemas#STANDARD_CODE}. Tout le reste — guichet, virements, moyens de paiement,
 * credit — est construit en code.
 *
 * <p>Un schema redige sous {@code LOAN_STANDARD} passait donc la validation, s'activait a deux, et
 * <b>n'etait lu par personne</b>. Pire que sans effet : celui qui l'avait redige croyait
 * l'imputation changee. L'ecart se serait vu au premier rapprochement, des mois plus tard, sans
 * qu'on sache le relier a ce parametrage-la.
 *
 * <p>D'ou ce catalogue, et les deux choses qu'il permet :
 *
 * <ul>
 *   <li><b>montrer</b> les schemas du socle — un comptable a le droit de savoir ce que la banque
 *       impute sur un retrait, et il ne peut pas le lire dans le code ;</li>
 *   <li><b>refuser</b> la redaction d'un schema pour un evenement que personne ne resout, avec la
 *       raison. {@link #requireOverridable} est appele avant toute ecriture.</li>
 * </ul>
 *
 * <p>Les libelles viennent d'une ressource ; le reste — variables libres, roles de compte, lignes —
 * est <b>deduit des modeles eux-memes</b>. Une liste tenue a la main a cote du code finirait par
 * ne plus le decrire.
 */
public final class StandardSchemas {

    private static final String RESOURCE = "/accounting/events.json";

    /** Origine d'un schema : construit par le socle, ou resolu depuis le parametrage. */
    public static final String SOURCE_SOCLE = "SOCLE";
    public static final String SOURCE_PARAMETRABLE = "PARAMETRABLE";

    private static final Map<String, String> MODULES = new LinkedHashMap<>();
    private static final Map<String, Label> LABELS = load();

    private record Label(String module, String text) {}

    private StandardSchemas() {}

    /**
     * Un evenement du catalogue.
     *
     * @param source      {@link #SOURCE_SOCLE} ou {@link #SOURCE_PARAMETRABLE}
     * @param variables   grandeurs que le module fournit au schema
     * @param roles       comptes que le schema designe par role, a rattacher au parametrage
     * @param schemaCode  code sous lequel un schema de remplacement doit etre redige, nul si
     *                    l'evenement ne se parametre pas
     */
    public record Event(String eventType, String label, String module, String moduleLabel,
                        String source, String schemaCode, List<String> variables,
                        List<String> roles, List<SchemaCatalog.DerivationView> derivations,
                        List<SchemaCatalog.LineView> lines) {}

    /** Le catalogue pour une devise : l'echelle d'arrondi change les expressions. */
    public static List<Event> all(CurrencyRef currency) {
        List<Event> events = new ArrayList<>();
        collect(events, OperationSchemas.all(currency), null);
        collect(events, LoanSchemas.all(currency), null);
        collect(events, FeeSchemas.all(currency), FeeSchemas.STANDARD_CODE);
        return List.copyOf(events);
    }

    /** Les modeles du socle, tous modules confondus, pour une devise. */
    public static Map<String, EventTemplate> templates(CurrencyRef currency) {
        Map<String, EventTemplate> merged = new LinkedHashMap<>();
        merged.putAll(OperationSchemas.all(currency));
        merged.putAll(LoanSchemas.all(currency));
        merged.putAll(FeeSchemas.all(currency));
        return java.util.Collections.unmodifiableMap(merged);
    }

    /** Vrai si un schema redige pour cet evenement serait effectivement resolu. */
    public static boolean isOverridable(String eventType) {
        return FeeSchemas.EVENT_FEE_CHARGE.equals(eventType);
    }

    /**
     * Refuse un evenement que personne ne resoudrait, avec la raison.
     *
     * <p>Le refus arrive a la redaction, pas a l'activation : un brouillon mort qui dort en base
     * finit par etre active un soir d'arrete par quelqu'un qui ne sait pas qu'il ne sert a rien.
     */
    public static void requireOverridable(Set<String> eventTypes) {
        for (String eventType : eventTypes) {
            if (isOverridable(eventType)) {
                continue;
            }
            Label known = LABELS.get(eventType);
            if (known == null) {
                throw new IllegalArgumentException(
                    "Evenement « " + eventType + " » inconnu du socle : aucun module ne le publie, "
                    + "le schema ne serait jamais lu. Evenements parametrables : "
                    + overridable() + ".");
            }
            throw new IllegalArgumentException(
                "L'evenement « " + eventType + " » (" + known.text() + ") est impute par le socle "
                + "lui-meme : un schema redige pour lui ne serait jamais resolu. Il se lit dans le "
                + "catalogue des schemas du socle. Evenements parametrables : " + overridable()
                + ".");
        }
    }

    /** Les evenements qu'un schema de parametrage peut aujourd'hui remplacer. */
    public static List<String> overridable() {
        return LABELS.keySet().stream().filter(StandardSchemas::isOverridable).toList();
    }

    /** Les libelles declares, pour le test d'accord avec les registres des modules. */
    public static Set<String> declared() {
        return LABELS.keySet();
    }

    // ------------------------------------------------------------------ interne

    private static void collect(List<Event> events, Map<String, EventTemplate> templates,
                                String schemaCode) {
        templates.forEach((eventType, template) -> {
            Label label = LABELS.get(eventType);
            if (label == null) {
                throw new IllegalStateException(
                    "Evenement " + eventType + " sans libelle dans " + RESOURCE);
            }
            List<SchemaCatalog.DerivationView> derivations = new ArrayList<>();
            template.derivations().forEach((name, expression) ->
                derivations.add(new SchemaCatalog.DerivationView(name, expression.source())));

            List<SchemaCatalog.LineView> lines = new ArrayList<>(template.lines().size());
            Set<String> roles = new LinkedHashSet<>();
            for (TemplateLine line : template.lines()) {
                lines.add(new SchemaCatalog.LineView(
                    line.account().toString(), line.direction().name(), line.amount().source(),
                    line.condition() == null ? null : line.condition().source(), line.label()));
                if (line.account().kind() == AccountRef.Kind.PARAMETER
                    || line.account().kind() == AccountRef.Kind.RESOLVER) {
                    roles.add(line.account().value());
                }
            }
            boolean overridable = isOverridable(eventType);
            events.add(new Event(eventType, label.text(), label.module(),
                                 MODULES.getOrDefault(label.module(), label.module()),
                                 overridable ? SOURCE_PARAMETRABLE : SOURCE_SOCLE,
                                 overridable ? schemaCode : null,
                                 List.copyOf(template.freeVariables()), List.copyOf(roles),
                                 List.copyOf(derivations), List.copyOf(lines)));
        });
    }

    private static Map<String, Label> load() {
        Map<String, Object> document = Json.parseObject(read());
        for (Object entry : list(document.get("modules"), "modules")) {
            Map<String, Object> module = object(entry);
            MODULES.put(text(module, "code"), text(module, "label"));
        }
        Map<String, Label> labels = new LinkedHashMap<>();
        for (Object entry : list(document.get("events"), "events")) {
            Map<String, Object> event = object(entry);
            String module = text(event, "module");
            if (!MODULES.containsKey(module)) {
                throw new IllegalStateException(
                    "Module " + module + " non declare dans " + RESOURCE);
            }
            if (labels.put(text(event, "eventType"),
                           new Label(module, text(event, "label"))) != null) {
                throw new IllegalStateException(
                    "Evenement declare deux fois dans " + RESOURCE + " : "
                    + text(event, "eventType"));
            }
        }
        return Map.copyOf(labels);
    }

    private static String read() {
        try (InputStream in = StandardSchemas.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Ressource absente : " + RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Lecture de " + RESOURCE, e);
        }
    }

    private static List<Object> list(Object raw, String field) {
        if (!(raw instanceof List<?> values) || values.isEmpty()) {
            throw new IllegalStateException(RESOURCE + " : « " + field + " » absent ou vide");
        }
        return List.copyOf(values);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalStateException(RESOURCE + " : objet attendu");
        }
        return (Map<String, Object>) map;
    }

    private static String text(Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalStateException(RESOURCE + " : champ « " + field + " » absent");
        }
        return string;
    }
}
