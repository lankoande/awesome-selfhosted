import { InjectionToken } from '@angular/core';
import { ArreteCaisse, EtatCaisse } from './modele/caisse.modele';

/**
 * Le port de la caisse.
 *
 * Deux règles du socle que l'écran affiche sans les réimplémenter : la caisse
 * du porteur est résolue depuis son jeton — pas choisie dans une liste — et une
 * journée dont une caisse mouvementée n'est pas arrêtée ne peut pas être
 * clôturée par le traitement de fin de journée.
 */
export interface Caisse {
  /** L'état de la caisse du porteur, solde théorique compris. */
  etat(legalEntityId: string): Promise<EtatCaisse>;

  /**
   * Arrête la caisse sur un montant compté. Le socle calcule l'écart et
   * l'impute au compte d'écart : le poste ne décide de rien, il compte.
   */
  arreter(legalEntityId: string, tillId: string, compte: number, devise: string): Promise<ArreteCaisse>;
}

export const CAISSE = new InjectionToken<Caisse>('Caisse');
