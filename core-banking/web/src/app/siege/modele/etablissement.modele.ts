/**
 * L'établissement et son plan de numérotation.
 *
 * Deux paramétrages que le socle porte et qu'aucun écran ne montrait. Le
 * premier est ce qui figure en en-tête de chaque relevé et de chaque état
 * transmis au superviseur ; le second dit comment se composent les numéros de
 * clients et de comptes — en zone UEMOA, un RIB avec sa clé.
 *
 * Ce fichier ne décide de rien : le socle compose et refuse. Il compose
 * **en aperçu**, pour qu'un gabarit en cours de rédaction se lise avec le
 * numéro qu'il produirait. Un opérateur qui doit calculer une clé modulo 97 de
 * tête ne relit pas son gabarit : il l'active et découvre au premier compte.
 */

export interface Etablissement {
  readonly id: string;
  readonly code: string;
  readonly name: string;
  readonly countryCode: string;
  readonly functionalCurrency: string;
  readonly businessDate: string;
  readonly status: string;
  readonly bankCode: string | null;
  readonly legalName: string | null;
  readonly approvalNumber: string | null;
  readonly taxId: string | null;
  readonly registryNumber: string | null;
  readonly address: string | null;
  readonly phone: string | null;
  readonly email: string | null;
}

/**
 * Ce qui se corrige. Le code, le pays et la devise de tenue n'y sont pas : ils
 * sont posés dans chaque écriture depuis le premier jour, et les changer serait
 * réécrire l'histoire comptable.
 *
 * Un champ absent reste ce qu'il était ; un champ vide est effacé.
 */
export interface DemandeEtablissement {
  readonly name?: string;
  readonly bankCode?: string;
  readonly legalName?: string;
  readonly approvalNumber?: string;
  readonly taxId?: string;
  readonly registryNumber?: string;
  readonly address?: string;
  readonly phone?: string;
  readonly email?: string;
}

export type DomaineNumerotation =
  'PARTY' | 'ACCOUNT' | 'LOAN_APPLICATION' | 'LOAN_CONTRACT' | 'TERM_DEPOSIT' | 'STANDING_ORDER';

export type NatureSegment =
  'LITERAL' | 'BANK_CODE' | 'BRANCH_CODE' | 'DATE' | 'SEQUENCE' | 'CHECK_DIGITS';

export type AlgorithmeCle = 'RIB_97' | 'LUHN';
export type PorteeCompteur = 'ENTITY' | 'BRANCH';
export type RemiseAZero = 'NEVER' | 'YEAR' | 'MONTH';
export type StatutRegle = 'DRAFT' | 'ACTIVE' | 'WITHDRAWN';

export interface Segment {
  readonly kind: NatureSegment;
  readonly literalValue: string | null;
  readonly length: number | null;
  readonly padChar: string | null;
  readonly datePattern: string | null;
  readonly algorithm: AlgorithmeCle | null;
}

export interface RegleNumerotation {
  readonly id: string;
  readonly domain: DomaineNumerotation;
  readonly label: string;
  readonly segments: readonly Segment[];
  readonly scope: PorteeCompteur;
  readonly reset: RemiseAZero;
  readonly sequenceStart: number;
  readonly status: StatutRegle;
}

/** Une règle à rédiger : le socle la vérifie à nouveau, ceci n'est pas une garantie. */
export interface DemandeRegle {
  readonly domain: DomaineNumerotation;
  readonly label: string;
  readonly segments: readonly Segment[];
  readonly sequenceScope: PorteeCompteur;
  readonly sequenceReset: RemiseAZero;
  readonly sequenceStart: number;
}

export const LIBELLE_DOMAINE: Readonly<Record<DomaineNumerotation, string>> = {
  PARTY: 'Référence client',
  ACCOUNT: 'Numéro de compte',
  LOAN_APPLICATION: 'Demande de crédit',
  LOAN_CONTRACT: 'Contrat de crédit',
  TERM_DEPOSIT: 'Dépôt à terme',
  STANDING_ORDER: 'Ordre permanent',
};

export const LIBELLE_SEGMENT: Readonly<Record<NatureSegment, string>> = {
  LITERAL: 'Texte fixe',
  BANK_CODE: 'Code banque',
  BRANCH_CODE: 'Code agence',
  DATE: 'Date',
  SEQUENCE: 'Compteur',
  CHECK_DIGITS: 'Clé de contrôle',
};

