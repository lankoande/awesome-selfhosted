package io.corebanking.schema.expr;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Variables disponibles a l'evaluation d'une expression.
 *
 * <p>Une variable absente leve une erreur nommant la variable et les variables disponibles. La
 * substituer par zero serait pire que l'echec : une commission calculee sur une assiette nulle
 * s'impute sans bruit, equilibree et fausse.
 */
public final class EvaluationContext {

    private final Map<String, BigDecimal> variables;

    private EvaluationContext(Map<String, BigDecimal> variables) {
        this.variables = Map.copyOf(variables);
    }

    public static EvaluationContext of(Map<String, BigDecimal> variables) {
        return new EvaluationContext(Objects.requireNonNull(variables, "variables"));
    }

    public static Builder builder() {
        return new Builder();
    }

    public BigDecimal require(String name) {
        BigDecimal value = variables.get(name);
        if (value == null) {
            throw new ExpressionException(
                "Variable « " + name + " » absente du contexte. Disponibles : "
                + variables.keySet().stream().sorted().toList()
                + ". Aucune valeur par defaut n'est appliquee : une assiette nulle produirait une "
                + "ecriture equilibree et fausse.");
        }
        return value;
    }

    public boolean has(String name) {
        return variables.containsKey(name);
    }

    public Set<String> names() {
        return variables.keySet();
    }

    public static final class Builder {
        private final Map<String, BigDecimal> variables = new LinkedHashMap<>();

        public Builder put(String name, BigDecimal value) {
            variables.put(name, value);
            return this;
        }

        public Builder put(String name, String value) {
            return put(name, new BigDecimal(value));
        }

        public Builder put(String name, io.corebanking.kernel.money.Money value) {
            return put(name, value.amount());
        }

        public EvaluationContext build() {
            return new EvaluationContext(variables);
        }
    }
}
