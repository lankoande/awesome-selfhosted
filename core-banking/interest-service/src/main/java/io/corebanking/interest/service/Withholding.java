package io.corebanking.interest.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Retenue a la source sur interets crediteurs, en vigueur sur une periode.
 *
 * @param code             code national de la retenue (IRC, IRCM, IRVM...)
 * @param ratePercent      taux applique au brut des interets
 * @param payableAccountId compte de la retenue a reverser, au passif
 */
public record Withholding(String code, BigDecimal ratePercent, UUID payableAccountId,
                          LocalDate validFrom, LocalDate validTo) {}
