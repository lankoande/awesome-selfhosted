import { COUPURES_XOF, coupuresDe, nombreDeCoupures, totalComptage } from './coupures';

describe('coupures du franc CFA', () => {
  it('distingue le billet de 500 de la pièce de 500', () => {
    const cinqCents = COUPURES_XOF.filter((c) => c.valeur === 500);
    expect(cinqCents.map((c) => c.genre)).toEqual(['billet', 'piece']);
    expect(new Set(COUPURES_XOF.map((c) => c.id)).size).toBe(COUPURES_XOF.length);
  });

  it('additionne en entiers : un comptage ne perd jamais un franc', () => {
    const comptage = { b10000: 200, b5000: 80, b2000: 45, b1000: 10, p25: 3 };
    expect(totalComptage(comptage)).toBe(2500075);
    expect(nombreDeCoupures(comptage)).toBe(338);
  });

  it('ignore une coupure inconnue plutôt que de produire NaN', () => {
    expect(totalComptage({ inexistante: 12 })).toBe(0);
  });

  it('ne propose pas de billetage pour une devise qui n’en a pas ici', () => {
    expect(coupuresDe('XOF').length).toBeGreaterThan(0);
    expect(coupuresDe('EUR')).toEqual([]);
  });
});
