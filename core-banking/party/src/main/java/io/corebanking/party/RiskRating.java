package io.corebanking.party;

/**
 * Notation du risque client. Elle commande le niveau de diligence et l'echeance de revue :
 * douze mois pour un risque eleve, vingt-quatre pour un risque moyen, trente-six pour un risque
 * faible.
 */
public enum RiskRating {
    LOW(KycLevel.SIMPLIFIED, 36),
    MEDIUM(KycLevel.STANDARD, 24),
    HIGH(KycLevel.ENHANCED, 12);

    private final KycLevel level;
    private final int reviewMonths;

    RiskRating(KycLevel level, int reviewMonths) {
        this.level = level;
        this.reviewMonths = reviewMonths;
    }

    public KycLevel level() {
        return level;
    }

    public int reviewMonths() {
        return reviewMonths;
    }
}
