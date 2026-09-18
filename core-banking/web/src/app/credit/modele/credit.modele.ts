import { EtatOperation } from '../../ui';

/** Un montant, tel que le socle le rend : jamais un nombre flottant. */
export interface Montant {
  readonly amount: string;
  readonly currency: string;
}

// ---------------------------------------------------------------- origination

/** Les sept états d'une demande, tels que le socle les nomme. */
export type StatutDemande =
  | 'SUBMITTED' | 'UNDER_REVIEW' | 'APPROVED' | 'REJECTED'
  | 'CANCELLED' | 'CONTRACTED' | 'EXPIRED';

/**
 * Nature d'une condition.
 *
 * **La distinction commande le déblocage.** Une condition *suspensive* empêche
 * la contractualisation tant qu'elle n'est pas levée ; une *résolutoire* court
 * après, et son non-respect ouvre un recours. Les confondre débloque un crédit
 * sans la garantie qui le couvrait — c'est la faute la plus coûteuse de
 * l'instruction, et c'est pourquoi l'écran ne les mélange jamais.
 */
export type NatureCondition = 'PRECEDENT' | 'SUBSEQUENT';

export interface Demande {
  readonly id: string;
  readonly reference: string;
  readonly customerId: string;
  readonly customerReference: string | null;
  readonly productCode: string;
  readonly purpose: string | null;
  readonly requestedAmount: Montant | null;
  readonly requestedTermMonths: number | null;
  readonly requestedOn: string | null;
  readonly status: StatutDemande;
  readonly contractId: string | null;
  readonly closedOn: string | null;
  readonly closingReason: string | null;
}

/**
 * L'analyse de la capacité de remboursement.
 *
 * `breaches` est ce que la **grille de risque de la banque** refuse, nommé par
 * le socle. L'écran le rend tel quel : un ratio seul est un chiffre, un
 * dépassement nommé est une décision à prendre.
 */
export interface Analyse {
  readonly id: string;
  readonly assessedOn: string | null;
  readonly monthlyIncome: Montant | null;
  readonly monthlyCharges: Montant | null;
  readonly existingCommitments: Montant | null;
  readonly downPayment: Montant | null;
  readonly requestedInstalment: Montant | null;
  readonly debtServiceRatioPercent: string | null;
  readonly externalScore: number | null;
  readonly scoreSource: string | null;
  readonly breaches: readonly string[];
}

export interface Condition {
  readonly id: string;
  readonly kind: NatureCondition;
  readonly description: string;
  readonly dueOn: string | null;
  readonly clearedOn: string | null;
  readonly evidence: string | null;
}

export interface Decision {
  readonly outcome: 'APPROVED' | 'REJECTED';
  readonly decidedOn: string | null;
  readonly grantedAmount: Montant | null;
  readonly grantedRatePercent: string | null;
  readonly grantedTermMonths: number | null;
  readonly validUntil: string | null;
  readonly reason: string | null;
  readonly waiverReason: string | null;
}

/** Un acte du dossier, daté. L'instruction se relit des années après. */
export interface Evenement {
  readonly kind: string;
  readonly occurredOn: string | null;
  readonly detail: string | null;
}

/** Le dossier complet : la demande et tout ce qui s'y est attaché. */
export interface DossierCredit {
  readonly demande: Demande;
  readonly analyses: readonly Analyse[];
  readonly conditions: readonly Condition[];
  readonly decision: Decision | null;
  readonly evenements: readonly Evenement[];
}

// ---------------------------------------------------------------- contrat

export type StatutContrat = 'DRAFT' | 'ACTIVE' | 'CLOSED' | 'WRITTEN_OFF';

/** Une échéance du tableau d'amortissement. */
export interface Echeance {
  readonly number: number;
  readonly dueDate: string | null;
  readonly principal: Montant | null;
  readonly interest: Montant | null;
  readonly insurance: Montant | null;
  readonly tax: Montant | null;
  readonly fee: Montant | null;
  readonly total: Montant | null;
}

