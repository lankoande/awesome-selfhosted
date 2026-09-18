import { InjectionToken } from '@angular/core';
import {
  Alerte, Declaration, DemandeDeclaration, DemandeScenario, DemandeTransmission, Scenario,
  StatutAlerte,
} from './modele/conformite.modele';

/** Ce que rend une action soumise à un second regard. */
export interface EnAttente {
  readonly operationId: string;
}

/**
 * Le port de la conformité LCB-FT.
 *
 * Quatre règles du socle que les écrans annoncent sans les tenir :
 *
 *   **une alerte n'est pas une sanction.** Elle constate, elle n'empêche rien.
 *   Seul le filtrage bloque — opérer avec une personne listée est l'infraction
 *   elle-même, pas un soupçon ;
 *
 *   **le classement porte son motif.** C'est la seule pièce que l'inspection
 *   viendra lire ; une alerte classée sans raison écrite ne se contrôle pas ;
 *
 *   **la déclaration de soupçon se rédige à deux** (202). Déclarer engage la
 *   banque et met en cause une personne ; ne pas déclarer l'engage autant ;
 *
 *   **le scénario se déclare à deux** (202) : il décide de ce que la banque
 *   regarde — et de ce qu'elle ne regardera pas.
 *
 * Et une règle qui ne se voit pas dans les signatures : **rien de ce qui passe
 * par ce port ne remonte au dossier client ni à une lecture d'agence.**
 * Informer la personne surveillée est un délit ; l'interface ne doit pas offrir
 * le chemin qui le rendrait possible par inadvertance.
 */
export interface Conformite {
  /** La file de travail, filtrée par statut quand on en donne un. */
  alertes(legalEntityId: string, statut: StatutAlerte | null): Promise<readonly Alerte[]>;

  alerte(legalEntityId: string, alertId: string): Promise<Alerte>;

  /** Prise en charge : l'alerte passe à l'instruction et porte le nom de l'analyste. */
  prendreEnCharge(legalEntityId: string, alertId: string, cleIdempotence: string): Promise<Alerte>;

  /** Classement motivé. Immédiat : instruire et classer sont le travail d'un seul. */
  classer(legalEntityId: string, alertId: string, motif: string,
          cleIdempotence: string): Promise<Alerte>;

  declarations(legalEntityId: string): Promise<readonly Declaration[]>;

  /** Rédiger passe par un second regard : la déclaration met en cause une personne. */
  rediger(legalEntityId: string, demande: DemandeDeclaration,
          cleIdempotence: string): Promise<EnAttente>;

  /** La transmission enregistre la référence rendue par la cellule : c'est la preuve du dépôt. */
  transmettre(legalEntityId: string, reportId: string, demande: DemandeTransmission,
              cleIdempotence: string): Promise<Declaration>;

  scenarios(legalEntityId: string): Promise<readonly Scenario[]>;

  /** Déclarer un scénario passe par un second regard. */
  declarerScenario(legalEntityId: string, demande: DemandeScenario,
                   cleIdempotence: string): Promise<EnAttente>;
}

export const CONFORMITE = new InjectionToken<Conformite>('Conformite');
