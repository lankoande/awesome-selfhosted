package io.corebanking.calendar;

import io.corebanking.ledger.domain.account.Direction;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

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
 */
public final class ValueDatePolicy {

    private final BusinessCalendar calendar;
    private final List<ValueDateRule> rules;

    public ValueDatePolicy(BusinessCalendar calendar, List<ValueDateRule> rules) {
        this.calendar = Objects.requireNonNull(calendar, "calendar");
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
    }

    public LocalDate valueDateFor(String operationType, String channel, Direction direction,
                                  LocalDate bookingDate) {
        return rules.stream()
            .filter(rule -> rule.covers(bookingDate))
            .filter(rule -> rule.matches(operationType, channel, direction))
            .max(Comparator.comparingInt(ValueDateRule::specificity))
            .orElseThrow(() -> new NoRuleException(operationType, channel, direction, bookingDate))
            .valueDate(bookingDate, calendar);
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
