import { Montant } from '../../guichet/modele/guichet.modele';

/**
 * Les moyens de paiement **d'un compte** : chéquiers, chèques, incidents, mandats.
 *
 * Ce sont les actes du guichet, pas ceux de la compensation. Ils ne se lisent
 * qu'en désignant un compte — le socle les expose ainsi, et c'est juste : un
 * chéquier appartient à un compte, un mandat est signé par un titulaire.
 */

// ------------------------------------------------------------ chéquiers

export type StatutChequier = 'ACTIVE' | 'CANCELLED';

export interface Chequier {
  readonly id: string;
  readonly firstNumber: number;
  readonly lastNumber: number;
  readonly deliveredOn: string;
  readonly status: StatutChequier;
  readonly fee: Montant | null;
}

export const LIBELLE_STATUT_CHEQUIER: Readonly<Record<StatutChequier, string>> = {
  ACTIVE: 'Délivré',
  CANCELLED: 'Annulé',
};

/** Ce qu'on demande en délivrant : le nombre de chèques ; les numéros suivent. */
export interface DemandeChequier {
  readonly count: number;
}

/**
 * Bornes du socle : un chéquier compte de 1 à 200 chèques.
 *
 * Ce ne sont pas des valeurs de goût. En deçà d'un, il n'y a pas de chéquier ;
 * au-delà de deux cents, une opposition sur perte porterait sur un carnet
 * qu'aucun client ne peut avoir suivi.
 */
export const CHEQUES_MINIMUM = 1;
export const CHEQUES_MAXIMUM = 200;

export function obstaclesAuChequier(demande: DemandeChequier): readonly string[] {
  if (!Number.isInteger(demande.count) || demande.count < CHEQUES_MINIMUM
      || demande.count > CHEQUES_MAXIMUM) {
    return [`Un chéquier compte de ${CHEQUES_MINIMUM} à ${CHEQUES_MAXIMUM} chèques.`];
  }
  return [];
}

// ------------------------------------------------------------ chèques

export type StatutCheque = 'UNUSED' | 'PAID' | 'STOPPED' | 'REJECTED';

export interface Cheque {
  readonly number: number;
  readonly bookId: string;
  readonly status: StatutCheque;
  readonly amount: Montant | null;
  readonly beneficiary: string | null;
  readonly paidOn: string | null;
  readonly stoppedOn: string | null;
  readonly stopReason: string | null;
}

export const LIBELLE_STATUT_CHEQUE: Readonly<Record<StatutCheque, string>> = {
  UNUSED: 'En circulation',
  PAID: 'Payé',
  STOPPED: 'Opposition',
  REJECTED: 'Rejeté',
};

/** Ce que l'état du chèque veut dire, dit au guichet. */
export const ATTENTE_CHEQUE: Readonly<Record<StatutCheque, string>> = {
  UNUSED: "Délivré au client, jamais présenté. Il peut l'être demain : ce numéro engage encore "
    + 'le compte.',
  PAID: 'Payé et comptabilisé. Un chèque payé ne se reprend pas.',
  STOPPED: 'Sous opposition. Aucun paiement ne passera sur ce numéro.',
  REJECTED: "Présenté sans provision suffisante. L'incident est au dossier, et il se déclare.",
};

export type MotifOpposition = 'LOSS' | 'THEFT' | 'FRAUDULENT_USE' | 'BEARER_INSOLVENCY';

/**
 * Les quatre motifs d'opposition, et ce sont les seuls.
 *
 * L'opposition sur chèque n'est pas libre : la loi uniforme UEMOA l'enferme
 * dans ces cas. Une opposition hors de ces motifs — un client mécontent de sa
 * livraison — expose la banque, et le guichetier qui la passe.
 */
export const LIBELLE_MOTIF_OPPOSITION: Readonly<Record<MotifOpposition, string>> = {
  LOSS: 'Perte du chèque',
  THEFT: 'Vol du chèque',
  FRAUDULENT_USE: 'Utilisation frauduleuse',
  BEARER_INSOLVENCY: 'Redressement ou liquidation du porteur',
};

