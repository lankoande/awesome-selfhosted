/**
 * Le paramétrage produit, tel que le socle le déclare.
 *
 * <h2>Pourquoi ce fichier ne contient aucune règle de produit</h2>
 *
 * Ce qu'un compte courant exige, ce qu'un dépôt à terme admet, ce qu'un taux d'agios rend
 * obligatoire : rien de tout cela n'est écrit ici. Ces règles vivent dans `families.json`, à côté
 * du code qui les applique, et le socle les **sert** (`GET /products/families`). Le poste les lit
 * et les applique — il ne les recopie pas.
 *
 * C'est la seule façon d'éviter la dérive : deux copies d'un même contrat finissent par diverger,
 * et l'écran proposerait alors un paramètre que l'activation refuse, ou tairait celui qu'elle
 * exige. Ce qui est dupliqué ici, c'est l'**algorithme** de validation, pas son contenu — et il
 * n'existe que pour dire ce qui manque avant d'envoyer, pas pour décider.
 */

// ------------------------------------------------------------ le contrat servi

/** Une exigence alternative : au moins l'un de ces éléments doit être renseigné. */
export interface Alternative {
  readonly of: readonly string[];
  readonly because: string;
}

/**
 * Une exigence conditionnelle : ce qu'un paramètre rend obligatoire.
 *
 * `fallback` est la valeur que le socle retient quand le paramètre est absent. Sans elle, une
 * exigence attachée à la valeur par défaut ne se déclencherait jamais — et c'est justement le cas
 * le plus fréquent, puisqu'un paramètre absent est un paramètre qu'on a oublié.
 */
export interface Condition {
  readonly when: string;
  readonly fallback: string | null;
  readonly in: readonly string[];
  readonly presence: boolean;
  readonly require: readonly string[];
  readonly requireTier: string | null;
  readonly because: string;
}

/** Un bloc répété : un jeu de paramètres par élément d'une liste — les commissions. */
export interface BlocRepete {
  readonly listParameter: string;
  readonly required: readonly string[];
  readonly optional: readonly string[];
  readonly conditions: readonly Condition[];
  readonly accounts: readonly string[];
}

export interface FamilleProduit {
  readonly code: string;
  readonly label: string;
  readonly required: readonly string[];
  readonly optional: readonly string[];
  readonly requireOneOf: readonly Alternative[];
  readonly conditions: readonly Condition[];
  readonly groups: readonly BlocRepete[];
  readonly accounts: readonly string[];
}

/** Marqueur remplacé par le code de l'élément dans un bloc répété. */
export const MARQUEUR = '{code}';

/** Préfixe désignant un barème par tranches plutôt qu'un paramètre. */
export const PREFIXE_TRANCHES = 'tier:';

/** Discriminant du barème des intérêts ; une commission porte `FEE:<code>`. */
export const BAREME_INTERETS = 'INTEREST';

// ------------------------------------------------------------ les versions

export type StatutVersion = 'DRAFT' | 'ACTIVE' | 'SUSPENDED' | 'WITHDRAWN';

export const LIBELLE_STATUT_VERSION: Readonly<Record<StatutVersion, string>> = {
  DRAFT: 'Brouillon',
  ACTIVE: 'En vigueur',
  SUSPENDED: 'Suspendue',
  WITHDRAWN: 'Retirée',
};

/** Ce que l'état d'une version veut dire, dit à celui qui paramètre. */
export const ATTENTE_VERSION: Readonly<Record<StatutVersion, string>> = {
  DRAFT: "Rédigée, jamais activée. Aucun compte ne la cite, aucun arrêté ne la résout : elle "
    + "n'engage rien tant qu'un second ne l'a pas validée.",
  ACTIVE: 'En vigueur sur sa période. Tout compte rattaché à ce produit résout ce paramétrage à '
    + 'chaque date de valeur traitée.',
  SUSPENDED: 'Suspendue.',
  WITHDRAWN: "Retirée avant d'avoir servi. Elle reste lisible : ce qui a été saisi une fois "
    + "explique pourquoi une version attendue n'existe pas.",
};

