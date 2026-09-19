/**
 * Le réseau d'agences et les conditions de banque.
 *
 * Deux paramétrages que le socle posait à deux et ne relisait nulle part : une agence créée
 * n'apparaissait dans aucun écran, et un férié, une règle de date de valeur ou une heure limite ne
 * se vérifiaient qu'en interrogeant la base. Celui qui paramétrait ajoutait donc une règle sans
 * voir celles qui existaient déjà — dont celle qu'il allait contredire.
 */

// ------------------------------------------------------------ le réseau

export type NatureAgence = 'HEAD_OFFICE' | 'REGION' | 'BRANCH';

export const LIBELLE_NATURE_AGENCE: Readonly<Record<NatureAgence, string>> = {
  HEAD_OFFICE: 'Siège',
  REGION: 'Direction régionale',
  BRANCH: 'Agence',
};

export interface Agence {
  readonly id: string;
  readonly code: string;
  readonly name: string;
  readonly kind: NatureAgence;
  readonly parentId: string | null;
  readonly status: string;
  readonly openedOn: string | null;
  readonly closedOn: string | null;
}

/** Une agence et ce qu'elle porte, telle que l'arbre du réseau la présente. */
export interface NoeudReseau {
  readonly agence: Agence;
  readonly profondeur: number;
}

/**
 * Le réseau à plat, mais dans l'ordre de l'arbre.
 *
 * Un tableau se lit, se trie et se cherche ; un arbre imbriqué ne fait aucune des trois. On garde
 * donc la ligne, et on porte la hiérarchie par un décalage — le siège, ses régions, leurs agences.
 *
 * **Aucune agence ne disparaît.** Celle dont le parent manque se rattache à la racine ; celle
 * qu'un cycle de rattachement rend inatteignable est ajoutée en fin de liste. Une ligne absente
 * parce que le paramétrage est incohérent serait le pire des deux mondes : on chercherait l'agence
 * au lieu de chercher le cycle.
 */
export function enArbre(agences: readonly Agence[]): readonly NoeudReseau[] {
  const connus = new Set(agences.map((a) => a.id));
  const enfants = new Map<string | null, Agence[]>();
  for (const agence of agences) {
    const parent = agence.parentId !== null && connus.has(agence.parentId) ? agence.parentId : null;
    const liste = enfants.get(parent);
    if (liste) {
      liste.push(agence);
    } else {
      enfants.set(parent, [agence]);
    }
  }
  const noeuds: NoeudReseau[] = [];
  const descendre = (parent: string | null, profondeur: number, vus: ReadonlySet<string>) => {
    const liste = [...(enfants.get(parent) ?? [])]
      .sort((a, b) => (a.kind === b.kind ? a.code.localeCompare(b.code)
                                         : rang(a.kind) - rang(b.kind)));
    for (const agence of liste) {
      // Un cycle de rattachement ferait boucler l'affichage : on s'arrête, la ligne reste visible.
      if (vus.has(agence.id)) {
        continue;
      }
      noeuds.push({ agence, profondeur });
      descendre(agence.id, profondeur + 1, new Set([...vus, agence.id]));
    }
  };
  descendre(null, 0, new Set());

  // Ce qu'un cycle rend inatteignable : deux agences qui se rattachent l'une à l'autre n'ont
  // aucune racine, et l'arbre seul les ferait disparaître toutes les deux.
  const rendus = new Set(noeuds.map((n) => n.agence.id));
  for (const agence of agences) {
    if (!rendus.has(agence.id)) {
      noeuds.push({ agence, profondeur: 0 });
    }
  }
  return noeuds;
}

function rang(kind: NatureAgence): number {
  return kind === 'HEAD_OFFICE' ? 0 : kind === 'REGION' ? 1 : 2;
}

/** Un compte de liaison par devise : c'est par lui que passent les écritures inter-agences. */
export interface DemandeAgence {
  readonly code: string;
  readonly name: string;
  readonly kind: NatureAgence;
  readonly parentId: string | null;
  readonly openedOn: string | null;
  readonly liaisonAccounts: Readonly<Record<string, string>>;
}

/**
 * Ce qui empêche de créer cette agence.
 *
 * Le compte de liaison mérite un mot : une écriture entre deux agences ne se porte pas d'un compte
 * client à un autre, elle transite par un compte de liaison tenu au siège, dans la devise de
 * l'opération. Sans lui, la première opération déplacée échoue — et elle échoue en agence, devant
 * un client.
 */
