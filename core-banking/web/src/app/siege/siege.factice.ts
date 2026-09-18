import { Injectable } from '@angular/core';
import { Montant, RefusMetier } from '../guichet/modele/guichet.modele';
import {
  DemandeEtablissement, DemandeRegle, DomaineNumerotation, Etablissement, RegleNumerotation,
  Segment,
} from './modele/etablissement.modele';
import {
  EtapeRun, FiltreBalance, LigneBalance, PageBalance, RunTfj, TotauxBalance,
} from './modele/siege.modele';
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

  private latence(): Promise<void> {
    return this.latenceMs === 0 ? Promise.resolve() : new Promise((r) => setTimeout(r, this.latenceMs));
  }
}
