import { Montant } from '../../guichet/modele/guichet.modele';

/**
 * Les moyens de paiement : virements sortants, remises de chèques, prélèvements.
 *
 * Trois files de travail qui se ressemblent et qui ne sont pas la même chose.
 * Toutes trois portent un **engagement pris avant d'être dénoué** : la banque a
 * débité, crédité ou bloqué, et attend le correspondant. Ce qui les distingue
 * est ce qu'on peut encore faire, et ce fichier ne dit que cela — le socle
 * refuse de toute façon, ce qui est ici évite un aller-retour.
 *
 * Aucun montant n'est calculé ici. Les frais et la taxe viennent du socle, qui
 * seul connaît le barème du produit et du canal.
 */

// ------------------------------------------------------------ virements émis

/**
 * Un ordre de paiement sortant.
 *
 * Il naît **ORDERED** : le compte du client est déjà débité, frais compris, et
 * le montant attend sur un compte de règlement sortant. Rien n'est parti.
 */
export interface OrdrePaiement {
  readonly id: string;
  readonly accountId: string;
  readonly amount: Montant;
  readonly fee: Montant | null;
  readonly tax: Montant | null;
  readonly beneficiaryName: string;
  readonly beneficiaryBank: string;
  readonly beneficiaryAccount: string;
  readonly reference: string | null;
  readonly channel: string | null;
  readonly status: StatutOrdre;
  readonly orderedOn: string;
  readonly sentOn: string | null;
  readonly settledOn: string | null;
  readonly returnedOn: string | null;
  readonly returnReason: string | null;
  readonly cancelledOn: string | null;
  readonly cancelReason: string | null;
}

export type StatutOrdre = 'ORDERED' | 'SENT' | 'SETTLED' | 'RETURNED' | 'CANCELLED';

export interface DemandeOrdre {
  readonly accountId: string;
  readonly amount: string;
  readonly currency: string;
  readonly beneficiaryName: string;
  readonly beneficiaryBank: string;
  readonly beneficiaryAccount: string;
  readonly reference: string | null;
  readonly channel: string | null;
}

export const LIBELLE_STATUT_ORDRE: Readonly<Record<StatutOrdre, string>> = {
  ORDERED: 'Enregistré',
  SENT: 'Envoyé',
  SETTLED: 'Réglé',
  RETURNED: 'Retourné',
  CANCELLED: 'Annulé',
};

/** Ce que l'ordre attend, dit au présent. */
export const ATTENTE_ORDRE: Readonly<Record<StatutOrdre, string>> = {
  ORDERED: "Débité du compte, pas encore parti. C'est le dernier moment où l'ordre s'annule.",
  SENT: "Parti au système de paiement. Il ne s'annule plus : il se règle, ou il revient.",
  SETTLED: 'Le correspondant a payé. Le compte de règlement est soldé sur le nostro.',
  RETURNED: 'Revenu impayé. Le montant est rendu au client ; les frais restent acquis.',
  CANCELLED: "Retiré avant d'être envoyé. Le débit a été contre-passé.",
};

// ------------------------------------------------------------ remises de chèques

/**
 * Une remise de chèque tiré sur une autre banque.
 *
 * Elle naît **DEPOSITED** : le client est crédité *sauf bonne fin*, et le
 * montant est bloqué jusqu'au règlement. Le solde monte, le disponible non —
 * c'est la seule façon honnête de présenter un chèque en cours d'encaissement.
 */
export interface Remise {
  readonly id: string;
  readonly accountId: string;
  readonly amount: Montant;
  readonly draweeBank: string;
  readonly chequeNumber: string;
  readonly drawerName: string | null;
  readonly channel: string | null;
  readonly status: StatutRemise;
  readonly depositedOn: string;
  readonly valueDate: string | null;
  readonly settledOn: string | null;
  readonly returnedOn: string | null;
  readonly returnReason: string | null;
}

export type StatutRemise = 'DEPOSITED' | 'SETTLED' | 'RETURNED';

export interface DemandeRemise {
  readonly accountId: string;
  readonly amount: string;
  readonly currency: string;
  readonly draweeBank: string;
  readonly chequeNumber: string;
  readonly drawerName: string | null;
  readonly channel: string | null;
}

