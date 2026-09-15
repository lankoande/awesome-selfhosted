package io.corebanking.ledger.domain.account;

import io.corebanking.kernel.money.CurrencyRef;
import java.util.Objects;
import java.util.UUID;

/**
 * Compte imputable.
 *
 * @param stripeCount      nombre de sous-soldes pour repartir la contention. 1 pour un compte
 *                         client ; 32 a 128 pour un compte chaud impute par toutes les operations
 *                         (liaison GAB, suspens de compensation, produits d'interets). Sans cette
 *                         repartition, le debit du systeme entier est celui d'un seul compte.
 * @param controlAvailable le disponible est-il verifie a la comptabilisation. Vrai pour les
 *                         comptes clients, faux pour les comptes generaux.
 */
public record Account(
    UUID id,
    UUID legalEntityId,
    String code,
    AccountKind kind,
    NormalBalance normalBalance,
    CurrencyRef currency,
    boolean postable,
    boolean controlAvailable,
    int stripeCount,
    AccountStatus status,
    UUID branchId) {

    /**
     * Compte sans agence precisee. Un compte client ou interne prendra le siege de son entite a
     * la creation ; un compte general n'a pas d'agence : son solde se tient par agence, en
     * dimension de chaque ligne.
     */
    public Account(UUID id, UUID legalEntityId, String code, AccountKind kind,
                   NormalBalance normalBalance, CurrencyRef currency, boolean postable,
                   boolean controlAvailable, int stripeCount, AccountStatus status) {
        this(id, legalEntityId, code, kind, normalBalance, currency, postable, controlAvailable,
             stripeCount, status, null);
    }

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(legalEntityId, "legalEntityId");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(normalBalance, "normalBalance");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(status, "status");
        if (stripeCount < 1 || stripeCount > 256) {
            throw new IllegalArgumentException("stripeCount hors bornes [1..256] : " + stripeCount);
        }
        if (controlAvailable && stripeCount > 1) {
            throw new IllegalArgumentException(
                "Le compte " + code + " controle son disponible et ne peut pas etre reparti sur "
                + stripeCount + " sous-soldes : verifier un disponible exigerait de verrouiller "
                + "tous les stripes, ce qui annulerait le benefice de la repartition. "
                + "Un compte chaud est un compte de contrepartie, jamais un compte a controle de solde.");
        }
    }

    public boolean isHot() {
        return stripeCount > 1;
    }

    /** Vrai pour un compte tenu par une agence : client, interne, ou compte d'attente d'agence. */
    public boolean hasBranch() {
        return branchId != null;
    }

    public Account withBranch(UUID branch) {
        return new Account(id, legalEntityId, code, kind, normalBalance, currency, postable,
                           controlAvailable, stripeCount, status, branch);
    }
}
