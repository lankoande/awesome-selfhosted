/**
 * Les schémas comptables : la traduction de chaque événement en lignes d'écriture.
 *
 * <h2>Ce que ce fichier ne contient pas</h2>
 *
 * Aucune liste d'événements, aucune règle d'imputation, aucun jugement sur ce qui est
 * paramétrable. Tout cela est **servi** (`GET /accounting-schemas/standard`) et lu tel quel. Le
 * poste ne connaît pas non plus le langage d'expressions : il n'en déduit ni les grandeurs à
 * fournir, ni le résultat. Il **demande** — `POST /accounting-schemas/trials` —, et le socle
 * répond avec les variables attendues et l'écriture produite.
 *
 * C'est une décision, pas une commodité. Un poste qui interpréterait `round(net, 0) + tax` aurait
 * deux analyseurs à tenir en accord, et le jour où ils divergeraient, l'écran montrerait une
 * écriture que la production ne produit pas. Sur de la comptabilité, ce serait pire qu'inutile.
 *
 * Ce qui vit ici, c'est ce qu'un écran doit savoir : ce qu'on peut faire d'un schéma, ce qui
 * empêche de l'envoyer, et comment une ligne se lit en français.
 */

// ------------------------------------------------------------ le contrat servi

export type StatutSchema = 'DRAFT' | 'ACTIVE' | 'SUSPENDED' | 'WITHDRAWN';

export const LIBELLE_STATUT_SCHEMA: Readonly<Record<StatutSchema, string>> = {
  DRAFT: 'Brouillon',
  ACTIVE: 'En vigueur',
  SUSPENDED: 'Suspendu',
  WITHDRAWN: 'Retiré',
};

/** Ce que l'état d'un schéma veut dire, dit à celui qui paramètre. */
export const ATTENTE_SCHEMA: Readonly<Record<StatutSchema, string>> = {
  DRAFT: "Rédigé, jamais activé. Aucune écriture ne s'en réclame : il n'engage rien tant qu'un "
    + "second ne l'a pas validé.",
  ACTIVE: 'En vigueur sur sa période. Chaque événement de cette période s’impute par lui, y '
    + 'compris au rejeu d’un arrêté déjà produit.',
  SUSPENDED: 'Suspendu.',
  WITHDRAWN: "Retiré avant d'avoir servi. Il reste lisible : ce qui a été écrit une fois explique "
    + "pourquoi un schéma attendu n'existe pas.",
};

/**
 * L'état tel qu'il se lit aujourd'hui.
 *
 * Le socle ne connaît que quatre statuts, et `ACTIVE` en couvre trois situations : un schéma qui
 * s'applique, un qui s'appliquera, un qui s'est appliqué. Afficher « en vigueur » sur un schéma
 * dont la validité s'est achevée ferait chercher longtemps pourquoi il ne s'applique plus.
 */
export type EtatSchema = StatutSchema | 'A_VENIR' | 'ECHU';

export const LIBELLE_ETAT_SCHEMA: Readonly<Record<EtatSchema, string>> = {
  ...LIBELLE_STATUT_SCHEMA,
  A_VENIR: 'À venir',
  ECHU: 'Échu',
};

export interface SchemaComptable {
  readonly id: string;
  readonly code: string;
  readonly label: string;
  readonly currency: string;
  readonly validFrom: string;
  readonly validTo: string | null;
  readonly status: StatutSchema;
  readonly createdBy: string | null;
  readonly createdAt: string | null;
  readonly approvedBy: string | null;
  readonly approvedAt: string | null;
  readonly withdrawnBy: string | null;
  readonly withdrawnAt: string | null;
}

/** Une variable calculée par le schéma, dans son ordre d'évaluation. */
export interface Derivation {
  readonly name: string;
  readonly expression: string;
}

export interface LigneSchema {
  readonly account: string;
  readonly direction: string;
  readonly amount: string;
  readonly condition: string | null;
  readonly label: string | null;
}

export interface EvenementSchema {
  readonly eventType: string;
  readonly derivations: readonly Derivation[];
  readonly lines: readonly LigneSchema[];
  readonly variables: readonly string[];
}

export interface SchemaComplet {
  readonly header: SchemaComptable;
  readonly events: readonly EvenementSchema[];
}

/** Origine d'un schéma : construit par le socle, ou résolu depuis le paramétrage. */
export type OrigineSchema = 'SOCLE' | 'PARAMETRABLE';

export const LIBELLE_ORIGINE: Readonly<Record<OrigineSchema, string>> = {
  SOCLE: 'Imputé par le socle',
  PARAMETRABLE: 'Paramétrable',
};

