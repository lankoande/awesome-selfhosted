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

    ValidatedEntry(PostingCommand command, List<ValidatedLine> lines, CurrencyRef functionalCurrency) {
        this.command = Objects.requireNonNull(command);
        this.lines = List.copyOf(lines);
        this.functionalCurrency = Objects.requireNonNull(functionalCurrency);
    }

    public PostingCommand command()          { return command; }
    public List<ValidatedLine> lines()       { return lines; }
    public CurrencyRef functionalCurrency()  { return functionalCurrency; }
}