export function obstaclesALAgence(demande: DemandeAgence,
                                  codesExistants: readonly string[]): readonly string[] {
  const obstacles: string[] = [];
  const code = demande.code.trim();
  if (!code) {
    obstacles.push('Le code de l’agence est celui qui figure dans les numéros de compte qu’elle '
                   + 'ouvre : il ne se change plus ensuite.');
  } else if (codesExistants.some((existant) => existant.toUpperCase() === code.toUpperCase())) {
    obstacles.push(`Le code ${code} est déjà porté par une agence de cette entité.`);
  }
  if (!demande.name.trim()) {
    obstacles.push('Le nom de l’agence est ce que lira le guichet.');
  }
  if (demande.kind !== 'HEAD_OFFICE' && demande.parentId === null) {
    obstacles.push('Une agence ou une région se rattache : au siège, ou à la région dont elle '
                   + 'dépend. Seul le siège n’a pas de parent.');
  }
  if (!demande.openedOn) {
    obstacles.push('Une agence ouvre à une date : c’est elle qui borne ce qu’on pourra lui '
                   + 'imputer.');
  }
  if (Object.keys(demande.liaisonAccounts).length === 0) {
    obstacles.push('Il faut au moins un compte de liaison. Sans lui, la première opération '
                   + 'déplacée échoue — en agence, devant un client.');
  }
  for (const [devise, compte] of Object.entries(demande.liaisonAccounts)) {
    if (!compte.trim()) {
      obstacles.push(`Le compte de liaison en ${devise} n’est pas désigné.`);
    }
  }
  return obstacles;
}

// ------------------------------------------------------------ les conditions de banque

export interface JourFerie {
  readonly date: string;
  readonly label: string;
}

export type SensOperation = 'DEBIT' | 'CREDIT';

export const LIBELLE_SENS_OPERATION: Readonly<Record<SensOperation, string>> = {
  DEBIT: 'Débit',
  CREDIT: 'Crédit',
};

export type UniteDecalage = 'CALENDAR_DAYS' | 'BUSINESS_DAYS';

export const LIBELLE_UNITE: Readonly<Record<UniteDecalage, string>> = {
  CALENDAR_DAYS: 'jours calendaires',
  BUSINESS_DAYS: 'jours ouvrés',
};

const LIBELLE_UNITE_SINGULIER: Readonly<Record<UniteDecalage, string>> = {
  CALENDAR_DAYS: 'jour calendaire',
  BUSINESS_DAYS: 'jour ouvré',
};

/** « +1 jour ouvré », pas « +1 jours ouvrés » : un écran de banque s'écrit en français. */
export function libelleUnite(unite: UniteDecalage, decalage: number): string {
  return Math.abs(decalage) <= 1 ? LIBELLE_UNITE_SINGULIER[unite] : LIBELLE_UNITE[unite];
}

export type Convention = 'UNADJUSTED' | 'FOLLOWING' | 'MODIFIED_FOLLOWING' | 'PRECEDING'
  | 'MODIFIED_PRECEDING';

export const LIBELLE_CONVENTION: Readonly<Record<Convention, string>> = {
  UNADJUSTED: 'Sans ajustement',
  FOLLOWING: 'Jour ouvré suivant',
  MODIFIED_FOLLOWING: 'Ouvré suivant, sans changer de mois',
  PRECEDING: 'Jour ouvré précédent',
  MODIFIED_PRECEDING: 'Ouvré précédent, sans changer de mois',
};

export interface RegleDateValeur {
  readonly id: string;
  readonly operationType: string;
  readonly channel: string | null;
  readonly direction: SensOperation;
  readonly offset: number;
  readonly unit: UniteDecalage;
  readonly convention: Convention;
  readonly validFrom: string;
  readonly validTo: string | null;
}

export interface HeureLimite {
  readonly id: string;
  readonly channel: string | null;
  readonly cutoffTime: string;
  readonly closesChannel: boolean;
  readonly validFrom: string;
  readonly validTo: string | null;
}

export interface ConditionsDeBanque {
  readonly calendarCode: string | null;
  readonly calendarLabel: string | null;
  readonly coversFrom: string | null;
  readonly coversTo: string | null;
  readonly weekend: readonly number[];
  readonly holidays: readonly JourFerie[];
  readonly rules: readonly RegleDateValeur[];
  readonly cutoffs: readonly HeureLimite[];
}

export const JOURS = ['', 'lundi', 'mardi', 'mercredi', 'jeudi', 'vendredi', 'samedi',
                      'dimanche'] as const;

export function libelleJour(jour: number): string {
  return JOURS[jour] ?? String(jour);
}

/**
 * Une règle en une phrase, celle qu'on répète au client.
 *
 * « Un virement débité, reçu par la compensation, prend valeur 2 jours ouvrés plus tard, reporté
 * au jour ouvré suivant. » Les colonnes d'un tableau disent la même chose et personne ne les
 * recompose de tête.
 */
