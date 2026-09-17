import { Montant } from '../../guichet/modele/guichet.modele';

/**
 * L'état d'une caisse à l'instant de l'arrêté.
 *
 * `book` est le **solde théorique** : ce que le registre dit que la caisse
 * contient, après tous les mouvements de la journée. Le comptage physique est
 * ce que le guichetier trouve dans le tiroir. L'écart entre les deux est le
 * sujet de l'arrêté — et il ne se rattrape pas le lendemain.
 */
export interface EtatCaisse {
  readonly tillId: string;
  readonly tillCode: string;
  readonly businessDate: string;
  readonly currency: string;
  readonly book: Montant;
  /** Nombre d'opérations passées par cette caisse aujourd'hui. */
  readonly mouvements: number;
  /** Déjà arrêtée aujourd'hui : on ne compte pas deux fois la même journée. */
  readonly arretee: boolean;
  readonly lacunes: readonly string[];
}

/** Miroir de `TillService.Closure`. */
export interface ArreteCaisse {
  readonly id: string;
  readonly tillId: string;
  readonly tillCode: string;
  readonly businessDate: string;
  readonly book: Montant;
  readonly counted: Montant;
  readonly difference: Montant;
  /** L'écriture d'écart, quand il y en a une. */
  readonly entryId: string | null;
}

/** L'écart, du point de vue de la caisse : positif = il y a plus que prévu. */
export function ecartDe(arrete: ArreteCaisse): number {
  return Number(arrete.difference.amount);
}
