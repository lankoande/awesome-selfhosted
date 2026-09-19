/**
 * Les maquettes d'états financiers : bilan, compte de résultat, hors bilan.
 *
 * <h2>Ce que le poste vérifie, et ce qu'il ne vérifie pas</h2>
 *
 * Le socle éprouve une maquette avant qu'elle n'entre en base, et c'est lui qui décide. Ce
 * fichier ne refait pas ce contrôle : il vérifie ce qui se voit **dans le formulaire lui-même** —
 * deux rubriques au même code, un total qui référence une rubrique absente ou postérieure, une
 * règle qui vise une rubrique qui n'est pas de détail — parce que ces défauts-là sont des
 * propriétés de la saisie, pas des règles vivant ailleurs.
 *
 * La nuance a un effet pratique : `StatementLayouts.validate` s'arrête au **premier** défaut. Sur
 * une maquette de quarante rubriques, corriger à l'aveugle une erreur par aller-retour est un
 * supplice. Le poste dit ce qu'il voit, tout de suite et tout à la fois ; le socle reste
 * l'autorité, et son refus s'affiche tel quel.
 *
 * <h2>Ce que le poste ajoute, et que le socle ne peut pas dire</h2>
 *
 * Les règles se lisent dans l'ordre et **la première qui reconnaît un compte l'emporte**. Une
 * règle entièrement recouverte par une précédente ne s'appliquera donc jamais. Le socle ne peut
 * pas la refuser — pendant la rédaction, c'est un état transitoire légitime —, mais un écran peut
 * la montrer. C'est le genre de défaut qu'on ne trouve qu'en lisant le bilan produit.
 */

import { Montant } from '../../guichet/modele/guichet.modele';

// ------------------------------------------------------------ le contrat servi

export type NatureEtat = 'BALANCE_SHEET' | 'INCOME_STATEMENT' | 'OFF_BALANCE_SHEET';

export const NATURES_ETAT: readonly NatureEtat[] = [
  'BALANCE_SHEET', 'INCOME_STATEMENT', 'OFF_BALANCE_SHEET',
];

export const LIBELLE_NATURE_ETAT: Readonly<Record<NatureEtat, string>> = {
  BALANCE_SHEET: 'Bilan',
  INCOME_STATEMENT: 'Compte de résultat',
  OFF_BALANCE_SHEET: 'Hors bilan',
};

/** Ce que chaque état présente, dit à celui qui le paramètre. */
export const OBJET_NATURE_ETAT: Readonly<Record<NatureEtat, string>> = {
  BALANCE_SHEET: 'Les soldes des comptes de bilan à une date, plus le résultat de l’exercice en '
    + 'cours. L’actif doit égaler le passif.',
  INCOME_STATEMENT: 'Les mouvements des comptes de charges et de produits sur une période — '
    + 'l’exercice en cours par défaut. Son solde est le résultat.',
  OFF_BALANCE_SHEET: 'Les engagements donnés et reçus à une date. Ils s’équilibrent entre eux.',
};

export type StatutMaquette = 'DRAFT' | 'ACTIVE' | 'WITHDRAWN';

export const STATUTS_MAQUETTE: readonly StatutMaquette[] = ['DRAFT', 'ACTIVE', 'WITHDRAWN'];

export const LIBELLE_STATUT_MAQUETTE: Readonly<Record<StatutMaquette, string>> = {
  DRAFT: 'Brouillon',
  ACTIVE: 'En vigueur',
  WITHDRAWN: 'Retirée',
};

export const ATTENTE_MAQUETTE: Readonly<Record<StatutMaquette, string>> = {
  DRAFT: "Rédigée, jamais activée. Aucun état ne s'en réclame : elle n'engage rien tant qu'un "
    + "second ne l'a pas validée. Elle s'essaie autant de fois qu'on veut.",
  ACTIVE: 'En vigueur sur sa période. Tout état produit à une date de cette période se présente '
    + 'par elle, y compris au rejeu.',
  WITHDRAWN: "Retirée avant d'avoir servi. Elle reste lisible : ce qui a été écrit une fois "
    + "explique pourquoi un état ne se présente pas comme on l'avait prévu.",
};

/** L'état effectif, à la date comptable — `ACTIVE` couvre trois situations au socle. */
export type EtatMaquette = StatutMaquette | 'A_VENIR' | 'ECHUE';

export const LIBELLE_ETAT_MAQUETTE: Readonly<Record<EtatMaquette, string>> = {
  ...LIBELLE_STATUT_MAQUETTE,
  A_VENIR: 'À venir',
  ECHUE: 'Échue',
};

