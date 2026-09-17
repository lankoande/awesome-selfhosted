/**
 * Le vocabulaire d'états, fermé, identique dans toute l'application. Le socle
 * ne connaît pas d'autre issue à une opération ; l'interface non plus.
 *
 * `en-attente` n'est ni un succès ni un échec : c'est l'état le plus fréquent
 * d'un back-office bancaire, et il a droit à sa couleur.
 */
export type EtatOperation =
  | 'brouillon'
  | 'en-attente'
  | 'comptabilise'
  | 'contre-passe'
  | 'rejete'
  | 'bloque';

export const ETATS: readonly EtatOperation[] = [
  'brouillon',
  'en-attente',
  'comptabilise',
  'contre-passe',
  'rejete',
  'bloque',
] as const;

export const LIBELLE_ETAT: Readonly<Record<EtatOperation, string>> = {
  brouillon: 'Brouillon',
  'en-attente': 'En attente',
  comptabilise: 'Comptabilisé',
  'contre-passe': 'Contre-passé',
  rejete: 'Rejeté',
  bloque: 'Bloqué',
};
