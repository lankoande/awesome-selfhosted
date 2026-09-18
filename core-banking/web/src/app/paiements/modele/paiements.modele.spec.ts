import { describe, expect, it } from 'vitest';
import {
  actesSurOrdre, actesSurPrelevement, actesSurRemise, DemandeOrdre, DemandeRemise, obstaclesAUneRemise,
  obstaclesAUnOrdre, OrdrePaiement, Prelevement, Remise, StatutOrdre, StatutPrelevement,
} from './paiements.modele';

const xof = (valeur: number) => ({ amount: String(valeur), currency: 'XOF' });

function ordre(status: StatutOrdre): OrdrePaiement {
  return {
    id: 'o-1', accountId: 'c-1', amount: xof(1250000), fee: xof(2500), tax: xof(450),
    beneficiaryName: 'SANOU Awa', beneficiaryBank: 'BOA-BF', beneficiaryAccount: '00123456789',
    reference: 'Loyer septembre', channel: 'BRANCH', status,
    orderedOn: '2026-09-15', sentOn: null, settledOn: null, returnedOn: null, returnReason: null,
    cancelledOn: null, cancelReason: null,
  };
}

function remise(status: Remise['status']): Remise {
  return {
    id: 'r-1', accountId: 'c-1', amount: xof(840000), draweeBank: 'ECOBANK-BF',
    chequeNumber: '0042118', drawerName: 'ETS KABORE', channel: 'BRANCH', status,
    depositedOn: '2026-09-16', valueDate: '2026-09-18', settledOn: null, returnedOn: null,
    returnReason: null,
  };
}

function prelevement(status: StatutPrelevement, direction: Prelevement['direction']): Prelevement {
  return {
    id: 'p-1', direction, accountId: 'c-1', mandateId: 'm-1', amount: xof(45000), fee: xof(500),
    dueDate: '2026-09-20', counterpartyName: 'SONABEL', counterpartyBank: null,
    counterpartyAccount: null, mandateReference: 'MDT-0042', reference: 'Facture 09/2026',
    channel: 'CLEARING', status, presentedOn: '2026-09-14', executedOn: null, valueDate: null,
    rejectionReason: null, settledOn: null, closedOn: null, closeReason: null,
  };
}

describe("les actes sur un ordre de paiement", () => {
  it('un ordre enregistré part ou se retire', () => {
    expect(actesSurOrdre(ordre('ORDERED'))).toEqual(['ENVOYER', 'ANNULER']);
  });

  it("un ordre envoyé ne s'annule plus : il se règle ou il revient", () => {
    // C'est la garantie qui compte : une fois au système de paiement, la banque
    // ne décide plus seule. Proposer « Annuler » ferait espérer l'impossible.
    expect(actesSurOrdre(ordre('SENT'))).toEqual(['REGLER', 'RETOURNER']);
    expect(actesSurOrdre(ordre('SENT'))).not.toContain('ANNULER');
  });

  it('un ordre réglé peut encore revenir impayé', () => {
    expect(actesSurOrdre(ordre('SETTLED'))).toEqual(['RETOURNER']);
  });

  it("un ordre dénoué n'accepte plus rien", () => {
    expect(actesSurOrdre(ordre('RETURNED'))).toEqual([]);
    expect(actesSurOrdre(ordre('CANCELLED'))).toEqual([]);
  });
});

describe('les actes sur une remise', () => {
  it("une remise à l'encaissement se règle ou revient impayée", () => {
    expect(actesSurRemise(remise('DEPOSITED'))).toEqual(['REGLER', 'RETOURNER']);
  });

  it('une remise dénouée n’accepte plus rien', () => {
    expect(actesSurRemise(remise('SETTLED'))).toEqual([]);
    expect(actesSurRemise(remise('RETURNED'))).toEqual([]);
  });
});

describe('les actes sur un prélèvement', () => {
  it("le traitement de fin de journée exécute : le poste ne le fait pas", () => {
    // PENDING n'offre que l'annulation. Un bouton « Exécuter » laisserait croire
    // qu'un prélèvement se force à la main, hors de l'arrêté.
    expect(actesSurPrelevement(prelevement('PENDING', 'RECEIVED'))).toEqual(['ANNULER']);
    expect(actesSurPrelevement(prelevement('PENDING', 'ISSUED'))).toEqual(['ANNULER']);
  });

  it('un prélèvement reçu exécuté se règle ou se rappelle', () => {
    expect(actesSurPrelevement(prelevement('COLLECTED', 'RECEIVED')))
      .toEqual(['REGLER', 'ANNULER']);
  });

  it("un prélèvement émis exécuté ne s'annule pas : il revient impayé", () => {
    expect(actesSurPrelevement(prelevement('COLLECTED', 'ISSUED')))
      .toEqual(['REGLER', 'RETOURNER']);
  });

  it('seul un prélèvement reçu se rembourse — c’est le débiteur qui conteste', () => {
    expect(actesSurPrelevement(prelevement('SETTLED', 'RECEIVED'))).toEqual(['REMBOURSER']);
    expect(actesSurPrelevement(prelevement('SETTLED', 'ISSUED'))).toEqual(['RETOURNER']);
  });

  it('un prélèvement rejeté est clos : le créancier represente', () => {
    expect(actesSurPrelevement(prelevement('REJECTED', 'RECEIVED'))).toEqual([]);
  });
});

describe("les obstacles à l'enregistrement", () => {
  const ordreValide: DemandeOrdre = {
    accountId: 'c-1', amount: '1250000', currency: 'XOF', beneficiaryName: 'SANOU Awa',
    beneficiaryBank: 'BOA-BF', beneficiaryAccount: '00123456789', reference: null, channel: null,
  };

  it('laisse passer un ordre complet', () => {
    expect(obstaclesAUnOrdre(ordreValide)).toEqual([]);
  });

  it('refuse un bénéficiaire incomplet, et dit ce que coûte l’erreur', () => {
    expect(obstaclesAUnOrdre({ ...ordreValide, beneficiaryAccount: '  ' }).join(' '))
      .toContain('les frais restent acquis');
  });

  it('refuse un montant nul ou négatif', () => {
    expect(obstaclesAUnOrdre({ ...ordreValide, amount: '0' })).toHaveLength(1);
    expect(obstaclesAUnOrdre({ ...ordreValide, amount: '-5' })).toHaveLength(1);
    expect(obstaclesAUnOrdre({ ...ordreValide, amount: '' })).toHaveLength(1);
  });

  const remiseValide: DemandeRemise = {
    accountId: 'c-1', amount: '840000', currency: 'XOF', draweeBank: 'ECOBANK-BF',
    chequeNumber: '0042118', drawerName: null, channel: null,
  };

  it('laisse passer une remise complète', () => {
    expect(obstaclesAUneRemise(remiseValide)).toEqual([]);
  });

  it('refuse une remise sans banque tirée ni numéro de chèque', () => {
    const obstacles = obstaclesAUneRemise({ ...remiseValide, draweeBank: '', chequeNumber: '' });
    expect(obstacles).toHaveLength(2);
    expect(obstacles.join(' ')).toContain('deux remises du même montant');
  });
});
