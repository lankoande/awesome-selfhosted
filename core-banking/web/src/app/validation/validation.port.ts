import { InjectionToken } from '@angular/core';
import { Identite, OperationEnAttente, PageOperations } from './modele/validation.modele';

/**
 * Le port de la double validation.
 *
 * Deux règles du socle que le front ne réimplémente pas, et ne peut pas :
 *   personne n'approuve sa propre demande — la politique d'habilitation le
 *   refuse, l'interface se contente de le dire avant le clic ;
 *   **la requête est rejouée à l'approbation** — l'exécution peut refuser ce
 *   que la saisie acceptait, parce que l'état du jour a changé entre-temps.
 */
export interface Validation {
  file(legalEntityId: string, page: number, taille: number): Promise<PageOperations>;
  lire(legalEntityId: string, id: string): Promise<OperationEnAttente>;

  /**
   * Approuve **et exécute**. Sur échec d'exécution, le socle lève : la décision
   * reste prise, l'opération passe en `FAILED`, et c'est au demandeur de
   * resoumettre une requête qui tienne compte de l'état du jour.
   */
  approuver(legalEntityId: string, id: string): Promise<OperationEnAttente>;

  /** Un rejet se motive : le socle refuse un motif vide. */
  rejeter(legalEntityId: string, id: string, motif: string): Promise<OperationEnAttente>;

  /**
   * Le porteur du jeton, quand le socle sait le dire. `null` : l'interface ne
   * pré-signale pas l'auto-approbation, et c'est l'API qui la refuse — ce qui
   * est de toute façon la seule frontière qui compte.
   */
  identite(): Promise<Identite | null>;
}

export const VALIDATION = new InjectionToken<Validation>('Validation');
