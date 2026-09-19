import { Injectable } from '@angular/core';
import { Montant, RefusMetier } from '../guichet/modele/guichet.modele';
import { FAMILLES_DEMONSTRATION } from './modele/produits.demonstration';
import {
  Agence, ConditionsDeBanque, DemandeAgence, DemandeFerie, DemandeHeureLimite,
  DemandeRegleDateValeur, HeureLimite, JourFerie, RegleDateValeur,
} from './modele/reseau.modele';
import {
  actesSurVersion, CompteGeneral, EnteteVersion, FamilleProduit, manquesDuParametrage, Tranche,
  VersionComplete, VersionProduit,
} from './modele/produits.modele';
import {
  DemandeEtablissement, DemandeRegle, DomaineNumerotation, Etablissement, RegleNumerotation,
  Segment,
} from './modele/etablissement.modele';
import {
  EtapeRun, FiltreBalance, LigneBalance, PageBalance, RunTfj, TotauxBalance,
} from './modele/siege.modele';
import { CATALOGUE_DEMONSTRATION } from './modele/schemas.demonstration';
import { essaiDeDemonstration } from './modele/schemas.essai-demonstration';
import {
  DemandeFermetureSchema, EnteteSchema, Essai, EvenementSocle, LigneSaisie, SchemaComplet,
  SchemaComptable,
} from './modele/schemas.modele';
import { EnAttenteSiege, Siege } from './siege.port';

function segment(kind: Segment['kind'], reglages: Partial<Segment> = {}): Segment {
  return {
    kind, literalValue: null, length: null, padChar: null, datePattern: null, algorithm: null,
    ...reglages,
  };
}

/**
 * L'établissement de démonstration.
 *
 * Il porte un code banque : sans lui, la règle du compte refuserait de composer
 * et l'écran montrerait un refus au lieu d'un RIB. Le code 10015 n'appartient à
 * personne — c'est un numéro de démonstration, et l'écran le dit.
 */
const ETABLISSEMENT: Etablissement = {
  id: '', code: 'BANQUE-DEMO', name: 'Socle bancaire', countryCode: 'BF',
  functionalCurrency: 'XOF', businessDate: '2026-09-18', status: 'ACTIVE',
  bankCode: '10015', legalName: 'Socle bancaire SA', approvalNumber: 'AGR-BF-2019-014',
  taxId: '00012345 A', registryNumber: 'RCCM BF-OUA-2019-B-0142',
  address: '01 BP 1420 Ouagadougou 01', phone: '+226 25 30 12 40',
  email: 'contact@socle-bancaire.example',
};

const REGLES: RegleNumerotation[] = [
  {
    id: 'r-party', domain: 'PARTY', label: 'Référence client',
    segments: [segment('LITERAL', { literalValue: 'CLI-' }),
               segment('SEQUENCE', { length: 6, padChar: '0' })],
    scope: 'ENTITY', reset: 'NEVER', sequenceStart: 1, status: 'ACTIVE',
  },
  {
    id: 'r-account', domain: 'ACCOUNT', label: 'Numéro de compte (RIB)',
    segments: [segment('BANK_CODE', { length: 5, padChar: '0' }),
               segment('BRANCH_CODE', { length: 5, padChar: '0' }),
               segment('SEQUENCE', { length: 12, padChar: '0' }),
               segment('CHECK_DIGITS', { length: 2, padChar: '0', algorithm: 'RIB_97' })],
    scope: 'BRANCH', reset: 'NEVER', sequenceStart: 1, status: 'ACTIVE',
  },
  {
    id: 'r-application', domain: 'LOAN_APPLICATION', label: 'Référence de demande de crédit',
    segments: [segment('LITERAL', { literalValue: 'DC-' }),
               segment('DATE', { datePattern: 'yyyy' }),
               segment('LITERAL', { literalValue: '-' }),
               segment('SEQUENCE', { length: 4, padChar: '0' })],
    scope: 'ENTITY', reset: 'YEAR', sequenceStart: 1, status: 'ACTIVE',
  },
  {
    id: 'r-contract-v1', domain: 'LOAN_CONTRACT', label: 'Contrat — ancien plan',
    segments: [segment('LITERAL', { literalValue: 'C' }),
               segment('SEQUENCE', { length: 7, padChar: '0' })],
    scope: 'ENTITY', reset: 'NEVER', sequenceStart: 1, status: 'WITHDRAWN',
  },
  {
    id: 'r-contract', domain: 'LOAN_CONTRACT', label: 'Référence de contrat de crédit',
    segments: [segment('LITERAL', { literalValue: 'CRD-' }),
               segment('DATE', { datePattern: 'yyyy' }),
               segment('LITERAL', { literalValue: '-' }),
               segment('SEQUENCE', { length: 5, padChar: '0' })],
    scope: 'ENTITY', reset: 'YEAR', sequenceStart: 1, status: 'ACTIVE',
  },
];

const DEVISE = 'XOF';

/**
 * L'ordre réel des étapes du traitement de fin de journée, celui que le socle
 * épingle dans son test de moteur. On ne l'invente pas : un exploitant qui
 * apprend l'écran doit reconnaître son traitement.
 */
const ETAPES = [
  'PRE_CHECKS', 'FX_RATES', 'HOLD_EXPIRY', 'DIRECT_DEBITS', 'STANDING_ORDERS', 'FEE_CHARGING',
  'LOAN_MOBILISATION', 'LOAN_SCHEDULE', 'LOAN_INTEREST_ACCRUAL', 'LOAN_LATE_CHARGES',
  'LOAN_CLASSIFICATION', 'LOAN_CLOSURE', 'TERM_DEPOSIT_ACCRUAL', 'TERM_DEPOSIT_MATURITY',
  'INTEREST_ACCRUAL', 'INTEREST_SETTLEMENT', 'FX_REVALUATION', 'DORMANCY', 'KYC_REVIEW',
  'DOCUMENT_EXPIRY', 'OFFER_EXPIRY', 'SUSPENSE_REVIEW', 'AML_MONITORING', 'REGULATORY_DEADLINES',
  'BALANCE_SNAPSHOT', 'RECONCILIATION', 'OPEN_NEXT_DAY',
] as const;

