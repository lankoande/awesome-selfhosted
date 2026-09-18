import { finDeCompte, formaterCompte, formaterMontant, lireMontant , formaterTaux } from './montant';

const FINE = ' ';

describe('formaterMontant', () => {
  it('groupe par trois avec une espace fine insécable', () => {
    expect(formaterMontant(2500000)).toBe(`2${FINE}500${FINE}000`);
  });

  it('respecte l’échelle de la devise : XOF n’a pas de décimale', () => {
    expect(formaterMontant(1170, 0)).toBe(`1${FINE}170`);
    expect(formaterMontant(1234.56, 2)).toBe(`1${FINE}234,56`);
  });

  it('écrit le signe négatif, et le positif seulement quand on le demande', () => {
    expect(formaterMontant(-120000)).toBe(`-${FINE}120${FINE}000`);
    expect(formaterMontant(1170, 0, { signe: 'toujours' })).toBe(`+${FINE}1${FINE}170`);
  });

  it('ne fabrique pas de zéro pour une valeur absente', () => {
    expect(formaterMontant(Number.NaN)).toBe('—');
  });
});

describe('lireMontant', () => {
  it('accepte ce qu’un opérateur tape vraiment', () => {
    expect(lireMontant(`2${FINE}500${FINE}000`)).toBe(2500000);
    expect(lireMontant('2 500 000')).toBe(2500000);
    expect(lireMontant('1234,56')).toBe(1234.56);
  });

  it('rend null plutôt que zéro sur une saisie vide ou illisible', () => {
    expect(lireMontant('')).toBeNull();
    expect(lireMontant('abc')).toBeNull();
    expect(lireMontant('-')).toBeNull();
  });
});

describe('affichage d’un compte', () => {
  it('groupe le numéro sans jamais le tronquer', () => {
    expect(formaterCompte('BF120010251000000004 17'.replace(/\s/g, ''), 4)).toContain('BF12');
  });

  it('abrège en gardant les quatre derniers caractères', () => {
    expect(finDeCompte('BF12001025100000000417')).toBe('····0417');
  });
});

describe('formaterTaux', () => {
  it("écrit la virgule décimale : le socle rend un point", () => {
    // 9.25 % sous les yeux d'un agent qui lit des virgules fait hésiter une
    // seconde, et cette seconde se paie en relecture.
    expect(formaterTaux('9.25')).toBe('9,25');
    expect(formaterTaux(29.1)).toBe('29,1');
  });

  it("n'ajoute pas de décimale à un taux entier", () => {
    expect(formaterTaux('9')).toBe('9');
    expect(formaterTaux(11)).toBe('11');
  });

  it('rend un tiret sur une absence ou une valeur illisible', () => {
    expect(formaterTaux(null)).toBe('—');
    expect(formaterTaux('')).toBe('—');
    expect(formaterTaux('abc')).toBe('—');
  });
});
