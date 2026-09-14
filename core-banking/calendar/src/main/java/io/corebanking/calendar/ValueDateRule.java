package io.corebanking.calendar;

import io.corebanking.ledger.domain.account.Direction;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Regle de date de valeur applicable a la ligne <b>client</b> d'une operation.
 *
 * <h2>L'asymetrie est le sujet</h2>
 *
 * <p>Les conditions de banque decalent rarement le debit et le credit de la meme facon : un retrait
 * porte souvent une date de valeur anterieure a l'operation, un versement une date posterieure. Ces
 * quelques journees sont un produit pour la banque et un cout pour le client ; elles figurent dans
 * les conditions signees, et leur controle est un point recurrent des inspections.
 *
 * <p>C'est pourquoi le sens fait partie de la clef de la regle. Un modele qui n'en tiendrait pas
 * compte ne saurait pas representer les conditions reellement pratiquees.
 *
 * @param channel   canal concerne, {@code null} pour toute operation du type
 * @param direction sens de la ligne client
 */
public record ValueDateRule(
    String operationType,
    String channel,
    Direction direction,
    int offset,
    OffsetUnit unit,
    BusinessDayConvention convention,
    LocalDate validFrom,
    LocalDate validTo) {

    public ValueDateRule {
        Objects.requireNonNull(operationType, "operationType");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(convention, "convention");
        Objects.requireNonNull(validFrom, "validFrom");
    }

    public boolean covers(LocalDate date) {
        return !date.isBefore(validFrom) && (validTo == null || !date.isAfter(validTo));
    }

    public boolean matches(String operationType, String channel, Direction direction) {
        return this.operationType.equals(operationType)
            && this.direction == direction
            && (this.channel == null || this.channel.equals(channel));
    }

    /** Une regle nommant un canal l'emporte sur une regle generale. */
    public int specificity() {
        return channel == null ? 0 : 1;
    }

    /** Date de valeur de la ligne client, a partir de la date comptable. */
    public LocalDate valueDate(LocalDate bookingDate, BusinessCalendar calendar) {
        LocalDate shifted = unit == OffsetUnit.BUSINESS_DAYS
            ? calendar.addBusinessDays(bookingDate, offset)
            : bookingDate.plusDays(offset);
        return convention.adjust(shifted, calendar);
    }
}
