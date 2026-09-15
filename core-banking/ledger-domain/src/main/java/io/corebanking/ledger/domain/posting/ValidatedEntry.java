package io.corebanking.ledger.domain.posting;

import io.corebanking.kernel.money.CurrencyRef;
import java.util.List;
import java.util.Objects;

/**
 * Commande validee. Sa seule existence atteste que tous les invariants d'ecriture sont verifies :
 * structure, comptes, devises, echelles, equilibre par devise et equilibre en contre-valeur.
 *
 * <p>Il n'existe aucun moyen d'en construire une sans passer par {@link EntryValidator}.
 */
public final class ValidatedEntry {

    private final PostingCommand command;
    private final List<ValidatedLine> lines;
    private final CurrencyRef functionalCurrency;
    private final java.util.UUID operationBranchId;

    ValidatedEntry(PostingCommand command, List<ValidatedLine> lines, CurrencyRef functionalCurrency,
                   java.util.UUID operationBranchId) {
        this.command = Objects.requireNonNull(command);
        this.lines = List.copyOf(lines);
        this.functionalCurrency = Objects.requireNonNull(functionalCurrency);
        this.operationBranchId = operationBranchId;
    }

    /** Agence de l'operation, telle que resolue : celle de la commande, sinon deduite, sinon le siege. */
    public java.util.UUID operationBranchId() { return operationBranchId; }

    public PostingCommand command()          { return command; }
    public List<ValidatedLine> lines()       { return lines; }
    public CurrencyRef functionalCurrency()  { return functionalCurrency; }
}