/** Un événement du catalogue du socle : ce que la banque impute, et ce qui se remplace. */
export interface EvenementSocle {
  readonly eventType: string;
  readonly label: string;
  readonly module: string;
  readonly moduleLabel: string;
  readonly source: OrigineSchema;
  readonly schemaCode: string | null;
  readonly variables: readonly string[];
  readonly roles: readonly string[];
  readonly derivations: readonly Derivation[];
  readonly lines: readonly LigneSchema[];
}

// ------------------------------------------------------------ l'essai

export interface ValeurDerivee {
  readonly name: string;
  readonly expression: string;
  readonly value: number;
}

export interface LigneEssai {
  readonly account: string;
  readonly direction: string;
  readonly amountExpression: string;
  readonly amount: number | null;
  readonly label: string | null;
  readonly posted: boolean;
  readonly skipped: string | null;
}

export interface RefusEssai {
  readonly code: string;
  readonly detail: string;
}

export interface Essai {
  readonly eventType: string;
  readonly variables: readonly string[];
  readonly derived: readonly ValeurDerivee[];
  readonly lines: readonly LigneEssai[];
  readonly debit: number;
  readonly credit: number;
  readonly imbalance: number;
  readonly rejection: RefusEssai | null;
}

/** Pourquoi une ligne n'est pas imputée. Le socle rend un code ; le libellé est d'ici. */
export const LIBELLE_ECART: Readonly<Record<string, string>> = {
  CONDITION: 'Condition fausse',
  MONTANT_NUL: 'Montant nul',
};

export function libelleEcart(code: string | null): string {
  return code === null ? '' : LIBELLE_ECART[code] ?? code;
}

/**
 * Pourquoi l'écriture entière est refusée.
 *
 * Un refus n'est pas une panne : c'est la réponse la plus utile de l'essai, celle qu'on cherchait
 * à comprendre. Le socle en donne le détail ; ce titre dit en trois mots de quoi il s'agit.
 */
export const LIBELLE_REFUS: Readonly<Record<string, string>> = {
  EVALUATION: 'Expression impossible à évaluer',
  MONTANT_NEGATIF: 'Montant négatif',
  MONTANT_NON_COMPTABILISABLE: 'Montant non comptabilisable dans la devise',
  LIGNE_UNIQUE: 'Moins de deux lignes imputées',
  DESEQUILIBRE: 'Débit et crédit différents',
};

export function libelleRefus(refus: RefusEssai | null): string {
  if (refus === null) {
    return '';
  }
  return LIBELLE_REFUS[refus.code] ?? refus.code;
}

// ------------------------------------------------------------ lire une ligne

/**
 * Ce qu'une référence de compte désigne, en français.
 *
 * Un schéma ne nomme jamais un compte par son identifiant : il le désigne par son rôle, et c'est
 * ce qui lui permet de servir dans deux filiales aux plans comptables différents. Encore faut-il
 * que le rôle se lise.
 */
export function libelleReference(reference: string): string {
  if (reference === 'CONTRACT') {
    return 'le compte du contrat';
  }
  const separateur = reference.indexOf(':');
  if (separateur < 0) {
    return reference;
  }
  const valeur = reference.slice(separateur + 1);
  switch (reference.slice(0, separateur)) {
    case 'GL': return `le compte général ${valeur}`;
    case 'PARAM': return `le compte du rôle « ${valeur} »`;
    case 'RESOLVE': return `le compte résolu « ${valeur} »`;
    default: return reference;
  }
}

/**
 * Une ligne lue comme une phrase, en trois morceaux.
 *
 * *« Débite le compte du contrat de `net_booked + tax_booked`, si `tax_booked > 0`. »* C'est
 * ainsi qu'on relit un schéma à voix haute avant de l'activer ; cinq colonnes disent la même
 * chose et personne ne les recompose de tête.
 *
 * Les morceaux sont rendus séparément pour que l'écran compose la phrase avec les expressions en
 * caractère fixe : « de total » se lit comme un mot français, « de `total` » comme une variable —
 * et c'en est une.
 */
export interface PhraseLigne {
  readonly debut: string;
  readonly montant: string;
  readonly condition: string | null;
}

export function partiesDeLaLigne(ligne: LigneSchema): PhraseLigne {
  const sens = ligne.direction === 'CREDIT' ? 'Crédite' : 'Débite';
  return {
    debut: `${sens} ${libelleReference(ligne.account)} de`,
    montant: ligne.amount,
    condition: ligne.condition,
  };
}

/** La même phrase, d'un seul tenant : pour un titre, une infobulle, un export. */
export function phraseDeLaLigne(ligne: LigneSchema): string {
  const parties = partiesDeLaLigne(ligne);
  const condition = parties.condition ? `, si ${parties.condition}` : '';
  return `${parties.debut} ${parties.montant}${condition}.`;
}