export interface Maquette {
  readonly id: string;
  readonly kind: NatureEtat;
  readonly code: string;
  readonly label: string;
  readonly validFrom: string;
  readonly validTo: string | null;
  readonly status: StatutMaquette;
  readonly createdBy: string | null;
  readonly createdAt: string | null;
  readonly approvedBy: string | null;
  readonly approvedAt: string | null;
  readonly withdrawnBy: string | null;
  readonly withdrawnAt: string | null;
}

export type NatureRubrique = 'DETAIL' | 'TOTAL' | 'PROFIT_OR_LOSS';

export const LIBELLE_NATURE_RUBRIQUE: Readonly<Record<NatureRubrique, string>> = {
  DETAIL: 'Détail',
  TOTAL: 'Total',
  PROFIT_OR_LOSS: 'Résultat de l’exercice',
};

export type Sens = 'DEBIT' | 'CREDIT';

export const LIBELLE_SENS: Readonly<Record<Sens, string>> = {
  DEBIT: 'Débit',
  CREDIT: 'Crédit',
};

/**
 * Une rubrique de l'état.
 *
 * @param side  sens de présentation : le montant est positif quand le solde est de ce côté
 * @param plus  rubriques ajoutées par un total
 * @param minus rubriques retranchées par un total
 */
export interface Rubrique {
  readonly ordinal: number;
  readonly code: string;
  readonly label: string;
  readonly level: number;
  readonly kind: NatureRubrique;
  readonly side: Sens;
  readonly plus: readonly string[];
  readonly minus: readonly string[];
}

/** Nature d'un compte au plan interne. Le socle en fixe la liste ; elle est fermée. */
export type NatureCompte = 'CUSTOMER' | 'GL' | 'INTERNAL' | 'NOSTRO' | 'SUSPENSE' | 'POSITION';

export const NATURES_COMPTE: readonly NatureCompte[] = [
  'CUSTOMER', 'GL', 'INTERNAL', 'NOSTRO', 'SUSPENSE', 'POSITION',
];

export const LIBELLE_NATURE_COMPTE: Readonly<Record<NatureCompte, string>> = {
  CUSTOMER: 'compte de client',
  GL: 'compte général',
  INTERNAL: 'compte interne',
  NOSTRO: 'compte nostro',
  SUSPENSE: 'compte de suspens',
  POSITION: 'compte de position',
};

/**
 * Le pluriel, écrit et non calculé.
 *
 * « compte de client » au pluriel ne s'obtient pas en ajoutant un *s* à la fin — ce serait
 * « compte de clients » —, et « compte général » donne « comptes généraux ». Un pluriel calculé
 * se trompe sur la moitié de cette liste, et la phrase d'une règle est ce qu'on relit à voix
 * haute avant d'activer un bilan.
 */
export const PLURIEL_NATURE_COMPTE: Readonly<Record<NatureCompte, string>> = {
  CUSTOMER: 'comptes de clients',
  GL: 'comptes généraux',
  INTERNAL: 'comptes internes',
  NOSTRO: 'comptes nostro',
  SUSPENSE: 'comptes de suspens',
  POSITION: 'comptes de position',
};

/** Une règle d'affectation : tout critère absent est indifférent ; il en faut au moins un. */
export interface RegleAffectation {
  readonly ordinal: number;
  readonly lineCode: string;
  readonly accountKind: NatureCompte | null;
  readonly codePrefix: string | null;
  readonly balanceSide: Sens | null;
}

export interface MaquetteComplete {
  readonly id: string;
  readonly kind: NatureEtat;
  readonly code: string;
  readonly label: string;
  readonly validFrom: string;
  readonly validTo: string | null;
  readonly status: StatutMaquette;
  readonly lines: readonly Rubrique[];
  readonly rules: readonly RegleAffectation[];
}

// ------------------------------------------------------------ l'essai

export interface MontantRubrique {
  readonly ordinal: number;
  readonly code: string;
  readonly label: string;
  readonly level: number;
  readonly kind: NatureRubrique;
  readonly side: Sens;
  readonly amount: Montant;
}

/** Un compte qu'aucune règle n'affecte, avec de quoi écrire la règle manquante. */
export interface CompteNonAffecte {
  readonly code: string;
  readonly accountKind: NatureCompte;
  readonly side: Sens;
  readonly amount: Montant;
}

