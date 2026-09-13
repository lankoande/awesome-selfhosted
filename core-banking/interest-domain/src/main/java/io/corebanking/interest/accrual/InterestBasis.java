package io.corebanking.interest.accrual;

/**
 * Assiette de calcul des interets sur une periode.
 *
 * <p>Le choix change le montant du simple au double sur un compte mouvemente. Il est contractuel,
 * jamais implicite.
 */
public enum InterestBasis {
    /** Chaque journee est remuneree sur son propre solde. Le plus favorable au client. */
    DAILY_BALANCE,
    /** Le solde le plus bas de la periode remunere toute la periode. Epargne classique. */
    MINIMUM_BALANCE,
    /** La moyenne des soldes quotidiens remunere toute la periode. */
    AVERAGE_DAILY_BALANCE
}
