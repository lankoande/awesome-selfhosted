import { EtatOperation } from '../../ui';

/** Un montant, tel que le socle le rend : jamais un nombre flottant. */
export interface Montant {
  readonly amount: string;
  readonly currency: string;
}

// ------------------------------------------------------------------- alertes

/**
 * Les quatre états d'une alerte, tels que le socle les nomme.
 *
 * `REPORTED` n'est pas un classement : c'est le sort d'une alerte qu'une
 * déclaration de soupçon a couverte. Une alerte déclarée ne se reclasse pas et
 * ne se déclare pas deux fois — ce serait deux dossiers pour un seul fait.
 */
export type StatutAlerte = 'OPEN' | 'UNDER_REVIEW' | 'CLOSED' | 'REPORTED';

/** D'où vient l'alerte. La distinction commande ce qu'on en fait. */
export type OrigineAlerte = 'SCREENING' | 'MONITORING';

/** Une opération qui a déclenché l'alerte : la pièce du dossier. */
export interface PieceAlerte {
  readonly entryId: string;
  readonly bookingDate: string | null;
  readonly accountId: string;
  readonly direction: string;
  readonly amount: Montant | null;
}

export interface Alerte {
  readonly id: string;
  readonly partyId: string;
  readonly scenarioCode: string | null;
  readonly origin: OrigineAlerte;
  readonly raisedOn: string | null;
  readonly detail: string | null;
  readonly amount: Montant | null;
  readonly status: StatutAlerte;
  readonly assignedTo: string | null;
  readonly closedOn: string | null;
  readonly closureReason: string | null;
  readonly closedBy: string | null;
  readonly reportId: string | null;
  readonly pieces: readonly PieceAlerte[];
}

// -------------------------------------------------------------- déclarations

/** Une déclaration de soupçon : rédigée à deux, transmise à la cellule. */
export interface Declaration {
  readonly id: string;
  readonly partyId: string;
  readonly reference: string;
  readonly draftedOn: string | null;
  readonly narrative: string;
  readonly transmittedOn: string | null;
  readonly transmissionReference: string | null;
  readonly alertIds: readonly string[];
}

export interface DemandeDeclaration {
  readonly partyId: string;
  readonly reference: string;
  readonly narrative: string;
  readonly alertIds: readonly string[];
}

export interface DemandeTransmission {
  readonly reference: string;
  readonly transmittedOn: string | null;
}

// ----------------------------------------------------------------- scénarios

/** Ce que le code sait compter. Ajouter une méthode est une livraison. */
export type MethodeScenario =
  | 'CASH_THRESHOLD' | 'STRUCTURING' | 'ATYPICAL_ACTIVITY' | 'DORMANT_REACTIVATION';

export interface Scenario {
  readonly id: string;
  readonly code: string;
  readonly label: string;
  readonly method: MethodeScenario;
  readonly thresholdAmount: string | null;
  readonly windowDays: number | null;
  readonly minimumCount: number | null;
  readonly ratio: string | null;
  readonly riskRating: string | null;
  readonly validFrom: string | null;
  readonly validTo: string | null;
}

export interface DemandeScenario {
  readonly code: string;
  readonly label: string;
  readonly method: MethodeScenario;
  readonly thresholdAmount: string | null;
  readonly windowDays: number | null;
  readonly minimumCount: number | null;
  readonly ratio: string | null;
  readonly riskRating: string | null;
  readonly validFrom: string | null;
  readonly validTo: string | null;
}

// ----------------------------------------------------------------- libellés

export const LIBELLE_STATUT_ALERTE: Readonly<Record<StatutAlerte, string>> = {
  OPEN: 'Ouverte',
  UNDER_REVIEW: 'En instruction',
  CLOSED: 'Classée',
  REPORTED: 'Déclarée',
};

export const LIBELLE_ORIGINE: Readonly<Record<OrigineAlerte, string>> = {
  SCREENING: 'Filtrage',
  MONITORING: 'Surveillance',
};

