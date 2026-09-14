package io.corebanking.schema;

import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.ledger.domain.account.Direction;
import io.corebanking.schema.expr.EvaluationContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Validation d'un schema comptable au chargement du parametrage.
 *
 * <h2>La methode : tirage sur les variables libres</h2>
 *
 * <p>Prouver symboliquement l'equilibre d'un schema quelconque demanderait une algebre formelle.
 * La verification faite ici est differente et suffisante en pratique : le schema est evalue sur
 * quelques centaines de jeux de valeurs tires de facon deterministe, et l'equilibre est verifie
 * <b>apres arrondi a l'echelle de la devise</b>.
 *
 * <p>Ce dernier point est l'essentiel. Le desequilibre qui coute cher n'est presque jamais un
 * oubli de ligne — celui-la se voit a la relecture. C'est l'arrondi : un schema qui debite
 * {@code total} et credite {@code net} puis {@code tva}, chacun arrondi separement, est exact en
 * arithmetique reelle et faux en XOF des que {@code net} et {@code tva} ont des decimales. La
 * verification par tirage le revele au deploiement du parametrage ; sans elle, il se manifeste en
 * production, au premier arrete, sous la forme d'un ecart de quelques unites dont l'origine est
 * introuvable.
 *
 * <p>Ce n'est pas une preuve, et cela doit etre dit : un schema dont le desequilibre n'apparait que
 * sur une combinaison tres particuliere peut passer. Le tirage inclut donc systematiquement les
 * valeurs limites — zero, un, et des montants a decimales — ou se concentrent les defauts reels.
 */
public final class SchemaValidator {

    /** Graine fixe : un schema valide aujourd'hui l'est encore demain, avec les memes tirages. */
    private static final long SEED = 20260914L;

    private static final int PROBES = 300;

    /** Valeurs de tirage : entiers, montants a decimales, taux usuels, et bornes. */
    private static final List<BigDecimal> SAMPLES = List.of(
        new BigDecimal("0"), new BigDecimal("1"), new BigDecimal("2"), new BigDecimal("7"),
        new BigDecimal("100"), new BigDecimal("1000"), new BigDecimal("123457"),
        new BigDecimal("999999999"), new BigDecimal("0.5"), new BigDecimal("0.18"),
        new BigDecimal("0.075"), new BigDecimal("1234.56"), new BigDecimal("33333.33"));

    private SchemaValidator() {}

    public static void validate(AccountingSchema schema, CurrencyRef currency) {
        for (EventTemplate template : schema.templates().values()) {
            validate(template, currency, schema.code());
        }
    }

    public static void validate(EventTemplate template, CurrencyRef currency, String schemaCode) {
        List<String> free = new ArrayList<>(template.freeVariables());
        Random random = new Random(SEED);

        for (int probe = 0; probe < PROBES; probe++) {
            Map<String, BigDecimal> values = draw(free, random, probe);
            check(template, currency, schemaCode, values);
        }
    }

    /**
     * Jeu de valeurs du tirage.
     *
     * <p>Les deux premieres variables sont balayees <b>systematiquement</b> — compteur en base
     * {@code SAMPLES.size()} — et les suivantes tirees au hasard. Un tirage integralement aleatoire
     * laisserait passer un defaut d'arrondi qui n'apparait que sur une paire de valeurs precise :
     * avec treize echantillons et deux variables, la probabilite de manquer la paire fautive en
     * trois cents tirages aleatoires reste de l'ordre de quinze pour cent. Trop pour un test dont
     * l'echec doit etre certain.
     */
    private static Map<String, BigDecimal> draw(List<String> free, Random random, int probe) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        int base = SAMPLES.size();
        for (int i = 0; i < free.size(); i++) {
            BigDecimal value;
            if (probe == 0) {
                value = BigDecimal.ZERO;                      // tout a zero
            } else if (probe == 1) {
                value = BigDecimal.ONE;                       // tout a un
            } else if (i == 0) {
                value = SAMPLES.get((probe - 2) % base);
            } else if (i == 1) {
                value = SAMPLES.get(((probe - 2) / base) % base);
            } else {
                value = SAMPLES.get(random.nextInt(base));
            }
            values.put(free.get(i), value);
        }
        return values;
    }

    private static void check(EventTemplate template, CurrencyRef currency, String schemaCode,
                              Map<String, BigDecimal> values) {
        BigDecimal imbalance;
        try {
            EvaluationContext context = template.derive(EvaluationContext.of(values));
            imbalance = BigDecimal.ZERO;
            for (TemplateLine line : template.lines()) {
                if (line.condition() != null && !line.condition().asBoolean(context)) {
                    continue;
                }
                BigDecimal raw = line.amount().asNumber(context);
                // Equilibre verifie sur le montant reellement impute, donc arrondi.
                BigDecimal booked = raw.setScale(currency.scale(), RoundingMode.HALF_EVEN);
                if (booked.signum() == 0) {
                    continue;
                }
                imbalance = line.direction() == Direction.DEBIT
                    ? imbalance.add(booked)
                    : imbalance.subtract(booked);
            }
        } catch (RuntimeException e) {
            throw new InvalidSchemaException(schemaCode, template.eventType(), values,
                "evaluation impossible : " + e.getMessage());
        }

        if (imbalance.signum() != 0) {
            throw new InvalidSchemaException(schemaCode, template.eventType(), values,
                "desequilibre de " + imbalance.toPlainString() + " " + currency
                + " apres arrondi a " + currency.scale() + " decimale(s)");
        }
    }

    /** Schema refuse au deploiement, avec le jeu de valeurs qui met le defaut en evidence. */
    public static class InvalidSchemaException extends RuntimeException {
        private final transient Map<String, BigDecimal> counterExample;

        public InvalidSchemaException(String schemaCode, String eventType,
                                      Map<String, BigDecimal> counterExample, String detail) {
            super("Schema " + schemaCode + ", evenement " + eventType + " : " + detail
                  + ". Contre-exemple : " + counterExample);
            this.counterExample = Map.copyOf(counterExample);
        }

        /** Valeurs pour lesquelles le schema echoue : reproductibles, la graine etant fixe. */
        public Map<String, BigDecimal> counterExample() {
            return counterExample;
        }
    }

}
