import {
  DemandeOrdre, DemandeRemise, OrdrePaiement, Prelevement, Remise, SensPrelevement, StatutOrdre,
  StatutPrelevement, StatutRemise,
} from '../modele/paiements.modele';
import { Decision, Page, Paiements } from '../paiements.port';

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

  /** Ce que la spécification vient vérifier. */
  dernierOrdre: DemandeOrdre | null = null;
  derniereRemise: DemandeRemise | null = null;
  derniereDecision: Decision | null = null;
}