/** Les étapes dont l'échec arrête la chaîne. */
const BLOQUANTES = new Set<string>([
  'PRE_CHECKS', 'FX_RATES', 'BALANCE_SNAPSHOT', 'RECONCILIATION', 'OPEN_NEXT_DAY',
]);

const ANOMALIES: Readonly<Record<string, readonly string[]>> = {
  SUSPENSE_REVIEW: ['3 suspens de plus de 30 jours sans responsable désigné'],
  AML_MONITORING: ['Scenario SEUIL_ESPECES : 2 alertes ouvertes'],
  KYC_REVIEW: ['14 dossiers dont la revue KYC est échue'],
};

function montant(valeur: number): Montant {
  return { amount: String(Math.round(valeur)), currency: DEVISE };
}

/**
 * Le plan comptable de la démonstration : ce qu'un paramétrage désigne.
 *
 * Quelques comptes, pas un plan SYSCOHADA entier — assez pour que le choix d'un compte
 * d'imputation se fasse à l'écran plutôt qu'au clavier, et pour qu'on voie la différence entre un
 * compte de charges et un compte de produits.
 */
const COMPTES_GENERAUX: CompteGeneral[] = [
  { id: 'gl-6021', code: '602100', kind: 'GL', normalBalance: 'DEBIT', currency: 'XOF',
    nature: 'INCOME_STATEMENT', status: 'ACTIVE', postable: true },
  { id: 'gl-7021', code: '702100', kind: 'GL', normalBalance: 'CREDIT', currency: 'XOF',
    nature: 'INCOME_STATEMENT', status: 'ACTIVE', postable: true },
  { id: 'gl-7031', code: '703100', kind: 'GL', normalBalance: 'CREDIT', currency: 'XOF',
    nature: 'INCOME_STATEMENT', status: 'ACTIVE', postable: true },
  { id: 'gl-3781', code: '378100', kind: 'GL', normalBalance: 'CREDIT', currency: 'XOF',
    nature: 'BALANCE_SHEET', status: 'ACTIVE', postable: true },
  { id: 'gl-4421', code: '442100', kind: 'GL', normalBalance: 'CREDIT', currency: 'XOF',
    nature: 'BALANCE_SHEET', status: 'ACTIVE', postable: true },
  { id: 'gl-3711', code: '371100', kind: 'GL', normalBalance: 'DEBIT', currency: 'XOF',
    nature: 'BALANCE_SHEET', status: 'ACTIVE', postable: true },
  { id: 'nos-boa', code: 'NOSTRO-BOA', kind: 'NOSTRO', normalBalance: 'DEBIT', currency: 'XOF',
    nature: 'BALANCE_SHEET', status: 'ACTIVE', postable: true },
  { id: 'int-cpn', code: 'INTERNE-COMPENSATION', kind: 'INTERNAL', normalBalance: 'DEBIT',
    currency: 'XOF', nature: 'BALANCE_SHEET', status: 'ACTIVE', postable: true },
];

/** Deux produits en vigueur, un brouillon qui attend, une version fermée : une vraie table. */
const PARAMETRES_COURANT: Record<string, string> = {
  'interest.day_count': 'ACT_365',
  'interest.side': 'CREDITOR',
  'interest.rate': '0',
  'interest.capitalisation': 'QUARTERLY',
  'interest.debit_account': 'gl-6021',
  'interest.credit_account': 'gl-3781',
  'overdraft.limit': '250000',
  'overdraft.rate': '13.5',
  'overdraft.excess_rate': '18',
  'overdraft.day_count': 'ACT_360',
  'overdraft.debit_account': 'gl-3711',
  'overdraft.credit_account': 'gl-7021',
  'overdraft.settlement': 'QUARTERLY',
  'overdraft.tax_rate': '18',
  'overdraft.tax_account': 'gl-4421',
  'ops.withdrawal_fee': '500',
  'ops.transfer_fee': '1500',
  'ops.fee_income_account': 'gl-7031',
  'ops.tax_rate': '18',
  'ops.tax_account': 'gl-4421',
  'ops.daily_debit_max': '2000000',
  'ops.cheque_book_fee': '3000',
  'ops.cheque_collection_account': 'int-cpn',
  'ops.payment_clearing_account': 'nos-boa',
  'dormancy.months': '24',
  'fee.codes': 'TENUE',
  'fee.TENUE.label': 'Frais de tenue de compte',
  'fee.TENUE.income_account': 'gl-7031',
  'fee.TENUE.frequency': 'MONTHLY',
  'fee.TENUE.basis': 'FLAT',
  'fee.TENUE.amount': '1000',
  'fee.TENUE.tax_rate': '18',
  'fee.TENUE.tax_account': 'gl-4421',
};

const PARAMETRES_EPARGNE: Record<string, string> = {
  'interest.day_count': 'ACT_365',
  'interest.side': 'CREDITOR',
  'interest.tiering_mode': 'PROGRESSIVE',
  'interest.capitalisation': 'QUARTERLY',
  'interest.debit_account': 'gl-6021',
  'interest.credit_account': 'gl-3781',
  'interest.withholding': 'IRVM',
};

