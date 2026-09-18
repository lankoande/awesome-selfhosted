import { Injectable } from '@angular/core';
import { RefusMetier } from '../guichet/modele/guichet.modele';
import { Credit, EnAttente } from './credit.port';
import {
  Analyse, Condition, Contrat, Decision, Demande, DemandeAnalyse, DemandeCondition,
  DemandeContrat, DemandeDeCredit, DemandeDecision, DemandeReglement, DossierCredit, Echeance,
  Evenement, Montant, Reglement, StatutDemande,
} from './modele/credit.modele';

const xof = (valeur: number): Montant => ({ amount: String(valeur), currency: 'XOF' });

/**
 * Les demandes de démonstration couvrent le cycle entier, un état par dossier :
 * déposée, en instruction avec dépassement de grille, accordée sous conditions
 * suspensives, contractée, refusée. C'est ce qu'il faut pour juger les écrans
 * sans socle.
 */
const DEMANDES: Demande[] = [
  {
    id: 'd-sankara', reference: 'DC-2026-0142', customerId: 'p-sankara',
    customerReference: 'CLI-000417', productCode: 'CRED-CONSO', purpose: 'Équipement du foyer',
    requestedAmount: xof(2500000), requestedTermMonths: 24, requestedOn: '2026-09-02',
    status: 'UNDER_REVIEW', contractId: null, closedOn: null, closingReason: null,
  },
  {
    id: 'd-ouedraogo', reference: 'DC-2026-0148', customerId: 'p-ouedraogo',
    customerReference: 'CLI-001182', productCode: 'CRED-HABITAT', purpose: 'Construction',
    requestedAmount: xof(18000000), requestedTermMonths: 120, requestedOn: '2026-09-05',
    status: 'APPROVED', contractId: null, closedOn: null, closingReason: null,
  },
  {
    id: 'd-kabore', reference: 'DC-2026-0151', customerId: 'p-kabore-ets',
    customerReference: 'CLI-002044', productCode: 'CRED-EQUIP', purpose: 'Véhicule utilitaire',
    requestedAmount: xof(9500000), requestedTermMonths: 48, requestedOn: '2026-09-08',
    status: 'CONTRACTED', contractId: 'c-kabore', closedOn: '2026-09-12',
    closingReason: null,
  },
  {
    id: 'd-traore', reference: 'DC-2026-0155', customerId: 'p-traore',
    customerReference: 'CLI-003390', productCode: 'CRED-CONSO', purpose: 'Trésorerie',
    requestedAmount: xof(4000000), requestedTermMonths: 36, requestedOn: '2026-09-10',
    status: 'REJECTED', contractId: null, closedOn: '2026-09-14',
    closingReason: 'Capacité de remboursement insuffisante',
  },
  {
    id: 'd-nikiema', reference: 'DC-2026-0160', customerId: 'p-nikiema',
    customerReference: 'CLI-005108', productCode: 'CRED-CONSO', purpose: 'Scolarité',
    requestedAmount: xof(800000), requestedTermMonths: 12, requestedOn: '2026-09-16',
    status: 'SUBMITTED', contractId: null, closedOn: null, closingReason: null,
  },
];

/** Une analyse qui passe la grille, et une qui la dépasse : les deux se voient. */
const ANALYSES: Readonly<Record<string, Analyse[]>> = {
  'd-sankara': [{
    id: 'a-1', assessedOn: '2026-09-04',
    monthlyIncome: xof(450000), monthlyCharges: xof(120000),
    existingCommitments: xof(65000), downPayment: xof(250000),
    requestedInstalment: xof(118500), debtServiceRatioPercent: '40.8',
    externalScore: 612, scoreSource: 'BIC-UEMOA',
    breaches: ['Taux d’endettement 40,8 % au-delà du plafond de 35 % de la grille CRED-CONSO.'],
  }],
  'd-ouedraogo': [{
    id: 'a-2', assessedOn: '2026-09-07',
    monthlyIncome: xof(1850000), monthlyCharges: xof(310000),
    existingCommitments: xof(0), downPayment: xof(3600000),
    requestedInstalment: xof(228400), debtServiceRatioPercent: '29.1',
    externalScore: 744, scoreSource: 'BIC-UEMOA', breaches: [],
  }],
  'd-traore': [{
    id: 'a-3', assessedOn: '2026-09-13',
    monthlyIncome: xof(210000), monthlyCharges: xof(95000),
    existingCommitments: xof(48000), downPayment: null,
    requestedInstalment: xof(131200), debtServiceRatioPercent: '85.3',
    externalScore: 388, scoreSource: 'BIC-UEMOA',
    breaches: [
      'Taux d’endettement 85,3 % au-delà du plafond de 35 % de la grille CRED-CONSO.',
      'Score externe 388 sous le minimum de 500 de la grille CRED-CONSO.',
    ],
  }],
};

