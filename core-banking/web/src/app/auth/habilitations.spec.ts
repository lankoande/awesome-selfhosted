import { Habilitations } from './auth.port';
import { OPERATION_PAR_ECRAN, autorise, espaceAutorise } from './habilitations';

const inconnues: Habilitations = { connues: false, operations: new Set() };
const connues = (...operations: string[]): Habilitations => ({ connues: true, operations: new Set(operations) });

describe('habilitations', () => {
  it("ne cache rien tant que le socle ne dit pas ce qui est permis", () => {
    // Cacher au hasard ferait croire qu'un écran n'existe pas.
    expect(autorise(inconnues, 'CASH_OPERATION')).toBe(true);
    expect(espaceAutorise(inconnues, 'siege/')).toBe(true);
  });

  it('cache un écran dont l’opération n’est pas accordée', () => {
    const droits = connues('CASH_OPERATION', 'ACCOUNT_JOURNAL_READ');
    expect(autorise(droits, 'CASH_OPERATION')).toBe(true);
    expect(autorise(droits, 'TRANSFER')).toBe(false);
  });

  it("montre un espace dès qu’un seul de ses écrans est autorisé", () => {
    expect(espaceAutorise(connues('LEDGER_READ'), 'siege/')).toBe(true);
    expect(espaceAutorise(connues('CASH_OPERATION'), 'siege/')).toBe(false);
  });

  it("n’exige rien d’un écran sans opération déclarée", () => {
    expect(autorise(connues(), undefined)).toBe(true);
  });

  it('ne nomme que des opérations du socle', () => {
    // La liste est une déclaration de ce que chaque écran appelle, pas une
    // copie de la politique : elle doit donc rester petite et explicite.
    for (const operation of Object.values(OPERATION_PAR_ECRAN)) {
      expect(operation).toMatch(/^[A-Z][A-Z_]+$/);
    }
    expect(Object.keys(OPERATION_PAR_ECRAN).length).toBeGreaterThan(0);
  });
});
