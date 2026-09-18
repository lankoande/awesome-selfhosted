import { EtatOperation } from '../../ui';

/** Un montant, tel que le socle le rend : jamais un nombre flottant. */
export interface Montant {
  readonly amount: string;
  readonly currency: string;
}

// ------------------------------------------------------------------ catalogue

/** Qui attend la déclaration. */
export type Destinataire =
  | 'CENTRAL_BANK' | 'BANKING_COMMISSION' | 'CREDIT_BUREAU' | 'TAX_AUTHORITY';

/** Ce que le code sait produire. Ajouter une méthode est une livraison. */
export type MethodeEtat =
  | 'ACCOUNTING_SITUATION' | 'CREDIT_REGISTRY' | 'PAYMENT_INCIDENTS' | 'CREDIT_BUREAU'
  | 'TAX_COLLECTION' | 'STATEMENT_PACK' | 'CONSOLIDATED_STATEMENTS';

export type Frequence = 'MONTHLY' | 'QUARTERLY' | 'YEARLY';

export interface Declaration {
  readonly id: string;
  readonly code: string;
  readonly label: string;
  readonly recipient: Destinataire;
  readonly method: MethodeEtat;
  readonly frequency: Frequence;
  readonly deadlineDays: number | null;
  readonly thresholdAmount: string | null;
  readonly subjectCode: string | null;
  readonly validFrom: string | null;
  readonly validTo: string | null;
}

export interface DemandeDeclaration {
  readonly code: string;
  readonly label: string;
  readonly recipient: Destinataire;
  readonly method: MethodeEtat;
  readonly frequency: Frequence;
  readonly deadlineDays: number | null;
  readonly thresholdAmount: string | null;
  readonly validFrom: string | null;
  readonly validTo: string | null;
}

// --------------------------------------------------------------------- états

export type StatutEtat = 'PRODUCED' | 'TRANSMITTED' | 'CANCELLED';

/** Ce que désigne une ligne d'état. */
export type NatureSujet = 'PARTY' | 'ACCOUNT' | 'GL_ACCOUNT';

export interface LigneEtat {
  readonly subjectKind: NatureSujet;
  readonly subjectReference: string | null;
  readonly label: string | null;
  readonly amount: Montant | null;
  readonly offBalance: Montant | null;
  readonly classification: string | null;
  readonly daysPastDue: number | null;
  readonly occurrences: number | null;
  readonly detail: string | null;
}

/**
 * Un état produit.
 *
 * **Il est figé.** Il porte ses lignes *et* le paramétrage sous lequel il a été
 * produit — le seuil du jour de la production, pas celui d'aujourd'hui. Sans
 * cela, un état régénéré six mois plus tard sortirait différent sans qu'on
 * puisse dire si ce sont les données ou le paramétrage qui ont bougé, et c'est
 * précisément la question que pose l'inspection.
 */
export interface Etat {
  readonly id: string;
  readonly declarationId: string;
  readonly declarationCode: string;
  readonly method: MethodeEtat;
  readonly subjectCode: string | null;
  readonly periodStart: string | null;
  readonly periodEnd: string | null;
  readonly dueOn: string | null;
  readonly producedOn: string | null;
  readonly thresholdUsed: string | null;
  readonly lineCount: number;
  readonly totalAmount: Montant | null;
  readonly status: StatutEtat;
  readonly transmittedOn: string | null;
  readonly transmissionReference: string | null;
  readonly cancelledOn: string | null;
  readonly cancellationReason: string | null;
  readonly anomalies: readonly string[];
  readonly lignes: readonly LigneEtat[];
}

/** L'état, et ce que donne son recalcul. Un écart est une anomalie. */
export interface DossierEtat {
  readonly etat: Etat;
  readonly ecarts: readonly string[];
}

/** Une échéance déclarative, vue à la date comptable. */
export interface Echeance {
  readonly declarationCode: string;
  readonly periodEnd: string | null;
  readonly dueOn: string | null;
  readonly produced: boolean;
}

export interface DemandeTransmission {
  readonly reference: string;
  readonly transmittedOn: string | null;
}