/**
 * Le dossier accordé porte une suspensive non levée : c'est le cas où l'écran
 * doit empêcher la contractualisation en nommant ce qui manque.
 */
const CONDITIONS: Readonly<Record<string, Condition[]>> = {
  'd-ouedraogo': [
    { id: 'co-1', kind: 'PRECEDENT', description: 'Hypothèque de premier rang inscrite au livre foncier',
      dueOn: '2026-10-15', clearedOn: null, evidence: null },
    { id: 'co-2', kind: 'PRECEDENT', description: 'Assurance décès-invalidité souscrite',
      dueOn: '2026-10-01', clearedOn: '2026-09-20', evidence: 'Police AXA n° 88412' },
    { id: 'co-3', kind: 'SUBSEQUENT', description: 'Justificatifs d’avancement des travaux chaque trimestre',
      dueOn: null, clearedOn: null, evidence: null },
  ],
  'd-kabore': [
    { id: 'co-4', kind: 'PRECEDENT', description: 'Gage sur le véhicule inscrit',
      dueOn: '2026-09-10', clearedOn: '2026-09-11', evidence: 'Inscription RCCM n° 2026-B-1207' },
  ],
};

const DECISIONS: Readonly<Record<string, Decision>> = {
  'd-ouedraogo': {
    outcome: 'APPROVED', decidedOn: '2026-09-09', grantedAmount: xof(16000000),
    grantedRatePercent: '8.5', grantedTermMonths: 120, validUntil: '2026-12-09',
    reason: 'Capacité de remboursement confirmée, apport de 20 %.',
    waiverReason: null,
  },
  'd-kabore': {
    outcome: 'APPROVED', decidedOn: '2026-09-11', grantedAmount: xof(9500000),
    grantedRatePercent: '9.25', grantedTermMonths: 48, validUntil: '2026-12-11',
    reason: 'Entreprise établie, garantie réelle constituée.', waiverReason: null,
  },
  'd-traore': {
    outcome: 'REJECTED', decidedOn: '2026-09-14', grantedAmount: null,
    grantedRatePercent: null, grantedTermMonths: null, validUntil: null,
    reason: 'Deux dépassements de grille non couverts par une dérogation.',
    waiverReason: null,
  },
};

const EVENEMENTS: Readonly<Record<string, Evenement[]>> = {
  'd-ouedraogo': [
    { kind: 'SUBMITTED', occurredOn: '2026-09-05', detail: 'Demande déposée au guichet OUA2' },
    { kind: 'ASSESSED', occurredOn: '2026-09-07', detail: 'Analyse versée : ratio 29,1 %' },
    { kind: 'CONDITION_ADDED', occurredOn: '2026-09-08', detail: 'Hypothèque de premier rang' },
    { kind: 'DECIDED', occurredOn: '2026-09-09', detail: 'Accordée : 16 000 000 XOF sur 120 mois' },
    { kind: 'CONDITION_CLEARED', occurredOn: '2026-09-20', detail: 'Assurance décès-invalidité' },
  ],
};

function echeancier(capital: number, taux: number, nombre: number, debut: string): Echeance[] {
  // Annuité constante, arrondie à l'unité : la démonstration montre une forme
  // juste, pas un calcul faisant foi — c'est le socle qui amortit.
  const mensuel = taux / 100 / 12;
  const annuite = Math.round(capital * mensuel / (1 - Math.pow(1 + mensuel, -nombre)));
  const lignes: Echeance[] = [];
  let reste = capital;
  const [a0, m0, j0] = debut.split('-').map(Number);
  for (let n = 1; n <= nombre; n++) {
    const interets = Math.round(reste * mensuel);
    const principal = n === nombre ? reste : annuite - interets;
    reste -= principal;
    const d = new Date(Date.UTC(a0!, m0! - 1 + n, j0!));
    lignes.push({
      number: n, dueDate: d.toISOString().slice(0, 10),
      principal: xof(principal), interest: xof(interets),
      insurance: xof(Math.round(capital * 0.0004)), tax: xof(Math.round(interets * 0.18)),
      fee: xof(0), total: xof(principal + interets + Math.round(capital * 0.0004)
                              + Math.round(interets * 0.18)),
    });
  }
  return lignes;
}