/**
 * L'état d'une version tel qu'il se lit aujourd'hui.
 *
 * Le socle ne connaît que quatre statuts, et `ACTIVE` en couvre trois situations : une version
 * qui s'applique, une qui s'appliquera, une qui s'est appliquée. Afficher « en vigueur » sur une
 * version dont la validité s'est achevée l'an dernier ferait chercher longtemps pourquoi elle ne
 * s'applique plus.
 */
export type EtatVersion = StatutVersion | 'A_VENIR' | 'ECHUE';

export const LIBELLE_ETAT_VERSION: Readonly<Record<EtatVersion, string>> = {
  ...LIBELLE_STATUT_VERSION,
  A_VENIR: 'À venir',
  ECHUE: 'Échue',
};

export interface VersionProduit {
  readonly id: string;
  readonly code: string;
  readonly productType: string;
  readonly label: string;
  readonly currency: string;
  readonly validFrom: string;
  readonly validTo: string | null;
  readonly status: StatutVersion;
  readonly createdBy: string | null;
  readonly createdAt: string | null;
  readonly approvedBy: string | null;
  readonly approvedAt: string | null;
}

/** Une tranche d'un barème : de `from` à `to` (exclu), au taux annuel donné. */
export interface Tranche {
  readonly from: string;
  readonly to: string | null;
  readonly annualRatePercent: string;
}

export interface VersionComplete {
  readonly header: VersionProduit;
  readonly parameters: Readonly<Record<string, string>>;
  readonly tiers: Readonly<Record<string, readonly Tranche[]>>;
}

/** Un compte du plan comptable, tel qu'un paramétrage le désigne. */
export interface CompteGeneral {
  readonly id: string;
  readonly code: string;
  readonly kind: string;
  readonly normalBalance: string;
  readonly currency: string;
  readonly nature: string;
  readonly status: string;
  readonly postable: boolean;
}

// ------------------------------------------------------------ les actes

export type ActeVersion = 'ACTIVER' | 'RETIRER' | 'FERMER';

export const LIBELLE_ACTE_VERSION: Readonly<Record<ActeVersion, string>> = {
  ACTIVER: 'Activer',
  RETIRER: 'Retirer le brouillon',
  FERMER: 'Fermer la validité',
};

/**
 * Ce qu'on peut faire d'une version.
 *
 * Un brouillon s'active ou se retire. Une version en vigueur ne se retire pas — les comptes
 * rattachés la résolvent à chaque date de valeur traitée, y compris passée : la sortir de l'état
 * actif ferait échouer leur arrêté. Sa validité se **ferme**, et c'est tout autre chose.
 *
 * Les actes suivent le **statut**, pas l'état effectif : une version échue reste `ACTIVE` au
 * socle, et sa validité peut encore se raccourcir — pas avant la date comptable, et c'est la
 * fermeture qui le dit.
 */
/** L'état effectif, à la date comptable de la banque — pas au jour civil du poste. */
export function etatEffectif(version: VersionProduit, journee: string): EtatVersion {
  if (version.status !== 'ACTIVE') {
    return version.status;
  }
  if (version.validFrom > journee) {
    return 'A_VENIR';
  }
  return version.validTo !== null && version.validTo < journee ? 'ECHUE' : 'ACTIVE';
}

export function actesSurVersion(version: VersionProduit): readonly ActeVersion[] {
  if (version.status === 'DRAFT') {
    return ['ACTIVER', 'RETIRER'];
  }
  return version.status === 'ACTIVE' ? ['FERMER'] : [];
}

export interface DemandeFermeture {
  readonly validTo: string | null;
}

/**
 * Ce qui empêche de fermer cette version.
 *
 * La date comptable de la banque est la borne : fermer avant elle changerait ce qu'un arrêté déjà
 * produit résoudrait au rejeu, donc les montants. C'est la règle qui fonde le paramétrage daté.
 */
