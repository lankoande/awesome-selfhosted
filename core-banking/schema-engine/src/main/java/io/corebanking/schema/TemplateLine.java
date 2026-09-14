package io.corebanking.schema;

import io.corebanking.ledger.domain.account.Direction;
import io.corebanking.schema.expr.Expression;
import java.util.Objects;

/**
 * Ligne modele d'un schema comptable.
 *
 * @param condition condition d'imputation, nulle si la ligne est toujours produite
 */
public record TemplateLine(
    AccountRef account,
    Direction direction,
    Expression amount,
    String label,
    Expression condition) {

    public TemplateLine {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");
    }

    public static TemplateLine debit(String accountRef, String amountExpression, String label) {
        return new TemplateLine(AccountRef.parse(accountRef), Direction.DEBIT,
                                io.corebanking.schema.expr.Expressions.parse(amountExpression),
                                label, null);
    }

    public static TemplateLine credit(String accountRef, String amountExpression, String label) {
        return new TemplateLine(AccountRef.parse(accountRef), Direction.CREDIT,
                                io.corebanking.schema.expr.Expressions.parse(amountExpression),
                                label, null);
    }

    public TemplateLine onlyIf(String conditionExpression) {
        return new TemplateLine(account, direction, amount, label,
                                io.corebanking.schema.expr.Expressions.parse(conditionExpression));
    }
}
