package io.corebanking.kernel.id;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Cle d'idempotence d'une commande de comptabilisation.
 *
 * <p>Elle est portee par l'appelant pour le trafic en ligne (en-tete {@code Idempotency-Key}) et
 * <b>derivee de facon deterministe</b> pour les traitements de masse : la reprise d'une etape de
 * TFJ recalcule exactement les memes cles et ne produit donc aucun doublon.
 */
public record IdempotencyKey(String value) {

    private static final int MAX_LENGTH = 200;

    public IdempotencyKey {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Cle d'idempotence vide");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                "Cle d'idempotence de " + value.length() + " caracteres : maximum " + MAX_LENGTH);
        }
    }

    public static IdempotencyKey of(String value) {
        return new IdempotencyKey(value);
    }

    /**
     * Cle deterministe d'une ecriture de batch. Les memes composants produisent toujours la meme
     * cle : c'est ce qui rend une reprise de TFJ sure.
     *
     * @param runId identifiant du run de TFJ
     * @param step  nom de l'etape
     * @param parts composants discriminants (compte, contrat, sequence...)
     */
    public static IdempotencyKey forBatch(String runId, String step, Object... parts) {
        StringBuilder sb = new StringBuilder(runId).append('|').append(step);
        for (Object part : parts) {
            sb.append('|').append(part);
        }
        String raw = sb.toString();
        if (raw.length() <= MAX_LENGTH) {
            return new IdempotencyKey(raw);
        }
        return new IdempotencyKey(runId + "|" + step + "|" + sha256(raw));
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