export function obstaclesALaFermeture(version: VersionProduit, demande: DemandeFermeture,
                                      dateComptable: string): readonly string[] {
  const obstacles: string[] = [];
  if (!demande.validTo) {
    obstacles.push('Une fermeture porte une date : celle à laquelle le produit cesse d’être '
                   + 'ouvrable.');
    return obstacles;
  }
  if (demande.validTo < dateComptable) {
    obstacles.push(`La banque en est au ${dateComptable}. Fermer avant cette date changerait ce `
                   + "qu'un arrêté déjà produit résoudrait au rejeu, donc les montants.");
  }
  if (demande.validTo < version.validFrom) {
    obstacles.push(`Cette version entre en vigueur le ${version.validFrom} : elle ne peut pas `
                   + 'finir avant de commencer.');
  }
  return obstacles;
}

// ------------------------------------------------------------ la rédaction

export interface EnteteVersion {
  readonly code: string;
  readonly productType: string;
  readonly label: string;
  readonly currency: string;
  readonly validFrom: string | null;
  readonly validTo: string | null;
}

/** Un champ que la famille demande, dans l'état courant de la saisie. */
export interface Champ {
  readonly nom: string;
  readonly obligatoire: boolean;
  /** Désigne un compte général : il se choisit dans le plan comptable, pas au clavier. */
  readonly compte: boolean;
  /** Pourquoi ce champ est devenu obligatoire, quand c'est une condition qui l'a rendu tel. */
  readonly raison: string | null;
}

function substituer(nom: string, element: string): string {
  return nom.split(MARQUEUR).join(element);
}

/** Les éléments déclarés par le paramètre-liste d'un bloc répété. */
export function elementsDuBloc(bloc: BlocRepete,
                               valeurs: Readonly<Record<string, string>>): readonly string[] {
  const declares = valeurs[bloc.listParameter];
  if (!declares || !declares.trim()) {
    return [];
  }
  return declares.split(',').map((e) => e.trim()).filter((e) => e !== '');
}

function declenchee(condition: Condition, valeurs: Readonly<Record<string, string>>): boolean {
  const valeur = valeurs[condition.when];
  if (condition.presence) {
    return valeur !== undefined && valeur !== '';
  }
  // Paramètre absent et sans valeur par défaut déclarée : la condition ne se déclenche pas.
  const effective = valeur === undefined || valeur === '' ? condition.fallback : valeur;
  return effective !== null && effective !== undefined && condition.in.includes(effective);
}

function poser(champs: Map<string, Champ>, nom: string, obligatoire: boolean, compte: boolean,
               raison: string | null): void {
  const existant = champs.get(nom);
  champs.set(nom, {
    nom,
    obligatoire: obligatoire || (existant?.obligatoire ?? false),
    compte: compte || (existant?.compte ?? false),
    raison: raison ?? existant?.raison ?? null,
  });
}

function conditionsDans(conditions: readonly Condition[],
                        valeurs: Readonly<Record<string, string>>,
                        comptes: ReadonlySet<string>, champs: Map<string, Champ>): void {
  for (const condition of conditions) {
    poser(champs, condition.when, false, comptes.has(condition.when), null);
    const active = declenchee(condition, valeurs);
    for (const nom of condition.require) {
      poser(champs, nom, active, comptes.has(nom), active ? condition.because : null);
    }
  }
}

/**
 * Les champs que la famille demande, compte tenu de ce qui est déjà saisi.
 *
 * L'ensemble n'est pas figé : renseigner un taux d'agios rend trois comptes obligatoires, déclarer
 * une commission ouvre son bloc. C'est le socle qui le dit, l'écran ne fait que le montrer — et le
 * montrer **pendant** la saisie plutôt qu'au refus de l'activation.
 */