const CONTRATS: Contrat[] = [
  {
    id: 'c-kabore', reference: 'PR-2026-0044', productCode: 'CRED-EQUIP',
    principal: xof(9500000), currency: 'XOF', status: 'ACTIVE', disbursedOn: '2026-09-12',
    daysPastDue: 0, asOf: '2026-09-17',
    echeancier: echeancier(9500000, 9.25, 48, '2026-09-12'),
    creances: [
      { id: 'cr-1', category: 'INTEREST', dueDate: '2026-10-12', instalmentNumber: 1,
        outstanding: xof(73229) },
      { id: 'cr-2', category: 'PRINCIPAL', dueDate: '2026-10-12', instalmentNumber: 1,
        outstanding: xof(164338) },
      { id: 'cr-3', category: 'FUTURE_PRINCIPAL', dueDate: null, instalmentNumber: null,
        outstanding: xof(9335662) },
    ],
    tauxAnnuel: '9.25', nombreEcheances: 48, methode: 'CONSTANT_ANNUITY',
  },
  {
    id: 'c-compaore', reference: 'PR-2025-0318', productCode: 'CRED-CONSO',
    principal: xof(3200000), currency: 'XOF', status: 'ACTIVE', disbursedOn: '2025-04-20',
    daysPastDue: 104, asOf: '2026-09-17',
    echeancier: echeancier(3200000, 11, 36, '2025-04-20'),
    creances: [
      { id: 'cr-4', category: 'PENALTIES', dueDate: '2026-06-20', instalmentNumber: 15,
        outstanding: xof(18400) },
      { id: 'cr-5', category: 'LATE_INTEREST', dueDate: '2026-06-20', instalmentNumber: 15,
        outstanding: xof(9750) },
      { id: 'cr-6', category: 'INTEREST', dueDate: '2026-06-20', instalmentNumber: 15,
        outstanding: xof(14820) },
      { id: 'cr-7', category: 'PRINCIPAL', dueDate: '2026-06-20', instalmentNumber: 15,
        outstanding: xof(90310) },
      { id: 'cr-8', category: 'FUTURE_PRINCIPAL', dueDate: null, instalmentNumber: null,
        outstanding: xof(1842000) },
    ],
    tauxAnnuel: '11', nombreEcheances: 36, methode: 'CONSTANT_ANNUITY',
  },
  {
    id: 'c-nikiema', reference: 'PR-2026-0051', productCode: 'CRED-CONSO',
    principal: xof(800000), currency: 'XOF', status: 'DRAFT', disbursedOn: null,
    daysPastDue: 0, asOf: '2026-09-17',
    echeancier: [], creances: [],
    tauxAnnuel: '10.5', nombreEcheances: 12, methode: 'CONSTANT_ANNUITY',
  },
];

/**
 * Un socle de crédit joué localement. Il rejoue **le comportement du socle**,
 * pas une version plus aimable : la décision et le déblocage rendent une
 * opération en attente, jamais un fait accompli.
 */
@Injectable()
export class CreditFactice implements Credit {
  latenceMs = 300;
  private rang = 40;

  async demandes(_entite: string, statut: StatutDemande | null, page: number, taille: number) {
    await this.attendre();
    const filtrees = statut ? DEMANDES.filter((d) => d.status === statut) : DEMANDES;
    const debut = page * taille;
    return {
      demandes: filtrees.slice(debut, debut + taille),
      page,
      precedent: page > 0,
      suivant: debut + taille < filtrees.length,
    };
  }

  async dossier(_entite: string, applicationId: string): Promise<DossierCredit> {
    await this.attendre();
    const demande = DEMANDES.find((d) => d.id === applicationId);
    if (!demande) {
      throw new RefusMetier(404, 'DEMANDE_INCONNUE', "Cette demande n'existe pas.");
    }
    return {
      demande,
      analyses: ANALYSES[applicationId] ?? [],
      conditions: CONDITIONS[applicationId] ?? [],
      decision: DECISIONS[applicationId] ?? null,
      evenements: EVENEMENTS[applicationId] ?? [],
    };
  }