/**
 * Nature d'une créance exigible. **L'ordre de l'énumération est l'ordre
 * d'imputation** d'un règlement : frais de recouvrement d'abord, capital non
 * échu en dernier. Le socle l'applique ; l'écran le montre pour que le
 * guichetier sache répondre à « à quoi va mon argent ».
 */
export type NatureCreance =
  | 'RECOVERY_FEES' | 'PENALTIES' | 'FEES_AND_INSURANCE'
  | 'LATE_INTEREST' | 'INTEREST' | 'PRINCIPAL' | 'FUTURE_PRINCIPAL';

export interface Creance {
  readonly id: string;
  readonly category: NatureCreance;
  readonly dueDate: string | null;
  readonly instalmentNumber: number | null;
  readonly outstanding: Montant | null;
}

export interface Contrat {
  readonly id: string;
  readonly reference: string;
  readonly productCode: string;
  readonly principal: Montant | null;
  readonly currency: string;
  readonly status: StatutContrat;
  readonly disbursedOn: string | null;
  readonly daysPastDue: number;
  readonly asOf: string | null;
  readonly echeancier: readonly Echeance[];
  readonly creances: readonly Creance[];
  readonly tauxAnnuel: string | null;
  readonly nombreEcheances: number | null;
  readonly methode: string | null;
}

/** Une ligne d'imputation d'un règlement, telle que le socle l'a décidée. */
export interface Imputation {
  readonly category: NatureCreance;
  readonly instalmentNumber: number | null;
  readonly amount: Montant | null;
  readonly remaining: Montant | null;
}

/** Ce que rend un règlement : ce qui a été imputé, et ce qui ne l'a pas été. */
export interface Reglement {
  readonly paid: Montant | null;
  readonly allocated: Montant | null;
  readonly unallocated: Montant | null;
  readonly imputations: readonly Imputation[];
}

// ---------------------------------------------------------------- fin de vie

/**
 * Effet d'un remboursement anticipé partiel sur l'échéancier restant.
 *
 * **Le choix appartient à l'emprunteur, pas à la banque**, et il change
 * beaucoup : à capital égal remboursé, raccourcir la durée fait économiser
 * bien plus d'intérêts qu'abaisser l'échéance. Ne proposer que l'un des deux
 * est un défaut fonctionnel courant — l'écran propose les deux et dit ce qui
 * les sépare.
 */
export type ModeAnticipe = 'SHORTEN_TERM' | 'REDUCE_INSTALMENT';

/**
 * Un passage en perte, tel que le socle l'a constaté.
 *
 * L'exposition est absorbée dans un ordre qui n'est pas négociable : les
 * **intérêts réservés** d'abord — ils ont déjà été sortis du résultat à la
 * suspension, et les passer en perte une seconde fois constaterait une charge
 * pour un produit jamais pris ; puis la **provision**, qui est faite pour
 * cela ; le reliquat seul est une **perte**. Un dossier sur-provisionné rend
 * l'excédent au résultat.
 *
 * **La créance reste due.** Sortie de l'actif, elle se suit au hors bilan :
 * `outstanding()` est ce qui reste à recouvrer.
 */
export interface Perte {
  readonly id: string;
  readonly contractReference: string;
  readonly writtenOffOn: string | null;
  readonly principalWritten: Montant | null;
  readonly receivablesWritten: Montant | null;
  readonly reservedUsed: Montant | null;
  readonly provisionUsed: Montant | null;
  readonly provisionReleased: Montant | null;
  readonly lossRecognised: Montant | null;
  readonly recovered: Montant | null;
  readonly reason: string | null;
  readonly bucketCode: string | null;
  readonly daysPastDue: number | null;
}

export interface Recouvrement {
  readonly id: string;
  readonly recoveredOn: string | null;
  readonly amount: Montant | null;
}

/** Ce que rend la lecture d'une perte : le constat, et ce qui a été recouvré depuis. */
export interface DossierPerte {
  readonly perte: Perte | null;
  readonly recouvrements: readonly Recouvrement[];
}

export interface DemandeAnticipe {
  readonly amount: string;
  readonly currency: string;
  readonly mode: ModeAnticipe;
}

