import { describe, expect, it } from 'vitest';
import {
  actesSurCheque, Cheque, CHEQUES_MAXIMUM, CHEQUES_MINIMUM, DemandeMandat,
  DemandePaiementCheque, Mandat, natureDuCreancier, obstaclesAuChequier, obstaclesAuMandat,
  obstaclesAuPaiement, peutRevoquer, StatutCheque,
} from './compte.modele';

function cheque(status: StatutCheque): Cheque {
  return {
    number: 4211801, bookId: 'cb-1', status, amount: null, beneficiary: null, paidOn: null,
    stoppedOn: null, stopReason: null,
  };
}

function paiement(surcharges: Partial<DemandePaiementCheque> = {}): DemandePaiementCheque {
  return {
    number: 4211801, amount: '250000', currency: 'XOF', mode: 'CASH', nostroAccountId: null,
    beneficiary: 'SANOU Awa', ...surcharges,
  };
}

function mandat(surcharges: Partial<DemandeMandat> = {}): DemandeMandat {
  return {
    reference: 'MDT-SONABEL-0031', creditorId: 'BF-SONABEL-001', creditorName: 'SONABEL',
    creditorAccountId: null, creditorBank: 'Coris Bank International',
    creditorAccount: 'BF7602002000000998877665', signedOn: '2026-09-01',
    validFrom: '2026-09-01', validTo: null, maxAmount: null, currency: 'XOF', ...surcharges,
  };
}

describe('la demande de chéquier', () => {
  it('accepte les bornes du socle', () => {
    expect(obstaclesAuChequier({ count: CHEQUES_MINIMUM })).toEqual([]);
    expect(obstaclesAuChequier({ count: 25 })).toEqual([]);
    expect(obstaclesAuChequier({ count: CHEQUES_MAXIMUM })).toEqual([]);
  });

  it('refuse ce qui déborde, dans un sens comme dans l’autre', () => {
    expect(obstaclesAuChequier({ count: 0 })).toHaveLength(1);
    expect(obstaclesAuChequier({ count: CHEQUES_MAXIMUM + 1 })).toHaveLength(1);
  });

  it("refuse un nombre de chèques qui n'est pas entier", () => {
    // Un demi-chèque n'existe pas ; le socle refuserait, et l'écran le sait
    // avant de faire attendre le client.
    expect(obstaclesAuChequier({ count: 12.5 })).toHaveLength(1);
  });
});

describe('les actes sur un chèque', () => {
  it('un chèque en circulation se paie ou se frappe d’opposition', () => {
    expect(actesSurCheque(cheque('UNUSED'))).toEqual(['PAYER', 'OPPOSER']);
  });

  it('un chèque payé ne se reprend pas', () => {
    // C'est la garantie qui compte : le paiement est comptabilisé, et une
    // opposition après coup ne défait rien. Proposer le bouton mentirait.
    expect(actesSurCheque(cheque('PAID'))).toEqual([]);
  });

  it("un chèque sous opposition ou rejeté n'accepte plus rien", () => {
    expect(actesSurCheque(cheque('STOPPED'))).toEqual([]);
    expect(actesSurCheque(cheque('REJECTED'))).toEqual([]);
  });
});

describe('le paiement d’un chèque', () => {
  it('passe quand le montant, le mode et le porteur tiennent', () => {
    expect(obstaclesAuPaiement(paiement())).toEqual([]);
  });

  it('refuse un montant vide, nul ou négatif', () => {
    expect(obstaclesAuPaiement(paiement({ amount: '' }))).toHaveLength(1);
    expect(obstaclesAuPaiement(paiement({ amount: '0' }))).toHaveLength(1);
    expect(obstaclesAuPaiement(paiement({ amount: '-1000' }))).toHaveLength(1);
  });

  it('exige un nostro en compensation, pas au guichet', () => {
    // Au guichet, la contrepartie est la caisse du poste : elle vient du jeton,
    // et l'écran n'a rien à demander. En compensation, elle se désigne.
    expect(obstaclesAuPaiement(paiement({ mode: 'CLEARING' }))).toHaveLength(1);
    expect(obstaclesAuPaiement(paiement({ mode: 'CLEARING', nostroAccountId: 'nos-1' })))
      .toEqual([]);
    expect(obstaclesAuPaiement(paiement({ mode: 'CASH', nostroAccountId: null }))).toEqual([]);
  });

  it('exige le porteur au guichet, pas en compensation', () => {
    // Au comptoir il est devant vous, et c'est lui que le chèque paie. En
    // compensation le nom vient de la banque présentatrice : l'exiger du poste
    // bloquerait un paiement que le socle accepte.
    expect(obstaclesAuPaiement(paiement({ beneficiary: null }))).toHaveLength(1);
    expect(obstaclesAuPaiement(paiement({ beneficiary: '   ' }))).toHaveLength(1);
    expect(obstaclesAuPaiement(paiement({ mode: 'CLEARING', nostroAccountId: 'nos-1',
                                          beneficiary: null }))).toEqual([]);
  });
});