const VERSIONS: VersionProduit[] = [
  { id: 'pv-cc-2026', code: 'CPTE-CHQ-PART', productType: 'CURRENT_ACCOUNT',
    label: 'Compte chèque particulier', currency: 'XOF', validFrom: '2026-01-01', validTo: null,
    status: 'ACTIVE', createdBy: 'ADIALLO', createdAt: '2025-12-04T10:12:00Z',
    approvedBy: 'MKONE', approvedAt: '2025-12-05T08:30:00Z' },
  { id: 'pv-cc-2025', code: 'CPTE-CHQ-PART', productType: 'CURRENT_ACCOUNT',
    label: 'Compte chèque particulier', currency: 'XOF', validFrom: '2025-01-01',
    validTo: '2025-12-31', status: 'ACTIVE', createdBy: 'ADIALLO',
    createdAt: '2024-12-02T09:00:00Z', approvedBy: 'MKONE', approvedAt: '2024-12-03T09:00:00Z' },
  { id: 'pv-ep-2026', code: 'EPARGNE-PART', productType: 'SAVINGS_ACCOUNT',
    label: 'Compte d’épargne particulier', currency: 'XOF', validFrom: '2026-01-01',
    validTo: null, status: 'ACTIVE', createdBy: 'ADIALLO', createdAt: '2025-12-04T10:40:00Z',
    approvedBy: 'MKONE', approvedAt: '2025-12-05T08:31:00Z' },
  { id: 'pv-ep-2027', code: 'EPARGNE-PART', productType: 'SAVINGS_ACCOUNT',
    label: 'Compte d’épargne particulier', currency: 'XOF', validFrom: '2027-01-01',
    validTo: null, status: 'DRAFT', createdBy: 'ADIALLO', createdAt: '2026-09-15T14:22:00Z',
    approvedBy: null, approvedAt: null },
  { id: 'pv-dat-retire', code: 'DAT-12M', productType: 'TERM_DEPOSIT',
    label: 'Dépôt à terme 12 mois', currency: 'XOF', validFrom: '2026-10-01', validTo: null,
    status: 'WITHDRAWN', createdBy: 'ADIALLO', createdAt: '2026-08-30T11:00:00Z',
    approvedBy: null, approvedAt: null },
];

const BAREMES: Record<string, Record<string, Tranche[]>> = {
  'pv-ep-2026': {
    INTEREST: [
      { from: '0', to: '500000', annualRatePercent: '2.5' },
      { from: '500000', to: '5000000', annualRatePercent: '3.5' },
      { from: '5000000', to: null, annualRatePercent: '4.25' },
    ],
  },
};

const PARAMETRES: Record<string, Record<string, string>> = {
  'pv-cc-2026': PARAMETRES_COURANT,
  'pv-cc-2025': { ...PARAMETRES_COURANT, 'overdraft.rate': '12', 'fee.TENUE.amount': '800' },
  'pv-ep-2026': PARAMETRES_EPARGNE,
  'pv-ep-2027': { ...PARAMETRES_EPARGNE, 'interest.rate': '3' },
  'pv-dat-retire': {},
};

/** Un réseau à trois niveaux : le siège, deux régions, quatre agences. */
const AGENCES: Agence[] = [
  { id: 'siege', code: 'SIEGE', name: 'Siège', kind: 'HEAD_OFFICE', parentId: null,
    status: 'ACTIVE', openedOn: '2014-01-02', closedOn: null },
  { id: 'reg-centre', code: 'REG-CENTRE', name: 'Direction régionale du Centre', kind: 'REGION',
    parentId: 'siege', status: 'ACTIVE', openedOn: '2016-04-01', closedOn: null },
  { id: 'reg-ouest', code: 'REG-OUEST', name: 'Direction régionale de l’Ouest', kind: 'REGION',
    parentId: 'siege', status: 'ACTIVE', openedOn: '2018-09-03', closedOn: null },
  { id: 'ag-oua1', code: '00011', name: 'Ouagadougou Centre', kind: 'BRANCH',
    parentId: 'reg-centre', status: 'ACTIVE', openedOn: '2014-01-02', closedOn: null },
  { id: 'ag-oua2', code: '00021', name: 'Ouagadougou Gounghin', kind: 'BRANCH',
    parentId: 'reg-centre', status: 'ACTIVE', openedOn: '2019-06-17', closedOn: null },
  { id: 'ag-bobo', code: '00022', name: 'Bobo-Dioulasso', kind: 'BRANCH', parentId: 'reg-ouest',
    status: 'ACTIVE', openedOn: '2018-09-03', closedOn: null },
  { id: 'ag-banfora', code: '00031', name: 'Banfora', kind: 'BRANCH', parentId: 'reg-ouest',
    status: 'CLOSED', openedOn: '2020-02-10', closedOn: '2025-03-31' },
];

const FERIES: JourFerie[] = [
  { date: '2026-01-01', label: 'Jour de l’an' },
  { date: '2026-01-03', label: 'Journée du soulèvement populaire' },
  { date: '2026-05-01', label: 'Fête du travail' },
  { date: '2026-08-05', label: 'Fête nationale' },
  { date: '2026-11-01', label: 'Toussaint' },
  { date: '2026-12-11', label: 'Proclamation de l’indépendance' },
  { date: '2026-12-25', label: 'Noël' },
];

const REGLES_VALEUR: RegleDateValeur[] = [
  { id: 'vd-1', operationType: 'CASH_DEPOSIT', channel: null, direction: 'CREDIT', offset: 0,
    unit: 'CALENDAR_DAYS', convention: 'UNADJUSTED', validFrom: '2026-01-01', validTo: null },
  { id: 'vd-2', operationType: 'CASH_WITHDRAWAL', channel: null, direction: 'DEBIT', offset: 0,
    unit: 'CALENDAR_DAYS', convention: 'UNADJUSTED', validFrom: '2026-01-01', validTo: null },
  { id: 'vd-3', operationType: 'TRANSFER', channel: 'CLEARING', direction: 'CREDIT', offset: 2,
    unit: 'BUSINESS_DAYS', convention: 'FOLLOWING', validFrom: '2026-01-01', validTo: null },
  { id: 'vd-4', operationType: 'TRANSFER', channel: 'CLEARING', direction: 'DEBIT', offset: 1,
    unit: 'BUSINESS_DAYS', convention: 'FOLLOWING', validFrom: '2026-01-01', validTo: null },
  { id: 'vd-5', operationType: 'CHEQUE_DEPOSIT', channel: null, direction: 'CREDIT', offset: 4,
    unit: 'BUSINESS_DAYS', convention: 'FOLLOWING', validFrom: '2026-01-01', validTo: null },
];

