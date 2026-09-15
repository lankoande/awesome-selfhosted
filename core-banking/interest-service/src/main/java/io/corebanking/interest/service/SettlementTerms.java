package io.corebanking.interest.service;

import io.corebanking.kernel.time.Periodicity;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Comment les interets courus sont regles au client.
 *
 * <p>Pour un compte remunere : capitalisation a periodicite fixe, retenue a la source deduite
 * lorsque le produit designe une retenue. Pour un compte debiteur : arrete des agios, taxe sur les
 * interets ajoutee lorsque le produit en declare une. Un produit sans reglement laisse le compte
 * de courus croitre indefiniment — c'est precisement le defaut que ces conditions ferment, et un
 * produit remunere doit les porter.
 *
 * @param periodicity     periodicite de reglement, calee sur le calendrier civil : fin de mois,
 *                        de trimestre, de semestre ou d'annee
 * @param withholdingCode code de la retenue a la source appliquee aux interets crediteurs, nul si
 *                        le produit en est exonere
 * @param taxRatePercent  taux de la taxe sur les agios, nul s'il n'y en a pas
 * @param taxAccount      compte de collecte de cette taxe, exige des qu'elle est non nulle
 */
public record SettlementTerms(Periodicity periodicity, String withholdingCode,
                              BigDecimal taxRatePercent, UUID taxAccount) {

    public SettlementTerms {
        Objects.requireNonNull(periodicity, "periodicity");
        if (periodicity == Periodicity.DAILY) {
            throw new IllegalArgumentException(
                "Un reglement quotidien des interets n'a pas de sens : les courus se reglent en fin "
                + "de periode civile.");
        }
        taxRatePercent = taxRatePercent == null ? BigDecimal.ZERO : taxRatePercent;
        if (taxRatePercent.signum() < 0) {
            throw new IllegalArgumentException("Taux de taxe negatif : " + taxRatePercent);
        }
        if (taxRatePercent.signum() > 0 && taxAccount == null) {
            throw new IllegalArgumentException(
                "Une taxe sur les agios sans compte de collecte resterait dans le produit de la "
                + "banque, et la declaration serait fausse.");
        }
    }

    public static SettlementTerms capitalisation(Periodicity periodicity, String withholdingCode) {
        return new SettlementTerms(periodicity, withholdingCode, BigDecimal.ZERO, null);
    }

    public static SettlementTerms overdraftCharge(Periodicity periodicity, BigDecimal taxRatePercent,
                                                  UUID taxAccount) {
        return new SettlementTerms(periodicity, null, taxRatePercent, taxAccount);
    }
}
