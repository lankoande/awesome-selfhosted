package io.corebanking.api.web;

import io.corebanking.kernel.money.Money;
import io.corebanking.ledger.domain.account.Account;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Corps des requetes. Les montants arrivent en chaine, avec leur devise, et sont confrontes a la
 * devise du compte avant de devenir un {@link Money} : une devise qui ne correspond pas est un
 * refus, jamais une conversion.
 */
public final class Requests {

    private Requests() {}

    public record Amount(String amount, String currency) {
        public Money on(Account account) {
            if (amount == null || currency == null) {
                throw new IllegalArgumentException("Montant et devise obligatoires");
            }
            if (!account.currency().code().equals(currency)) {
                throw new IllegalArgumentException(
                    "Le compte " + account.code() + " est tenu en " + account.currency().code()
                    + ", la requete est en " + currency);
            }
            return Money.of(new BigDecimal(amount), account.currency());
        }
    }

    public record CashOperation(String amount, String currency, UUID cashAccountId, String channel,
                                String narrative) {
        public Money on(Account account) {
            return new Amount(amount, currency).on(account);
        }
    }

    public record Transfer(UUID sourceAccountId, UUID destinationAccountId, String amount,
                           String currency, String channel, String narrative) {}

    /** Les actes a double validation n'ont pas d'approbateur dans le corps : c'est le checker. */
    public record OpenAccount(String code, UUID holderPartyId, String productCode, String currency) {}

    public record CloseAccount(UUID payoutAccountId) {}

    public record BlockAccount(String kind, String reason, String reference) {}

    public record LiftBlock(String reason) {}

    public record PlaceHold(String amount, String currency, String type, String reference,
                            LocalDate expiresOn) {}

    public record Identifier(String kind, String value, LocalDate issuedOn, LocalDate expiresOn,
                             String issuer) {}

    public record CreateParty(String reference, String kind, String displayName,
                              LocalDate birthOrRegistrationDate, String countryCode, String segment,
                              List<Identifier> identifiers) {}

    public record VerifyKyc(String rating, LocalDate verifiedOn) {}

    public record RunEod(LocalDate businessDate, String mode) {}

    public record CancelEod(LocalDate reversalBookingDate, String reason) {}

    /** Reponse d'une creation : l'identifiant de ce qui a ete cree. */
    public record Created(UUID id) {}
}