const HEURES: HeureLimite[] = [
  { id: 'co-1', channel: 'CLEARING', cutoffTime: '14:30', closesChannel: false,
    validFrom: '2026-01-01', validTo: null },
  { id: 'co-2', channel: 'BRANCH', cutoffTime: '16:00', closesChannel: false,
    validFrom: '2026-01-01', validTo: null },
];

/**
 * Source de démonstration du siège.
 *
 * Le premier passage échoue sur `PRE_CHECKS` — une caisse mouvementée n'est pas
 * arrêtée. C'est exactement ce qui relie l'arrêté de caisse du guichet à la
 * clôture de la journée, et c'est le refus le plus fréquent en agence. La
 * reprise passe.
 */
@Injectable()
export class SiegeFactice implements Siege {
  latenceMs = 300;

  private readonly runs = new Map<string, RunTfj>();

  private identite: Etablissement = ETABLISSEMENT;
  private plan: readonly RegleNumerotation[] = REGLES;

  /**
   * L'étape qui échouera au prochain passage, essai à blanc compris — c'est
   * tout l'intérêt de l'essai : il trouve le blocage avant que la journée soit
   * engagée. Consommée au premier passage, comme si la caisse avait été
   * arrêtée entre-temps. Les tests la mettent à `null` pour un passage propre.
   */
  echecPrevu: string | null = 'PRE_CHECKS';

  async lancerTfj(legalEntityId: string, journee: string, mode: 'REAL' | 'DRY_RUN'): Promise<RunTfj> {
    await this.latence();
    const echoue = this.echecPrevu;
    this.echecPrevu = null;
    const run = this.construire(legalEntityId, journee, mode, echoue);
    this.runs.set(run.id, run);
    return run;
  }

  async lireTfj(_entite: string, runId: string): Promise<RunTfj> {
    await this.latence();
    return this.require(runId);
  }

  async reprendreTfj(_entite: string, runId: string): Promise<RunTfj> {
    await this.latence();
    const run = this.require(runId);
    if (run.status !== 'FAILED') {
      throw new RefusMetier(409, 'REPRISE_IMPOSSIBLE',
        `Le passage est ${this.mot(run.status)}.`,
        "On ne reprend qu'un traitement en échec : il repart de l'étape qui a échoué, pas du début.");
    }
    const repris = this.construire(run.legalEntityId, run.businessDate, run.mode, null, run.id);
    this.runs.set(repris.id, repris);
    return repris;
  }

  async annulerTfj(_entite: string, runId: string): Promise<RunTfj> {
    await this.latence();
    const run = this.require(runId);
    if (run.status === 'CANCELLED') {
      throw new RefusMetier(409, 'DEJA_ANNULE', 'Ce passage est déjà annulé.');
    }
    const annule: RunTfj = { ...run, status: 'CANCELLED', finishedAt: new Date().toISOString() };
    this.runs.set(runId, annule);
    return annule;
  }

  async balance(_entite: string, filtre: FiltreBalance, page: number, taille: number): Promise<PageBalance> {
    await this.latence();
    const toutes = this.lignes().filter((l) => filtre.kind === null || l.kind === filtre.kind);
    const debut = page * taille;
    return {
      lignes: toutes.slice(debut, debut + taille),
      numero: page,
      taille,
      precedent: page > 0,
      suivant: debut + taille < toutes.length,
    };
  }

  async totauxBalance(_entite: string, filtre: FiltreBalance): Promise<readonly TotauxBalance[]> {
    await this.latence();
    const lignes = this.lignes().filter((l) => filtre.kind === null || l.kind === filtre.kind);
    const somme = (choix: (l: LigneBalance) => Montant) =>
      lignes.reduce((total, l) => total + Number(choix(l).amount), 0);

    const clotureDebit = somme((l) => l.closingDebit);
    const clotureCredit = somme((l) => l.closingCredit);
    return [{
      currency: DEVISE,
      accounts: lignes.length,
      // L'équilibre est constaté, pas décrété : on compare les deux colonnes.
      balanced: clotureDebit === clotureCredit,
      openingDebit: montant(somme((l) => l.openingDebit)),
      openingCredit: montant(somme((l) => l.openingCredit)),
      movementDebit: montant(somme((l) => l.movementDebit)),
      movementCredit: montant(somme((l) => l.movementCredit)),
      closingDebit: montant(clotureDebit),
      closingCredit: montant(clotureCredit),
    }];
  }

  // ----------------------------------------------------------------- détails

  private construire(legalEntityId: string, journee: string, mode: 'REAL' | 'DRY_RUN',
                     echecA: string | null, _repriseDe?: string): RunTfj {
    const steps: EtapeRun[] = [];
    let arrete = false;
    ETAPES.forEach((nom, index) => {
      const bloquante = BLOQUANTES.has(nom);
      if (arrete) {
        steps.push({ order: index + 1, name: nom, status: 'PENDING', read: 0, written: 0,
                     anomalies: [], blocking: bloquante, error: null });
        return;
      }
      if (nom === echecA) {
        arrete = true;
        steps.push({
          order: index + 1, name: nom, status: 'FAILED', read: 3, written: 0, anomalies: [],
          blocking: true,
          error: "La caisse OUA2-C02 a été mouvementée aujourd’hui et n’est pas arrêtée. "
            + "Une journée ne se clôt pas sur une caisse ouverte.",
        });
        return;
      }
      const lu = 40 + ((index * 137) % 900);
      steps.push({
        order: index + 1, name: nom, status: 'COMPLETED',
        read: lu, written: mode === 'DRY_RUN' ? 0 : Math.floor(lu / 3),
        anomalies: ANOMALIES[nom] ?? [], blocking: bloquante, error: null,
      });
    });

    return {
      id: crypto.randomUUID(),
      legalEntityId,
      businessDate: journee,
      mode,
      status: echecA ? 'FAILED' : 'COMPLETED',
      startedAt: new Date(Date.now() - 42_000).toISOString(),
      finishedAt: new Date().toISOString(),
      steps,
    };
  }

