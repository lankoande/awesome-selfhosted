import { OperationEnAttente, StatutSocle, decidable, etatAffiche, etatDe } from './validation.modele';

const STATUTS: readonly StatutSocle[] = ['PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'EXECUTED', 'FAILED'];

function operation(statut: StatutSocle, expiresAt: string): OperationEnAttente {
  return {
    id: 'PND-1', operation: 'ACCOUNT_LIMIT_MANAGE', handler: 'Account.limit', status: statut,
    makerId: 'u-1', makerUsername: 'm.o', madeAt: '2026-09-17T08:00:00Z', expiresAt,
    decidedBy: null, decidedAt: null, decisionReason: null, payload: {}, result: null, error: null,
  };
}

describe('états de la double validation', () => {
  it('donne un état distinct à chacun des six statuts du socle', () => {
    const etats = STATUTS.map(etatDe);
    expect(new Set(etats).size).toBe(STATUTS.length);
  });

  it("ne fond pas « approuvée non confirmée » dans « comptabilisé »", () => {
    // Une approbation sans exécution confirmée est une anomalie d'exploitation.
    // L'écraser en succès la rendrait invisible le jour où elle compte.
    expect(etatDe('APPROVED')).not.toBe(etatDe('EXECUTED'));
    expect(etatDe('APPROVED')).toBe('approuve');
  });

  it('distingue un rejet humain d’un échec d’exécution', () => {
    expect(etatDe('REJECTED')).toBe('rejete');
    expect(etatDe('FAILED')).toBe('echoue');
  });

  it("n’est décidable qu’en attente et avant l’échéance", () => {
    const maintenant = new Date('2026-09-17T12:00:00Z');
    expect(decidable(operation('PENDING', '2026-09-17T18:00:00Z'), maintenant)).toBe(true);
    expect(decidable(operation('PENDING', '2026-09-17T09:00:00Z'), maintenant)).toBe(false);
    expect(decidable(operation('REJECTED', '2026-09-17T18:00:00Z'), maintenant)).toBe(false);
  });
});

describe('état affiché', () => {
  const maintenant = new Date('2026-09-17T12:00:00Z');

  it("montre « expirée » dès que l’échéance est passée, même si le socle dit encore PENDING", () => {
    // Le socle n'expire que paresseusement, au moment où quelqu'un tente de
    // décider. Afficher « en attente » enverrait un valideur sur une opération
    // que plus personne ne peut décider.
    expect(etatAffiche(operation('PENDING', '2026-09-17T09:00:00Z'), maintenant)).toBe('expire');
    expect(etatAffiche(operation('PENDING', '2026-09-17T18:00:00Z'), maintenant)).toBe('en-attente');
  });

  it('ne touche pas aux états déjà décidés', () => {
    expect(etatAffiche(operation('EXECUTED', '2026-09-17T09:00:00Z'), maintenant)).toBe('comptabilise');
    expect(etatAffiche(operation('FAILED', '2026-09-17T09:00:00Z'), maintenant)).toBe('echoue');
  });
});
