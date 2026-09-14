package io.corebanking.schema;

import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.posting.PostingLine;
import io.corebanking.schema.expr.EvaluationContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Traduction d'un evenement metier en lignes de comptabilisation. */
public final class SchemaEngine {

    private SchemaEngine() {}

    /**
     * Produit les lignes correspondant a un evenement.
     *
     * <p>Trois regles d'imputation, toutes explicites :
     *
     * <ul>
     *   <li><b>Une ligne a montant nul n'est pas imputee.</b> Une TVA a zero sur une operation
     *       exoneree ne doit pas produire d'ecriture ; le journal n'accepte d'ailleurs que des
     *       montants strictement positifs.</li>
     *   <li><b>Un montant negatif est refuse.</b> Le sens est porte par la direction, jamais par le
     *       signe. Un schema qui produit un montant negatif se trompe de direction.</li>
     *   <li><b>Un montant non comptabilisable est refuse</b>, il n'est pas arrondi en silence.
     *       Decider qui supporte l'ecart d'arrondi est une decision de gestion : elle appartient a
     *       l'auteur du schema, qui l'ecrit avec {@code round()}, pas au moteur.</li>
     * </ul>
     */
    public static List<PostingLine> linesFor(EventTemplate template, EvaluationContext input,
                                             AccountResolver resolver, CurrencyRef currency,
                                             LocalDate valueDate) {
        EvaluationContext context = template.derive(input);
        List<PostingLine> lines = new ArrayList<>(template.lines().size());

        for (TemplateLine line : template.lines()) {
            if (line.condition() != null && !line.condition().asBoolean(context)) {
                continue;
            }
            BigDecimal raw = line.amount().asNumber(context);
            if (raw.signum() == 0) {
                continue;
            }
            if (raw.signum() < 0) {
                throw new InvalidSchemaAmountException(template.eventType(), line,
                    "montant negatif " + raw.toPlainString()
                    + " : le sens est porte par la direction, jamais par le signe");
            }
            Money amount = Money.of(raw, currency);
            if (!amount.isBookable()) {
                throw new InvalidSchemaAmountException(template.eventType(), line,
                    "montant " + amount + " non comptabilisable en " + currency + " ("
                    + currency.scale() + " decimale(s)). Le moteur n'arrondit pas a votre place : "
                    + "employez round(...) dans le schema et imputez l'ecart explicitement.");
            }
            UUID account = resolver.resolve(line.account());
            lines.add(new PostingLine(account, line.direction(), amount, valueDate, line.label(),
                                      null));
        }

        if (lines.size() < 2) {
            throw new InvalidSchemaAmountException(template.eventType(), null,
                "les conditions et les montants nuls ont reduit l'ecriture a " + lines.size()
                + " ligne(s) : la partie double en exige deux");
        }
        return lines;
    }

    /** Montant produit par un schema et refuse a l'imputation. */
    public static class InvalidSchemaAmountException extends RuntimeException {
        public InvalidSchemaAmountException(String eventType, TemplateLine line, String detail) {
            super("Schema de l'evenement " + eventType
                  + (line == null ? "" : ", ligne " + line.direction() + " " + line.account())
                  + " : " + detail);
        }
    }
}
