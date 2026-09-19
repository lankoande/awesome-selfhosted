import { describe, expect, it } from 'vitest';
import { MAQUETTES_DEMONSTRATION } from './maquettes.demonstration';
import {
  actesSurMaquette, calculDuTotal, CompteNonAffecte, etatEffectif, libelleNombreOrphelins,
  libelleNombreRubriques, Maquette, obstaclesALaFermeture, obstaclesAlEntete, obstaclesAuxRegles,
  obstaclesAuxRubriques, phraseDeLaRegle, RegleSaisie, regleProposee, reglesCouvertes, Rubrique,
  PLURIEL_NATURE_COMPTE, RubriqueSaisie, rubriquesDeDetail,
} from './maquettes.modele';

function maquette(reglages: Partial<Maquette> = {}): Maquette {
  return {
    id: 'mq-1', kind: 'BALANCE_SHEET', code: 'BILAN', label: 'Bilan', validFrom: '2026-01-01',
    validTo: null, status: 'ACTIVE', createdBy: null, createdAt: null, approvedBy: null,
    approvedAt: null, withdrawnBy: null, withdrawnAt: null, ...reglages,
  };
}

function rubrique(reglages: Partial<RubriqueSaisie> = {}): RubriqueSaisie {
  return { code: 'A1', label: 'Caisse', level: 1, kind: 'DETAIL', side: 'DEBIT', plus: [],
           minus: [], ...reglages };
}

function regle(reglages: Partial<RegleSaisie> = {}): RegleSaisie {
  return { lineCode: 'A1', accountKind: '', codePrefix: '', balanceSide: '', ...reglages };
}

const ENTETE = {
  kind: 'BALANCE_SHEET' as const, code: 'BILAN-2027', label: 'Bilan 2027',
  validFrom: '2027-01-01', validTo: null,
};

describe('l’état d’une maquette', () => {
  it('distingue à venir, en vigueur et échue, à la date comptable', () => {
    expect(etatEffectif(maquette({ validFrom: '2027-01-01' }), '2026-09-18')).toBe('A_VENIR');
    expect(etatEffectif(maquette({ validTo: '2026-06-30' }), '2026-09-18')).toBe('ECHUE');
    expect(etatEffectif(maquette(), '2026-09-18')).toBe('ACTIVE');
    expect(etatEffectif(maquette({ status: 'WITHDRAWN' }), '2026-09-18')).toBe('WITHDRAWN');
  });

  it('propose d’activer ou de retirer un brouillon, de fermer une maquette en vigueur', () => {
    expect(actesSurMaquette(maquette({ status: 'DRAFT' }))).toEqual(['ACTIVER', 'RETIRER']);
    expect(actesSurMaquette(maquette())).toEqual(['FERMER']);
    expect(actesSurMaquette(maquette({ status: 'WITHDRAWN' }))).toEqual([]);
  });

  it('refuse une fermeture antérieure à la date comptable', () => {
    const obstacles = obstaclesALaFermeture(maquette(), { validTo: '2026-05-01' }, '2026-09-18');
    expect(obstacles.join(' ')).toContain('déjà produit');
  });
});

describe('l’identité d’une maquette', () => {
  /**
   * L'obstacle qui compte : écrire quarante rubriques pour découvrir à l'activation qu'une
   * maquette sans terme occupe la place.
   */
  it('avertit qu’une maquette en vigueur sans terme interdit la suivante', () => {
    const obstacles = obstaclesAlEntete(ENTETE, [maquette({ code: 'BILAN-2026' })]);
    expect(obstacles.join(' ')).toContain('BILAN-2026');
    expect(obstacles.join(' ')).toContain('validité n’est pas fermée');
  });

  it('n’avertit pas pour une autre nature d’état', () => {
    const autre = maquette({ kind: 'INCOME_STATEMENT', code: 'CR-2026' });
    expect(obstaclesAlEntete(ENTETE, [autre])).toEqual([]);
  });

  it('n’avertit pas si la maquette en vigueur a un terme', () => {
    const bornee = maquette({ code: 'BILAN-2026', validTo: '2026-12-31' });
    expect(obstaclesAlEntete(ENTETE, [bornee])).toEqual([]);
  });
});

