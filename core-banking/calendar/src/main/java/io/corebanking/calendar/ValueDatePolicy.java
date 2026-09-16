package io.corebanking.calendar;

import io.corebanking.ledger.domain.account.Direction;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Determination de la date de valeur.
 *
 * <h2>La date de valeur se calcule, elle ne se fournit pas</h2>
 *
 * <p>Tant que l'appelant transmet la date de valeur, chaque canal finit par appliquer sa propre
 * interpretation des conditions de banque, et les ecarts ne se voient pas : l'ecriture est
 * equilibree, la comptabilite juste, et seuls les agios sont faux. Le defaut se manifeste par une
 * reclamation, des mois plus tard, sur une operation que plus personne ne sait reconstituer.
 *
 * <p>Le moteur la calcule donc a partir des conditions en vigueur <b>a la date comptable traitee</b>,
 * et l'absence de regle est un refus, jamais un repli sur la date comptable — ce repli serait la
 * forme la plus discrete de l'erreur, puisqu'il produit un resultat plausible.
 *
 * <h2>L'heure limite du canal</h2>
 *
 * <p>Une operation en ligne recue apres l'heure limite de son canal porte la date de valeur du
 * jour ouvre suivant : les conditions se calculent depuis ce jour-la. Un canal qui ferme a son
 * heure limite refuse l'operation. L'heure se lit a l'horloge, dans le fuseau de l'entite ;
 * un traitement de lot n'a pas d'heure limite — il execute ce qui est a l'echeance.
 */
public final class ValueDatePolicy {

    private final BusinessCalendar calendar;
    private final List<ValueDateRule> rules;
    private final List<ChannelCutoff> cutoffs;
    private final ZoneId zone;
    private final Clock clock;

    /** Sans heure limite : ce que voit un traitement, ou un test des seules conditions. */
    public ValueDatePolicy(BusinessCalendar calendar, List<ValueDateRule> rules) {
        this(calendar, rules, List.of(), ZoneId.of("UTC"), Clock.systemUTC());
    }

    public ValueDatePolicy(BusinessCalendar calendar, List<ValueDateRule> rules,
                           List<ChannelCutoff> cutoffs, ZoneId zone, Clock clock) {
        this.calendar = Objects.requireNonNull(calendar, "calendar");
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        this.cutoffs = List.copyOf(Objects.requireNonNull(cutoffs, "cutoffs"));
        this.zone = Objects.requireNonNull(zone, "zone");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** La date de valeur d'une operation en ligne : l'heure limite du canal s'applique. */
    public LocalDate valueDateFor(String operationType, String channel, Direction direction,
                                  LocalDate bookingDate) {
        return valueDateFor(operationType, channel, direction, bookingDate, true);
    }

    /**
     * @param online vrai pour une operation recue par un canal, a laquelle l'heure limite
     *               s'applique ; faux pour un traitement de lot, qui n'en a pas
     */
    public LocalDate valueDateFor(String operationType, String channel, Direction direction,
                                  LocalDate bookingDate, boolean online) {
        ValueDateRule rule = rules.stream()
            .filter(r -> r.covers(bookingDate))
            .filter(r -> r.matches(operationType, channel, direction))
            .max(Comparator.comparingInt(ValueDateRule::specificity))
            .orElseThrow(() -> new NoRuleException(operationType, channel, direction, bookingDate));
        LocalDate base = bookingDate;
        if (online) {
            Optional<ChannelCutoff> cutoff = cutoffFor(channel, bookingDate);
            if (cutoff.isPresent()) {
                LocalTime now = LocalTime.now(clock.withZone(zone));
                if (cutoff.get().passedAt(now)) {
                    if (cutoff.get().closesChannel()) {
                        throw new ChannelClosedException(channel, cutoff.get().cutoffTime(), now);
                    }
                    base = calendar.nextBusinessDay(bookingDate);
                }
            }
        }
        return rule.valueDate(base, calendar);
    }

    /** L'heure limite qui vaut pour le canal a la date : celle qui le nomme, sinon la generale. */
    public Optional<ChannelCutoff> cutoffFor(String channel, LocalDate bookingDate) {
        return cutoffs.stream()
            .filter(cutoff -> cutoff.covers(bookingDate))
            .filter(cutoff -> cutoff.matches(channel))
            .max(Comparator.comparingInt(ChannelCutoff::specificity));
    }

    /** Le canal est ferme a cette heure : l'operation attend l'ouverture, elle ne se date pas. */
    public static class ChannelClosedException extends RuntimeException {
        public ChannelClosedException(String channel, LocalTime cutoffTime, LocalTime now) {
            super("Le canal " + (channel == null ? "(tous)" : channel) + " est ferme depuis "
                  + cutoffTime + " ; il est " + now.withNano(0) + " : l'operation attend "
                  + "l'ouverture du jour ouvre suivant, elle ne se date pas d'aujourd'hui.");
        }
    }

    public BusinessCalendar calendar() {
        return calendar;
    }

    /** Aucune condition de banque ne couvre l'operation demandee. */
    public static class NoRuleException extends RuntimeException {
        public NoRuleException(String operationType, String channel, Direction direction,
                               LocalDate bookingDate) {
            super("Aucune condition de date de valeur pour « " + operationType + " » au "
                  + bookingDate + " (canal " + (channel == null ? "non precise" : channel)
                  + ", sens " + direction + "). Se rabattre sur la date comptable produirait un "
                  + "resultat plausible et faux : le parametrage doit couvrir l'operation.");
        }
    }
}