// ---------------------------------------------------------------- fiscalité

/** Sur quoi la taxe se calcule. */
export type AssietteTaxe = 'INTEREST_PAID' | 'FEES_CHARGED' | 'TRANSACTION';

export interface RegleFiscale {
  readonly id: string;
  readonly code: string;
  readonly label: string;
  readonly basis: AssietteTaxe;
  readonly ratePercent: string | null;
  readonly collectionAccountId: string | null;
  readonly validFrom: string | null;
  readonly validTo: string | null;
}

export interface DemandeRegleFiscale {
  readonly code: string;
  readonly label: string;
  readonly basis: AssietteTaxe;
  readonly ratePercent: string | null;
  readonly collectionAccountId: string;
  readonly validFrom: string | null;
  readonly validTo: string | null;
}

// ----------------------------------------------------------------- libellés

export const LIBELLE_DESTINATAIRE: Readonly<Record<Destinataire, string>> = {
  CENTRAL_BANK: 'Banque centrale',
  BANKING_COMMISSION: 'Commission bancaire',
  CREDIT_BUREAU: "Bureau d'information sur le crédit",
  TAX_AUTHORITY: 'Administration fiscale',
};

export const LIBELLE_METHODE_ETAT: Readonly<Record<MethodeEtat, string>> = {
  ACCOUNTING_SITUATION: 'Situation comptable',
  CREDIT_REGISTRY: 'Centrale des risques',
  PAYMENT_INCIDENTS: 'Incidents de paiement',
  CREDIT_BUREAU: "Bureau d'information sur le crédit",
  TAX_COLLECTION: 'Taxes collectées',
  STATEMENT_PACK: 'Liasse',
  CONSOLIDATED_STATEMENTS: 'Comptes consolidés',
};

/** Ce que la méthode va chercher, dit à celui qui déclare. */
export const EFFET_METHODE_ETAT: Readonly<Record<MethodeEtat, string>> = {
  ACCOUNTING_SITUATION: 'Les soldes du grand livre à la fin de la période.',
  CREDIT_REGISTRY: 'Les engagements de crédit au-delà du seuil de recensement.',
  PAYMENT_INCIDENTS: 'Les incidents constatés sur la période — chèques et prélèvements impayés.',
  CREDIT_BUREAU: "Les engagements des clients qui ont donné leur consentement, et eux seuls.",
  TAX_COLLECTION: 'Les taxes retenues et collectées sur la période.',
  STATEMENT_PACK: 'La liasse déclarée : bilan, compte de résultat, hors bilan.',
  CONSOLIDATED_STATEMENTS: 'Les comptes du groupe, membre par membre, éliminations faites.',
};

/** Les méthodes qui admettent un seuil de recensement. Les autres n'en ont pas. */
export const METHODES_A_SEUIL: readonly MethodeEtat[] = ['CREDIT_REGISTRY'];

export const LIBELLE_FREQUENCE: Readonly<Record<Frequence, string>> = {
  MONTHLY: 'Mensuelle',
  QUARTERLY: 'Trimestrielle',
  YEARLY: 'Annuelle',
};

export const LIBELLE_STATUT_ETAT: Readonly<Record<StatutEtat, string>> = {
  PRODUCED: 'Produit',
  TRANSMITTED: 'Transmis',
  CANCELLED: 'Annulé',
};

export const LIBELLE_ASSIETTE: Readonly<Record<AssietteTaxe, string>> = {
  INTEREST_PAID: 'Intérêts versés',
  FEES_CHARGED: 'Commissions perçues',
  TRANSACTION: 'Opérations',
};

export const EFFET_ASSIETTE: Readonly<Record<AssietteTaxe, string>> = {
  INTEREST_PAID:
    'Retenue à la source sur les intérêts crédités au client. Elle diminue ce qu’il reçoit.',
  FEES_CHARGED: 'Taxe sur les commissions facturées. Elle augmente ce que le client paie.',
  TRANSACTION: 'Taxe assise sur l’opération elle-même.',
};

