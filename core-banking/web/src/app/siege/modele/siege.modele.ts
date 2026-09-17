import { Montant } from '../../guichet/modele/guichet.modele';

/** Miroir de `TfjRun.StepExecution` — une étape du traitement. */
export interface EtapeRun {
  readonly order: number;
  readonly name: string;
  readonly status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';
  readonly read: number;
  readonly written: number;
  /**
   * Les anomalies d'une étape qui a **réussi** : elles ne bloquent pas la
   * chaîne, mais elles se lisent. Une anomalie non lue est une anomalie qui
   * revient le lendemain, en plus gros.
   */
  readonly anomalies: readonly string[];
  /** Vrai quand l'échec de cette étape arrête tout le traitement. */
  readonly blocking: boolean;
  readonly error: string | null;
}

/** Miroir de `TfjRun` — un passage du traitement de fin de journée. */
export interface RunTfj {
  readonly id: string;
  readonly legalEntityId: string;
  readonly businessDate: string;
  /** `DRY_RUN` n'écrit rien : c'est un essai, et l'écran ne doit jamais le laisser croire autrement. */
  readonly mode: 'REAL' | 'DRY_RUN';
  readonly status: 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
  readonly startedAt: string;
  readonly finishedAt: string | null;
  readonly steps: readonly EtapeRun[];
}

/** Miroir de `LedgerUseCases.BalanceLine`. */
export interface LigneBalance {
  readonly accountId: string;
  readonly code: string;
  /**
   * L'intitulé du compte. Le contrat ne le porte pas : l'implémentation HTTP le
   * laisse vide plutôt que d'inventer un libellé à partir du numéro, et
   * l'écran affiche alors le seul code — ce qui est exact.
   */
  readonly libelle?: string;
  readonly currency: string;
  readonly kind: 'CUSTOMER' | 'GL' | 'INTERNAL' | 'NOSTRO' | 'SUSPENSE' | 'POSITION';
  readonly nature: 'BALANCE_SHEET' | 'PROFIT_AND_LOSS' | 'OFF_BALANCE_SHEET';
  readonly normalBalance: 'DEBIT' | 'CREDIT';
  readonly openingDebit: Montant;
  readonly openingCredit: Montant;
  readonly movementDebit: Montant;
  readonly movementCredit: Montant;
  readonly closingDebit: Montant;
  readonly closingCredit: Montant;
}

/**
 * Miroir de `LedgerUseCases.BalanceTotals`, une entrée par devise.
 *
 * `balanced` est l'information de tête, pas une colonne de plus : une balance
 * déséquilibrée veut dire que le registre ne se tient pas, et rien de ce qu'on
 * en tire — état financier, déclaration — ne vaut tant que ce n'est pas réglé.
 */
export interface TotauxBalance {
  readonly currency: string;
  readonly accounts: number;
  readonly balanced: boolean;
  readonly openingDebit: Montant;
  readonly openingCredit: Montant;
  readonly movementDebit: Montant;
  readonly movementCredit: Montant;
  readonly closingDebit: Montant;
  readonly closingCredit: Montant;
}

export interface PageBalance {
  readonly lignes: readonly LigneBalance[];
  readonly numero: number;
  readonly taille: number;
  readonly precedent: boolean;
  readonly suivant: boolean;
}

export interface FiltreBalance {
  readonly du: string | null;
  readonly au: string | null;
  readonly kind: LigneBalance['kind'] | null;
}

/** Libellés français des natures de compte du socle. */
export const LIBELLE_KIND: Readonly<Record<LigneBalance['kind'], string>> = {
  CUSTOMER: 'Clientèle',
  GL: 'Général',
  INTERNAL: 'Interne',
  NOSTRO: 'Nostro',
  SUSPENSE: 'Suspens',
  POSITION: 'Position',
};

export const LIBELLE_NATURE: Readonly<Record<LigneBalance['nature'], string>> = {
  BALANCE_SHEET: 'Bilan',
  PROFIT_AND_LOSS: 'Résultat',
  OFF_BALANCE_SHEET: 'Hors bilan',
};

/**
 * Une étape n'a pas été tentée quand le traitement s'est arrêté avant elle.
 * « En attente » sur un run terminé ne veut pas dire « à venir » : ça veut dire
 * « jamais exécutée », et c'est ce qu'il faut lire.
 */
export function nonTentee(etape: EtapeRun, run: RunTfj): boolean {
  return etape.status === 'PENDING' && run.status !== 'RUNNING';
}

/** Le nombre d'anomalies de tout le passage, bloquantes exclues. */
export function anomaliesDe(run: RunTfj): number {
  return run.steps.reduce((somme, etape) => somme + etape.anomalies.length, 0);
}
