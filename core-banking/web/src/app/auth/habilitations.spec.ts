import { Droit, Habilitations, Portee } from './auth.port';
import {
  HABILITATIONS_INCONNUES, LIBELLE_PORTEE, OPERATION_PAR_ECRAN, autorise, depasseLePlafond,
  droit, espaceAutorise, exigeUnSecondRegard, plafond, portee,
} from './habilitations';

const inconnues = HABILITATIONS_INCONNUES;

function accorde(operation: string, detail: Partial<Omit<Droit, 'operation'>> = {}): Droit {
  return {
    operation,
    portee: 'OWN_ENTITY',
    secondRegard: false,
    horsAgence: false,
    plafonds: new Map(),
    plafondsHorsAgence: new Map(),
    ...detail,
  };
}

const connues = (...droits: readonly Droit[]): Habilitations => ({
  connues: true,
  droits: new Map(droits.map((d) => [d.operation, d])),
});

describe('habilitations — ce qui s’affiche', () => {
  it("ne cache rien tant que le socle ne dit pas ce qui est permis", () => {
    // Cacher au hasard ferait croire qu'un écran n'existe pas.
    expect(autorise(inconnues, 'CASH_OPERATION')).toBe(true);
    expect(espaceAutorise(inconnues, 'siege/')).toBe(true);
  });

  it('cache un écran dont l’opération n’est pas accordée', () => {
    const droits = connues(accorde('CASH_OPERATION'), accorde('ACCOUNT_JOURNAL_READ'));
    expect(autorise(droits, 'CASH_OPERATION')).toBe(true);
    expect(autorise(droits, 'TRANSFER')).toBe(false);
  });

  it("montre un espace dès qu’un seul de ses écrans est autorisé", () => {
    expect(espaceAutorise(connues(accorde('LEDGER_READ')), 'siege/')).toBe(true);
    expect(espaceAutorise(connues(accorde('CASH_OPERATION')), 'siege/')).toBe(false);
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

describe('habilitations — le second regard', () => {
  it('annonce le second regard quand la politique l’exige', () => {
    const droits = connues(accorde('LOAN_DISBURSE', { secondRegard: true }));
    expect(exigeUnSecondRegard(droits, 'LOAN_DISBURSE')).toBe(true);
  });

  it('ne l’annonce pas quand on ne sait pas', () => {
    // Se tromper dans ce sens se rattrape à l'écran suivant ; l'inverse ferait
    // attendre une validation qui ne viendra jamais.
    expect(exigeUnSecondRegard(inconnues, 'LOAN_DISBURSE')).toBe(false);
    expect(exigeUnSecondRegard(connues(accorde('LOAN_DISBURSE')), 'LOAN_DISBURSE')).toBe(false);
  });
});

describe('habilitations — les plafonds', () => {
  const caisse = connues(accorde('CASH_OPERATION', {
    portee: 'OWN_BRANCH',
    horsAgence: true,
    plafonds: new Map([['XOF', { amount: '2000000', currency: 'XOF' }]]),
    plafondsHorsAgence: new Map([['XOF', { amount: '500000', currency: 'XOF' }]]),
  }));

  it('rend le plafond de la devise demandée', () => {
    expect(plafond(caisse, 'CASH_OPERATION', 'XOF')?.amount).toBe('2000000');
    expect(plafond(caisse, 'CASH_OPERATION', 'EUR')).toBeNull();
  });

  it('distingue le plafond déplacé : on opère sans le dossier sous les yeux', () => {
    expect(plafond(caisse, 'CASH_OPERATION', 'XOF', true)?.amount).toBe('500000');
  });

  it('ne rend aucun plafond quand on ne sait rien', () => {
    expect(plafond(inconnues, 'CASH_OPERATION', 'XOF')).toBeNull();
  });

  it('avertit au-delà du plafond, et se tait en deçà', () => {
    expect(depasseLePlafond(caisse, 'CASH_OPERATION', 1_999_999, 'XOF')).toBe(false);
    expect(depasseLePlafond(caisse, 'CASH_OPERATION', 2_000_000, 'XOF')).toBe(false);
    expect(depasseLePlafond(caisse, 'CASH_OPERATION', 2_000_001, 'XOF')).toBe(true);
  });

  it('avertit plus tôt en opération déplacée', () => {
    expect(depasseLePlafond(caisse, 'CASH_OPERATION', 1_000_000, 'XOF', true)).toBe(true);
    expect(depasseLePlafond(caisse, 'CASH_OPERATION', 1_000_000, 'XOF', false)).toBe(false);
  });

  it('se tait sur un montant absent ou nul : on n’avertit pas d’une saisie vide', () => {
    expect(depasseLePlafond(caisse, 'CASH_OPERATION', null, 'XOF')).toBe(false);
    expect(depasseLePlafond(caisse, 'CASH_OPERATION', 0, 'XOF')).toBe(false);
  });

  it('se tait quand aucun plafond ne s’applique : l’absence n’est pas zéro', () => {
    const sansPlafond = connues(accorde('TRANSFER'));
    expect(depasseLePlafond(sansPlafond, 'TRANSFER', 999_999_999, 'XOF')).toBe(false);
  });
});

describe('habilitations — la portée', () => {
  it('rend la portée, et un libellé pour chacune', () => {
    const droits = connues(accorde('PARTY_READ', { portee: 'OWN_BRANCH' }));
    expect(portee(droits, 'PARTY_READ')).toBe('OWN_BRANCH');
    for (const p of ['OWN_BRANCH', 'OWN_ENTITY', 'ANY_ENTITY'] as Portee[]) {
      expect(LIBELLE_PORTEE[p].length).toBeGreaterThan(0);
    }
  });

  it('rend null quand on ne sait pas', () => {
    expect(portee(inconnues, 'PARTY_READ')).toBeNull();
    expect(droit(inconnues, 'PARTY_READ')).toBeNull();
  });
});
