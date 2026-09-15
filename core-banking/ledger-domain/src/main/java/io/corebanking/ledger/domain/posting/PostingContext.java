package io.corebanking.ledger.domain.posting;

import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.ledger.domain.account.Account;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Contexte de validation d'une commande : la devise de tenue de compte de l'entite et les comptes
 * reellement charges depuis le referentiel.
 *
 * <p>Les comptes sont fournis par l'appelant plutot que resolus par le validateur : le domaine
 * reste pur, sans acces aux donnees, donc entierement testable sans base.
 */
public record PostingContext(CurrencyRef functionalCurrency, Map<UUID, Account> accounts,
                             UUID headOfficeId, Set<UUID> liaisonAccountIds) {

    /** Contexte sans agences : le domaine pur, ou une entite qui n'en a pas encore. */
    public PostingContext(CurrencyRef functionalCurrency, Map<UUID, Account> accounts) {
        this(functionalCurrency, accounts, null, Set.of());
    }

    public PostingContext {
        Objects.requireNonNull(functionalCurrency, "functionalCurrency");
        accounts = Map.copyOf(Objects.requireNonNull(accounts, "accounts"));
        liaisonAccountIds = Set.copyOf(liaisonAccountIds == null ? Set.of() : liaisonAccountIds);
    }

    /** Cours neutre, applique aux lignes deja libellees dans la devise de tenue de compte. */
    public static final BigDecimal UNIT_RATE = BigDecimal.ONE;
}
