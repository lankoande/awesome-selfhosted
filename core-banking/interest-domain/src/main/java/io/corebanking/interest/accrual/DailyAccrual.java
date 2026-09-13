package io.corebanking.interest.accrual;

import io.corebanking.kernel.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Interet couru d'une journee, avec l'integralite de ses composantes.
 *
 * <p>Le montant seul ne suffit pas. Une reclamation client sur des agios se traite en reconstituant
 * le calcul jour par jour : assiette retenue, taux effectif, fraction d'annee. Conserver ces
 * elements transforme une investigation de plusieurs heures en une lecture.
 */
public record DailyAccrual(
    LocalDate day,
    Money basisBalance,
    BigDecimal effectiveRate,
    BigDecimal yearFraction,
    Money amount) {}