  async deposer(demande: DemandeDeCredit): Promise<{ id: string }> {
    await this.attendre(500);
    if (Number(demande.requestedAmount) <= 0) {
      throw new RefusMetier(422, 'MONTANT_INVALIDE', 'Le montant demandé doit être positif.');
    }
    return { id: `d-${this.rang++}` };
  }

  async analyser(_e: string, _a: string, analyse: DemandeAnalyse): Promise<void> {
    await this.attendre(400);
    if (Number(analyse.monthlyIncome) <= 0) {
      throw new RefusMetier(422, 'REVENU_INVALIDE', 'Le revenu mensuel doit être positif.');
    }
  }

  async poserCondition(_e: string, _a: string, condition: DemandeCondition): Promise<void> {
    await this.attendre(300);
    if (!condition.description.trim()) {
      throw new RefusMetier(422, 'CONDITION_VIDE', 'Une condition se décrit.');
    }
  }

  async leverCondition(): Promise<EnAttente> {
    await this.attendre(400);
    return { operationId: `PND-0003${this.rang++}` };
  }

  async decider(_e: string, _a: string, decision: DemandeDecision): Promise<EnAttente> {
    await this.attendre(500);
    if (!decision.reason.trim()) {
      throw new RefusMetier(422, 'MOTIF_ABSENT', 'Une décision se motive.');
    }
    return { operationId: `PND-0004${this.rang++}` };
  }

  async contractualiser(_e: string, applicationId: string, contrat: DemandeContrat) {
    await this.attendre(500);
    // Le socle refuse ce que l'écran annonçait : la règle est la sienne.
    const suspensives = (CONDITIONS[applicationId] ?? [])
      .filter((c) => c.kind === 'PRECEDENT' && !c.clearedOn);
    if (suspensives.length > 0) {
      throw new RefusMetier(
        409, 'CONDITIONS_NON_LEVEES',
        'Toutes les conditions suspensives ne sont pas levées.',
        suspensives.map((c) => c.description).join(' · '));
    }
    return {
      contractId: `c-${this.rang++}`,
      reference: contrat.contractReference?.trim() || `PR-2026-${this.rang}`,
    };
  }

  async retirer(): Promise<void> {
    await this.attendre(300);
  }

  async contrats(_entite: string, page: number, taille: number) {
    await this.attendre();
    const debut = page * taille;
    return {
      contrats: CONTRATS.slice(debut, debut + taille),
      page,
      precedent: page > 0,
      suivant: debut + taille < CONTRATS.length,
    };
  }

  async contrat(_entite: string, contractId: string): Promise<Contrat> {
    await this.attendre();
    const contrat = CONTRATS.find((c) => c.id === contractId);
    if (!contrat) {
      throw new RefusMetier(404, 'CONTRAT_INCONNU', "Ce contrat n'existe pas.");
    }
    return contrat;
  }

  async debloquer(_e: string, contractId: string): Promise<EnAttente> {
    await this.attendre(500);
    const contrat = CONTRATS.find((c) => c.id === contractId);
    if (contrat && contrat.status !== 'DRAFT') {
      throw new RefusMetier(409, 'DEJA_DEBLOQUE', 'Ce contrat est déjà débloqué.');
    }
    return { operationId: `PND-0005${this.rang++}` };
  }

  async regler(_e: string, contractId: string, reglement: DemandeReglement): Promise<Reglement> {
    await this.attendre(500);
    const contrat = CONTRATS.find((c) => c.id === contractId);
    if (!contrat) {
      throw new RefusMetier(404, 'CONTRAT_INCONNU', "Ce contrat n'existe pas.");
    }
    // L'imputation suit l'ordre des créances ouvertes : c'est ce que fait le
    // socle, et ce que le guichetier doit pouvoir expliquer au client.
    let reste = Number(reglement.amount);
    const imputations = [];
    for (const creance of contrat.creances) {
      if (reste <= 0) break;
      const du = Number(creance.outstanding?.amount ?? '0');
      const impute = Math.min(reste, du);
      reste -= impute;
      imputations.push({
        category: creance.category,
        instalmentNumber: creance.instalmentNumber,
        amount: xof(impute),
        remaining: xof(du - impute),
      });
    }
    const paye = Number(reglement.amount);
    return {
      paid: xof(paye), allocated: xof(paye - reste), unallocated: xof(reste), imputations,
    };
  }

  private attendre(ms = this.latenceMs): Promise<void> {
    return new Promise((resoudre) => setTimeout(resoudre, ms));
  }
}
