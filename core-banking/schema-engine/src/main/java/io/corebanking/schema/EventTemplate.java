package io.corebanking.schema;

import io.corebanking.schema.expr.EvaluationContext;
import io.corebanking.schema.expr.Expression;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Traduction d'un evenement metier en jeu d'ecritures.
 *
 * <h2>Les derivations, et pourquoi elles sont indispensables</h2>
 *
 * <p>Un schema de commission manipule des grandeurs liees : {@code total = net + tva}. Si ces trois
 * grandeurs arrivaient comme des variables independantes, deux consequences :
 *
 * <ul>
 *   <li>rien ne garantirait la coherence a l'execution — un appelant pourrait fournir un total qui
 *       ne correspond pas a la somme de ses composantes, et l'ecriture serait desequilibree ;</li>
 *   <li>la validation par tirage serait impossible : des valeurs tirees independamment ne
 *       respecteraient jamais l'identite, et tout schema, meme correct, semblerait faux.</li>
 * </ul>
 *
 * <p>Les derivations rendent donc le schema <b>autosuffisant</b> : seules les grandeurs libres sont
 * fournies, les autres sont calculees, et la coherence est structurelle.
 *
 * @param derivations variables calculees, dans l'ordre : chacune peut utiliser les precedentes
 */
public record EventTemplate(
    String eventType,
    LinkedHashMap<String, Expression> derivations,
    List<TemplateLine> lines) {

    public EventTemplate {
        Objects.requireNonNull(eventType, "eventType");
        derivations = new LinkedHashMap<>(
            derivations == null ? Map.of() : derivations);
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        if (lines.size() < 2) {
            throw new IllegalArgumentException(
                "Le schema de l'evenement " + eventType + " compte " + lines.size()
                + " ligne(s) : la partie double en exige au moins deux.");
        }
    }

    /** Contexte enrichi des variables derivees, evaluees dans l'ordre de declaration. */
    public EvaluationContext derive(EvaluationContext input) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        input.names().forEach(name -> values.put(name, input.require(name)));

        for (Map.Entry<String, Expression> derivation : derivations.entrySet()) {
            EvaluationContext partial = EvaluationContext.of(values);
            values.put(derivation.getKey(), derivation.getValue().asNumber(partial));
        }
        return EvaluationContext.of(values);
    }

    /** Variables que l'appelant doit fournir : referencees, et non calculees par le schema. */
    public Set<String> freeVariables() {
        Set<String> referenced = new LinkedHashSet<>();
        derivations.values().forEach(expression -> expression.collectVariables(referenced));
        for (TemplateLine line : lines) {
            line.amount().collectVariables(referenced);
            if (line.condition() != null) {
                line.condition().collectVariables(referenced);
            }
        }
        referenced.removeAll(derivations.keySet());
        return referenced;
    }

    public static Builder of(String eventType) {
        return new Builder(eventType);
    }

    public static final class Builder {
        private final String eventType;
        private final LinkedHashMap<String, Expression> derivations = new LinkedHashMap<>();
        private final List<TemplateLine> lines = new ArrayList<>();

        private Builder(String eventType) {
            this.eventType = eventType;
        }

        public Builder derive(String name, String expression) {
            derivations.put(name, io.corebanking.schema.expr.Expressions.parse(expression));
            return this;
        }

        public Builder line(TemplateLine line) {
            lines.add(line);
            return this;
        }

        public EventTemplate build() {
            return new EventTemplate(eventType, derivations, lines);
        }
    }
}
