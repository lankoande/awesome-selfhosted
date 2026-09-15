package io.corebanking.ledger.domain.posting;

import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import java.util.UUID;

/**
 * Ligne validee, enrichie du compte resolu et de sa contre-valeur dans la devise de tenue de
 * compte de l'entite.
 */
public record ValidatedLine(PostingLine line, Account account, Money functionalAmount,
                            UUID branchId, LineKind kind) {

    /** Montant en sens comptable brut : positif au debit, negatif au credit. */
    public Money debitSigned() {
        return line.direction() == io.corebanking.ledger.domain.account.Direction.DEBIT
            ? line.amount() : line.amount().negate();
    }

    public Money debitSignedFunctional() {
        return line.direction() == io.corebanking.ledger.domain.account.Direction.DEBIT
            ? functionalAmount : functionalAmount.negate();
    }


    /**
     * Montant signe selon le sens naturel du compte : positif s'il augmente le solde.
     * C'est la seule forme utilisee pour agreger un solde.
     */
    public Money signedAmount() {
        return account.normalBalance().increasedBy(line.direction())
            ? line.amount()
            : line.amount().negate();
    }

    /** Contre-valeur signee, pour l'agregation en devise de tenue de compte. */
    public Money signedFunctionalAmount() {
        return account.normalBalance().increasedBy(line.direction())
            ? functionalAmount
            : functionalAmount.negate();
    }
}
