import { EtatOperation } from '../../ui';

/** Nature d'un tiers, telle que le socle la nomme. */
export type NatureTiers = 'NATURAL_PERSON' | 'LEGAL_PERSON';

/** État de la connaissance client. Quatre valeurs, quatre conduites à tenir. */
export type StatutKyc = 'PENDING' | 'VERIFIED' | 'EXPIRED' | 'BLOCKED';

export type NiveauKyc = 'SIMPLIFIED' | 'STANDARD' | 'ENHANCED';
export type StatutTiers = 'ACTIVE' | 'BLOCKED' | 'CLOSED';
export type Risque = 'LOW' | 'MEDIUM' | 'HIGH';

/** Nature d'une pièce du dossier, telle que le socle l'énumère. */
export type NaturePiece =
  | 'IDENTITY' | 'ADDRESS_PROOF' | 'INCOME_PROOF' | 'ARTICLES'
  | 'TRADE_REGISTRY_EXTRACT' | 'TAX_CERTIFICATE' | 'SIGNATURE_SPECIMEN'
  | 'PHOTO' | 'OTHER';

export interface Tiers {
  readonly id: string;
  readonly reference: string;
  readonly displayName: string;
  readonly kind: NatureTiers;
  readonly countryCode: string | null;
  readonly birthOrRegistrationDate: string | null;
  readonly segment: string | null;
  readonly status: StatutTiers;
  readonly statusReason: string | null;
  readonly kycStatus: StatutKyc;
  readonly kycLevel: NiveauKyc;
  readonly kycVerifiedOn: string | null;
  readonly kycReviewDue: string | null;
  readonly riskRating: Risque;
}

export interface PageTiers {
  readonly tiers: readonly Tiers[];
  readonly page: number;
  readonly precedent: boolean;
  readonly suivant: boolean;
}

/** Une pièce du dossier, datée. */
export interface Piece {
  readonly id: string;
  readonly kind: NaturePiece;
  readonly reference: string | null;
  readonly issuer: string | null;
  readonly issuedOn: string | null;
  readonly expiresOn: string | null;
  readonly collectedOn: string | null;
  /** Remplacée par une pièce plus récente : conservée, plus en vigueur. */
  readonly supersededBy: string | null;
}

/**
 * L'état du dossier, tel que le socle le calcule.
 *
 * `complete` ne se lit jamais seul : un dossier incomplet sans dire **ce qui**
 * manque n'est pas actionnable au guichet. Le socle nomme les natures
 * manquantes et les natures expirées ; l'écran les rend telles quelles.
 */
export interface Dossier {
  readonly partyId: string;
  readonly reference: string | null;
  readonly kind: NatureTiers;
  readonly level: NiveauKyc;
  readonly complete: boolean;
  readonly missing: readonly NaturePiece[];
  readonly expired: readonly NaturePiece[];
  readonly beneficialOwnersMissing: boolean;
  readonly unverifiedOwners: readonly string[];
  /** Faux : aucune politique KYC n'est déclarée, donc rien n'est exigé. */
  readonly policyDeclared: boolean;
  readonly summary: string;
}

/**
 * Un bénéficiaire effectif déclaré. Les noms miroitent `BeneficialOwners.Owner`
 * du contrat — `validTo` est bien une **fin de validité**, pas une date de
 * sortie : une déclaration close reste au dossier, elle cesse seulement d'être
 * en vigueur.
 */
export interface BeneficiaireEffectif {
  readonly id: string;
  readonly ownerName: string;
  /** Le socle rend un nombre ; on le garde en texte pour ne pas l'arrondir. */
  readonly ownershipPercent: string;
  readonly ownerReference: string | null;
  readonly declaredOn: string | null;
  readonly validTo: string | null;
}

export interface DemandeTiers {
  readonly legalEntityId: string;
  readonly displayName: string;
  readonly kind: NatureTiers;
  readonly countryCode: string;
  readonly birthOrRegistrationDate: string | null;
  readonly reference: string | null;
  readonly segment: string | null;
}

export interface DemandeOuverture {
  readonly legalEntityId: string;
  readonly holderPartyId: string;
  readonly productCode: string;
  readonly currency: string;
  /** Le numéro de compte. Le contrat l'accepte vide ; le socle le compose. */
  readonly code: string | null;
}

/**
 * Ce que rend une demande d'ouverture : l'identifiant de l'opération mise en
 * attente. **Il n'y a pas d'autre issue favorable.** Le contrat ne déclare que
 * 202 et le contrôleur du socle répond `ACCEPTED` sans condition — un compte ne
 * s'ouvre jamais dans la foulée. Modéliser une seconde issue reviendrait à
 * écrire un écran que personne ne verrait, et à laisser croire au guichetier
 * qu'elle peut survenir.
 */
export interface IssueOuverture {
  readonly operationId: string;
}

const ETAT_TIERS: Readonly<Record<StatutTiers, EtatOperation>> = {
  ACTIVE: 'comptabilise',
  BLOCKED: 'bloque',
  CLOSED: 'expire',
};