describe('les rubriques', () => {
  /**
   * Le socle s'arrête au premier défaut. Sur quarante rubriques, corriger une erreur par
   * aller-retour est un supplice : le poste les rend toutes.
   */
  it('rend tous les défauts ensemble, et non un par aller-retour', () => {
    const obstacles = obstaclesAuxRubriques([
      rubrique({ code: 'A1', label: '' }),
      rubrique({ code: 'A1', label: 'Doublon' }),
      rubrique({ code: 'T', label: 'Total', kind: 'TOTAL', plus: ['ZZ'] }),
    ], 'BALANCE_SHEET');
    expect(obstacles.length).toBeGreaterThanOrEqual(3);
    expect(obstacles.join(' ')).toContain('libellé');
    expect(obstacles.join(' ')).toContain('le code A1');
    expect(obstacles.join(' ')).toContain('ZZ');
  });

  it('refuse un total qui somme une rubrique postérieure', () => {
    const obstacles = obstaclesAuxRubriques([
      rubrique({ code: 'T', label: 'Total', kind: 'TOTAL', plus: ['A1'] }),
      rubrique({ code: 'A1', label: 'Caisse' }),
    ], 'BALANCE_SHEET');
    expect(obstacles.join(' ')).toContain('qui la suit');
  });

  it('refuse le résultat de l’exercice ailleurs qu’au bilan, et au débit', () => {
    const horsBilan = obstaclesAuxRubriques([
      rubrique({ code: 'R', label: 'Résultat', kind: 'PROFIT_OR_LOSS', side: 'CREDIT' }),
      rubrique(),
    ], 'INCOME_STATEMENT');
    expect(horsBilan.join(' ')).toContain('qu’au bilan');

    const auDebit = obstaclesAuxRubriques([
      rubrique({ code: 'R', label: 'Résultat', kind: 'PROFIT_OR_LOSS', side: 'DEBIT' }),
      rubrique(),
    ], 'BALANCE_SHEET');
    expect(auDebit.join(' ')).toContain('au crédit');
  });

  it('exige au moins une rubrique de détail : c’est elle qui reçoit les comptes', () => {
    const obstacles = obstaclesAuxRubriques([
      rubrique({ code: 'T', label: 'Total', kind: 'TOTAL', plus: [] }),
    ], 'BALANCE_SHEET');
    expect(obstacles.join(' ')).toContain('au moins une rubrique de détail');
  });
});

describe('les règles', () => {
  it('refuse une règle qui affecte à un total', () => {
    const obstacles = obstaclesAuxRegles(
      [regle({ lineCode: 'T', accountKind: 'CUSTOMER' })],
      [rubrique({ code: 'T', label: 'Total', kind: 'TOTAL', plus: ['A1'] })]);
    expect(obstacles.join(' ')).toContain('rubrique de détail');
  });

  it('refuse une règle sans critère : elle prendrait tous les comptes', () => {
    expect(obstaclesAuxRegles([regle()], [rubrique()]).join(' ')).toContain('au moins un critère');
  });

  /**
   * Le défaut que le socle ne peut pas refuser : pendant la rédaction, une règle recouverte est
   * un état transitoire légitime. On ne le découvre, sinon, qu'en lisant un bilan dont une
   * rubrique est vide.
   */
  it('signale une règle qu’une précédente recouvre entièrement', () => {
    const regles = [
      regle({ lineCode: 'A1', accountKind: 'CUSTOMER' }),
      regle({ lineCode: 'A2', accountKind: 'CUSTOMER', balanceSide: 'DEBIT' }),
    ];
    expect(reglesCouvertes(regles)).toEqual([1]);
  });

  it('ne signale pas une règle plus large placée après une plus étroite', () => {
    const regles = [
      regle({ lineCode: 'A1', accountKind: 'CUSTOMER', balanceSide: 'DEBIT' }),
      regle({ lineCode: 'A2', accountKind: 'CUSTOMER' }),
    ];
    expect(reglesCouvertes(regles)).toEqual([]);
  });

  it('tient compte du préfixe : 201 recouvre 2011, pas l’inverse', () => {
    expect(reglesCouvertes([
      regle({ codePrefix: '201' }),
      regle({ codePrefix: '2011' }),
    ])).toEqual([1]);
    expect(reglesCouvertes([
      regle({ codePrefix: '2011' }),
      regle({ codePrefix: '201' }),
    ])).toEqual([]);
  });

  it('ne retient que les rubriques de détail comme cibles possibles', () => {
    const cibles = rubriquesDeDetail([
      rubrique({ code: 'A1' }),
      rubrique({ code: 'T', kind: 'TOTAL', plus: ['A1'] }),
      rubrique({ code: 'R', kind: 'PROFIT_OR_LOSS', side: 'CREDIT' }),
    ]);
    expect(cibles.map((r) => r.code)).toEqual(['A1']);
  });
});

