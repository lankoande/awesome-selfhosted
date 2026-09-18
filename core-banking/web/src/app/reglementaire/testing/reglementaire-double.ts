import { EnAttente, Reglementaire } from '../reglementaire.port';
import {
  Declaration, DemandeDeclaration, DemandeRegleFiscale, DemandeTransmission, DossierEtat,
  Echeance, Etat, RegleFiscale, StatutEtat,
} from '../modele/reglementaire.modele';

export const ETAT_ID = '77777777-7777-4777-8777-000000000031';

const xof = (valeur: number) => ({ amount: String(valeur), currency: 'XOF' });

export const DECLARATION_DOUBLE: Declaration = {
  id: 'dec-1', code: 'SIT-COMPTA', label: 'Situation comptable mensuelle',
  recipient: 'CENTRAL_BANK', method: 'ACCOUNTING_SITUATION', frequency: 'MONTHLY',
  deadlineDays: 15, thresholdAmount: null, subjectCode: null,
  validFrom: '2026-01-01', validTo: null,
};

export const ETAT_DOUBLE: Etat = {
  id: ETAT_ID, declarationId: 'dec-1', declarationCode: 'SIT-COMPTA',
  method: 'ACCOUNTING_SITUATION', subjectCode: null,
  periodStart: '2026-08-01', periodEnd: '2026-08-31', dueOn: '2026-09-15',
  producedOn: '2026-09-03', thresholdUsed: null, lineCount: 2, totalAmount: xof(4820640000),
  status: 'PRODUCED', transmittedOn: null, transmissionReference: null,
  cancelledOn: null, cancellationReason: null, anomalies: [],
  lignes: [
    { subjectKind: 'GL_ACCOUNT', subjectReference: '101', label: 'Caisse', amount: xof(84200000),
      offBalance: null, classification: null, daysPastDue: null, occurrences: null, detail: null },
    { subjectKind: 'GL_ACCOUNT', subjectReference: '251', label: 'Comptes de dépôt',
      amount: xof(4736440000), offBalance: null, classification: null, daysPastDue: null,
      occurrences: null, detail: null },
  ],
};

export const ECHEANCE_DOUBLE: Echeance = {
  declarationCode: 'SIT-COMPTA', periodEnd: '2026-08-31', dueOn: '2026-09-15', produced: false,
};

export const REGLE_DOUBLE: RegleFiscale = {
  id: 'tx-1', code: 'IRC', label: 'Impôt sur le revenu des créances', basis: 'INTEREST_PAID',
  ratePercent: '15', collectionAccountId: 'gl-4451', validFrom: '2026-01-01', validTo: null,
};

/**
 * Un réglementaire sous contrôle. Tout le port implémenté, chaque test ne
 * redéfinissant que ce qui l'intéresse.
 */
export class ReglementaireDouble implements Reglementaire {
  readonly productions: { declarationId: string; periodEnd: string }[] = [];
  readonly transmissions: DemandeTransmission[] = [];
  readonly annulations: { filingId: string; motif: string }[] = [];
  readonly catalogues: DemandeDeclaration[] = [];
  readonly taxes: DemandeRegleFiscale[] = [];
  readonly cles: string[] = [];

  echeancesRendues: readonly Echeance[] = [ECHEANCE_DOUBLE];
  declarationsRendues: readonly Declaration[] = [DECLARATION_DOUBLE];
  etatsRendus: readonly Etat[] = [ETAT_DOUBLE];
  dossierRendu: DossierEtat = { etat: ETAT_DOUBLE, ecarts: [] };
  reglesRendues: readonly RegleFiscale[] = [REGLE_DOUBLE];
  statutDemande: StatutEtat | null = null;

  async echeances(): Promise<readonly Echeance[]> {
    return this.echeancesRendues;
  }

  async declarations(): Promise<readonly Declaration[]> {
    return this.declarationsRendues;
  }

  async declarer(_e: string, demande: DemandeDeclaration, cle: string): Promise<EnAttente> {
    this.catalogues.push(demande);
    this.cles.push(cle);
    return { operationId: 'op-cat' };
  }

  async produire(_e: string, declarationId: string, periodEnd: string,
                 cle: string): Promise<Etat> {
    this.productions.push({ declarationId, periodEnd });
    this.cles.push(cle);
    return ETAT_DOUBLE;
  }

  async etats(_e: string, statut: StatutEtat | null): Promise<readonly Etat[]> {
    this.statutDemande = statut;
    return this.etatsRendus;
  }

  async etat(): Promise<DossierEtat> {
    return this.dossierRendu;
  }

  async transmettre(_e: string, _filingId: string, demande: DemandeTransmission,
                    cle: string): Promise<EnAttente> {
    this.transmissions.push(demande);
    this.cles.push(cle);
    return { operationId: 'op-tr' };
  }

  async annuler(_e: string, filingId: string, motif: string, cle: string): Promise<Etat> {
    this.annulations.push({ filingId, motif });
    this.cles.push(cle);
    const annule: Etat = {
      ...this.dossierRendu.etat, status: 'CANCELLED', cancellationReason: motif,
      cancelledOn: '2026-09-18',
    };
    this.dossierRendu = { etat: annule, ecarts: [] };
    return annule;
  }

  async reglesFiscales(): Promise<readonly RegleFiscale[]> {
    return this.reglesRendues;
  }

  async declarerRegleFiscale(_e: string, demande: DemandeRegleFiscale,
                             cle: string): Promise<EnAttente> {
    this.taxes.push(demande);
    this.cles.push(cle);
    return { operationId: 'op-tax' };
  }
}