const ETAT_KYC: Readonly<Record<StatutKyc, EtatOperation>> = {
  VERIFIED: 'comptabilise',
  PENDING: 'en-attente',
  EXPIRED: 'expire',
  BLOCKED: 'bloque',
};

export const etatDuTiers = (statut: StatutTiers): EtatOperation => ETAT_TIERS[statut];
export const etatDuKyc = (statut: StatutKyc): EtatOperation => ETAT_KYC[statut];

export const LIBELLE_NATURE: Readonly<Record<NatureTiers, string>> = {
  NATURAL_PERSON: 'Personne physique',
  LEGAL_PERSON: 'Personne morale',
};

/**
 * L'état d'un client, dans les mots du référentiel. Le badge emprunte ses
 * couleurs au vocabulaire des écritures — c'est voulu, la gravité se lit
 * pareil — mais un client n'est pas « comptabilisé ».
 */
export const LIBELLE_STATUT: Readonly<Record<StatutTiers, string>> = {
  ACTIVE: 'Actif',
  BLOCKED: 'Bloqué',
  CLOSED: 'Clos',
};

export const LIBELLE_KYC: Readonly<Record<StatutKyc, string>> = {
  PENDING: 'À vérifier',
  VERIFIED: 'Vérifié',
  EXPIRED: 'Revue dépassée',
  BLOCKED: 'Bloqué',
};

export const LIBELLE_NIVEAU: Readonly<Record<NiveauKyc, string>> = {
  SIMPLIFIED: 'Simplifié',
  STANDARD: 'Standard',
  ENHANCED: 'Renforcé',
};

export const LIBELLE_RISQUE: Readonly<Record<Risque, string>> = {
  LOW: 'Faible',
  MEDIUM: 'Moyen',
  HIGH: 'Élevé',
};

export const LIBELLE_PIECE: Readonly<Record<NaturePiece, string>> = {
  IDENTITY: "Pièce d'identité",
  ADDRESS_PROOF: 'Justificatif de domicile',
  INCOME_PROOF: 'Justificatif de revenus',
  ARTICLES: 'Statuts',
  TRADE_REGISTRY_EXTRACT: 'Extrait du registre du commerce',
  TAX_CERTIFICATE: 'Attestation fiscale',
  SIGNATURE_SPECIMEN: 'Spécimen de signature',
  PHOTO: 'Photographie',
  OTHER: 'Autre pièce',
};

/**
 * Le libellé d'une pièce au milieu d'une phrase. Les libellés sont capitalisés
 * pour tenir seuls dans une colonne ; inlinés tels quels ils produiraient
 * « Pièce manquante : Justificatif de domicile. »
 */
function enPhrase(libelle: string): string {
  return libelle.charAt(0).toLocaleLowerCase('fr') + libelle.slice(1);
}

/**
 * Ce qui empêche d'ouvrir un compte à ce tiers, en clair.
 *
 * Le socle refuse l'ouverture sur un dossier incomplet ou une connaissance
 * client non vérifiée (`requireOnboardable`), et l'écran ne réimplémente pas
 * cette règle : il annonce ce que le socle refusera, pour que le guichetier ne
 * remplisse pas un formulaire qui sera rejeté. **Le refus qui fait foi reste
 * celui du socle.**
 *
 * Les comptes existants du tiers continuent de fonctionner : ce contrôle
 * arrête ce qu'on allait ouvrir, pas ce qui existe.
 */
export function obstaclesAOuverture(tiers: Tiers, dossier: Dossier | null): readonly string[] {
  const obstacles: string[] = [];
  if (tiers.status !== 'ACTIVE') {
    obstacles.push(`Le tiers est ${tiers.status === 'BLOCKED' ? 'bloqué' : 'clos'}`
                   + (tiers.statusReason ? ` : ${tiers.statusReason}` : '.'));
  }
  if (tiers.kycStatus !== 'VERIFIED') {
    obstacles.push(`Connaissance client : ${LIBELLE_KYC[tiers.kycStatus].toLowerCase()}.`);
  }
  if (dossier && !dossier.complete) {
    for (const nature of dossier.missing) {
      obstacles.push(`Pièce manquante : ${enPhrase(LIBELLE_PIECE[nature])}.`);
    }
    for (const nature of dossier.expired) {
      obstacles.push(`Pièce expirée : ${enPhrase(LIBELLE_PIECE[nature])}.`);
    }
    if (dossier.beneficialOwnersMissing) obstacles.push('Bénéficiaires effectifs non déclarés.');
    for (const nom of dossier.unverifiedOwners) obstacles.push(`Bénéficiaire non vérifié : ${nom}.`);
  }
  return obstacles;
}

/** Une pièce est-elle périmée à la date donnée ? */
export function expiree(piece: Piece, jour: string): boolean {
  return piece.expiresOn !== null && piece.expiresOn < jour;
}