export interface DemandeReechelonnement {
  readonly instalments: number;
  readonly firstDueDate: string | null;
  readonly effectiveFrom: string | null;
  readonly reason: string;
}

export interface DemandeRevisionTaux {
  readonly annualRatePercent: string;
  readonly effectiveFrom: string | null;
}

export interface DemandePerte {
  readonly reason: string;
  readonly writtenOffOn: string | null;
}

export interface DemandeRecouvrement {
  readonly amount: string;
  readonly channelAccountId: string;
  readonly recoveredOn: string | null;
}

// ---------------------------------------------------------------- demandes

export interface DemandeDeCredit {
  readonly legalEntityId: string;
  readonly customerId: string;
  readonly productCode: string;
  readonly currency: string;
  readonly requestedAmount: string;
  readonly requestedTermMonths: number;
  readonly purpose: string;
  readonly reference: string | null;
  readonly requestedOn: string | null;
}

export interface DemandeAnalyse {
  readonly monthlyIncome: string;
  readonly monthlyCharges: string;
  readonly downPayment: string | null;
  readonly ratePercent: string;
  readonly externalScore: number | null;
  readonly scoreSource: string | null;
  readonly assessedOn: string | null;
}

export interface DemandeCondition {
  readonly kind: NatureCondition;
  readonly description: string;
  readonly dueOn: string | null;
}

export interface DemandeDecision {
  readonly outcome: 'APPROVED' | 'REJECTED';
  readonly grantedAmount: string | null;
  readonly grantedRatePercent: string | null;
  readonly grantedTermMonths: number | null;
  readonly reason: string;
  readonly waiverReason: string | null;
  readonly decidedOn: string | null;
}

export interface DemandeContrat {
  readonly contractReference: string | null;
  readonly loanAccountId: string;
  readonly settlementAccountId: string;
  readonly disbursementDate: string | null;
}

export interface DemandeReglement {
  readonly amount: string;
  readonly currency: string;
  readonly valueDate: string | null;
}

// ---------------------------------------------------------------- libellés

export const LIBELLE_STATUT_DEMANDE: Readonly<Record<StatutDemande, string>> = {
  SUBMITTED: 'Déposée',
  UNDER_REVIEW: 'En instruction',
  APPROVED: 'Accordée',
  REJECTED: 'Refusée',
  CANCELLED: 'Retirée',
  CONTRACTED: 'Contractée',
  EXPIRED: 'Caduque',
};

export const LIBELLE_STATUT_CONTRAT: Readonly<Record<StatutContrat, string>> = {
  DRAFT: 'Non débloqué',
  ACTIVE: 'En cours',
  CLOSED: 'Soldé',
  WRITTEN_OFF: 'Passé en perte',
};

export const LIBELLE_CONDITION: Readonly<Record<NatureCondition, string>> = {
  PRECEDENT: 'Suspensive',
  SUBSEQUENT: 'Résolutoire',
};

export const LIBELLE_CREANCE: Readonly<Record<NatureCreance, string>> = {
  RECOVERY_FEES: 'Frais de recouvrement',
  PENALTIES: 'Pénalités',
  FEES_AND_INSURANCE: 'Frais et assurance',
  LATE_INTEREST: 'Intérêts de retard',
  INTEREST: 'Intérêts',
  PRINCIPAL: 'Capital échu',
  FUTURE_PRINCIPAL: 'Capital non échu',
};

export const LIBELLE_MODE_ANTICIPE: Readonly<Record<ModeAnticipe, string>> = {
  SHORTEN_TERM: 'Raccourcir la durée',
  REDUCE_INSTALMENT: "Abaisser l'échéance",
};

/** Ce que chaque mode fait, en une phrase que l'emprunteur comprend. */
export const EFFET_MODE_ANTICIPE: Readonly<Record<ModeAnticipe, string>> = {
  SHORTEN_TERM:
    "L'échéance ne change pas ; le crédit se termine plus tôt. C'est l'option "
    + "la plus économique : les intérêts cessent de courir plus tôt.",
  REDUCE_INSTALMENT:
    "La durée ne change pas ; l'échéance baisse. Elle soulage la trésorerie "
    + 'mensuelle et coûte davantage au total.',
};