export interface EtatProduit {
  readonly kind: NatureEtat;
  readonly layoutId: string;
  readonly layoutCode: string;
  readonly layoutLabel: string;
  readonly currency: string;
  readonly from: string | null;
  readonly to: string;
  readonly lines: readonly MontantRubrique[];
  readonly totalDebit: Montant;
  readonly totalCredit: Montant;
  readonly net: Montant;
  readonly consistent: boolean;
  readonly anomalies: readonly string[];
  readonly unassigned: readonly CompteNonAffecte[];
}

// ------------------------------------------------------------ lire une maquette

/**
 * Une règle lue comme une phrase.
 *
 * *« Affecte à « Dépôts de la clientèle » : les comptes de client dont le solde est créditeur. »*
 * Cinq colonnes disent la même chose et personne ne les recompose de tête — surtout pas au moment
 * de comprendre pourquoi un compte est tombé dans la mauvaise rubrique.
 */
export function phraseDeLaRegle(regle: RegleAffectation,
                                rubriques: readonly Rubrique[]): string {
  const rubrique = rubriques.find((candidate) => candidate.code === regle.lineCode);
  const cible = rubrique ? `« ${rubrique.label} »` : `« ${regle.lineCode} »`;
  const criteres: string[] = [];
  criteres.push(regle.accountKind ? `les ${PLURIEL_NATURE_COMPTE[regle.accountKind]}`
                                  : 'les comptes');
  if (regle.codePrefix) {
    criteres.push(`dont le code commence par ${regle.codePrefix}`);
  }
  if (regle.balanceSide) {
    criteres.push(`dont le solde est ${regle.balanceSide === 'DEBIT' ? 'débiteur' : 'créditeur'}`);
  }
  return `Affecte à ${cible} : ${criteres.join(' ')}.`;
}

/** Un total lu comme son calcul : *Total actif = Caisse + Crédits + Autres actifs*. */
export function calculDuTotal(rubrique: Rubrique, rubriques: readonly Rubrique[]): string {
  const nom = (code: string) =>
    rubriques.find((candidate) => candidate.code === code)?.label ?? code;
  const parts = [
    ...rubrique.plus.map((code, index) => (index === 0 ? nom(code) : `+ ${nom(code)}`)),
    ...rubrique.minus.map((code) => `− ${nom(code)}`),
  ];
  return `${rubrique.label} = ${parts.join(' ')}`;
}

// ------------------------------------------------------------ les actes

export type ActeMaquette = 'ACTIVER' | 'RETIRER' | 'FERMER';

export const LIBELLE_ACTE_MAQUETTE: Readonly<Record<ActeMaquette, string>> = {
  ACTIVER: 'Activer',
  RETIRER: 'Retirer le brouillon',
  FERMER: 'Fermer la validité',
};

/**
 * Ce qu'on peut faire d'une maquette.
 *
 * Une maquette en vigueur ne se retire pas : un état se produit à une date, y compris passée, et
 * seule une maquette active se résout — la sortir de cet état changerait la présentation d'un
 * bilan déjà transmis au superviseur. Sa validité se **ferme**.
 */
export function actesSurMaquette(maquette: Maquette): readonly ActeMaquette[] {
  if (maquette.status === 'DRAFT') {
    return ['ACTIVER', 'RETIRER'];
  }
  return maquette.status === 'ACTIVE' ? ['FERMER'] : [];
}

export function etatEffectif(maquette: Maquette, journee: string): EtatMaquette {
  if (maquette.status !== 'ACTIVE') {
    return maquette.status;
  }
  if (maquette.validFrom > journee) {
    return 'A_VENIR';
  }
  return maquette.validTo !== null && maquette.validTo < journee ? 'ECHUE' : 'ACTIVE';
}

export interface DemandeFermetureMaquette {
  readonly validTo: string | null;
}

export function obstaclesALaFermeture(maquette: Maquette, demande: DemandeFermetureMaquette,
                                      dateComptable: string): readonly string[] {
  const obstacles: string[] = [];
  if (!demande.validTo) {
    obstacles.push('Une fermeture porte une date : le dernier jour où l’état se présente ainsi.');
    return obstacles;
  }
  if (demande.validTo < dateComptable) {
    obstacles.push(`La banque en est au ${dateComptable}. Fermer avant cette date présenterait `
                   + 'autrement un état déjà produit et transmis.');
  }
  if (demande.validTo < maquette.validFrom) {
    obstacles.push(`Cette maquette entre en vigueur le ${maquette.validFrom} : elle ne peut pas `
                   + 'finir avant de commencer.');
  }
  return obstacles;
}

// ------------------------------------------------------------ la rédaction

export interface EnteteMaquette {
  readonly kind: NatureEtat;
  readonly code: string;
  readonly label: string;
  readonly validFrom: string | null;
  readonly validTo: string | null;
}

