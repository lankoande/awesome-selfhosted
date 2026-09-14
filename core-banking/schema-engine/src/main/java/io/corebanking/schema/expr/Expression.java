package io.corebanking.schema.expr;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Expression d'un schema comptable, compilee une fois et evaluee ensuite.
 *
 * <p>Le langage est volontairement minuscule : litteraux, variables, quatre operations,
 * parentheses, comparaisons, {@code and}/{@code or}/{@code not}, et une poignee de fonctions. Il
 * n'a ni variable affectable, ni boucle, ni appel externe.
 *
 * <p>Cette pauvrete est le sujet. Un moteur de regles expressif finit par devenir un langage de
 * programmation sans tests, sans revue et sans debogueur, dans lequel la logique de calcul des
 * commissions d'une banque est ecrite par des personnes qui n'ont jamais vu de compilateur. C'est le
 * principal facteur d'ingouvernabilite des core banking anciens. Ce qui releve d'un algorithme —
 * amortissement, provisionnement, decompte des jours — reste du code Java, teste et revu.
 */
public interface Expression {

    /** Resultat : {@link BigDecimal} ou {@link Boolean}. */
    Object evaluate(EvaluationContext context);

    /**
     * Texte d'origine de l'expression.
     *
     * <p>Conserve pour deux raisons : le schema est persiste sous sa forme source, et une
     * imputation doit rester explicable — restituer l'arbre syntaxique a un controleur interne
     * n'aurait aucun sens, restituer « round(base + base * taux_tva, 0) » en a un.
     */
    default String source() {
        return toString();
    }

    /** Variables referencees, utilisees pour valider un schema par tirage. */
    default Set<String> variables() {
        Set<String> names = new LinkedHashSet<>();
        collectVariables(names);
        return names;
    }

    void collectVariables(Set<String> into);

    default BigDecimal asNumber(EvaluationContext context) {
        Object value = evaluate(context);
        if (value instanceof BigDecimal number) {
            return number;
        }
        throw new ExpressionException("Valeur numerique attendue, obtenu : " + value);
    }

    default boolean asBoolean(EvaluationContext context) {
        Object value = evaluate(context);
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new ExpressionException("Condition booleenne attendue, obtenu : " + value);
    }
}
