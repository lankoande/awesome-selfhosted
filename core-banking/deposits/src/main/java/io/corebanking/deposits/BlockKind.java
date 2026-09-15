package io.corebanking.deposits;

/**
 * Nature d'un blocage de compte.
 *
 * <ul>
 *   <li>{@code DEBIT} : opposition, saisie conservatoire — les fonds entrent, rien ne sort.</li>
 *   <li>{@code TOTAL} : gel — plus aucune operation du client, ni en debit ni en credit ; seule la
 *       banque elle-meme continue d'ecrire (interets capitalises, contre-passation).</li>
 * </ul>
 */
public enum BlockKind { DEBIT, TOTAL }