export const LIBELLE_STATUT_REMISE: Readonly<Record<StatutRemise, string>> = {
  DEPOSITED: 'À l’encaissement',
  SETTLED: 'Réglée',
  RETURNED: 'Impayée',
};

export const ATTENTE_REMISE: Readonly<Record<StatutRemise, string>> = {
  DEPOSITED: 'Créditée sauf bonne fin. Le montant est bloqué : le solde monte, le disponible non.',
  SETTLED: 'La banque tirée a payé. Le blocage est levé, le client dispose des fonds.',
  RETURNED: 'Revenue impayée. Le crédit a été contre-passé et le blocage levé.',
};

// ------------------------------------------------------------ prélèvements

export type SensPrelevement = 'RECEIVED' | 'ISSUED';
export type StatutPrelevement = 'PENDING' | 'COLLECTED' | 'REJECTED' | 'SETTLED' | 'CANCELLED'
  | 'RETURNED' | 'REFUNDED';

/**
 * Un prélèvement, reçu ou émis.
 *
 * **Reçu** : un créancier prélève sur un compte de la banque, sur un mandat
 * signé par le débiteur. **Émis** : un client de la banque prélève sur un
 * compte d'ailleurs, et la banque le crédite sauf bonne fin.
 *
 * Le passage de `PENDING` à `COLLECTED` n'est pas un geste de poste : c'est le
 * traitement de fin de journée qui l'exécute à l'échéance.
 */
export interface Prelevement {
  readonly id: string;
  readonly direction: SensPrelevement;
  readonly accountId: string;
  readonly mandateId: string | null;
  readonly amount: Montant;
  readonly fee: Montant | null;
  readonly dueDate: string;
  readonly counterpartyName: string | null;
  readonly counterpartyBank: string | null;
  readonly counterpartyAccount: string | null;
  readonly mandateReference: string | null;
  readonly reference: string | null;
  readonly channel: string | null;
  readonly status: StatutPrelevement;
  readonly presentedOn: string | null;
  readonly executedOn: string | null;
  readonly valueDate: string | null;
  readonly rejectionReason: string | null;
  readonly settledOn: string | null;
  readonly closedOn: string | null;
  readonly closeReason: string | null;
}

export const LIBELLE_SENS: Readonly<Record<SensPrelevement, string>> = {
  RECEIVED: 'Reçu',
  ISSUED: 'Émis',
};

export const LIBELLE_STATUT_PRELEVEMENT: Readonly<Record<StatutPrelevement, string>> = {
  PENDING: 'En attente',
  COLLECTED: 'Exécuté',
  REJECTED: 'Rejeté',
  SETTLED: 'Réglé',
  CANCELLED: 'Annulé',
  RETURNED: 'Retourné',
  REFUNDED: 'Remboursé',
};

export const ATTENTE_PRELEVEMENT: Readonly<Record<StatutPrelevement, string>> = {
  PENDING: "Présenté, pas encore exécuté. C'est le traitement de fin de journée qui l'exécutera "
    + "à l'échéance, pas le poste.",
  COLLECTED: 'Exécuté sur le compte. Le règlement avec le correspondant reste à faire.',
  REJECTED: "Le traitement n'a pas pu exécuter : provision insuffisante, mandat révoqué, compte "
    + 'bloqué. Le motif est au dossier.',
  SETTLED: 'Réglé avec le correspondant.',
  CANCELLED: 'Retiré. Ce qui avait été passé a été contre-passé.',
  RETURNED: 'Revenu impayé. Les frais restent acquis.',
  REFUNDED: 'Remboursé au débiteur, qui a contesté. Les frais restent acquis.',
};

// ------------------------------------------------------------ ce qu'on peut faire

/** Les actes que le poste propose, tous domaines confondus. */
export type Acte = 'ENVOYER' | 'REGLER' | 'RETOURNER' | 'ANNULER' | 'REMBOURSER';

export const LIBELLE_ACTE: Readonly<Record<Acte, string>> = {
  ENVOYER: 'Envoyer',
  REGLER: 'Régler',
  RETOURNER: 'Retourner impayé',
  ANNULER: 'Annuler',
  REMBOURSER: 'Rembourser',
};

/** Les actes qui exigent un motif écrit : il reste au dossier et remonte au client. */
export const ACTES_A_MOTIF: readonly Acte[] = ['RETOURNER', 'ANNULER', 'REMBOURSER'];

