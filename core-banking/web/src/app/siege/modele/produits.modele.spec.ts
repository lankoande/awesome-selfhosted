import { describe, expect, it } from 'vitest';
import {
  actesSurVersion, champsDe, Condition, elementsDuBloc, enSections, etatEffectif, FamilleProduit,
  manquesDuParametrage, obstaclesALaFermeture, obstaclesAlEntete, sectionDe, StatutVersion,
  VersionProduit,
} from './produits.modele';

/** Une condition complète : le socle en sert toujours tous les champs. */
function condition(partiel: Partial<Condition>): Condition {
  return {
    when: '', fallback: null, in: [], presence: false, require: [], requireTier: null,
    because: 'parce que', ...partiel,
  };
}

/**
 * Une famille taillée sur le vrai contrat : intérêts obligatoires, agios facultatifs mais
 * exigeants dès qu'on y touche, commissions en bloc répété.
 */
const FAMILLE: FamilleProduit = {
  code: 'CURRENT_ACCOUNT',
  label: 'Compte courant',
  required: ['interest.day_count', 'interest.side', 'interest.credit_account'],
  optional: ['dormancy.months'],
  requireOneOf: [{
    of: ['interest.rate', 'tier:INTEREST'],
    because: 'sans taux ni barème, aucun intérêt ne serait jamais calculé',
  }],
  conditions: [
    condition({
      when: 'overdraft.rate', presence: true,
      require: ['overdraft.debit_account', 'overdraft.settlement'],
      because: 'des agios sans comptes d’imputation échoueraient à la première journée débitrice',
    }),
  ],
  groups: [{
    listParameter: 'fee.codes',
    required: ['fee.{code}.income_account'],
    optional: ['fee.{code}.amount', 'fee.{code}.basis'],
    accounts: ['fee.{code}.income_account'],
    conditions: [
      condition({
        when: 'fee.{code}.basis', fallback: 'FLAT', in: ['FLAT'],
        require: ['fee.{code}.amount'],
        because: 'une commission forfaitaire sans montant se percevrait à zéro',
      }),
      condition({
        when: 'fee.{code}.basis', in: ['TIERED_ON_CLOSING_BALANCE'],
        requireTier: 'FEE:{code}',
        because: 'le barème de la commission n’existe pas',
      }),
    ],
  }],
  accounts: ['interest.credit_account', 'overdraft.debit_account'],
};

function version(status: StatutVersion, surcharges: Partial<VersionProduit> = {}): VersionProduit {
  return {
    id: 'pv-1', code: 'CPTE-CHQ-PART', productType: 'CURRENT_ACCOUNT', label: 'Compte chèque',
    currency: 'XOF', validFrom: '2026-01-01', validTo: null, status,
    createdBy: 'u-1', createdAt: '2026-01-01T09:00:00Z', approvedBy: null, approvedAt: null,
    ...surcharges,
  };
}

describe('les champs que la famille demande', () => {
  it('rend obligatoires ceux que la famille exige, et repère les comptes', () => {
    const champs = champsDe(FAMILLE, {});
    const jour = champs.find((c) => c.nom === 'interest.day_count');
    const compte = champs.find((c) => c.nom === 'interest.credit_account');

    expect(jour?.obligatoire).toBe(true);
    expect(compte?.compte).toBe(true);
    expect(champs.find((c) => c.nom === 'dormancy.months')?.obligatoire).toBe(false);
  });

  it('montre le déclencheur d’une condition avant même qu’elle se déclenche', () => {
    // On ne peut pas renseigner un taux d'agios si le champ n'apparaît jamais.
    expect(champsDe(FAMILLE, {}).map((c) => c.nom)).toContain('overdraft.rate');
  });

  it('rend obligatoires les champs qu’une condition déclenche, et dit pourquoi', () => {
    const avant = champsDe(FAMILLE, {});
    expect(avant.find((c) => c.nom === 'overdraft.debit_account')?.obligatoire).toBe(false);

    const apres = champsDe(FAMILLE, { 'overdraft.rate': '12' });
    const compte = apres.find((c) => c.nom === 'overdraft.debit_account');
    expect(compte?.obligatoire).toBe(true);
    expect(compte?.raison).toContain('première journée débitrice');
    expect(compte?.compte).toBe(true);
  });

  it('ouvre un jeu de champs par commission déclarée', () => {
    const sans = champsDe(FAMILLE, {});
    expect(sans.map((c) => c.nom)).not.toContain('fee.TENUE.income_account');

    const avec = champsDe(FAMILLE, { 'fee.codes': 'TENUE, RETRAIT' });
    const noms = avec.map((c) => c.nom);
    expect(noms).toContain('fee.TENUE.income_account');
    expect(noms).toContain('fee.RETRAIT.income_account');
    expect(avec.find((c) => c.nom === 'fee.TENUE.income_account')?.compte).toBe(true);
  });

  it('applique la valeur par défaut d’une condition de bloc', () => {
    // fee.X.basis absent vaut FLAT : le montant est donc obligatoire sans que rien ne soit saisi.
    const champs = champsDe(FAMILLE, { 'fee.codes': 'TENUE' });
    expect(champs.find((c) => c.nom === 'fee.TENUE.amount')?.obligatoire).toBe(true);
  });
});