export const LIBELLE_NATURE_SUJET: Readonly<Record<NatureSujet, string>> = {
  PARTY: 'Tiers',
  ACCOUNT: 'Compte',
  GL_ACCOUNT: 'Compte général',
};

// ------------------------------------------------------------------- règles

/** L'état visuel d'un état réglementaire. */
export function etatDeLEtat(statut: StatutEtat): EtatOperation {
  switch (statut) {
    case 'PRODUCED': return 'en-attente';
    case 'TRANSMITTED': return 'comptabilise';
    case 'CANCELLED': return 'contre-passe';
  }
}

/**
 * Ce qui empêche de transmettre cet état, dit en clair.
 *
 * **La règle qui compte : un état qui porte des anomalies ne se transmet pas.**
 * Il se produit — c'est ainsi qu'on voit ce qui ne va pas — mais on ne déclare
 * pas au superviseur des comptes dont on sait qu'ils sont faux. Corriger,
 * reprendre l'état, puis transmettre.
 */
export function obstaclesALaTransmission(etat: Etat): readonly string[] {
  const obstacles: string[] = [];
  if (etat.status === 'TRANSMITTED') {
    obstacles.push('Cet état est déjà transmis : il ne se transmet pas deux fois.');
  }
  if (etat.status === 'CANCELLED') {
    obstacles.push('Cet état est annulé : reprenez-en un, puis transmettez-le.');
  }
  for (const anomalie of etat.anomalies) {
    obstacles.push(anomalie);
  }
  return obstacles;
}

/**
 * Ce qui empêche d'annuler cet état.
 *
 * **Ce qui est transmis ne s'annule pas** : on dépose un rectificatif, on ne
 * réécrit pas l'histoire.
 */
export function obstaclesALAnnulation(etat: Etat): readonly string[] {
  if (etat.status === 'TRANSMITTED') {
    return ['Ce qui est transmis ne s’annule pas : il se rectifie par un dépôt suivant.'];
  }
  if (etat.status === 'CANCELLED') {
    return ['Cet état est déjà annulé.'];
  }
  return [];
}

/** En retard : l'échéance est passée et rien n'est parti. */
export function enRetard(etat: Etat, aujourdHui: string): boolean {
  return etat.status !== 'TRANSMITTED' && etat.dueOn !== null && aujourdHui > etat.dueOn;
}

/**
 * Les échéances qui ne sont pas encore produites.
 *
 * Le socle rend **toutes** les échéances dépassées ; celles qui sont produites
 * y figurent parce qu'une production n'est pas un dépôt. L'écran distingue les
 * deux : ce qui n'existe pas encore, et ce qui existe mais n'est pas parti.
 */
export function aProduire(echeances: readonly Echeance[]): readonly Echeance[] {
  return echeances.filter((e) => !e.produced);
}

/**
 * Ce qui empêche de déclarer cette déclaration au catalogue.
 *
 * Deux refus du socle sont anticipés : un délai de dépôt absent, et un seuil
 * posé sur une méthode qui n'en admet pas.
 */
export function obstaclesAuCatalogue(demande: DemandeDeclaration): readonly string[] {
  const obstacles: string[] = [];
  if (demande.code.trim() === '') obstacles.push('Le code est obligatoire.');
  if (demande.label.trim() === '') obstacles.push('Le libellé est obligatoire.');
  if (!demande.validFrom) obstacles.push('La date d’entrée en vigueur est obligatoire.');
  if (demande.validFrom && demande.validTo && demande.validTo < demande.validFrom) {
    obstacles.push('Une déclaration ne cesse pas avant de commencer.');
  }
  if (demande.deadlineDays === null || demande.deadlineDays <= 0) {
    obstacles.push('Le délai de dépôt est obligatoire : sans lui, aucun retard ne se constate.');
  }
  const seuil = demande.thresholdAmount;
  const admetUnSeuil = METHODES_A_SEUIL.includes(demande.method);
  if (seuil !== null && seuil !== '' && !admetUnSeuil) {
    obstacles.push(`La méthode «${' '}${LIBELLE_METHODE_ETAT[demande.method]}${' '}» `
      + 'ne recense pas au-delà d’un seuil : ce seuil ne serait jamais lu.');
  }
  if (admetUnSeuil && (seuil === null || seuil === '')) {
    obstacles.push('La centrale des risques recense au-delà d’un seuil : il est obligatoire.');
  }
  return obstacles;
}

