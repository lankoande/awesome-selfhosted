package io.corebanking.product;

import io.corebanking.interest.rate.RateSchedule;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Version d'un produit en vigueur sur une periode.
 *
 * <p>Une version n'est jamais modifiee : un changement de bareme cree une nouvelle version, la
 * precedente etant close a la veille. C'est ce qui permet de rejouer un arrete passe avec les
 * parametres de l'epoque.
 *
 * @param validTo borne <b>incluse</b>, nulle si la version est sans terme
 */
public record ProductVersion(
    UUID id,
    UUID legalEntityId,
    String code,
    String productType,
    String label,
    String currency,
    LocalDate validFrom,
    LocalDate validTo,
    ParameterSet parameters,
    RateSchedule rateSchedule,
    UUID createdBy,
    UUID approvedBy) {

    public ProductVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(parameters, "parameters");
    }

    public boolean coversDate(LocalDate date) {
        return !date.isBefore(validFrom) && (validTo == null || !date.isAfter(validTo));
    }

    /** Bareme par tranches, absent si le produit se contente d'un taux unique. */
    public Optional<RateSchedule> tieredSchedule() {
        return Optional.ofNullable(rateSchedule);
    }
}