  private require(runId: string): RunTfj {
    const run = this.runs.get(runId);
    if (!run) throw new RefusMetier(404, 'PASSAGE_INTROUVABLE', 'Passage inconnu.');
    return run;
  }

  private mot(statut: RunTfj['status']): string {
    return { RUNNING: 'en cours', COMPLETED: 'terminé', FAILED: 'en échec', CANCELLED: 'annulé' }[statut];
  }

  /**
   * Une balance de démonstration qui **tombe juste**. Une balance d'essai qui
   * ne s'équilibre pas apprendrait l'écran à l'envers : l'alarme d'équilibre
   * doit se déclencher quand le registre ne se tient pas, pas en permanence.
   */
  private lignes(): readonly LigneBalance[] {
    const ligne = (
      code: string, kind: LigneBalance['kind'], nature: LigneBalance['nature'],
      normal: 'DEBIT' | 'CREDIT', ouvD: number, ouvC: number, mvtD: number, mvtC: number,
      libelle: string,
    ): LigneBalance => ({
      accountId: `cpt-${code}`, code, libelle, currency: DEVISE, kind, nature, normalBalance: normal,
      openingDebit: montant(ouvD), openingCredit: montant(ouvC),
      movementDebit: montant(mvtD), movementCredit: montant(mvtC),
      closingDebit: montant(ouvD + mvtD), closingCredit: montant(ouvC + mvtC),
    });

    return [
      ligne('10100', 'GL', 'BALANCE_SHEET', 'CREDIT', 0, 150000000, 0, 0, 'Capital et dotations'),
      ligne('25110', 'CUSTOMER', 'BALANCE_SHEET', 'CREDIT', 0, 207770000, 19687000, 24900000, 'Comptes de dépôts clientèle'),
      ligne('26110', 'CUSTOMER', 'BALANCE_SHEET', 'DEBIT', 184300000, 0, 11500000, 6200000, 'Crédits à la clientèle'),
      ligne('44210', 'GL', 'BALANCE_SHEET', 'CREDIT', 0, 1840000, 0, 187000, 'TAF collectée'),
      ligne('57110', 'INTERNAL', 'BALANCE_SHEET', 'DEBIT', 74200000, 0, 24900000, 18400000, 'Caisse des agences'),
      ligne('58110', 'INTERNAL', 'BALANCE_SHEET', 'DEBIT', 12000000, 0, 3400000, 3400000, 'Comptes de liaison'),
      ligne('37100', 'SUSPENSE', 'BALANCE_SHEET', 'DEBIT', 1250000, 0, 340000, 340000, 'Comptes de suspens'),
      ligne('47100', 'NOSTRO', 'BALANCE_SHEET', 'DEBIT', 92400000, 0, 6200000, 11800000, 'Correspondants bancaires'),
      ligne('35110', 'POSITION', 'BALANCE_SHEET', 'DEBIT', 4200000, 0, 620000, 620000, 'Position de change'),
      ligne('70611', 'GL', 'PROFIT_AND_LOSS', 'CREDIT', 0, 8420000, 0, 1100000, 'Commissions perçues'),
      ligne('70210', 'GL', 'PROFIT_AND_LOSS', 'CREDIT', 0, 21300000, 0, 2400000, "Produits d'intérêts"),
      ligne('60110', 'GL', 'PROFIT_AND_LOSS', 'DEBIT', 6780000, 0, 900000, 0, 'Charges générales'),
      ligne('63110', 'GL', 'PROFIT_AND_LOSS', 'DEBIT', 14200000, 0, 1800000, 0, 'Charges de personnel'),
      ligne('90110', 'GL', 'OFF_BALANCE_SHEET', 'DEBIT', 35000000, 0, 2000000, 0, 'Engagements donnés'),
      ligne('90910', 'GL', 'OFF_BALANCE_SHEET', 'CREDIT', 0, 35000000, 0, 2000000, 'Contrepartie hors bilan'),
    ];
  }

  // ------------------------------------------------------------ établissement

  async etablissement(legalEntityId: string): Promise<Etablissement> {
    await this.latence();
    return { ...this.identite, id: legalEntityId };
  }

  async majEtablissement(legalEntityId: string,
                         demande: DemandeEtablissement): Promise<EnAttenteSiege> {
    await this.latence();
    // La démonstration applique la correction tout de suite pour que l'écran
    // montre le résultat, et annonce quand même le second regard : le socle, lui,
    // attendra l'approbation. Ne pas l'annoncer ferait dire à un exploitant que
    // c'est déjà fait.
    this.identite = {
      ...this.identite,
      name: demande.name?.trim() || this.identite.name,
      bankCode: this.applique(demande.bankCode, this.identite.bankCode),
      legalName: this.applique(demande.legalName, this.identite.legalName),
      approvalNumber: this.applique(demande.approvalNumber, this.identite.approvalNumber),
      taxId: this.applique(demande.taxId, this.identite.taxId),
      registryNumber: this.applique(demande.registryNumber, this.identite.registryNumber),
      address: this.applique(demande.address, this.identite.address),
      phone: this.applique(demande.phone, this.identite.phone),
      email: this.applique(demande.email, this.identite.email),
    };
    return { operationId: `op-etab-${Date.now()}` };
  }

  private applique(soumis: string | undefined, courant: string | null): string | null {
    if (soumis === undefined) {
      return courant;
    }
    return soumis.trim() ? soumis.trim() : null;
  }

  // ------------------------------------------------------------ numérotation

  async regles(): Promise<readonly RegleNumerotation[]> {
    await this.latence();
    return this.plan;
  }