export function champsDe(famille: FamilleProduit,
                         valeurs: Readonly<Record<string, string>>): readonly Champ[] {
  const comptes = new Set(famille.accounts);
  const champs = new Map<string, Champ>();

  for (const nom of famille.required) {
    poser(champs, nom, true, comptes.has(nom), null);
  }
  for (const nom of famille.optional) {
    poser(champs, nom, false, comptes.has(nom), null);
  }
  for (const alternative of famille.requireOneOf) {
    for (const entree of alternative.of) {
      if (!entree.startsWith(PREFIXE_TRANCHES)) {
        poser(champs, entree, false, comptes.has(entree), null);
      }
    }
  }
  conditionsDans(famille.conditions, valeurs, comptes, champs);

  for (const bloc of famille.groups) {
    poser(champs, bloc.listParameter, false, false, null);
    for (const element of elementsDuBloc(bloc, valeurs)) {
      const comptesDuBloc = new Set(bloc.accounts.map((n) => substituer(n, element)));
      for (const nom of bloc.required) {
        const resolu = substituer(nom, element);
        poser(champs, resolu, true, comptesDuBloc.has(resolu), null);
      }
      for (const nom of bloc.optional) {
        const resolu = substituer(nom, element);
        poser(champs, resolu, false, comptesDuBloc.has(resolu), null);
      }
      const conditions = bloc.conditions.map<Condition>((condition) => ({
        ...condition,
        when: substituer(condition.when, element),
        require: condition.require.map((n) => substituer(n, element)),
        requireTier: condition.requireTier === null ? null
                                                    : substituer(condition.requireTier, element),
      }));
      conditionsDans(conditions, valeurs, comptesDuBloc, champs);
    }
  }
  return [...champs.values()].sort((a, b) => a.nom.localeCompare(b.nom));
}

/**
 * Ce qui empêche d'activer cette version — le même contrôle que le socle, avant l'envoi.
 *
 * Le socle collecte tous les manques avant d'échouer, et l'écran fait de même : s'arrêter au
 * premier obligerait à repasser autant de fois qu'il manque de lignes.
 *
 * L'écran ne **bloque** pas la rédaction sur ces obstacles — un brouillon a le droit d'être
 * incomplet, c'est ce qui en fait un brouillon. Il les annonce, pour que l'activation ne soit pas
 * une surprise.
 */
export function manquesDuParametrage(famille: FamilleProduit,
                                     valeurs: Readonly<Record<string, string>>,
                                     baremes: readonly string[]): readonly string[] {
  const manques: string[] = [];
  const renseigne = (nom: string) => valeurs[nom] !== undefined && valeurs[nom] !== '';

  for (const nom of famille.required) {
    if (!renseigne(nom)) {
      manques.push(`Paramètre obligatoire absent : ${nom}`);
    }
  }
  for (const alternative of famille.requireOneOf) {
    const tenu = alternative.of.some((entree) => entree.startsWith(PREFIXE_TRANCHES)
      ? baremes.includes(entree.slice(PREFIXE_TRANCHES.length))
      : renseigne(entree));
    if (!tenu) {
      manques.push(`Aucun de ${alternative.of.join(', ')} n’est renseigné — ${alternative.because}`);
    }
  }
  const verifier = (conditions: readonly Condition[]) => {
    for (const condition of conditions) {
      if (!declenchee(condition, valeurs)) {
        continue;
      }
      const valeur = valeurs[condition.when] ?? `${condition.fallback} par défaut`;
      for (const nom of condition.require) {
        if (!renseigne(nom)) {
          manques.push(`${nom} est obligatoire dès lors que ${condition.when} vaut ${valeur} — `
                       + condition.because);
        }
      }
      if (condition.requireTier !== null && !baremes.includes(condition.requireTier)) {
        manques.push(`Aucun barème par tranches ${condition.requireTier} alors que `
                     + `${condition.when} vaut ${valeur} — ${condition.because}`);
      }
    }
  };
  verifier(famille.conditions);

  for (const bloc of famille.groups) {
    for (const element of elementsDuBloc(bloc, valeurs)) {
      for (const nom of bloc.required) {
        const resolu = substituer(nom, element);
        if (!renseigne(resolu)) {
          manques.push(`Paramètre obligatoire absent : ${resolu}`);
        }
      }
      verifier(bloc.conditions.map<Condition>((condition) => ({
        ...condition,
        when: substituer(condition.when, element),
        require: condition.require.map((n) => substituer(n, element)),
        requireTier: condition.requireTier === null ? null
                                                    : substituer(condition.requireTier, element),
      })));
    }
  }
  return manques;
}

