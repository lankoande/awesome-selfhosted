import { Conformite, EnAttente } from '../conformite.port';
import {
  Alerte, Declaration, DemandeDeclaration, DemandeScenario, DemandeTransmission, Scenario,
  StatutAlerte,
} from '../modele/conformite.modele';

export const ALERTE_ID = '66666666-6666-4666-8666-000000000011';

const xof = (valeur: number) => ({ amount: String(valeur), currency: 'XOF' });

export const ALERTE_DOUBLE: Alerte = {
  id: ALERTE_ID, partyId: 'p-1', scenarioCode: 'ESP-5M', origin: 'MONITORING',
  raisedOn: '2026-09-15', detail: 'Espèces cumulées 7 250 000 XOF sur 30 jours.',
  amount: xof(7250000), status: 'OPEN', assignedTo: null, closedOn: null, closureReason: null,
  closedBy: null, reportId: null,
  pieces: [
    { entryId: 'e-1', bookingDate: '2026-09-02', accountId: 'ac-1', direction: 'CREDIT',
      amount: xof(2400000) },
    { entryId: 'e-2', bookingDate: '2026-09-08', accountId: 'ac-1', direction: 'CREDIT',
      amount: xof(2450000) },
  ],
};

export const DECLARATION_DOUBLE: Declaration = {
  id: 'dec-1', partyId: 'p-9', reference: 'DS-2026-0007', draftedOn: '2026-08-12',
  narrative: 'Dépôts d’espèces répétés sans rapport avec l’activité déclarée.',
  transmittedOn: null, transmissionReference: null, alertIds: ['al-0'],
};

export const SCENARIO_DOUBLE: Scenario = {
  id: 'sc-1', code: 'ESP-5M', label: 'Espèces au-delà de 5 000 000 sur 30 jours',
  method: 'CASH_THRESHOLD', thresholdAmount: '5000000', windowDays: 30, minimumCount: null,
  ratio: null, riskRating: null, validFrom: '2026-01-01', validTo: null,
};

/**
 * Une conformité sous contrôle. Tout le port implémenté, chaque test ne
 * redéfinissant que ce qui l'intéresse.
 */
export class ConformiteDouble implements Conformite {
  readonly prisesEnCharge: string[] = [];
  readonly classements: { alertId: string; motif: string }[] = [];
  readonly redactions: DemandeDeclaration[] = [];
  readonly transmissions: DemandeTransmission[] = [];
  readonly scenariosDeclares: DemandeScenario[] = [];
  readonly cles: string[] = [];

  alertesRendues: readonly Alerte[] = [ALERTE_DOUBLE];
  alerteRendue: Alerte = ALERTE_DOUBLE;
  declarationsRendues: readonly Declaration[] = [DECLARATION_DOUBLE];
  scenariosRendus: readonly Scenario[] = [SCENARIO_DOUBLE];
  statutDemande: StatutAlerte | null = null;

  async alertes(_e: string, statut: StatutAlerte | null): Promise<readonly Alerte[]> {
    this.statutDemande = statut;
    return this.alertesRendues;
  }

  async alerte(): Promise<Alerte> {
    return this.alerteRendue;
  }

  async prendreEnCharge(_e: string, alertId: string, cle: string): Promise<Alerte> {
    this.prisesEnCharge.push(alertId);
    this.cles.push(cle);
    this.alerteRendue = { ...this.alerteRendue, status: 'UNDER_REVIEW', assignedTo: 'moi' };
    return this.alerteRendue;
  }

  async classer(_e: string, alertId: string, motif: string, cle: string): Promise<Alerte> {
    this.classements.push({ alertId, motif });
    this.cles.push(cle);
    this.alerteRendue = {
      ...this.alerteRendue, status: 'CLOSED', closureReason: motif, closedOn: '2026-09-18',
    };
    return this.alerteRendue;
  }

  async declarations(): Promise<readonly Declaration[]> {
    return this.declarationsRendues;
  }

  async rediger(_e: string, demande: DemandeDeclaration, cle: string): Promise<EnAttente> {
    this.redactions.push(demande);
    this.cles.push(cle);
    return { operationId: 'op-1' };
  }

  async transmettre(_e: string, _reportId: string, demande: DemandeTransmission,
                    cle: string): Promise<Declaration> {
    this.transmissions.push(demande);
    this.cles.push(cle);
    const declaration: Declaration = {
      ...this.declarationsRendues[0],
      transmittedOn: demande.transmittedOn ?? '2026-09-18',
      transmissionReference: demande.reference,
    };
    this.declarationsRendues = [declaration];
    return declaration;
  }

  async scenarios(): Promise<readonly Scenario[]> {
    return this.scenariosRendus;
  }

  async declarerScenario(_e: string, demande: DemandeScenario, cle: string): Promise<EnAttente> {
    this.scenariosDeclares.push(demande);
    this.cles.push(cle);
    return { operationId: 'op-2' };
  }
}