export type ModePaiement = 'CASH' | 'CLEARING';

export const LIBELLE_MODE_PAIEMENT: Readonly<Record<ModePaiement, string>> = {
  CASH: 'Au guichet, sur votre caisse',
  CLEARING: 'Par compensation, sur un nostro',
};

export interface DemandePaiementCheque {
  readonly number: number;
  readonly amount: string;
  readonly currency: string;
  readonly mode: ModePaiement;
  readonly nostroAccountId: string | null;
  readonly beneficiary: string | null;
}

export interface DemandeOpposition {
  readonly number: number;
  readonly reason: MotifOpposition;
}

/** Les actes qu'un chèque accepte encore. */
export type ActeCheque = 'PAYER' | 'OPPOSER';

export const LIBELLE_ACTE_CHEQUE: Readonly<Record<ActeCheque, string>> = {
  PAYER: 'Payer',
  OPPOSER: 'Faire opposition',
};

/**
 * Ce qu'on peut faire d'un chèque.
 *
 * Seul un chèque **en circulation** se paie ou se frappe d'opposition. Un
 * chèque payé ne se reprend pas ; un chèque déjà sous opposition n'a pas besoin
 * d'une seconde ; un chèque rejeté a fait son incident.
 */
export function actesSurCheque(cheque: Cheque): readonly ActeCheque[] {
  return cheque.status === 'UNUSED' ? ['PAYER', 'OPPOSER'] : [];
}

/** Ce qui empêche de payer ce chèque. */
export function obstaclesAuPaiement(demande: DemandePaiementCheque): readonly string[] {
  const obstacles: string[] = [];
  const montant = Number(demande.amount);
  if (demande.amount.trim() === '' || !Number.isFinite(montant) || montant <= 0) {
    obstacles.push('Le montant du chèque est un nombre strictement positif.');
  }
  if (demande.mode === 'CLEARING' && !(demande.nostroAccountId ?? '').trim()) {
    obstacles.push('Un paiement par compensation se règle sur un compte nostro : il faut le '
                   + 'désigner. Au guichet, c\'est votre caisse, et elle vient du jeton.');
  }
  // Le socle accepte un porteur vide ; le guichet non. Au comptoir, celui qui
  // présente le chèque est devant vous : ne pas le nommer, c'est payer à
  // personne et n'avoir rien à opposer le jour où le tireur conteste. En
  // compensation, le nom vient de la banque présentatrice, pas du poste.
  if (demande.mode === 'CASH' && !(demande.beneficiary ?? '').trim()) {
    obstacles.push('Au guichet, le porteur se nomme : il est devant vous, et c\'est lui que le '
                   + 'chèque paie.');
  }
  return obstacles;
}

// ------------------------------------------------------------ incidents

export interface IncidentCheque {
  readonly id: string;
  readonly number: number;
  readonly amount: Montant;
  readonly occurredOn: string;
  readonly reason: string;
  readonly presentedBy: string | null;
}

// ------------------------------------------------------------ mandats

export type StatutMandat = 'ACTIVE' | 'REVOKED';

export interface Mandat {
  readonly id: string;
  readonly reference: string;
  readonly creditorId: string;
  readonly creditorName: string;
  readonly creditorAccountId: string | null;
  readonly creditorBank: string | null;
  readonly creditorAccount: string | null;
  readonly signedOn: string | null;
  readonly validFrom: string | null;
  readonly validTo: string | null;
  readonly maxAmount: Montant | null;
  readonly status: StatutMandat;
  readonly revokedOn: string | null;
  readonly revocationReason: string | null;
}

export const LIBELLE_STATUT_MANDAT: Readonly<Record<StatutMandat, string>> = {
  ACTIVE: 'Actif',
  REVOKED: 'Révoqué',
};