const ETAT_DEMANDE: Readonly<Record<StatutDemande, EtatOperation>> = {
  SUBMITTED: 'brouillon',
  UNDER_REVIEW: 'en-attente',
  APPROVED: 'approuve',
  REJECTED: 'rejete',
  CANCELLED: 'expire',
  CONTRACTED: 'comptabilise',
  EXPIRED: 'expire',
};

const ETAT_CONTRAT: Readonly<Record<StatutContrat, EtatOperation>> = {
  DRAFT: 'brouillon',
  ACTIVE: 'comptabilise',
  CLOSED: 'contre-passe',
  WRITTEN_OFF: 'rejete',
};

export function etatDeLaDemande(statut: StatutDemande): EtatOperation {
  return ETAT_DEMANDE[statut];
}

export function etatDuContrat(statut: StatutContrat): EtatOperation {
  return ETAT_CONTRAT[statut];
}

/**
 * Ce qui reste dû au hors bilan après un passage en perte : sorti de l'actif,
 * pas encore recouvré. C'est la somme que le recouvrement poursuit, et elle ne
 * se déduit pas d'une seule ligne — d'où ce calcul plutôt qu'un champ affiché.
 */
export function resteARecouvrer(perte: Perte): string {
  const chiffre = (m: Montant | null) => Number(m?.amount ?? '0');
  const reste = chiffre(perte.principalWritten) + chiffre(perte.receivablesWritten)
    - chiffre(perte.recovered);
  return String(reste);
}

/**
 * Le retard est un état, pas une note en bas de page.
 *
 * Les seuils sont ceux du classement prudentiel usuel de l'UEMOA — 30, 90,
 * 180 jours. Ils restent ici parce qu'ils ne commandent **rien** : le socle
 * provisionne selon sa propre grille, paramétrée et revue. Ce découpage sert à
 * ce qu'un chargé de crédit voie d'un coup d'œil la gravité d'un retard, pas à
 * décider quoi que ce soit.
 */
export function graviteDuRetard(jours: number): 'aucun' | 'surveille' | 'douteux' | 'compromis' {
  if (jours <= 0) return 'aucun';
  if (jours < 90) return 'surveille';
  if (jours < 180) return 'douteux';
  return 'compromis';
}

/**
 * Ce qui empêche de contractualiser, en clair.
 *
 * Le socle refuse ; l'écran affiche le refus à l'avance pour éviter une saisie
 * perdue. **Seules les conditions suspensives comptent** : une résolutoire non
 * levée est un engagement à suivre, pas un obstacle.
 */
export function obstaclesALaContractualisation(dossier: DossierCredit): readonly string[] {
  const obstacles: string[] = [];
  const { demande, decision, conditions } = dossier;

  if (demande.status === 'CONTRACTED') {
    obstacles.push('La demande est déjà contractée.');
    return obstacles;
  }
  if (demande.status !== 'APPROVED') {
    obstacles.push(
      `La demande est ${LIBELLE_STATUT_DEMANDE[demande.status].toLowerCase()} : `
      + "seule une demande accordée se contractualise.");
  }
  if (decision?.outcome === 'REJECTED') {
    obstacles.push('La décision est un refus.');
  }
  for (const condition of conditions) {
    if (condition.kind === 'PRECEDENT' && !condition.clearedOn) {
      obstacles.push(`Condition suspensive non levée : ${condition.description}`);
    }
  }
  return obstacles;
}

/** Les conditions résolutoires encore ouvertes : à suivre, pas à bloquer. */
export function engagementsASuivre(dossier: DossierCredit): readonly Condition[] {
  return dossier.conditions.filter((c) => c.kind === 'SUBSEQUENT' && !c.clearedOn);
}

/** La dernière analyse versée au dossier : c'est elle qui fait foi. */
export function analyseEnVigueur(dossier: DossierCredit): Analyse | null {
  return dossier.analyses.length === 0 ? null : dossier.analyses[dossier.analyses.length - 1]!;
}