describe('les manques annoncés avant l’activation', () => {
  const complet = {
    'interest.day_count': 'ACT_365',
    'interest.side': 'CREDITOR',
    'interest.credit_account': 'cpt-gl-1',
    'interest.rate': '3',
  };

  it('ne trouve rien à redire à un paramétrage complet', () => {
    expect(manquesDuParametrage(FAMILLE, complet, [])).toEqual([]);
  });

  it('nomme tous les manques d’un coup, pas le premier', () => {
    // S'arrêter au premier obligerait à repasser autant de fois qu'il manque de lignes.
    const manques = manquesDuParametrage(FAMILLE, {}, []);
    expect(manques.length).toBeGreaterThan(3);
    expect(manques.some((m) => m.includes('interest.day_count'))).toBe(true);
    expect(manques.some((m) => m.includes('aucun intérêt ne serait jamais calculé'))).toBe(true);
  });

  it('un barème tient lieu de taux', () => {
    const sansTaux = { ...complet, 'interest.rate': '' };
    expect(manquesDuParametrage(FAMILLE, sansTaux, [])).toHaveLength(1);
    expect(manquesDuParametrage(FAMILLE, sansTaux, ['INTEREST'])).toEqual([]);
  });

  it('exige ce qu’un taux d’agios entraîne, en citant la conséquence', () => {
    const manques = manquesDuParametrage(FAMILLE, { ...complet, 'overdraft.rate': '12' }, []);
    expect(manques).toHaveLength(2);
    expect(manques[0]).toContain('première journée débitrice');
  });

  it('exige le barème d’une commission calculée par tranches', () => {
    const avecCommission = {
      ...complet, 'fee.codes': 'TENUE', 'fee.TENUE.income_account': 'cpt-gl-2',
      'fee.TENUE.basis': 'TIERED_ON_CLOSING_BALANCE',
    };
    expect(manquesDuParametrage(FAMILLE, avecCommission, []))
      .satisfies((m: string[]) => m.some((ligne) => ligne.includes('FEE:TENUE')));
    expect(manquesDuParametrage(FAMILLE, avecCommission, ['FEE:TENUE'])).toEqual([]);
  });

  it('une commission déclarée sans compte de produit est un manque', () => {
    const manques = manquesDuParametrage(
      FAMILLE, { ...complet, 'fee.codes': 'TENUE', 'fee.TENUE.amount': '1000' }, []);
    expect(manques.some((m) => m.includes('fee.TENUE.income_account'))).toBe(true);
  });
});

describe('les actes sur une version', () => {
  it('un brouillon s’active ou se retire', () => {
    expect(actesSurVersion(version('DRAFT'))).toEqual(['ACTIVER', 'RETIRER']);
  });

  it('une version en vigueur ne se retire pas : sa validité se ferme', () => {
    // La sortir de l'état actif ferait échouer l'arrêté de tous les comptes qui la citent.
    expect(actesSurVersion(version('ACTIVE'))).toEqual(['FERMER']);
    expect(actesSurVersion(version('ACTIVE'))).not.toContain('RETIRER');
  });

  it('une version retirée n’accepte plus rien', () => {
    expect(actesSurVersion(version('WITHDRAWN'))).toEqual([]);
  });
});