export const LIBELLE_PORTEE_COMPTEUR: Readonly<Record<PorteeCompteur, string>> = {
  ENTITY: 'une série pour la banque',
  BRANCH: 'une série par agence',
};

export const LIBELLE_REMISE: Readonly<Record<RemiseAZero, string>> = {
  NEVER: 'jamais',
  YEAR: 'chaque année',
  MONTH: 'chaque mois',
};

export const LIBELLE_STATUT_REGLE: Readonly<Record<StatutRegle, string>> = {
  DRAFT: 'Brouillon',
  ACTIVE: 'Active',
  WITHDRAWN: 'Retirée',
};

/** Les formats de date que l'écran propose. Rien n'interdit d'en saisir un autre. */
export const FORMATS_DATE: readonly { readonly motif: string; readonly exemple: string }[] = [
  { motif: 'yyyy', exemple: '2026' },
  { motif: 'yy', exemple: '26' },
  { motif: 'yyyyMM', exemple: '202609' },
  { motif: 'yyMM', exemple: '2609' },
  { motif: 'yyyyMMdd', exemple: '20260918' },
];

// ------------------------------------------------------------ ce qui se refuse

/**
 * Ce qui empêche de rédiger cette règle.
 *
 * Les mêmes contrôles que le socle, dits avant l'envoi. Le socle refuse de
 * toute façon — ce qui est ici évite un aller-retour, jamais une garantie.
 */
export function obstaclesAuGabarit(demande: DemandeRegle): readonly string[] {
  const obstacles: string[] = [];
  if (!demande.label.trim()) {
    obstacles.push('Une règle porte un libellé : c\'est ce qu\'on lira dans la liste.');
  }
  if (demande.segments.length === 0) {
    obstacles.push('Un gabarit porte au moins un segment.');
  }
  const compteurs = demande.segments.filter((s) => s.kind === 'SEQUENCE').length;
  if (compteurs !== 1) {
    obstacles.push(compteurs === 0
      ? 'Un gabarit porte un compteur : sans lui, tous les numéros composés seraient identiques.'
      : 'Un gabarit ne porte qu\'un compteur : avec deux, aucun numéro ne serait lisible.');
  }
  demande.segments.forEach((segment, index) => {
    if (segment.kind === 'CHECK_DIGITS' && index !== demande.segments.length - 1) {
      obstacles.push('La clé de contrôle se calcule sur ce qui la précède : elle est le dernier '
                     + 'segment, jamais au milieu.');
    }
    if (segment.kind === 'LITERAL' && !(segment.literalValue ?? '').length) {
      obstacles.push('Un segment de texte fixe porte son texte.');
    }
    if (segment.kind === 'DATE' && !(segment.datePattern ?? '').trim()) {
      obstacles.push('Un segment de date porte son format.');
    }
    if ((segment.kind === 'SEQUENCE' || segment.kind === 'BANK_CODE'
         || segment.kind === 'BRANCH_CODE') && !segment.length) {
      obstacles.push(`Le segment « ${LIBELLE_SEGMENT[segment.kind]} » porte son cadrage.`);
    }
  });
  if (demande.sequenceScope === 'BRANCH'
      && !demande.segments.some((s) => s.kind === 'BRANCH_CODE')) {
    obstacles.push('Une série par agence sans code agence dans le numéro : deux agences '
                   + 'composeraient le même numéro. Ajoutez le code agence, ou revenez à une '
                   + 'série pour la banque.');
  }
  return obstacles;
}

// ------------------------------------------------------------ l'aperçu

/**
 * La table de transcodage des lettres du RIB.
 *
 * A et J valent 1, B, K et S valent 2, et ainsi de suite jusqu'à I, R et Z qui
 * valent 9. Ce n'est pas un modulo : les trois séries ne sont pas alignées —
 * la troisième commence à S, pas à la lettre qui vaudrait 1.
 */
const CHIFFRE_RIB: Readonly<Record<string, number>> = {
  A: 1, J: 1,
  B: 2, K: 2, S: 2,
  C: 3, L: 3, T: 3,
  D: 4, M: 4, U: 4,
  E: 5, N: 5, V: 5,
  F: 6, O: 6, W: 6,
  G: 7, P: 7, X: 7,
  H: 8, Q: 8, Y: 8,
  I: 9, R: 9, Z: 9,
};