/**
 * Ce que chaque origine veut dire pour celui qui instruit.
 *
 * La distinction n'est pas documentaire : une correspondance de filtrage porte
 * sur l'identité et le socle a déjà bloqué l'opération ; une alerte de
 * surveillance porte sur un comportement et **rien n'a été bloqué**. Les
 * confondre fait chercher un blocage qui n'existe pas, ou croire qu'il n'y en a
 * pas alors qu'un client est arrêté au guichet.
 */
export const EFFET_ORIGINE: Readonly<Record<OrigineAlerte, string>> = {
  SCREENING:
    "Correspondance avec une liste. Opérer avec une personne listée est l'infraction "
    + 'elle-même : le socle a déjà refusé l’opération. L’instruction sert à établir si la '
    + 'correspondance vise bien cette personne.',
  MONITORING:
    'Compteur de surveillance franchi. Rien n’a été bloqué : une statistique n’est pas une '
    + 'preuve, et priver quelqu’un de son argent sur une présomption serait la faute.',
};

export const LIBELLE_METHODE: Readonly<Record<MethodeScenario, string>> = {
  CASH_THRESHOLD: "Seuil d'espèces",
  STRUCTURING: 'Fractionnement',
  ATYPICAL_ACTIVITY: 'Activité atypique',
  DORMANT_REACTIVATION: 'Réveil de compte dormant',
};

/** Ce que la méthode compte, dit à celui qui pose le scénario. */
export const EFFET_METHODE: Readonly<Record<MethodeScenario, string>> = {
  CASH_THRESHOLD: 'Espèces cumulées au-delà d’un montant, sur une fenêtre.',
  STRUCTURING:
    'Opérations sous le seuil, répétées, dont la somme le franchit : le fractionnement.',
  ATYPICAL_ACTIVITY: 'Flux hors de proportion avec le profil déclaré au dossier.',
  DORMANT_REACTIVATION: 'Un compte oublié qui se remet à bouger.',
};

// ----------------------------------------------------------------- règles

/**
 * Les paramètres qu'une méthode exige pour vouloir dire quelque chose.
 *
 * C'est la même règle que `MonitoringScenarios.requireParameters` côté socle.
 * On ne la recopie pas pour décider à sa place — le socle refuse, toujours —
 * mais pour **ne pas demander un seuil à une méthode qui n'en a pas** et ne pas
 * laisser partir un scénario qui ne surveillerait rien.
 */
export const PARAMETRES_REQUIS: Readonly<Record<MethodeScenario, readonly string[]>> = {
  CASH_THRESHOLD: ['thresholdAmount', 'windowDays'],
  STRUCTURING: ['thresholdAmount', 'windowDays', 'minimumCount'],
  ATYPICAL_ACTIVITY: ['windowDays', 'ratio'],
  DORMANT_REACTIVATION: ['thresholdAmount'],
};

/** Dix ans : au-delà, une fenêtre de surveillance ne surveille plus, elle archive. */
export const FENETRE_MAXIMALE_JOURS = 3650;

/**
 * Ce qui empêche de soumettre ce scénario, dit en clair.
 *
 * Vide ne veut pas dire accepté : le socle garde le dernier mot. L'écran se
 * contente de ne pas faire soumettre ce qu'il sait incomplet.
 */