/**
 * Ce qui empêche de déclarer cette règle fiscale.
 *
 * Le taux vit entre 0 et 100 — un taux de 25 n'est pas 0,25 — et le compte de
 * collecte est obligatoire : une taxe retenue sans compte où la loger serait
 * retenue au client sans être due à personne.
 */
export function obstaclesALaRegleFiscale(demande: DemandeRegleFiscale): readonly string[] {
  const obstacles: string[] = [];
  if (demande.code.trim() === '') obstacles.push('Le code est obligatoire.');
  if (demande.label.trim() === '') obstacles.push('Le libellé est obligatoire.');
  if (demande.collectionAccountId.trim() === '') {
    obstacles.push('Le compte de collecte est obligatoire : une taxe retenue se loge quelque '
      + 'part, sinon elle est prise au client sans être due à personne.');
  }
  if (!demande.validFrom) obstacles.push('La date d’entrée en vigueur est obligatoire.');
  if (demande.validFrom && demande.validTo && demande.validTo < demande.validFrom) {
    obstacles.push('Une taxe ne cesse pas avant de commencer.');
  }
  const taux = demande.ratePercent;
  if (taux === null || taux === '') {
    obstacles.push('Le taux est obligatoire.');
  } else {
    const valeur = Number(taux);
    if (!Number.isFinite(valeur) || valeur < 0 || valeur > 100) {
      obstacles.push('Un taux s’exprime en pour cent, entre 0 et 100.');
    }
  }
  return obstacles;
}

// ---------------------------------------------------------------- calendrier

/**
 * Les dernières périodes closes pour cette fréquence, la plus récente d'abord.
 *
 * **Cela propose, cela ne décide pas.** Le socle refuse une date qui ne ferme
 * pas de période — « un état se produit sur la période que le superviseur
 * attend, pas sur un intervalle choisi ». L'écran évite simplement de faire
 * saisir une date dont il sait déjà qu'elle sera refusée.
 */
export function periodesCloses(frequence: Frequence, aujourdHui: string,
                               combien = 6): readonly string[] {
  const [annee, mois] = aujourdHui.split('-').map(Number);
  const pas = frequence === 'YEARLY' ? 12 : frequence === 'QUARTERLY' ? 3 : 1;
  const periodes: string[] = [];
  // On part du mois précédent : le mois courant n'est pas clos.
  let curseur = annee * 12 + (mois - 1) - 1;
  while (periodes.length < combien) {
    const a = Math.floor(curseur / 12);
    const m = (curseur % 12) + 1;
    if ((frequence === 'MONTHLY')
        || (frequence === 'QUARTERLY' && m % 3 === 0)
        || (frequence === 'YEARLY' && m === 12)) {
      periodes.push(finDeMois(a, m));
      curseur -= pas;
    } else {
      curseur -= 1;
    }
  }
  return periodes;
}

/** Le dernier jour d'un mois, en ISO. */
function finDeMois(annee: number, mois: number): string {
  const dernier = new Date(Date.UTC(annee, mois, 0)).getUTCDate();
  return `${annee}-${String(mois).padStart(2, '0')}-${String(dernier).padStart(2, '0')}`;
}

/** « août 2026 », pour dire une période à un comptable plutôt qu'une date ISO. */
export function libellePeriode(periodEnd: string | null, frequence?: Frequence): string {
  if (!periodEnd) return '—';
  const [a, m] = periodEnd.split('-').map(Number);
  if (frequence === 'YEARLY') return String(a);
  if (frequence === 'QUARTERLY') return `T${Math.ceil(m / 3)} ${a}`;
  return `${MOIS[m - 1]} ${a}`;
}

const MOIS = ['janvier', 'février', 'mars', 'avril', 'mai', 'juin',
              'juillet', 'août', 'septembre', 'octobre', 'novembre', 'décembre'];