function chiffreRib(caractere: string): number {
  if (caractere >= '0' && caractere <= '9') {
    return caractere.charCodeAt(0) - 48;
  }
  return CHIFFRE_RIB[caractere.toUpperCase()] ?? -1;
}

/**
 * La clé de contrôle de ce qui précède.
 *
 * `RIB_97` rend le complément à 97 : le numéro entier, clé comprise, est
 * divisible par 97. Le calcul porte sur la chaîne entière plutôt que sur la
 * formule à trois poids du RIB français, qui suppose des longueurs fixes — la
 * BCEAO n'a pas les mêmes.
 */
export function cleDeControle(algorithme: AlgorithmeCle, prefixe: string): string {
  let chiffres = '';
  for (const caractere of prefixe) {
    const chiffre = chiffreRib(caractere);
    if (chiffre >= 0) {
      chiffres += String(chiffre);
    }
  }
  if (!chiffres) {
    return '';
  }
  if (algorithme === 'RIB_97') {
    const reste = Number((BigInt(chiffres) * 100n) % 97n);
    return String(97 - reste).padStart(2, '0');
  }
  let somme = 0;
  let double = true;
  for (let i = chiffres.length - 1; i >= 0; i--) {
    let chiffre = chiffres.charCodeAt(i) - 48;
    if (double) {
      chiffre *= 2;
      if (chiffre > 9) {
        chiffre -= 9;
      }
    }
    double = !double;
    somme += chiffre;
  }
  return String((10 - (somme % 10)) % 10);
}

/** Ce que le format de date rend, pour les motifs que l'écran propose. */
function dateFormatee(motif: string, jour: string): string {
  const [annee, mois, quantieme] = jour.split('-');
  return motif
    .replace('yyyy', annee)
    .replace('yy', annee.slice(2))
    .replace('MM', mois)
    .replace('dd', quantieme);
}

/** Ce qui manque pour composer un aperçu : le socle, lui, refuserait. */
export interface ContexteApercu {
  readonly bankCode: string | null;
  readonly branchCode: string | null;
  readonly jour: string;
  readonly compteur: number;
}

/**
 * Le numéro que ce gabarit produirait.
 *
 * Un segment dont la valeur manque est rendu en points d'interrogation plutôt
 * que refusé : l'aperçu sert à lire un gabarit, pas à valider un état. Ce qui
 * manque se voit, et c'est le but.
 */
export function apercu(demande: DemandeRegle, contexte: ContexteApercu): string {
  let sortie = '';
  for (const segment of demande.segments) {
    const cadre = (valeur: string): string => {
      const taille = segment.length ?? valeur.length;
      const remplissage = segment.padChar ?? '0';
      return valeur.length > taille
        ? valeur.slice(0, taille)
        : remplissage.repeat(taille - valeur.length) + valeur;
    };
    switch (segment.kind) {
      case 'LITERAL':
        sortie += segment.literalValue ?? '';
        break;
      case 'BANK_CODE':
        sortie += cadre(contexte.bankCode ?? '?');
        break;
      case 'BRANCH_CODE':
        sortie += cadre(contexte.branchCode ?? '?');
        break;
      case 'DATE':
        sortie += dateFormatee(segment.datePattern ?? 'yyyy', contexte.jour);
        break;
      case 'SEQUENCE':
        sortie += cadre(String(contexte.compteur));
        break;
      case 'CHECK_DIGITS':
        sortie += cadre(cleDeControle(segment.algorithm ?? 'RIB_97', sortie));
        break;
    }
  }
  return sortie;
}

/** Le gabarit vide d'un segment de la nature choisie, prêt à être réglé. */
export function segmentNeuf(kind: NatureSegment): Segment {
  switch (kind) {
    case 'LITERAL':
      return { kind, literalValue: '', length: null, padChar: null, datePattern: null,
               algorithm: null };
    case 'DATE':
      return { kind, literalValue: null, length: null, padChar: null, datePattern: 'yyyy',
               algorithm: null };
    case 'CHECK_DIGITS':
      return { kind, literalValue: null, length: 2, padChar: '0', datePattern: null,
               algorithm: 'RIB_97' };
    case 'SEQUENCE':
      return { kind, literalValue: null, length: 6, padChar: '0', datePattern: null,
               algorithm: null };
    default:
      return { kind, literalValue: null, length: 5, padChar: '0', datePattern: null,
               algorithm: null };
  }
}