/** Les actes qui exigent un compte nostro : c'est là que le correspondant règle. */
export const ACTES_A_NOSTRO: readonly Acte[] = ['REGLER'];

/**
 * Ce qu'un ordre de paiement accepte encore.
 *
 * Mêmes gardes que `PaymentService` : envoyer depuis `ORDERED`, régler depuis
 * `SENT`, retourner depuis `SENT` ou `SETTLED`, annuler depuis `ORDERED` seul —
 * une fois parti, un ordre ne s'annule plus, il revient.
 */
export function actesSurOrdre(ordre: OrdrePaiement): readonly Acte[] {
  switch (ordre.status) {
    case 'ORDERED':
      return ['ENVOYER', 'ANNULER'];
    case 'SENT':
      return ['REGLER', 'RETOURNER'];
    case 'SETTLED':
      return ['RETOURNER'];
    default:
      return [];
  }
}

/** Ce qu'une remise accepte encore : régler ou retourner, tant qu'elle est à l'encaissement. */
export function actesSurRemise(remise: Remise): readonly Acte[] {
  return remise.status === 'DEPOSITED' ? ['REGLER', 'RETOURNER'] : [];
}

/**
 * Ce qu'un prélèvement accepte encore.
 *
 * Le sens compte autant que l'état : seul un prélèvement **reçu** se rembourse
 * — c'est le débiteur qui conteste —, et seul un prélèvement **émis** revient
 * impayé — c'est le débiteur d'ailleurs qui ne paie pas.
 */
export function actesSurPrelevement(prelevement: Prelevement): readonly Acte[] {
  const actes: Acte[] = [];
  const recu = prelevement.direction === 'RECEIVED';
  if (prelevement.status === 'COLLECTED') {
    actes.push('REGLER');
  }
  if (prelevement.status === 'PENDING' || (prelevement.status === 'COLLECTED' && recu)) {
    actes.push('ANNULER');
  }
  if (!recu && (prelevement.status === 'COLLECTED' || prelevement.status === 'SETTLED')) {
    actes.push('RETOURNER');
  }
  if (recu && prelevement.status === 'SETTLED') {
    actes.push('REMBOURSER');
  }
  return actes;
}

/**
 * Ce qui empêche d'enregistrer cet ordre de paiement.
 *
 * Le socle refuse de toute façon ; ce qui est ici évite d'envoyer une requête
 * qu'on sait fausse, et le dit dans les mots du métier.
 */
export function obstaclesAUnOrdre(demande: DemandeOrdre): readonly string[] {
  const obstacles: string[] = [];
  if (!demande.accountId.trim()) {
    obstacles.push('Un ordre part d’un compte : il faut le désigner.');
  }
  if (!estMontantPositif(demande.amount)) {
    obstacles.push('Le montant est un nombre strictement positif.');
  }
  if (!demande.beneficiaryName.trim() || !demande.beneficiaryBank.trim()
      || !demande.beneficiaryAccount.trim()) {
    obstacles.push('Un paiement sortant désigne son bénéficiaire : nom, banque et compte. '
                   + "Un virement mal adressé revient des semaines plus tard, et les frais "
                   + 'restent acquis.');
  }
  return obstacles;
}

/** Ce qui empêche d'enregistrer cette remise. */
export function obstaclesAUneRemise(demande: DemandeRemise): readonly string[] {
  const obstacles: string[] = [];
  if (!demande.accountId.trim()) {
    obstacles.push('Une remise crédite un compte : il faut le désigner.');
  }
  if (!estMontantPositif(demande.amount)) {
    obstacles.push('Le montant est un nombre strictement positif.');
  }
  if (!demande.draweeBank.trim()) {
    obstacles.push('La banque tirée est celle qui paiera : sans elle, la remise ne se présente '
                   + 'nulle part.');
  }
  if (!demande.chequeNumber.trim()) {
    obstacles.push('Le numéro du chèque est ce qui distingue deux remises du même montant.');
  }
  return obstacles;
}

function estMontantPositif(valeur: string): boolean {
  const nombre = Number(valeur);
  return valeur.trim() !== '' && Number.isFinite(nombre) && nombre > 0;
}