  async proposition(legalEntityId: string, domaine: DomaineNumerotation): Promise<DemandeRegle> {
    await this.latence();
    const propositions: Readonly<Record<DomaineNumerotation, DemandeRegle>> = {
      PARTY: this.demande('PARTY', 'Référence client', [
        segment('LITERAL', { literalValue: 'CLI-' }),
        segment('SEQUENCE', { length: 6, padChar: '0' })]),
      ACCOUNT: this.demande('ACCOUNT', 'Numéro de compte (RIB)', [
        segment('BANK_CODE', { length: 5, padChar: '0' }),
        segment('BRANCH_CODE', { length: 5, padChar: '0' }),
        segment('SEQUENCE', { length: 12, padChar: '0' }),
        segment('CHECK_DIGITS', { length: 2, padChar: '0', algorithm: 'RIB_97' })], 'BRANCH'),
      LOAN_APPLICATION: this.demande('LOAN_APPLICATION', 'Référence de demande de crédit', [
        segment('LITERAL', { literalValue: 'DC-' }), segment('DATE', { datePattern: 'yyyy' }),
        segment('LITERAL', { literalValue: '-' }),
        segment('SEQUENCE', { length: 4, padChar: '0' })], 'ENTITY', 'YEAR'),
      LOAN_CONTRACT: this.demande('LOAN_CONTRACT', 'Référence de contrat de crédit', [
        segment('LITERAL', { literalValue: 'CRD-' }), segment('DATE', { datePattern: 'yyyy' }),
        segment('LITERAL', { literalValue: '-' }),
        segment('SEQUENCE', { length: 5, padChar: '0' })], 'ENTITY', 'YEAR'),
      TERM_DEPOSIT: this.demande('TERM_DEPOSIT', 'Référence de dépôt à terme', [
        segment('LITERAL', { literalValue: 'DAT-' }), segment('DATE', { datePattern: 'yyyy' }),
        segment('LITERAL', { literalValue: '-' }),
        segment('SEQUENCE', { length: 5, padChar: '0' })], 'ENTITY', 'YEAR'),
      STANDING_ORDER: this.demande('STANDING_ORDER', "Référence d'ordre permanent", [
        segment('LITERAL', { literalValue: 'OP-' }),
        segment('SEQUENCE', { length: 7, padChar: '0' })]),
    };
    return propositions[domaine];
  }

  private demande(domain: DomaineNumerotation, label: string, segments: readonly Segment[],
                  sequenceScope: DemandeRegle['sequenceScope'] = 'ENTITY',
                  sequenceReset: DemandeRegle['sequenceReset'] = 'NEVER'): DemandeRegle {
    return { domain, label, segments, sequenceScope, sequenceReset, sequenceStart: 1 };
  }

  async redigerRegle(legalEntityId: string, demande: DemandeRegle): Promise<{ id: string }> {
    await this.latence();
    const id = `r-${this.plan.length + 1}-${Date.now()}`;
    this.plan = [...this.plan, {
      id, domain: demande.domain, label: demande.label, segments: demande.segments,
      scope: demande.sequenceScope, reset: demande.sequenceReset,
      sequenceStart: demande.sequenceStart, status: 'DRAFT',
    }];
    return { id };
  }

  async activerRegle(legalEntityId: string, ruleId: string): Promise<EnAttenteSiege> {
    await this.latence();
    const choisie = this.plan.find((regle) => regle.id === ruleId);
    if (!choisie) {
      throw new RefusMetier(404, 'REGLE_INCONNUE', 'Règle de numérotation inconnue.');
    }
    // Comme au socle : activer retire celle qui numérotait. La retirée reste
    // en liste — les numéros qu'elle a composés restent explicables.
    this.plan = this.plan.map((regle) => {
      if (regle.id === ruleId) {
        return { ...regle, status: 'ACTIVE' as const };
      }
      return regle.domain === choisie.domain && regle.status === 'ACTIVE'
        ? { ...regle, status: 'WITHDRAWN' as const }
        : regle;
    });
    return { operationId: `op-regle-${Date.now()}` };
  }

  // ------------------------------------------------------------ paramétrage produit

  private versionsEnCours: readonly VersionProduit[] = VERSIONS;
  private readonly parametres: Record<string, Record<string, string>> = { ...PARAMETRES };
  private readonly baremes: Record<string, Record<string, Tranche[]>> = { ...BAREMES };

  async familles(): Promise<readonly FamilleProduit[]> {
    await this.latence();
    return FAMILLES_DEMONSTRATION;
  }

  async versions(legalEntityId: string, code: string | null,
                 statut: string | null): Promise<readonly VersionProduit[]> {
    await this.latence();
    return this.versionsEnCours.filter(
      (v) => (code === null || v.code === code) && (statut === null || v.status === statut));
  }

  async version(legalEntityId: string, versionId: string): Promise<VersionComplete> {
    await this.latence();
    const header = this.versionsEnCours.find((v) => v.id === versionId);
    if (!header) {
      throw new RefusMetier(404, 'VERSION_INCONNUE', 'Version de produit inconnue.');
    }
    return {
      header,
      parameters: this.parametres[versionId] ?? {},
      tiers: this.baremes[versionId] ?? {},
    };
  }

  async redigerVersion(legalEntityId: string, entete: EnteteVersion,
                       parametres: Readonly<Record<string, string>>,
                       baremes: Readonly<Record<string, readonly Tranche[]>>,
                       ): Promise<{ readonly id: string }> {
    await this.latence();
    const id = `pv-${Date.now()}`;
    // Le brouillon a le droit d'être incomplet : la démonstration ne contrôle rien ici, comme le
    // socle. C'est l'activation qui confronte le paramétrage à sa famille.
    this.versionsEnCours = [{
      id, code: entete.code, productType: entete.productType, label: entete.label,
      currency: entete.currency, validFrom: entete.validFrom ?? '', validTo: entete.validTo,
      status: 'DRAFT', createdBy: 'VOUS', createdAt: new Date().toISOString(),
      approvedBy: null, approvedAt: null,
    }, ...this.versionsEnCours];
    this.parametres[id] = { ...parametres };
    this.baremes[id] = Object.fromEntries(
      Object.entries(baremes).map(([cle, tranches]) => [cle, [...tranches]]));
    return { id };
  }