describe('la lecture', () => {
  const RUBRIQUES: readonly Rubrique[] = [
    { ordinal: 1, code: 'A1', label: 'Caisse', level: 1, kind: 'DETAIL', side: 'DEBIT',
      plus: [], minus: [] },
    { ordinal: 2, code: 'A2', label: 'Créances', level: 1, kind: 'DETAIL', side: 'DEBIT',
      plus: [], minus: [] },
    { ordinal: 3, code: 'TA', label: 'Total actif', level: 0, kind: 'TOTAL', side: 'DEBIT',
      plus: ['A1', 'A2'], minus: [] },
  ];

  it('lit une règle comme une phrase, critères compris', () => {
    expect(phraseDeLaRegle(
      { ordinal: 1, lineCode: 'A2', accountKind: 'CUSTOMER', codePrefix: '201',
        balanceSide: 'DEBIT' }, RUBRIQUES))
      .toBe('Affecte à « Créances » : les comptes de clients dont le code commence par 201 '
            + 'dont le solde est débiteur.');
  });

  it('dit « les comptes » quand la nature est indifférente', () => {
    expect(phraseDeLaRegle(
      { ordinal: 1, lineCode: 'A1', accountKind: null, codePrefix: null, balanceSide: 'CREDIT' },
      RUBRIQUES))
      .toBe('Affecte à « Caisse » : les comptes dont le solde est créditeur.');
  });

  /**
   * « compte général » au pluriel donne « comptes généraux ». Un pluriel calculé écrirait
   * « les compte générals » dans la phrase qu'on relit avant d'activer un bilan.
   */
  it('met la nature du compte au pluriel, y compris quand il est irrégulier', () => {
    expect(PLURIEL_NATURE_COMPTE.GL).toBe('comptes généraux');
    expect(phraseDeLaRegle(
      { ordinal: 1, lineCode: 'A1', accountKind: 'GL', codePrefix: '70', balanceSide: null },
      RUBRIQUES))
      .toBe('Affecte à « Caisse » : les comptes généraux dont le code commence par 70.');
  });

  it('lit un total comme son calcul', () => {
    expect(calculDuTotal(RUBRIQUES[2], RUBRIQUES)).toBe('Total actif = Caisse + Créances');
  });
});

describe('la correction d’un compte sans rubrique', () => {
  /**
   * Le sens du solde est repris : un compte de client débiteur est une créance, créditeur un
   * dépôt. Proposer une règle sans le sens enverrait les deux dans la même rubrique.
   */
  it('propose une règle qui reprend la nature et le sens du solde', () => {
    const compte: CompteNonAffecte = {
      code: '20110', accountKind: 'CUSTOMER', side: 'DEBIT',
      amount: { amount: '18400000', currency: 'XOF' },
    };
    expect(regleProposee(compte, 'A2')).toEqual({
      lineCode: 'A2', accountKind: 'CUSTOMER', codePrefix: '', balanceSide: 'DEBIT',
    });
  });
});

describe('les accords', () => {
  it('accorde « rubrique » et « compte » au nombre', () => {
    expect(libelleNombreRubriques(1)).toBe('1 rubrique');
    expect(libelleNombreRubriques(8)).toBe('8 rubriques');
    expect(libelleNombreOrphelins(1)).toBe('1 compte sans rubrique');
    expect(libelleNombreOrphelins(3)).toBe('3 comptes sans rubrique');
  });
});

describe('la démonstration', () => {
  it('porte un bilan et un compte de résultat en vigueur, plus un brouillon', () => {
    expect(MAQUETTES_DEMONSTRATION.filter((m) => m.status === 'ACTIVE')).toHaveLength(2);
    expect(MAQUETTES_DEMONSTRATION.filter((m) => m.status === 'DRAFT')).toHaveLength(1);
  });
});