describe('le mandat de prélèvement', () => {
  it('passe avec sa référence et son créancier', () => {
    expect(obstaclesAuMandat(mandat())).toEqual([]);
  });

  it('exige la référence, l’identifiant et le nom du créancier', () => {
    expect(obstaclesAuMandat(mandat({ reference: '  ' }))).toHaveLength(1);
    expect(obstaclesAuMandat(mandat({ creditorId: '' }))).toHaveLength(1);
    expect(obstaclesAuMandat(mandat({ creditorName: '' }))).toHaveLength(1);
  });

  it('accepte un créancier client de la banque, désigné par son compte', () => {
    expect(obstaclesAuMandat(mandat({ creditorAccountId: 'cpt-sonabel', creditorBank: null,
                                      creditorAccount: null }))).toEqual([]);
  });

  it('refuse un créancier désigné deux fois, ou pas du tout', () => {
    // Le socle pose le ou exclusif : un compte de la banque, ou une banque et
    // un compte d'ailleurs. Les deux ensemble, et on ne sait plus qui prélève.
    expect(obstaclesAuMandat(mandat({ creditorAccountId: 'cpt-sonabel' }))).toHaveLength(1);
    expect(obstaclesAuMandat(mandat({ creditorBank: null, creditorAccount: null })))
      .toHaveLength(1);
    expect(obstaclesAuMandat(mandat({ creditorBank: 'Coris Bank International',
                                      creditorAccount: null }))).toHaveLength(1);
  });

  it('exige la signature et la prise d’effet', () => {
    // Elles ne sont pas décoratives : la date de signature fait foi si le
    // prélèvement est contesté, la prise d'effet dit à partir de quand.
    expect(obstaclesAuMandat(mandat({ signedOn: null }))).toHaveLength(1);
    expect(obstaclesAuMandat(mandat({ validFrom: null }))).toHaveLength(1);
  });

  it('accepte un mandat sans plafond', () => {
    // Le socle ne l'exige pas. L'écran ne l'invente pas non plus : il le dit.
    expect(obstaclesAuMandat(mandat({ maxAmount: null }))).toEqual([]);
    expect(obstaclesAuMandat(mandat({ maxAmount: '' }))).toEqual([]);
  });

  it('refuse un plafond qui ne plafonne rien', () => {
    expect(obstaclesAuMandat(mandat({ maxAmount: '0' }))).toHaveLength(1);
    expect(obstaclesAuMandat(mandat({ maxAmount: 'beaucoup' }))).toHaveLength(1);
  });

  it('refuse une validité qui finit avant de commencer', () => {
    expect(obstaclesAuMandat(mandat({ validFrom: '2026-09-01', validTo: '2026-08-31' })))
      .toHaveLength(1);
    expect(obstaclesAuMandat(mandat({ validFrom: '2026-09-01', validTo: '2026-09-01' })))
      .toEqual([]);
  });
});

describe('la révocation d’un mandat', () => {
  const actif: Mandat = {
    id: 'md-1', reference: 'MDT-0031', creditorId: 'BF-SONABEL-001', creditorName: 'SONABEL',
    creditorAccountId: null, creditorBank: 'Coris Bank International',
    creditorAccount: 'BF7602002000000998877665', signedOn: '2026-09-01',
    validFrom: '2026-09-01', validTo: null, maxAmount: null, status: 'ACTIVE', revokedOn: null,
    revocationReason: null,
  };

  it('un mandat actif se révoque', () => {
    expect(peutRevoquer(actif)).toBe(true);
  });

  it('un mandat révoqué ne se révoque pas deux fois', () => {
    expect(peutRevoquer({ ...actif, status: 'REVOKED', revokedOn: '2026-09-10' })).toBe(false);
  });

  it('le créancier se lit interne ou externe selon son compte chez vous', () => {
    expect(natureDuCreancier(actif)).toBe('EXTERNE');
    expect(natureDuCreancier({ ...actif, creditorAccountId: 'cpt-sonabel' })).toBe('INTERNE');
  });
});