/**
 * Ce qui empêche d'enregistrer la maquette : son identité.
 *
 * Le dernier obstacle n'est pas une règle de saisie mais une contrainte du socle rendue lisible :
 * une seule maquette active par nature d'état et par date. Tant que celle en vigueur n'a pas de
 * terme, la suivante s'écrira et **ne pourra jamais s'activer**. Le dire à la rédaction évite
 * d'écrire quarante rubriques pour rien.
 */
export function obstaclesAlEntete(entete: EnteteMaquette,
                                  maquettes: readonly Maquette[]): readonly string[] {
  const obstacles: string[] = [];
  if (!entete.code.trim()) {
    obstacles.push('Une maquette porte un code.');
  }
  if (!entete.label.trim()) {
    obstacles.push('Un libellé est attendu : c’est ce qu’on lit dans la liste des maquettes.');
  }
  if (!entete.validFrom) {
    obstacles.push('Une entrée en vigueur est attendue : une maquette s’applique sur une période.');
  }
  if (entete.validTo && entete.validFrom && entete.validTo < entete.validFrom) {
    obstacles.push('La fin de validité précède son début.');
  }
  const bloquante = maquettes.find((maquette) => maquette.kind === entete.kind
    && maquette.status === 'ACTIVE' && maquette.validTo === null);
  if (bloquante) {
    obstacles.push(`La maquette ${bloquante.code} est en vigueur sans terme pour cette nature `
                   + 'd’état : aucune suivante ne pourra être activée tant que sa validité n’est '
                   + 'pas fermée.');
  }
  return obstacles;
}

/** Une rubrique en cours de saisie : rien n'y est figé, pas même son rang. */
export interface RubriqueSaisie {
  readonly code: string;
  readonly label: string;
  readonly level: number;
  readonly kind: NatureRubrique;
  readonly side: Sens;
  readonly plus: readonly string[];
  readonly minus: readonly string[];
}

/** Une règle en cours de saisie. */
export interface RegleSaisie {
  readonly lineCode: string;
  readonly accountKind: NatureCompte | '';
  readonly codePrefix: string;
  readonly balanceSide: Sens | '';
}

/**
 * Ce qui empêche les rubriques d'être envoyées : la cohérence interne du formulaire.
 *
 * Tous les défauts sont rendus ensemble, et non un par aller-retour : c'est la différence entre
 * corriger une maquette et la deviner.
 */
export function obstaclesAuxRubriques(rubriques: readonly RubriqueSaisie[],
                                      nature: NatureEtat): readonly string[] {
  const obstacles: string[] = [];
  if (rubriques.length === 0) {
    obstacles.push('Une maquette a au moins une rubrique.');
    return obstacles;
  }
  const vus = new Set<string>();
  let detail = false;
  let resultats = 0;
  rubriques.forEach((rubrique, index) => {
    const rang = index + 1;
    if (!rubrique.code.trim()) {
      obstacles.push(`Rubrique ${rang} : un code est attendu.`);
    } else if (vus.has(rubrique.code)) {
      obstacles.push(`Deux rubriques portent le code ${rubrique.code}.`);
    }
    if (!rubrique.label.trim()) {
      obstacles.push(`Rubrique ${rubrique.code || rang} : un libellé est attendu.`);
    }
    if (rubrique.kind === 'DETAIL') {
      detail = true;
      if (rubrique.plus.length > 0 || rubrique.minus.length > 0) {
        obstacles.push(`Rubrique ${rubrique.code} : une rubrique de détail ne totalise rien.`);
      }
    }
    if (rubrique.kind === 'TOTAL') {
      if (rubrique.plus.length === 0 && rubrique.minus.length === 0) {
        obstacles.push(`Rubrique ${rubrique.code} : un total somme au moins une rubrique.`);
      }
      for (const cite of [...rubrique.plus, ...rubrique.minus]) {
        if (!vus.has(cite)) {
          obstacles.push(`Rubrique ${rubrique.code} : totalise ${cite}, qui la suit ou n’existe `
                         + 'pas. Un total ne somme que ce qui le précède.');
        }
      }
    }
    if (rubrique.kind === 'PROFIT_OR_LOSS') {
      resultats += 1;
      if (nature !== 'BALANCE_SHEET') {
        obstacles.push(`Rubrique ${rubrique.code} : le résultat de l’exercice ne se présente `
                       + 'qu’au bilan.');
      }
      if (rubrique.side !== 'CREDIT') {
        obstacles.push(`Rubrique ${rubrique.code} : le résultat se présente au crédit, positif `
                       + 'pour un bénéfice.');
      }
    }
    if (rubrique.code.trim()) {
      vus.add(rubrique.code);
    }
  });
  if (!detail) {
    obstacles.push('Une maquette a au moins une rubrique de détail : c’est elle qui reçoit les '
                   + 'comptes.');
  }
  if (resultats > 1) {
    obstacles.push('Une seule rubrique présente le résultat de l’exercice.');
  }
  return obstacles;
}