/** Le créancier est un compte de la banque, ou une banque et un compte d'ailleurs. */
export type NatureCreancier = 'INTERNE' | 'EXTERNE';

export const LIBELLE_NATURE_CREANCIER: Readonly<Record<NatureCreancier, string>> = {
  INTERNE: 'Un compte de la banque',
  EXTERNE: 'Une banque et un compte d\'ailleurs',
};

export function natureDuCreancier(mandat: Mandat): NatureCreancier {
  return mandat.creditorAccountId !== null ? 'INTERNE' : 'EXTERNE';
}

export interface DemandeMandat {
  readonly reference: string;
  readonly creditorId: string;
  readonly creditorName: string;
  readonly creditorAccountId: string | null;
  readonly creditorBank: string | null;
  readonly creditorAccount: string | null;
  readonly signedOn: string | null;
  readonly validFrom: string | null;
  readonly validTo: string | null;
  readonly maxAmount: string | null;
  readonly currency: string;
}

/**
 * Ce qui empêche d'enregistrer ce mandat.
 *
 * Le plafond est facultatif au socle, et l'écran ne l'invente pas — mais il le
 * dit : un mandat sans plafond autorise le créancier à prélever ce qu'il veut,
 * et c'est au client de le savoir avant de signer.
 *
 * Le créancier, lui, n'est pas facultatif et ne se désigne pas deux fois : ou
 * bien c'est un compte de la banque, ou bien c'est une banque et un compte
 * d'ailleurs. Le socle refuse les deux ensemble comme il refuse aucun des deux
 * — et l'écran refuse avant d'envoyer, parce que le message du socle arriverait
 * après que le client a signé.
 */
export function obstaclesAuMandat(demande: DemandeMandat): readonly string[] {
  const obstacles: string[] = [];
  if (!demande.reference.trim()) {
    obstacles.push('La référence du mandat est celle que le créancier citera à chaque '
                   + 'prélèvement : sans elle, aucun rapprochement.');
  }
  if (!demande.creditorId.trim()) {
    obstacles.push('L\'identifiant du créancier est celui qui voyage sur chaque présentation : '
                   + 'il identifie le préleveur, quand son nom ne fait que le désigner.');
  }
  if (!demande.creditorName.trim()) {
    obstacles.push('Le créancier est celui qui prélèvera : il se nomme.');
  }
  const interne = (demande.creditorAccountId ?? '').trim() !== '';
  const externe = (demande.creditorBank ?? '').trim() !== ''
                  && (demande.creditorAccount ?? '').trim() !== '';
  if (interne === externe) {
    obstacles.push(interne
      ? 'Le créancier est un compte de la banque, ou une banque et un compte d\'ailleurs : pas '
        + 'les deux.'
      : 'Le créancier se rattache à un compte : celui qu\'il tient chez vous, ou sa banque et '
        + 'son compte ailleurs.');
  }
  if (!demande.signedOn) {
    obstacles.push('Un mandat porte la date à laquelle le client l\'a signé : c\'est elle qui '
                   + 'fait foi si le prélèvement est contesté.');
  }
  if (!demande.validFrom) {
    obstacles.push('Un mandat prend effet à une date : sans elle, on ne sait pas à partir de '
                   + 'quand le créancier peut prélever.');
  }
  if (demande.maxAmount !== null && demande.maxAmount.trim() !== '') {
    const plafond = Number(demande.maxAmount);
    if (!Number.isFinite(plafond) || plafond <= 0) {
      obstacles.push('Un plafond de prélèvement est un nombre strictement positif. Laissez-le '
                     + 'vide pour ne pas en poser.');
    }
  }
  if (demande.validFrom && demande.validTo && demande.validTo < demande.validFrom) {
    obstacles.push('La fin de validité ne précède pas son début.');
  }
  return obstacles;
}

/** Un mandat révoqué ne se révoque pas deux fois. */
export function peutRevoquer(mandat: Mandat): boolean {
  return mandat.status === 'ACTIVE';
}