// ------------------------------------------------------------ les actes

export type ActeSchema = 'ACTIVER' | 'RETIRER' | 'FERMER';

export const LIBELLE_ACTE_SCHEMA: Readonly<Record<ActeSchema, string>> = {
  ACTIVER: 'Activer',
  RETIRER: 'Retirer le brouillon',
  FERMER: 'Fermer la validité',
};

/**
 * Ce qu'on peut faire d'un schéma.
 *
 * Un brouillon s'active ou se retire. Un schéma en vigueur ne se retire pas : les imputations se
 * résolvent à la date de valeur traitée, y compris passée, et seul un schéma actif se résout —
 * le sortir de cet état changerait ce qu'un arrêté rejoué produirait. Sa validité se **ferme**,
 * et c'est aussi ce qui permet d'en activer un suivant sous le même code.
 */
export function actesSurSchema(schema: SchemaComptable): readonly ActeSchema[] {
  if (schema.status === 'DRAFT') {
    return ['ACTIVER', 'RETIRER'];
  }
  return schema.status === 'ACTIVE' ? ['FERMER'] : [];
}

/** L'état effectif, à la date comptable de la banque — pas au jour civil du poste. */
export function etatEffectif(schema: SchemaComptable, journee: string): EtatSchema {
  if (schema.status !== 'ACTIVE') {
    return schema.status;
  }
  if (schema.validFrom > journee) {
    return 'A_VENIR';
  }
  return schema.validTo !== null && schema.validTo < journee ? 'ECHU' : 'ACTIVE';
}

export interface DemandeFermetureSchema {
  readonly validTo: string | null;
}

/**
 * Ce qui empêche de fermer ce schéma.
 *
 * La date comptable de la banque est la borne : fermer avant elle changerait ce qu'un arrêté déjà
 * produit résoudrait au rejeu, donc les imputations.
 */
export function obstaclesALaFermeture(schema: SchemaComptable, demande: DemandeFermetureSchema,
                                      dateComptable: string): readonly string[] {
  const obstacles: string[] = [];
  if (!demande.validTo) {
    obstacles.push('Une fermeture porte une date : celle à laquelle le schéma cesse de '
                   + 's’appliquer.');
    return obstacles;
  }
  if (demande.validTo < dateComptable) {
    obstacles.push(`La banque en est au ${dateComptable}. Fermer avant cette date changerait ce `
                   + "qu'un arrêté déjà produit résoudrait au rejeu, donc les imputations.");
  }
  if (demande.validTo < schema.validFrom) {
    obstacles.push(`Ce schéma entre en vigueur le ${schema.validFrom} : il ne peut pas finir `
                   + 'avant de commencer.');
  }
  return obstacles;
}

// ------------------------------------------------------------ la rédaction

export interface EnteteSchema {
  readonly code: string;
  readonly label: string;
  readonly currency: string;
  readonly eventType: string;
  readonly validFrom: string | null;
  readonly validTo: string | null;
}

/**
 * Ce qui empêche d'enregistrer le brouillon : son identité, pas son contenu.
 *
 * Le refus d'un événement que le socle impute lui-même se lit dans le **catalogue servi**, jamais
 * dans une liste tenue ici. Le jour où un module deviendra paramétrable, l'écran l'apprendra du
 * socle sans qu'on ait à le relivrer — et, plus important, il ne l'inventera jamais avant lui.
 */
export function obstaclesAlEntete(entete: EnteteSchema,
                                  catalogue: readonly EvenementSocle[]): readonly string[] {
  const obstacles: string[] = [];
  if (!entete.code.trim()) {
    obstacles.push('Un schéma porte un code : c’est lui qu’une commission désigne.');
  }
  if (!entete.label.trim()) {
    obstacles.push('Un libellé est attendu : c’est ce qu’on lit dans la liste des schémas.');
  }
  if (!entete.currency.trim()) {
    obstacles.push("La devise fixe l'échelle d'arrondi : le même schéma ne peut pas servir en XOF "
                   + 'et en EUR.');
  }
  if (!entete.validFrom) {
    obstacles.push('Une entrée en vigueur est attendue : un schéma s’applique sur une période.');
  }
  if (entete.validTo && entete.validFrom && entete.validTo < entete.validFrom) {
    obstacles.push('La fin de validité précède son début.');
  }

  const evenement = catalogue.find(candidat => candidat.eventType === entete.eventType);
  if (!entete.eventType) {
    obstacles.push('Un schéma traduit un événement : il reste à le choisir.');
  } else if (!evenement) {
    obstacles.push(`L’événement « ${entete.eventType} » est inconnu du socle : aucun module ne le `
                   + 'publie, le schéma ne serait jamais lu.');
  } else if (evenement.source !== 'PARAMETRABLE') {
    obstacles.push(`« ${evenement.label} » est imputé par le socle lui-même : un schéma rédigé `
                   + 'pour lui ne serait jamais résolu. Il se lit, il ne se remplace pas.');
  }
  return obstacles;
}

