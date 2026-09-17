import { EtatOperation } from '../../ui';

/**
 * Le modèle de la double validation, aligné sur `MakerChecker.View`.
 *
 * Les neuf statuts du socle se lisent tels quels : `PENDING`, `APPROVED`,
 * `REJECTED`, `EXPIRED`, `EXECUTED`, `FAILED`. On les traduit dans le
 * vocabulaire d'états de l'interface sans jamais en écraser deux en un.
 */
export type StatutSocle = 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | 'EXECUTED' | 'FAILED';

export interface OperationEnAttente {
  readonly id: string;
  /** L'opération d'habilitation, par exemple `LOAN_RESCHEDULE`. */
  readonly operation: string;
  /** Le cas d'usage soumis, par exemple `Loan.reschedule`. */
  readonly handler: string;
  readonly status: StatutSocle;
  readonly makerId: string;
  readonly makerUsername: string;
  readonly madeAt: string;
  readonly expiresAt: string;
  readonly decidedBy: string | null;
  readonly decidedAt: string | null;
  readonly decisionReason: string | null;
  /** La requête soumise, telle qu'elle sera rejouée à l'approbation. */
  readonly payload: Readonly<Record<string, unknown>>;
  readonly result: unknown;
  /** Renseigné quand l'exécution a échoué après une approbation valide. */
  readonly error: string | null;
}

export interface PageOperations {
  readonly elements: readonly OperationEnAttente[];
  readonly numero: number;
  readonly taille: number;
  readonly precedent: boolean;
  readonly suivant: boolean;
}

/** L'identité du porteur, quand le socle sait la dire. */
export interface Identite {
  readonly id: string;
  readonly username: string;
}

/**
 * Traduction des statuts du socle. Un statut, un état : `APPROVED` sans
 * exécution confirmée est une anomalie d'exploitation, et la fondre dans
 * « comptabilisé » la rendrait invisible le jour où elle compte.
 */
export function etatDe(statut: StatutSocle): EtatOperation {
  switch (statut) {
    case 'PENDING': return 'en-attente';
    case 'APPROVED': return 'approuve';
    case 'EXECUTED': return 'comptabilise';
    case 'REJECTED': return 'rejete';
    case 'FAILED': return 'echoue';
    case 'EXPIRED': return 'expire';
  }
}

/** Une opération n'est décidable qu'en attente, et avant son échéance. */
export function decidable(operation: OperationEnAttente, maintenant = new Date()): boolean {
  return operation.status === 'PENDING' && new Date(operation.expiresAt) > maintenant;
}

/**
 * L'état tel qu'un opérateur doit le lire.
 *
 * Le socle n'expire que paresseusement : il marque `EXPIRED` au moment où
 * quelqu'un tente de décider. Une ligne dont l'échéance est passée reste donc
 * `PENDING` en base — mais l'afficher « en attente » ferait perdre du temps à
 * un valideur sur une opération que personne ne peut plus décider.
 */
export function etatAffiche(operation: OperationEnAttente, maintenant = new Date()): EtatOperation {
  if (operation.status === 'PENDING' && new Date(operation.expiresAt) <= maintenant) return 'expire';
  return etatDe(operation.status);
}