/** Ce qui empêche les règles d'être envoyées. */
export function obstaclesAuxRegles(regles: readonly RegleSaisie[],
                                   rubriques: readonly RubriqueSaisie[]): readonly string[] {
  const obstacles: string[] = [];
  if (regles.length === 0) {
    obstacles.push('Une maquette sans règle ne présenterait aucun compte.');
    return obstacles;
  }
  regles.forEach((regle, index) => {
    const rang = index + 1;
    const cible = rubriques.find((rubrique) => rubrique.code === regle.lineCode);
    if (!cible) {
      obstacles.push(`Règle ${rang} : elle affecte à ${regle.lineCode || '—'}, qui n’est pas une `
                     + 'rubrique de cette maquette.');
    } else if (cible.kind !== 'DETAIL') {
      obstacles.push(`Règle ${rang} : elle affecte à « ${cible.label} », qui est une rubrique de `
                     + `${LIBELLE_NATURE_RUBRIQUE[cible.kind].toLowerCase()}. Une règle affecte à `
                     + 'une rubrique de détail.');
    }
    if (!regle.accountKind && !regle.codePrefix.trim() && !regle.balanceSide) {
      obstacles.push(`Règle ${rang} : au moins un critère. Une règle sans critère prendrait tous `
                     + 'les comptes.');
    }
  });
  return obstacles;
}

/**
 * Les règles qu'une précédente recouvre entièrement : elles ne s'appliqueront jamais.
 *
 * La première règle qui reconnaît un compte l'emporte. Une règle dont tous les critères sont au
 * moins aussi exigeants que ceux d'une règle antérieure est donc morte — et c'est un défaut qu'on
 * ne découvre, sinon, qu'en lisant le bilan produit et en se demandant pourquoi une rubrique est
 * vide.
 *
 * Le socle ne peut pas le refuser : pendant la rédaction, c'est un état transitoire légitime.
 * L'écran, lui, peut le montrer.
 */
export function reglesCouvertes(regles: readonly RegleSaisie[]): readonly number[] {
  const mortes: number[] = [];
  for (let j = 0; j < regles.length; j++) {
    const candidate = regles[j]!;
    for (let i = 0; i < j; i++) {
      if (recouvre(regles[i]!, candidate)) {
        mortes.push(j);
        break;
      }
    }
  }
  return mortes;
}

/** Vrai si tout compte que `etroite` reconnaîtrait est déjà reconnu par `large`. */
function recouvre(large: RegleSaisie, etroite: RegleSaisie): boolean {
  if (large.accountKind && large.accountKind !== etroite.accountKind) {
    return false;
  }
  if (large.balanceSide && large.balanceSide !== etroite.balanceSide) {
    return false;
  }
  const prefixeLarge = large.codePrefix.trim();
  return prefixeLarge === '' || etroite.codePrefix.trim().startsWith(prefixeLarge);
}

/**
 * La règle que proposerait un compte resté sans rubrique.
 *
 * Une anomalie qu'on corrige d'un geste vaut mieux qu'une anomalie qu'on recopie. Le sens du
 * solde est repris : un compte de client débiteur est un crédit à la clientèle, créditeur un
 * dépôt — ce n'est jamais la même rubrique.
 */
export function regleProposee(compte: CompteNonAffecte, lineCode: string): RegleSaisie {
  return {
    lineCode,
    accountKind: compte.accountKind,
    codePrefix: '',
    balanceSide: compte.side,
  };
}

/** Les rubriques de détail : les seules qu'une règle puisse viser. */
export function rubriquesDeDetail(
    rubriques: readonly RubriqueSaisie[]): readonly RubriqueSaisie[] {
  return rubriques.filter((rubrique) => rubrique.kind === 'DETAIL');
}

/** « 1 rubrique », « 8 rubriques ». */
export function libelleNombreRubriques(nombre: number): string {
  return `${nombre} rubrique${Math.abs(nombre) > 1 ? 's' : ''}`;
}

/** « 1 compte sans rubrique », « 12 comptes sans rubrique ». */
export function libelleNombreOrphelins(nombre: number): string {
  return `${nombre} compte${Math.abs(nombre) > 1 ? 's' : ''} sans rubrique`;
}