/** Une ligne en cours de saisie. Rien n'y est encore figé, pas même la direction. */
export interface LigneSaisie {
  readonly account: string;
  readonly direction: string;
  readonly amount: string;
  readonly condition: string;
  readonly label: string;
}

const PREFIXES_CONNUS = ['GL:', 'PARAM:', 'RESOLVE:'];

/** Ce qui empêche une ligne d'être envoyée. Le socle refuserait ; autant le dire à la saisie. */
export function obstaclesALaLigne(ligne: LigneSaisie): readonly string[] {
  const obstacles: string[] = [];
  const reference = ligne.account.trim();
  if (!reference) {
    obstacles.push('Une ligne désigne un compte.');
  } else if (reference !== 'CONTRACT'
             && !PREFIXES_CONNUS.some(prefixe => reference.startsWith(prefixe)
                                                 && reference.length > prefixe.length)) {
    obstacles.push(`Référence « ${reference} » non reconnue. Formes attendues : CONTRACT, `
                   + 'GL:<code>, PARAM:<rôle>, RESOLVE:<nom>.');
  }
  if (ligne.direction !== 'DEBIT' && ligne.direction !== 'CREDIT') {
    obstacles.push('Une ligne est au débit ou au crédit.');
  }
  if (!ligne.amount.trim()) {
    obstacles.push('Une ligne porte un montant, ou l’expression qui le calcule.');
  }
  return obstacles;
}

/**
 * Ce qui empêche l'événement entier d'être envoyé.
 *
 * Le contrôle d'équilibre appartient au socle, qui l'éprouve par tirage sur des centaines de jeux
 * de valeurs. Ici, on ne vérifie que ce qui se voit sans calculer : deux lignes au minimum, et
 * les deux sens représentés. Un schéma tout au débit est une faute de saisie, pas un cas limite —
 * le dire tout de suite évite d'aller chercher un contre-exemple qui ne veut rien dire.
 */
export function obstaclesAlEvenement(lignes: readonly LigneSaisie[]): readonly string[] {
  const obstacles: string[] = [];
  if (lignes.length < 2) {
    obstacles.push('La partie double exige au moins deux lignes.');
    return obstacles;
  }
  if (!lignes.some(ligne => ligne.direction === 'DEBIT')) {
    obstacles.push('Aucune ligne au débit : l’écriture ne peut pas s’équilibrer.');
  }
  if (!lignes.some(ligne => ligne.direction === 'CREDIT')) {
    obstacles.push('Aucune ligne au crédit : l’écriture ne peut pas s’équilibrer.');
  }
  return obstacles;
}

// ------------------------------------------------------------ lire le catalogue

/** Un module du catalogue et ses événements, dans l'ordre servi. */
export interface ModuleSocle {
  readonly code: string;
  readonly label: string;
  readonly evenements: readonly EvenementSocle[];
}

/**
 * Le catalogue regroupé par module, sans jamais perdre un événement.
 *
 * L'ordre des modules est celui de leur première apparition : c'est l'ordre du fichier servi, et
 * il est voulu — le guichet d'abord, le crédit ensuite.
 */
export function parModule(catalogue: readonly EvenementSocle[]): readonly ModuleSocle[] {
  const ordre: string[] = [];
  const parCode = new Map<string, EvenementSocle[]>();
  for (const evenement of catalogue) {
    let groupe = parCode.get(evenement.module);
    if (!groupe) {
      groupe = [];
      parCode.set(evenement.module, groupe);
      ordre.push(evenement.module);
    }
    groupe.push(evenement);
  }
  return ordre.map(code => ({
    code,
    label: parCode.get(code)?.[0]?.moduleLabel ?? code,
    evenements: parCode.get(code) ?? [],
  }));
}

/**
 * « 1 événement », « 14 événements ».
 *
 * Le pluriel systématique est le défaut qu'on ne voit plus au bout de trois relectures, et que le
 * premier lecteur voit tout de suite. Une règle, un compteur, une fonction.
 */
export function libelleNombreEvenements(nombre: number): string {
  return `${nombre} événement${Math.abs(nombre) > 1 ? 's' : ''}`;
}

/** Les événements qu'un schéma de paramétrage peut réellement remplacer. */
export function parametrables(catalogue: readonly EvenementSocle[]): readonly EvenementSocle[] {
  return catalogue.filter(evenement => evenement.source === 'PARAMETRABLE');
}
