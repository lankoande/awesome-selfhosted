import { Credit, EnAttente } from '../credit.port';
import {
  Contrat, Demande, DemandeAnalyse, DemandeCondition, DemandeContrat, DemandeDeCredit,
  DemandeDecision, DemandeReglement, DossierCredit, Reglement, StatutDemande,
} from '../modele/credit.modele';

export const DEMANDE_ID = '44444444-4444-4444-8444-000000000142';
export const CONTRAT_ID = '55555555-5555-4555-8555-000000000044';

const xof = (valeur: number) => ({ amount: String(valeur), currency: 'XOF' });

export const DEMANDE_DOUBLE: Demande = {
  id: DEMANDE_ID, reference: 'DC-2026-0142', customerId: 'p-1',
  customerReference: 'CLI-000417', productCode: 'CRED-CONSO', purpose: 'Équipement',
  requestedAmount: xof(2500000), requestedTermMonths: 24, requestedOn: '2026-09-02',
  status: 'UNDER_REVIEW', contractId: null, closedOn: null, closingReason: null,
};

/** Un dossier en instruction, sans analyse ni condition : le point de départ. */
export const DOSSIER_DOUBLE: DossierCredit = {
  demande: DEMANDE_DOUBLE,
  analyses: [],
  conditions: [],
  decision: null,
  evenements: [],
};

export const CONTRAT_DOUBLE: Contrat = {
  id: CONTRAT_ID, reference: 'PR-2026-0044', productCode: 'CRED-EQUIP',
  principal: xof(9500000), currency: 'XOF', status: 'ACTIVE', disbursedOn: '2026-09-12',
  daysPastDue: 0, asOf: '2026-09-17',
  echeancier: [{
    number: 1, dueDate: '2026-10-12', principal: xof(164338), interest: xof(73229),
    insurance: xof(3800), tax: xof(13181), fee: xof(0), total: xof(254548),
  }],
  creances: [
    { id: 'cr-1', category: 'INTEREST', dueDate: '2026-10-12', instalmentNumber: 1,
      outstanding: xof(73229) },
    { id: 'cr-2', category: 'PRINCIPAL', dueDate: '2026-10-12', instalmentNumber: 1,
      outstanding: xof(164338) },
    { id: 'cr-3', category: 'FUTURE_PRINCIPAL', dueDate: null, instalmentNumber: null,
      outstanding: xof(9335662) },
  ],
  tauxAnnuel: '9.25', nombreEcheances: 48, methode: 'CONSTANT_ANNUITY',
};

/**
 * Un socle de crédit sous contrôle. Tout le port implémenté, chaque test ne
 * redéfinissant que ce qui l'intéresse.
 */
export class CreditDouble implements Credit {
  readonly depots: DemandeDeCredit[] = [];
  readonly analyses: DemandeAnalyse[] = [];
  readonly conditionsPosees: DemandeCondition[] = [];
  readonly decisions: DemandeDecision[] = [];
  readonly contrats_: DemandeContrat[] = [];
  readonly reglements: DemandeReglement[] = [];
  readonly clesDecision: string[] = [];

  dossierRendu: DossierCredit = DOSSIER_DOUBLE;
  contratRendu: Contrat = CONTRAT_DOUBLE;
  listeDemandes: readonly Demande[] = [DEMANDE_DOUBLE];
  listeContrats: readonly Contrat[] = [CONTRAT_DOUBLE];
  suivant = false;

  issueDecision: () => Promise<EnAttente> = async () => ({ operationId: 'PND-000401' });
  issueDeblocage: () => Promise<EnAttente> = async () => ({ operationId: 'PND-000501' });
  issueDepot: () => Promise<{ id: string }> = async () => ({ id: DEMANDE_ID });
  issueContrat: () => Promise<{ contractId: string; reference: string }> =
    async () => ({ contractId: CONTRAT_ID, reference: 'PR-2026-0044' });
  issueReglement: () => Promise<Reglement> = async () => ({
    paid: xof(250000), allocated: xof(237567), unallocated: xof(12433),
    imputations: [
      { category: 'INTEREST', instalmentNumber: 1, amount: xof(73229), remaining: xof(0) },
      { category: 'PRINCIPAL', instalmentNumber: 1, amount: xof(164338), remaining: xof(0) },
    ],
  });

  async demandes(_e: string, statut: StatutDemande | null, page: number) {
    const filtrees = statut
      ? this.listeDemandes.filter((d) => d.status === statut) : this.listeDemandes;
    return { demandes: filtrees, page, precedent: page > 0, suivant: this.suivant };
  }

  async dossier(): Promise<DossierCredit> {
    return this.dossierRendu;
  }

  async deposer(demande: DemandeDeCredit): Promise<{ id: string }> {
    this.depots.push(demande);
    return this.issueDepot();
  }

  async analyser(_e: string, _a: string, analyse: DemandeAnalyse): Promise<void> {
    this.analyses.push(analyse);
  }

  async poserCondition(_e: string, _a: string, condition: DemandeCondition): Promise<void> {
    this.conditionsPosees.push(condition);
  }

  async leverCondition(): Promise<EnAttente> {
    return { operationId: 'PND-000301' };
  }

  async decider(_e: string, _a: string, decision: DemandeDecision,
                cle: string): Promise<EnAttente> {
    this.decisions.push(decision);
    this.clesDecision.push(cle);
    return this.issueDecision();
  }

  async contractualiser(_e: string, _a: string, contrat: DemandeContrat) {
    this.contrats_.push(contrat);
    return this.issueContrat();
  }

  async retirer(): Promise<void> {
    // Rien à retenir : les tests qui s'y intéressent remplacent la méthode.
  }

  async contrats(_e: string, page: number) {
    return { contrats: this.listeContrats, page, precedent: page > 0, suivant: this.suivant };
  }

  async contrat(): Promise<Contrat> {
    return this.contratRendu;
  }

  async debloquer(): Promise<EnAttente> {
    return this.issueDeblocage();
  }

  async regler(_e: string, _c: string, reglement: DemandeReglement): Promise<Reglement> {
    this.reglements.push(reglement);
    return this.issueReglement();
  }
}
