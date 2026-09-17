/**
 * Le vocabulaire d'états, fermé, identique dans toute l'application. Le socle
 * ne connaît pas d'autre issue à une opération ; l'interface non plus.
 *
 * `en-attente` n'est ni un succès ni un échec : c'est l'état le plus fréquent
 * d'un back-office bancaire, et il a droit à sa couleur.
 *
 * Neuf états, un par issue du socle. Trois sont arrivés avec la file de
 * validation, et aucun n'est décoratif :
 *   `approuve` — décidée, exécution non confirmée : une anomalie d'exploitation,
 *     pas un succès. L'écraser en « comptabilisé » la cacherait.
 *   `echoue`   — approuvée, mais l'exécution a refusé. La décision reste ;
 *     c'est le demandeur qui doit resoumettre.
 *   `expire`   — le délai a couru sans que personne ne décide.
 */
export type EtatOperation =
  | 'brouillon'
  | 'en-attente'
  | 'approuve'
  | 'comptabilise'
  | 'contre-passe'
  | 'rejete'
  | 'echoue'
  | 'expire'
  | 'bloque';

export const ETATS: readonly EtatOperation[] = [
  'brouillon',
  'en-attente',
  'approuve',
  'comptabilise',
  'contre-passe',
  'rejete',
  'echoue',
  'expire',
  'bloque',
] as const;

export const LIBELLE_ETAT: Readonly<Record<EtatOperation, string>> = {
  brouillon: 'Brouillon',
  'en-attente': 'En attente',
  approuve: 'Approuvée, non confirmée',
  comptabilise: 'Comptabilisé',
  'contre-passe': 'Contre-passé',
  rejete: 'Rejeté',
  echoue: 'Échouée',
  expire: 'Expirée',
  bloque: 'Bloqué',
};