/** Ce qui empêche d'enregistrer le brouillon lui-même : son identité, pas son contenu. */
export function obstaclesAlEntete(entete: EnteteVersion): readonly string[] {
  const obstacles: string[] = [];
  if (!entete.code.trim()) {
    obstacles.push('Le code du produit est celui qu’une ouverture de compte citera.');
  }
  if (!entete.productType.trim()) {
    obstacles.push('La famille décide de ce que le produit doit porter : elle se choisit d’abord.');
  }
  if (!entete.label.trim()) {
    obstacles.push('L’intitulé est ce que lira le guichet.');
  }
  if (!entete.currency.trim()) {
    obstacles.push('Un produit est tenu dans une devise, et un compte s’y ouvre dans celle-là.');
  }
  if (!entete.validFrom) {
    obstacles.push('Une version entre en vigueur à une date : c’est elle qui la rend résolvable.');
  }
  if (entete.validFrom && entete.validTo && entete.validTo < entete.validFrom) {
    obstacles.push('La fin de validité ne précède pas son début.');
  }
  return obstacles;
}

// ------------------------------------------------------------ présentation

/**
 * Le préfixe d'un paramètre, qui donne sa section : `interest.rate` → `interest`.
 *
 * Les blocs répétés portent le code de l'élément : `fee.TENUE.amount` → `fee.TENUE`.
 */
export function sectionDe(nom: string): string {
  const morceaux = nom.split('.');
  if (morceaux.length <= 1) {
    return 'general';
  }
  return morceaux[0] === 'fee' && morceaux.length > 2 ? `fee.${morceaux[1]}` : morceaux[0];
}

/**
 * Les sections connues, dans l'ordre où on les lit.
 *
 * Un préfixe absent de cette table s'affiche tel quel : le poste ne cache jamais un paramètre
 * faute de savoir le nommer. C'est un confort de lecture, pas un filtre.
 */
export const LIBELLE_SECTION: Readonly<Record<string, string>> = {
  general: 'Général',
  interest: 'Intérêts',
  overdraft: 'Découvert et agios',
  ops: 'Opérations, frais et plafonds',
  dormancy: 'Dormance',
  fee: 'Commissions',
  term: 'Dépôt à terme',
  loan: 'Crédit',
};

export function libelleSection(section: string): string {
  if (section.startsWith('fee.')) {
    return `Commission ${section.slice(4)}`;
  }
  return LIBELLE_SECTION[section] ?? section;
}

/** Les champs regroupés par section, dans l'ordre de lecture. */
export interface Section {
  readonly cle: string;
  readonly libelle: string;
  readonly champs: readonly Champ[];
}

const ORDRE: readonly string[] = ['general', 'interest', 'term', 'loan', 'overdraft', 'ops',
                                 'dormancy'];

export function enSections(champs: readonly Champ[]): readonly Section[] {
  const par = new Map<string, Champ[]>();
  for (const champ of champs) {
    const cle = sectionDe(champ.nom);
    const liste = par.get(cle);
    if (liste) {
      liste.push(champ);
    } else {
      par.set(cle, [champ]);
    }
  }
  const rang = (cle: string) => {
    const index = ORDRE.indexOf(cle);
    return index === -1 ? ORDRE.length : index;
  };
  return [...par.entries()]
    .sort((a, b) => rang(a[0]) - rang(b[0]) || a[0].localeCompare(b[0]))
    .map(([cle, liste]) => ({ cle, libelle: libelleSection(cle), champs: liste }));
}