export function obstaclesAuScenario(demande: DemandeScenario): readonly string[] {
  const obstacles: string[] = [];
  if (demande.code.trim() === '') obstacles.push('Le code est obligatoire.');
  if (demande.label.trim() === '') {
    obstacles.push('Le libellé est obligatoire : c’est ce que l’analyste lira sur l’alerte.');
  }
  if (!demande.validFrom) obstacles.push('La date d’entrée en vigueur est obligatoire.');
  if (demande.validFrom && demande.validTo && demande.validTo < demande.validFrom) {
    obstacles.push('Un scénario ne cesse pas avant de commencer.');
  }
  for (const parametre of PARAMETRES_REQUIS[demande.method]) {
    const valeur = demande[parametre as keyof DemandeScenario];
    if (valeur === null || valeur === '' || valeur === undefined) {
      obstacles.push(`${LIBELLE_PARAMETRE[parametre]} est exigé par la méthode «${' '}`
        + `${LIBELLE_METHODE[demande.method]}${' '}».`);
    }
  }
  if (demande.windowDays !== null
      && (demande.windowDays < 1 || demande.windowDays > FENETRE_MAXIMALE_JOURS)) {
    obstacles.push(`Une fenêtre de surveillance va de 1 à ${FENETRE_MAXIMALE_JOURS} jours.`);
  }
  if (demande.method === 'STRUCTURING' && demande.minimumCount !== null
      && demande.minimumCount < 2) {
    obstacles.push('Un fractionnement se compte à partir de deux opérations.');
  }
  if (demande.thresholdAmount !== null && demande.thresholdAmount !== ''
      && Number(demande.thresholdAmount) <= 0) {
    obstacles.push('Un seuil est positif.');
  }
  if (demande.ratio !== null && demande.ratio !== '' && Number(demande.ratio) <= 0) {
    obstacles.push('Un facteur d’écart est positif.');
  }
  return obstacles;
}

export const LIBELLE_PARAMETRE: Readonly<Record<string, string>> = {
  thresholdAmount: 'Le seuil',
  windowDays: 'La fenêtre',
  minimumCount: 'Le nombre minimal d’opérations',
  ratio: 'Le facteur d’écart',
};

/**
 * Ce qui empêche de rédiger cette déclaration, dit en clair.
 *
 * Deux refus du socle sont anticipés ici parce qu'ils se voient à l'écran et
 * qu'un valideur ne doit pas les découvrir : **une déclaration ne mélange pas
 * deux tiers**, et **une alerte déjà déclarée est déjà couverte**.
 */
export function obstaclesALaDeclaration(
  demande: DemandeDeclaration, alertes: readonly Alerte[],
): readonly string[] {
  const obstacles: string[] = [];
  if (demande.reference.trim() === '') obstacles.push('La référence est obligatoire.');
  if (demande.narrative.trim() === '') {
    obstacles.push('L’exposé des faits est obligatoire : c’est lui que la cellule lira, '
      + 'pas la liste des alertes.');
  }
  if (demande.alertIds.length === 0) {
    obstacles.push('Une déclaration cite les alertes qu’elle couvre : sans elles, rien ne la '
      + 'rattache à des faits.');
  }
  const citees = alertes.filter((a) => demande.alertIds.includes(a.id));
  const tiers = new Set(citees.map((a) => a.partyId));
  if (tiers.size > 1) {
    obstacles.push('Une déclaration ne mélange pas deux dossiers : toutes les alertes citées '
      + 'portent sur le même tiers.');
  }
  for (const alerte of citees) {
    if (alerte.status === 'REPORTED') {
      obstacles.push(`L’alerte du ${alerte.raisedOn ?? '—'} est déjà couverte par une `
        + 'déclaration : deux dossiers pour un seul fait.');
    }
  }
  return obstacles;
}

/** L'état visuel d'une alerte. Les couleurs du socle, le vocabulaire de la conformité. */
export function etatDeLAlerte(statut: StatutAlerte): EtatOperation {
  switch (statut) {
    case 'OPEN': return 'en-attente';
    case 'UNDER_REVIEW': return 'approuve';
    case 'CLOSED': return 'comptabilise';
    case 'REPORTED': return 'bloque';
  }
}

/** Une alerte ouverte ou en instruction reste à traiter. */
export function aTraiter(alerte: Alerte): boolean {
  return alerte.status === 'OPEN' || alerte.status === 'UNDER_REVIEW';
}

/** Ce que l'alerte totalise, quand elle porte un montant. */
export function totalDesPieces(alerte: Alerte): string {
  return String(alerte.pieces.reduce((somme, piece) => somme + Number(piece.amount?.amount ?? '0'),
                                     0));
}
