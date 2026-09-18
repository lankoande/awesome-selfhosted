import {
  Cheque, Chequier, DemandeChequier, DemandeMandat, DemandeOpposition, DemandePaiementCheque,
  IncidentCheque, Mandat, StatutCheque,
} from '../modele/compte.modele';
import {
  DemandeOrdre, DemandeRemise, OrdrePaiement, Prelevement, Remise, SensPrelevement, StatutOrdre,
  StatutPrelevement, StatutRemise,
} from '../modele/paiements.modele';
import { Decision, EnAttentePaiements, Page, Paiements } from '../paiements.port';

const xof = (valeur: number) => ({ amount: String(valeur), currency: 'XOF' });

export const ORDRE_ENREGISTRE: OrdrePaiement = {
  id: 'op-0142', accountId: 'cpt-1', amount: xof(1250000), fee: xof(2500), tax: xof(450),
  beneficiaryName: 'SANOU Awa', beneficiaryBank: 'BOA Burkina Faso',
  beneficiaryAccount: 'BF7601001000000123456789', reference: 'Loyer septembre', channel: 'BRANCH',
  status: 'ORDERED', orderedOn: '2026-09-18', sentOn: null, settledOn: null, returnedOn: null,
  returnReason: null, cancelledOn: null, cancelReason: null,
};

export const ORDRE_ENVOYE: OrdrePaiement = {
  ...ORDRE_ENREGISTRE, id: 'op-0141', status: 'SENT', sentOn: '2026-09-17',
};

export const REMISE_A_LENCAISSEMENT: Remise = {
  id: 'rc-0231', accountId: 'cpt-1', amount: xof(4200000), draweeBank: 'Ecobank Burkina',
  chequeNumber: '0042118', drawerName: 'SOCIETE MINIERE DU SAHEL', channel: 'BRANCH',
  status: 'DEPOSITED', depositedOn: '2026-09-18', valueDate: '2026-09-22', settledOn: null,
  returnedOn: null, returnReason: null,
};

export const PRELEVEMENT_RECU_EXECUTE: Prelevement = {
  id: 'pr-0509', direction: 'RECEIVED', accountId: 'cpt-1', mandateId: 'md-0027',
  amount: xof(28500), fee: xof(500), dueDate: '2026-09-17', counterpartyName: 'ONEA',
  counterpartyBank: null, counterpartyAccount: null, mandateReference: 'MDT-ONEA-0027',
  reference: 'Facture 08/2026', channel: 'CLEARING', status: 'COLLECTED',
  presentedOn: '2026-09-13', executedOn: '2026-09-17', valueDate: '2026-09-17',
  rejectionReason: null, settledOn: null, closedOn: null, closeReason: null,
};

export const PRELEVEMENT_EMIS_EXECUTE: Prelevement = {
  ...PRELEVEMENT_RECU_EXECUTE, id: 'pr-0498', direction: 'ISSUED', mandateId: null,
  counterpartyName: 'ZONGO Adama', counterpartyBank: 'Ecobank Burkina',
  counterpartyAccount: 'BF7603003000000778899001',
};

export const CHEQUIER_DELIVRE: Chequier = {
  id: 'cb-0044', firstNumber: 4211801, lastNumber: 4211825, deliveredOn: '2026-07-03',
  status: 'ACTIVE', fee: xof(3000),
};

export const CHEQUE_EN_CIRCULATION: Cheque = {
  number: 4211804, bookId: 'cb-0044', status: 'UNUSED', amount: null, beneficiary: null,
  paidOn: null, stoppedOn: null, stopReason: null,
};

export const CHEQUE_PAYE: Cheque = {
  ...CHEQUE_EN_CIRCULATION, number: 4211801, status: 'PAID', amount: xof(420000),
  beneficiary: 'SAWADOGO Boukary', paidOn: '2026-08-29',
};

export const INCIDENT_SANS_PROVISION: IncidentCheque = {
  id: 'ic-0007', number: 4211802, amount: xof(1750000), occurredOn: '2026-09-05',
  reason: 'Provision insuffisante', presentedBy: 'Coris Bank International',
};

export const MANDAT_ACTIF: Mandat = {
  id: 'md-0031', reference: 'MDT-SONABEL-0031', creditorId: 'BF-SONABEL-001',
  creditorName: 'SONABEL', creditorAccountId: null, creditorBank: 'Coris Bank International',
  creditorAccount: 'BF7602002000000998877665', signedOn: '2025-11-04', validFrom: '2025-12-01',
  validTo: null, maxAmount: xof(150000), status: 'ACTIVE', revokedOn: null,
  revocationReason: null,
};

export const MANDAT_REVOQUE: Mandat = {
  ...MANDAT_ACTIF, id: 'md-0012', reference: 'MDT-ORANGE-0012', creditorName: 'ORANGE BF',
  status: 'REVOKED', revokedOn: '2026-04-30', revocationReason: 'Résiliation',
};

function page<T>(lignes: readonly T[]): Page<T> {
  return { lignes, numero: 0, taille: 25, precedent: false, suivant: false };
}

/**
 * Le double du port des moyens de paiement.
 *
 * Il rend de quoi monter les trois écrans et garde ce que le poste a envoyé :
 * c'est ce que les spécifications viennent vérifier — l'acte, son motif, son
 * nostro. Une classe plutôt qu'un objet : une spécification qui ne couvre qu'une
 * méthode n'a pas à réécrire les onze autres.
 */
export class PaiementsDouble implements Paiements {
  async ordres(legalEntityId: string, statut: StatutOrdre | null): Promise<Page<OrdrePaiement>> {
    const toutes = [ORDRE_ENREGISTRE, ORDRE_ENVOYE];
    return page(statut === null ? toutes : toutes.filter((o) => o.status === statut));
  }