  async activerVersion(legalEntityId: string, versionId: string): Promise<EnAttenteSiege> {
    await this.latence();
    const version = this.requise(versionId);
    this.refuserSiHorsEtat(actesSurVersion(version).includes('ACTIVER'), 'Activer', version.status);
    // Le socle confronte d'abord le paramétrage à sa famille : la démonstration aussi, sinon
    // l'écran n'apprendrait jamais à présenter un refus de complétude.
    const famille = FAMILLES_DEMONSTRATION.find((f) => f.code === version.productType);
    const manques = famille
      ? manquesDuParametrage(famille, this.parametres[versionId] ?? {},
                             Object.keys(this.baremes[versionId] ?? {}))
      : [];
    if (manques.length > 0) {
      throw new RefusMetier(400, 'PARAMETRAGE_INCOMPLET',
        `Produit ${version.code} : le paramétrage ne tient pas.`, manques.join(' | '));
    }
    return { operationId: `op-produit-${Date.now()}` };
  }

  async fermerVersion(legalEntityId: string, versionId: string,
                      validTo: string): Promise<EnAttenteSiege> {
    await this.latence();
    const version = this.requise(versionId);
    this.refuserSiHorsEtat(actesSurVersion(version).includes('FERMER'), 'Fermer', version.status);
    return { operationId: `op-produit-${Date.now()}` };
  }

  async retirerVersion(legalEntityId: string, versionId: string): Promise<VersionProduit> {
    await this.latence();
    const version = this.requise(versionId);
    this.refuserSiHorsEtat(actesSurVersion(version).includes('RETIRER'), 'Retirer', version.status);
    const retiree: VersionProduit = { ...version, status: 'WITHDRAWN' };
    this.versionsEnCours = this.versionsEnCours.map((v) => (v.id === versionId ? retiree : v));
    return retiree;
  }

  async comptesGeneraux(legalEntityId: string, texte: string): Promise<readonly CompteGeneral[]> {
    await this.latence();
    const cherche = texte.trim().toUpperCase();
    return cherche === '' ? COMPTES_GENERAUX
                          : COMPTES_GENERAUX.filter((c) => c.code.toUpperCase().includes(cherche));
  }

  private requise(versionId: string): VersionProduit {
    const version = this.versionsEnCours.find((v) => v.id === versionId);
    if (!version) {
      throw new RefusMetier(404, 'VERSION_INCONNUE', 'Version de produit inconnue.');
    }
    return version;
  }

  private refuserSiHorsEtat(permis: boolean, acte: string, etat: string): void {
    if (!permis) {
      throw new RefusMetier(409, 'ETAT_INCOMPATIBLE', 'Cet acte ne se fait plus.',
        `La version est ${etat} : ${acte.toLowerCase()} n'est plus possible dans cet état.`);
    }
  }

  // ------------------------------------------------------------ réseau et calendrier

  private agencesEnCours: readonly Agence[] = AGENCES;
  private feriesEnCours: readonly JourFerie[] = FERIES;
  private reglesValeurEnCours: readonly RegleDateValeur[] = REGLES_VALEUR;
  private heuresEnCours: readonly HeureLimite[] = HEURES;

  async agences(): Promise<readonly Agence[]> {
    await this.latence();
    return this.agencesEnCours;
  }

  async creerAgence(legalEntityId: string, demande: DemandeAgence): Promise<EnAttenteSiege> {
    await this.latence();
    // Comme au socle : la création part à la validation, et l'agence n'existe pas avant.
    this.derniereAgence = demande;
    return { operationId: `op-agence-${Date.now()}` };
  }

  async conditions(): Promise<ConditionsDeBanque> {
    await this.latence();
    return {
      calendarCode: 'BF', calendarLabel: 'Jours ouvrés — Burkina Faso',
      coversFrom: '2026-01-01', coversTo: '2026-12-31', weekend: [6, 7],
      holidays: this.feriesEnCours, rules: this.reglesValeurEnCours, cutoffs: this.heuresEnCours,
    };
  }

  async ajouterFerie(legalEntityId: string, demande: DemandeFerie): Promise<EnAttenteSiege> {
    await this.latence();
    this.dernierFerie = demande;
    return { operationId: `op-ferie-${Date.now()}` };
  }

  async ajouterRegle(legalEntityId: string,
                     demande: DemandeRegleDateValeur): Promise<EnAttenteSiege> {
    await this.latence();
    this.derniereRegleValeur = demande;
    return { operationId: `op-regle-valeur-${Date.now()}` };
  }

  async ajouterHeureLimite(legalEntityId: string,
                           demande: DemandeHeureLimite): Promise<EnAttenteSiege> {
    await this.latence();
    this.derniereHeure = demande;
    return { operationId: `op-heure-${Date.now()}` };
  }

  // ------------------------------------------------------------ schémas comptables

  /**
   * Le seul schéma paramétré de la démonstration : une commission de tenue de compte.
   *
   * Rédigé, jamais activé — c'est l'état dans lequel un schéma se relit le plus souvent, et celui
   * qui laisse voir les trois actes : l'essai, l'activation, le retrait.
   */
  private schemasEnCours: readonly SchemaComptable[] = [{
    id: 'sc-1', code: 'FRAIS-TENUE', label: 'Frais de tenue de compte', currency: 'XOF',
    validFrom: '2026-10-01', validTo: null, status: 'DRAFT',
    createdBy: null, createdAt: '2026-09-15T09:12:00Z', approvedBy: null, approvedAt: null,
    withdrawnBy: null, withdrawnAt: null,
  }];

