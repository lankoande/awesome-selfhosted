package io.corebanking.api.usecase;

import io.corebanking.ledger.store.Journal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;

/**
 * Le curseur d'une lecture du journal : une {@link Journal.Position position} encodee, opaque
 * pour le client. Un curseur qui ne se relit pas est une requete invalide, pas une page vide.
 */
public final class JournalCursor {

    private JournalCursor() {}

    public static String encode(Journal.Position position) {
        String plain = position.bookingDate() + "|" + position.knowledgeTime() + "|"
            + position.entryId() + "|" + position.lineNumber();
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /** @return la position, ou nulle si le curseur est absent */
    public static Journal.Position decode(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            String plain = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = plain.split("\\|", -1);
            if (parts.length != 4) {
                throw new IllegalArgumentException("quatre champs attendus");
            }
            return new Journal.Position(LocalDate.parse(parts[0]), Instant.parse(parts[1]),
                                        UUID.fromString(parts[2]), Integer.parseInt(parts[3]));
        } catch (RuntimeException e) {
            throw new Paging.InvalidPageException(
                "Curseur invalide : rendre celui de la page precedente, tel quel");
        }
    }
}
