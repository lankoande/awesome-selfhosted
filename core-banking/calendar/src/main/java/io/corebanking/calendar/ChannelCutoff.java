package io.corebanking.calendar;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Heure limite d'un canal, dans le fuseau de l'entite.
 *
 * <p>Au-dela, l'operation porte la date de valeur du jour ouvre suivant — le calcul des
 * conditions de banque part de ce jour-la — ; si le canal ferme a son heure limite, l'operation
 * est refusee. Une heure limite sans canal vaut pour toute operation, quel que soit le canal,
 * et une heure limite nommant un canal l'emporte sur elle.
 *
 * @param channel       le canal, ou nul pour tous
 * @param closesChannel vrai si le canal ferme a l'heure limite au lieu de decaler la valeur
 */
public record ChannelCutoff(String channel, LocalTime cutoffTime, boolean closesChannel,
                            LocalDate validFrom, LocalDate validTo) {

    public ChannelCutoff {
        Objects.requireNonNull(cutoffTime, "cutoffTime");
        Objects.requireNonNull(validFrom, "validFrom");
        if (validTo != null && validTo.isBefore(validFrom)) {
            throw new IllegalArgumentException("Une heure limite ne finit pas avant de commencer");
        }
        if (channel != null && channel.isBlank()) {
            channel = null;
        }
    }

    public boolean covers(LocalDate date) {
        return !date.isBefore(validFrom) && (validTo == null || !date.isAfter(validTo));
    }

    public boolean matches(String channel) {
        return this.channel == null || this.channel.equals(channel);
    }

    public int specificity() {
        return channel == null ? 0 : 1;
    }

    /** Vrai a l'heure limite et apres. */
    public boolean passedAt(LocalTime time) {
        return !time.isBefore(cutoffTime);
    }
}