  private readonly lignesDuSchema: readonly LigneSaisie[] = [
    { account: 'CONTRACT', direction: 'DEBIT', amount: 'net_booked + tax_booked',
      condition: '', label: 'Frais de tenue de compte' },
    { account: 'PARAM:fee_income', direction: 'CREDIT', amount: 'net_booked',
      condition: '', label: 'Commissions percues' },
    { account: 'PARAM:fee_tax', direction: 'CREDIT', amount: 'tax_booked',
      condition: 'tax_booked > 0', label: 'Taxe collectee' },
  ];

  private readonly derivationsDuSchema: readonly (readonly [string, string])[] = [
    ['net_booked', 'round(net, 0)'],
    ['tax_booked', 'round(tax, 0)'],
  ];

  async evenementsDuSocle(): Promise<readonly EvenementSocle[]> {
    await this.latence();
    return CATALOGUE_DEMONSTRATION;
  }

  async schemas(legalEntityId: string, code: string | null,
                statut: string | null): Promise<readonly SchemaComptable[]> {
    await this.latence();
    return this.schemasEnCours.filter((schema) =>
      (!code || schema.code === code) && (!statut || schema.status === statut));
  }

  async schema(legalEntityId: string, schemaId: string): Promise<SchemaComplet> {
    await this.latence();
    const entete = this.schemasEnCours.find((candidat) => candidat.id === schemaId);
    if (!entete) {
      throw new RefusMetier(404, 'SCHEMA_INCONNU', 'Schéma introuvable.',
                            'Aucun schéma ne porte cet identifiant.');
    }
    return {
      header: entete,
      events: [{
        eventType: 'FEE_CHARGE',
        derivations: this.derivationsDuSchema.map(([name, expression]) => ({ name, expression })),
        lines: this.lignesDuSchema.map((ligne) => ({
          account: ligne.account, direction: ligne.direction, amount: ligne.amount,
          condition: ligne.condition || null, label: ligne.label || null,
        })),
        variables: ['net', 'tax'],
      }],
    };
  }

  async essayer(legalEntityId: string, evenement: string, devise: string | null,
                lignes: readonly LigneSaisie[],
                derivations: readonly (readonly [string, string])[],
                valeurs: Readonly<Record<string, string>>): Promise<Essai> {
    await this.latence();
    return essaiDeDemonstration(evenement, this.echelle(devise), lignes, derivations, valeurs);
  }

  async essayerLeSocle(legalEntityId: string, evenement: string, devise: string | null,
                       valeurs: Readonly<Record<string, string>>): Promise<Essai> {
    await this.latence();
    const modele = CATALOGUE_DEMONSTRATION.find((candidat) => candidat.eventType === evenement);
    if (!modele) {
      throw new RefusMetier(422, 'EVENEMENT_INCONNU', 'Événement inconnu du socle.',
                            'Aucun schéma du socle ne traduit cet événement.');
    }
    return essaiDeDemonstration(evenement, this.echelle(devise),
      modele.lines.map((ligne) => ({
        account: ligne.account, direction: ligne.direction, amount: ligne.amount,
        condition: ligne.condition ?? '', label: ligne.label ?? '',
      })),
      modele.derivations.map((derivation) => [derivation.name, derivation.expression] as const),
      valeurs);
  }

  async redigerSchema(legalEntityId: string, entete: EnteteSchema,
                      lignes: readonly LigneSaisie[],
                      derivations: readonly (readonly [string, string])[]): Promise<{ readonly id: string }> {
    await this.latence();
    this.dernierSchema = { entete, lignes, derivations };
    const id = `sc-${this.schemasEnCours.length + 1}`;
    this.schemasEnCours = [...this.schemasEnCours, {
      id, code: entete.code, label: entete.label, currency: entete.currency,
      validFrom: entete.validFrom ?? '', validTo: entete.validTo, status: 'DRAFT',
      createdBy: null, createdAt: new Date().toISOString(), approvedBy: null, approvedAt: null,
      withdrawnBy: null, withdrawnAt: null,
    }];
    return { id };
  }

  async activerSchema(legalEntityId: string, schemaId: string): Promise<EnAttenteSiege> {
    await this.latence();
    this.derniereActivationSchema = schemaId;
    return { operationId: `op-schema-${Date.now()}` };
  }

  async fermerSchema(legalEntityId: string, schemaId: string,
                     demande: DemandeFermetureSchema): Promise<EnAttenteSiege> {
    await this.latence();
    this.derniereFermetureSchema = { schemaId, validTo: demande.validTo };
    return { operationId: `op-fermeture-schema-${Date.now()}` };
  }

  async retirerSchema(legalEntityId: string, schemaId: string): Promise<void> {
    await this.latence();
    this.dernierRetraitSchema = schemaId;
    this.schemasEnCours = this.schemasEnCours.map((schema) => schema.id === schemaId
      ? { ...schema, status: 'WITHDRAWN' as const, withdrawnAt: new Date().toISOString() }
      : schema);
  }

  /** L'échelle de la devise : c'est elle qui décide de ce qui est comptabilisable. */
  private echelle(devise: string | null): number {
    return devise === 'EUR' || devise === 'USD' ? 2 : 0;
  }

  dernierSchema: {
    entete: EnteteSchema;
    lignes: readonly LigneSaisie[];
    derivations: readonly (readonly [string, string])[];
  } | null = null;
  derniereActivationSchema: string | null = null;
  derniereFermetureSchema: { schemaId: string; validTo: string | null } | null = null;
  dernierRetraitSchema: string | null = null;

  /** Ce que la démonstration a reçu : les écrans de bout en bout s'en servent. */
  derniereAgence: DemandeAgence | null = null;
  dernierFerie: DemandeFerie | null = null;
  derniereRegleValeur: DemandeRegleDateValeur | null = null;
  derniereHeure: DemandeHeureLimite | null = null;

  private latence(): Promise<void> {
    return this.latenceMs === 0 ? Promise.resolve() : new Promise((r) => setTimeout(r, this.latenceMs));
  }
}
