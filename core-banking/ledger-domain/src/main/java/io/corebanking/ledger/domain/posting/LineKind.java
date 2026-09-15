package io.corebanking.ledger.domain.posting;

/**
 * Nature d'une ligne d'ecriture.
 *
 * <p>Une ligne de liaison est generee par le service d'imputation pour equilibrer une ecriture
 * agence par agence ; elle n'est jamais saisie, ne figure sur aucun releve client, et se
 * contre-passe avec l'ecriture qui la porte. Une ligne saisie sur un compte de liaison — une
 * correction du comptable — est une ligne de liaison aussi : c'est le compte qui fait la nature.
 */
public enum LineKind {
    BUSINESS, LIAISON
}
