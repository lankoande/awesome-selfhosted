package io.corebanking.kernel.id;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * Generation d'identifiants UUID v7 : 48 bits d'horodatage en tete, puis de l'aleatoire.
 *
 * <p>L'ordre lexicographique suit l'ordre de creation, ce qui evite la fragmentation des index
 * B-tree sous insertion soutenue. Avec des UUID v4 purement aleatoires, chaque insertion touche
 * une page differente et le debit d'ecriture du journal s'effondre a mesure que la table grossit.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {}

    public static UUID newId() {
        return newId(Instant.now());
    }

    public static UUID newId(Instant at) {
        long millis = at.toEpochMilli();
        byte[] bytes = new byte[10];
        RANDOM.nextBytes(bytes);

        long msb = (millis & 0xFFFFFFFFFFFFL) << 16;
        msb |= 0x7000L;                                   // version 7
        msb |= ((bytes[0] & 0xFFL) << 8) | (bytes[1] & 0xFFL);

        long lsb = 0;
        for (int i = 2; i < 10; i++) {
            lsb = (lsb << 8) | (bytes[i] & 0xFFL);
        }
        lsb &= 0x3FFFFFFFFFFFFFFFL;
        lsb |= 0x8000000000000000L;                       // variant IETF

        return new UUID(msb, lsb);
    }
}