export function phraseDeLaRegle(regle: RegleDateValeur): string {
  const canal = regle.channel ? `par ${regle.channel}` : 'tous canaux';
  const sens = LIBELLE_SENS_OPERATION[regle.direction].toLowerCase();
  const decalage = regle.offset === 0
    ? 'le jour même'
    : `${regle.offset > 0 ? '+' : ''}${regle.offset} `
      + libelleUnite(regle.unit, regle.offset);
  return `${regle.operationType} (${canal}), au ${sens} : ${decalage}, `
    + `${LIBELLE_CONVENTION[regle.convention].toLowerCase()}.`;
}

export interface DemandeFerie {
  readonly date: string | null;
  readonly label: string;
}

export function obstaclesAuFerie(demande: DemandeFerie,
                                 conditions: ConditionsDeBanque): readonly string[] {
  const obstacles: string[] = [];
  if (!demande.date) {
    obstacles.push('Un jour férié porte une date.');
    return obstacles;
  }
  if (!demande.label.trim()) {
    obstacles.push('Le libellé du férié est ce qui explique, des années après, pourquoi une '
                   + 'échéance a été reportée.');
  }
  if (conditions.holidays.some((ferie) => ferie.date === demande.date)) {
    obstacles.push('Ce jour est déjà déclaré férié.');
  }
  // Hors de la période saisie, le calendrier refuse de répondre plutôt que de présumer qu'un jour
  // non saisi est ouvré : déclarer un férié au-delà ne servirait à rien.
  if (conditions.coversFrom && demande.date < conditions.coversFrom) {
    obstacles.push(`Le calendrier ne couvre qu’à partir du ${conditions.coversFrom}.`);
  }
  if (conditions.coversTo && demande.date > conditions.coversTo) {
    obstacles.push(`Le calendrier ne couvre que jusqu’au ${conditions.coversTo} : au-delà, il `
                   + 'refuse de répondre plutôt que de présumer un jour ouvré.');
  }
  return obstacles;
}

export interface DemandeRegleDateValeur {
  readonly operationType: string;
  readonly channel: string | null;
  readonly direction: SensOperation;
  readonly offset: number | null;
  readonly unit: UniteDecalage;
  readonly convention: Convention;
  readonly validFrom: string | null;
  readonly validTo: string | null;
}

export function obstaclesALaRegle(demande: DemandeRegleDateValeur): readonly string[] {
  const obstacles: string[] = [];
  if (!demande.operationType.trim()) {
    obstacles.push('Une règle porte sur un type d’opération : versement, retrait, virement, '
                   + 'remise…');
  }
  if (demande.offset === null || !Number.isInteger(demande.offset)) {
    obstacles.push('Le décalage est un nombre entier de jours — zéro pour une valeur du jour.');
  }
  if (!demande.validFrom) {
    obstacles.push('Une règle entre en vigueur à une date.');
  }
  if (demande.validFrom && demande.validTo && demande.validTo < demande.validFrom) {
    obstacles.push('La fin de validité ne précède pas son début.');
  }
  return obstacles;
}

export interface DemandeHeureLimite {
  readonly channel: string | null;
  readonly cutoffTime: string;
  readonly closesChannel: boolean;
  readonly validFrom: string | null;
  readonly validTo: string | null;
}

const HEURE = /^([01]\d|2[0-3]):[0-5]\d$/;

/**
 * Ce qui empêche de poser cette heure limite.
 *
 * Le chevauchement est refusé par le socle — deux heures limites de même portée sur des périodes
 * qui se croisent rendraient la date de valeur dépendante de l'ordre de lecture. L'écran le dit
 * avant d'envoyer, parce que le refus arriverait après le second regard.
 */
export function obstaclesALHeureLimite(demande: DemandeHeureLimite,
                                       existantes: readonly HeureLimite[]): readonly string[] {
  const obstacles: string[] = [];
  if (!HEURE.test(demande.cutoffTime)) {
    obstacles.push('L’heure limite s’écrit HH:mm, de 00:00 à 23:59.');
  }
  if (!demande.validFrom) {
    obstacles.push('Une heure limite entre en vigueur à une date.');
  }
  if (demande.validFrom && demande.validTo && demande.validTo < demande.validFrom) {
    obstacles.push('La fin de validité ne précède pas son début.');
  }
  if (demande.validFrom) {
    const memePortee = existantes.filter((h) => (h.channel ?? '') === (demande.channel ?? ''));
    const chevauche = memePortee.some((h) =>
      (h.validTo === null || h.validTo >= demande.validFrom!)
      && (demande.validTo === null || demande.validTo >= h.validFrom));
    if (chevauche) {
      obstacles.push('Une heure limite de même portée couvre déjà cette période. Deux heures qui '
                     + 'se chevauchent rendraient la date de valeur dépendante de l’ordre de '
                     + 'lecture : le socle les refuse.');
    }
  }
  return obstacles;
}
