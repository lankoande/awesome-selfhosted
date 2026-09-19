package io.corebanking.schema;

import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.schema.expr.EvaluationContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Essai d'un schema comptable sur un jeu de valeurs, sans rien imputer.
 *
 * <h2>Pourquoi un essai, alors que la validation par tirage existe deja</h2>
 *
 * <p>{@link SchemaValidator} repond a une question de deploiement — <i>ce schema est-il equilibre
 * pour toute valeur ?</i> — et sa reponse est un oui ou un contre-exemple. Ce n'est pas la question
 * que se pose celui qui redige le schema. La sienne est : <i>pour cette operation-la, quelles
 * lignes cela produit-il ?</i>
 *
 * <p>Personne ne lit {@code round(net, 0) + round(tax, 0)} et n'en deduit l'ecriture. On la lit en
 * la posant sur un cas. L'essai rend donc les variables derivees, ligne par ligne, puis les lignes
 * retenues et celles qui ne le sont pas <b>avec la raison</b> — condition fausse ou montant nul —,
 * et enfin les totaux au debit et au credit.
 *
 * <h2>Fidele au moteur, y compris dans ses refus</h2>
 *
 * <p>L'essai applique exactement les regles de {@link SchemaEngine} : montant nul non impute,
 * montant negatif refuse, montant non comptabilisable refuse plutot qu'arrondi, moins de deux
 * lignes refuse. Un essai qui arrondirait la ou le moteur refuse mentirait a son lecteur — il
 * montrerait une ecriture que la production n'accepterait pas.
 *
 * <p>Il ne leve jamais : un refus est une <b>reponse</b>, pas une panne. C'est meme la reponse la
 * plus utile, puisque c'est celle qu'on cherche a comprendre.
 */
public final class SchemaSimulator {

    private SchemaSimulator() {}

    /** Variable calculee par le schema, avec sa valeur sur ce jeu. */
    public record Derived(String name, String expression, BigDecimal value) {}

    /**
     * Ligne du schema evaluee sur ce jeu.
     *
     * @param posted  vrai si la ligne serait imputee
     * @param skipped raison de la mise a l'ecart : {@code CONDITION}, {@code MONTANT_NUL}, ou nul
     */
    public record Line(String account, String direction, String amountExpression,
                       BigDecimal amount, String label, boolean posted, String skipped) {}

    /** Refus du moteur, avec son code et le detail que la production afficherait. */
    public record Rejection(String code, String detail) {}

    /**
     * Resultat de l'essai.
     *
     * @param imbalance debit moins credit sur les seules lignes imputees ; zero si le schema est
     *                  equilibre sur ce jeu
     */
    public record Outcome(String eventType, List<String> variables, List<Derived> derived,
                          List<Line> lines, BigDecimal debit, BigDecimal credit,
                          BigDecimal imbalance, Rejection rejection) {}

    public static Outcome run(EventTemplate template, CurrencyRef currency,
                              Map<String, BigDecimal> values) {
        List<String> free = List.copyOf(template.freeVariables());

        Map<String, BigDecimal> supplied = new LinkedHashMap<>();
        for (String name : free) {
            BigDecimal given = values.get(name);
            supplied.put(name, given == null ? BigDecimal.ZERO : given);
        }

        EvaluationContext context;
        List<Derived> derived = new ArrayList<>();
        try {
            context = template.derive(EvaluationContext.of(supplied));
            template.derivations().forEach((name, expression) ->
                derived.add(new Derived(name, expression.source(), context.require(name))));
        } catch (RuntimeException e) {
            return new Outcome(template.eventType(), free, List.of(), List.of(), BigDecimal.ZERO,
                               BigDecimal.ZERO, BigDecimal.ZERO,
                               new Rejection("EVALUATION", message(e)));
        }

        List<Line> lines = new ArrayList<>(template.lines().size());
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        int posted = 0;

        for (TemplateLine line : template.lines()) {
            String account = line.account().toString();
            String direction = line.direction().name();
            String source = line.amount().source();
            try {
                if (line.condition() != null && !line.condition().asBoolean(context)) {
                    lines.add(new Line(account, direction, source, null, line.label(), false,
                                       "CONDITION"));
                    continue;
                }
                BigDecimal raw = line.amount().asNumber(context);
                if (raw.signum() == 0) {
                    lines.add(new Line(account, direction, source, raw, line.label(), false,
                                       "MONTANT_NUL"));
                    continue;
                }
                if (raw.signum() < 0) {
                    lines.add(new Line(account, direction, source, raw, line.label(), false, null));
                    return refused(template, free, derived, lines, "MONTANT_NEGATIF",
                        "Montant negatif " + raw.toPlainString() + " sur la ligne " + direction
                        + " " + account + " : le sens est porte par la direction, jamais par le "
                        + "signe.");
                }
                if (!Money.of(raw, currency).isBookable()) {
                    lines.add(new Line(account, direction, source, raw, line.label(), false, null));
                    return refused(template, free, derived, lines, "MONTANT_NON_COMPTABILISABLE",
                        "Montant " + raw.toPlainString() + " non comptabilisable en "
                        + currency.code() + " (" + currency.scale() + " decimale(s)) sur la ligne "
                        + direction + " " + account + ". Le moteur n'arrondit pas a votre place : "
                        + "employez round(...) et imputez l'ecart explicitement.");
                }
                lines.add(new Line(account, direction, source, raw, line.label(), true, null));
                posted++;
                if (line.direction() == io.corebanking.ledger.domain.account.Direction.DEBIT) {
                    debit = debit.add(raw);
                } else {
                    credit = credit.add(raw);
                }
            } catch (RuntimeException e) {
                lines.add(new Line(account, direction, source, null, line.label(), false, null));
                return refused(template, free, derived, lines, "EVALUATION", message(e));
            }
        }

        BigDecimal imbalance = debit.subtract(credit);
        Rejection rejection = null;
        if (posted < 2) {
            rejection = new Rejection("LIGNE_UNIQUE",
                "Les conditions et les montants nuls ont reduit l'ecriture a " + posted
                + " ligne(s) : la partie double en exige deux.");
        } else if (imbalance.signum() != 0) {
            rejection = new Rejection("DESEQUILIBRE",
                "Desequilibre de " + imbalance.toPlainString() + " " + currency.code()
                + " entre le debit et le credit.");
        }
        return new Outcome(template.eventType(), free, derived, lines, debit, credit, imbalance,
                           rejection);
    }

    private static Outcome refused(EventTemplate template, List<String> free, List<Derived> derived,
                                   List<Line> lines, String code, String detail) {
        return new Outcome(template.eventType(), free, derived, lines, BigDecimal.ZERO,
                           BigDecimal.ZERO, BigDecimal.ZERO, new Rejection(code, detail));
    }

    private static String message(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