describe('l’état effectif d’une version', () => {
  const journee = '2026-09-18';

  it('distingue ce qui s’applique, ce qui s’appliquera et ce qui s’est appliqué', () => {
    // Le socle ne connaît que ACTIVE pour les trois. Les confondre à l'écran ferait chercher
    // longtemps pourquoi un produit « en vigueur » ne s'ouvre plus.
    expect(etatEffectif(version('ACTIVE'), journee)).toBe('ACTIVE');
    expect(etatEffectif(version('ACTIVE', { validFrom: '2027-01-01' }), journee)).toBe('A_VENIR');
    expect(etatEffectif(version('ACTIVE', { validTo: '2025-12-31' }), journee)).toBe('ECHUE');
  });

  it('s’apprécie à la date comptable, jamais au jour civil du poste', () => {
    const version2025 = version('ACTIVE', { validFrom: '2025-01-01', validTo: '2025-12-31' });
    expect(etatEffectif(version2025, '2025-06-01')).toBe('ACTIVE');
    expect(etatEffectif(version2025, '2026-01-01')).toBe('ECHUE');
  });

  it('laisse les autres statuts tels quels', () => {
    expect(etatEffectif(version('DRAFT', { validFrom: '2020-01-01' }), journee)).toBe('DRAFT');
    expect(etatEffectif(version('WITHDRAWN'), journee)).toBe('WITHDRAWN');
  });

  it('une version échue accepte encore la fermeture : son statut, lui, n’a pas changé', () => {
    // Raccourcir une validité passée reste refusé — c'est la fermeture qui le dit, pas l'état.
    expect(actesSurVersion(version('ACTIVE', { validTo: '2025-12-31' }))).toEqual(['FERMER']);
  });
});

describe('la fermeture d’une validité', () => {
  const active = version('ACTIVE', { validFrom: '2026-01-01' });

  it('accepte la date comptable et au-delà', () => {
    expect(obstaclesALaFermeture(active, { validTo: '2026-09-18' }, '2026-09-18')).toEqual([]);
    expect(obstaclesALaFermeture(active, { validTo: '2026-12-31' }, '2026-09-18')).toEqual([]);
  });

  it('refuse une date déjà arrêtée', () => {
    const obstacles = obstaclesALaFermeture(active, { validTo: '2026-09-17' }, '2026-09-18');
    expect(obstacles).toHaveLength(1);
    expect(obstacles[0]).toContain('rejeu');
  });

  it('refuse une fin antérieure à l’entrée en vigueur', () => {
    const tardive = version('ACTIVE', { validFrom: '2027-01-01' });
    expect(obstaclesALaFermeture(tardive, { validTo: '2026-12-01' }, '2026-09-18'))
      .toHaveLength(1);
  });

  it('exige une date', () => {
    expect(obstaclesALaFermeture(active, { validTo: null }, '2026-09-18')).toHaveLength(1);
  });
});

describe('l’identité d’une version', () => {
  const entete = {
    code: 'CPTE-CHQ', productType: 'CURRENT_ACCOUNT', label: 'Compte chèque', currency: 'XOF',
    validFrom: '2026-01-01', validTo: null,
  };

  it('passe quand tout est nommé', () => {
    expect(obstaclesAlEntete(entete)).toEqual([]);
  });

  it('refuse une version sans code, sans famille, sans devise ou sans entrée en vigueur', () => {
    expect(obstaclesAlEntete({ ...entete, code: ' ' })).toHaveLength(1);
    expect(obstaclesAlEntete({ ...entete, productType: '' })).toHaveLength(1);
    expect(obstaclesAlEntete({ ...entete, currency: '' })).toHaveLength(1);
    expect(obstaclesAlEntete({ ...entete, validFrom: null })).toHaveLength(1);
  });

  it('refuse une validité qui finit avant de commencer', () => {
    expect(obstaclesAlEntete({ ...entete, validTo: '2025-12-31' })).toHaveLength(1);
  });
});

describe('la lecture d’un paramétrage', () => {
  it('range les paramètres par section, commission par commission', () => {
    expect(sectionDe('interest.rate')).toBe('interest');
    expect(sectionDe('fee.TENUE.amount')).toBe('fee.TENUE');
    expect(sectionDe('fee.codes')).toBe('fee');

    const sections = enSections(champsDe(FAMILLE, { 'fee.codes': 'TENUE' }));
    expect(sections.map((s) => s.cle)).toContain('fee.TENUE');
    expect(sections.find((s) => s.cle === 'fee.TENUE')?.libelle).toBe('Commission TENUE');
    // Les intérêts se lisent avant les agios : c'est l'ordre du métier, pas l'alphabet.
    expect(sections.findIndex((s) => s.cle === 'interest'))
      .toBeLessThan(sections.findIndex((s) => s.cle === 'overdraft'));
  });

  it('découpe la liste d’un bloc répété sur les virgules, espaces compris', () => {
    expect(elementsDuBloc(FAMILLE.groups[0], { 'fee.codes': ' TENUE , RETRAIT ,, ' }))
      .toEqual(['TENUE', 'RETRAIT']);
    expect(elementsDuBloc(FAMILLE.groups[0], {})).toEqual([]);
  });
});
