package io.corebanking.party;

import java.time.LocalDate;
import java.util.Objects;

/** Un identifiant du tiers : nature, valeur, validite. */
public record PartyIdentifier(IdentifierKind kind, String value, LocalDate issuedOn,
                              LocalDate expiresOn, String issuer) {

    public PartyIdentifier {
        Objects.requireNonNull(kind, "kind");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Identifiant " + kind + " sans valeur");
        }
        value = value.strip();
        if (issuedOn != null && expiresOn != null && expiresOn.isBefore(issuedOn)) {
            throw new IllegalArgumentException(
                "Identifiant " + kind + " expire avant d'etre delivre : " + issuedOn + " > "
                + expiresOn);
        }
    }

    public static PartyIdentifier of(IdentifierKind kind, String value) {
        return new PartyIdentifier(kind, value, null, null, null);
    }

    public boolean expiredAt(LocalDate date) {
        return expiresOn != null && expiresOn.isBefore(date);
    }
}