  async ordre(legalEntityId: string, orderId: string): Promise<OrdrePaiement> {
    return orderId === ORDRE_ENVOYE.id ? ORDRE_ENVOYE : ORDRE_ENREGISTRE;
  }

  async ordonner(legalEntityId: string, demande: DemandeOrdre): Promise<OrdrePaiement> {
    this.dernierOrdre = demande;
    return { ...ORDRE_ENREGISTRE, id: 'op-neuf' };
  }

  async deciderOrdre(legalEntityId: string, orderId: string,
                     decision: Decision): Promise<OrdrePaiement> {
    this.derniereDecision = decision;
    return { ...ORDRE_ENREGISTRE, id: orderId, status: 'SENT', sentOn: '2026-09-18' };
  }

  async remises(legalEntityId: string, statut: StatutRemise | null): Promise<Page<Remise>> {
    return page(statut === null || statut === 'DEPOSITED' ? [REMISE_A_LENCAISSEMENT] : []);
  }

  async remise(): Promise<Remise> {
    return REMISE_A_LENCAISSEMENT;
  }

  async remettre(legalEntityId: string, demande: DemandeRemise): Promise<Remise> {
    this.derniereRemise = demande;
    return { ...REMISE_A_LENCAISSEMENT, id: 'rc-neuve' };
  }

  async deciderRemise(legalEntityId: string, depositId: string,
                      decision: Decision): Promise<Remise> {
    this.derniereDecision = decision;
    return { ...REMISE_A_LENCAISSEMENT, id: depositId, status: 'SETTLED',
             settledOn: '2026-09-18' };
  }

  async prelevements(legalEntityId: string, sens: SensPrelevement | null,
                     statut: StatutPrelevement | null): Promise<Page<Prelevement>> {
    const tous = [PRELEVEMENT_RECU_EXECUTE, PRELEVEMENT_EMIS_EXECUTE];
    return page(tous.filter((p) => (sens === null || p.direction === sens)
                                   && (statut === null || p.status === statut)));
  }

  async prelevement(legalEntityId: string, directDebitId: string): Promise<Prelevement> {
    return directDebitId === PRELEVEMENT_EMIS_EXECUTE.id ? PRELEVEMENT_EMIS_EXECUTE
                                                         : PRELEVEMENT_RECU_EXECUTE;
  }

  async deciderPrelevement(legalEntityId: string, directDebitId: string,
                           decision: Decision): Promise<Prelevement> {
    this.derniereDecision = decision;
    return { ...PRELEVEMENT_RECU_EXECUTE, id: directDebitId, status: 'SETTLED',
             settledOn: '2026-09-18' };
  }

  // -------------------------------------------------------------- par compte

  async chequiers(): Promise<readonly Chequier[]> {
    return [CHEQUIER_DELIVRE];
  }

  async delivrerChequier(legalEntityId: string, accountId: string,
                         demande: DemandeChequier): Promise<EnAttentePaiements> {
    this.dernierChequier = demande;
    return { operationId: 'op-attente-chequier' };
  }

  async cheques(legalEntityId: string, accountId: string,
                statut: StatutCheque | null): Promise<readonly Cheque[]> {
    this.dernierFiltreCheques = statut;
    const tous = [CHEQUE_EN_CIRCULATION, CHEQUE_PAYE];
    return statut === null ? tous : tous.filter((c) => c.status === statut);
  }

  async payerCheque(legalEntityId: string, accountId: string,
                    demande: DemandePaiementCheque): Promise<Cheque> {
    this.dernierPaiement = demande;
    return { ...CHEQUE_EN_CIRCULATION, status: 'PAID', paidOn: '2026-09-18',
             amount: { amount: demande.amount, currency: demande.currency },
             beneficiary: demande.beneficiary };
  }

  async opposer(legalEntityId: string, accountId: string,
                demande: DemandeOpposition): Promise<Cheque> {
    this.derniereOpposition = demande;
    return { ...CHEQUE_EN_CIRCULATION, status: 'STOPPED', stoppedOn: '2026-09-18',
             stopReason: demande.reason };
  }

  async incidents(): Promise<readonly IncidentCheque[]> {
    return [INCIDENT_SANS_PROVISION];
  }

  async mandats(): Promise<readonly Mandat[]> {
    return [MANDAT_ACTIF, MANDAT_REVOQUE];
  }

  async enregistrerMandat(legalEntityId: string, accountId: string,
                          demande: DemandeMandat): Promise<EnAttentePaiements> {
    this.dernierMandat = demande;
    return { operationId: 'op-attente-mandat' };
  }

  async revoquerMandat(legalEntityId: string, mandateId: string, motif: string): Promise<Mandat> {
    this.derniereRevocation = { mandateId, motif };
    return { ...MANDAT_ACTIF, id: mandateId, status: 'REVOKED', revokedOn: '2026-09-18',
             revocationReason: motif };
  }

  /** Ce que la spécification vient vérifier. */
  dernierOrdre: DemandeOrdre | null = null;
  dernierChequier: DemandeChequier | null = null;
  dernierPaiement: DemandePaiementCheque | null = null;
  derniereOpposition: DemandeOpposition | null = null;
  dernierMandat: DemandeMandat | null = null;
  derniereRevocation: { mandateId: string; motif: string } | null = null;
  dernierFiltreCheques: StatutCheque | null = null;
  derniereRemise: DemandeRemise | null = null;
  derniereDecision: Decision | null = null;
}
