import { InjectionToken } from '@angular/core';
import { FiltreBalance, PageBalance, RunTfj, TotauxBalance } from './modele/siege.modele';

/**
 * Le port du siège : l'exploitation du traitement de fin de journée et les
 * restitutions comptables.
 *
 * Rien ici ne décide de ce que fait le traitement. Le front lance, lit,
 * reprend, annule — et affiche ce que le socle répond, y compris ce qui a
 * échoué et ce qui n'a jamais été tenté.
 */
export interface Siege {
  /**
   * Lance le traitement. `DRY_RUN` n'écrit rien : c'est l'essai qu'un
   * exploitant fait avant d'engager sa journée.
   */
  lancerTfj(legalEntityId: string, journee: string, mode: 'REAL' | 'DRY_RUN'): Promise<RunTfj>;
  lireTfj(legalEntityId: string, runId: string): Promise<RunTfj>;
  /** Reprend à l'étape échouée ; ne vaut que sur un passage en échec. */
  reprendreTfj(legalEntityId: string, runId: string): Promise<RunTfj>;
  /** Annule le passage. Le socle refuse si une journée postérieure a déjà tourné. */
  annulerTfj(legalEntityId: string, runId: string): Promise<RunTfj>;

  balance(legalEntityId: string, filtre: FiltreBalance, page: number, taille: number): Promise<PageBalance>;
  /** Une entrée par devise : une balance ne s'additionne pas entre devises. */
  totauxBalance(legalEntityId: string, filtre: FiltreBalance): Promise<readonly TotauxBalance[]>;
}

export const SIEGE = new InjectionToken<Siege>('Siege');
