import {
  Etat, aProduire, enRetard, libellePeriode, obstaclesALAnnulation, obstaclesALaRegleFiscale,
  obstaclesALaTransmission, obstaclesAuCatalogue, periodesCloses,
} from './reglementaire.modele';

const ETAT = (surcharge: Partial<Etat> = {}): Etat => ({
  id: 'e-1', declarationId: 'd-1', declarationCode: 'SIT-COMPTA',
  method: 'ACCOUNTING_SITUATION', subjectCode: null, periodStart: '2026-08-01',
  periodEnd: '2026-08-31', dueOn: '2026-09-15', producedOn: '2026-09-03', thresholdUsed: null,
  lineCount: 2, totalAmount: null, status: 'PRODUCED', transmittedOn: null,
  transmissionReference: null, cancelledOn: null, cancellationReason: null, anomalies: [],
  lignes: [], ...surcharge,
});

const CATALOGUE = {
  code: 'SIT', label: 'Situation', recipient: 'CENTRAL_BANK' as const,
  method: 'ACCOUNTING_SITUATION' as const, frequency: 'MONTHLY' as const, deadlineDays: 15,
  thresholdAmount: null, validFrom: '2026-01-01', validTo: null,
};

const TAXE = {
  code: 'IRC', label: 'Impôt sur le revenu des créances', basis: 'INTEREST_PAID' as const,
  ratePercent: '15', collectionAccountId: 'gl-4451', validFrom: '2026-01-01', validTo: null,
};

describe('transmission d’un état', () => {
  it('laisse passer un état produit et sans anomalie', () => {
    expect(obstaclesALaTransmission(ETAT())).toEqual([]);
  });

  it('refuse un état qui porte des anomalies : on ne déclare pas des comptes faux', () => {
    const obstacles = obstaclesALaTransmission(ETAT({ anomalies: ['Balance déséquilibrée.'] }));
    expect(obstacles).toContain('Balance déséquilibrée.');
  });

  it('refuse de transmettre deux fois', () => {
    const obstacles = obstaclesALaTransmission(ETAT({ status: 'TRANSMITTED' }));
    expect(obstacles.some((o) => o.includes('déjà transmis'))).toBe(true);
  });
});

describe('annulation d’un état', () => {
  it('laisse annuler un état produit', () => {
    expect(obstaclesALAnnulation(ETAT())).toEqual([]);
  });

  it('refuse d’annuler un état transmis : on rectifie, on ne réécrit pas', () => {
    const obstacles = obstaclesALAnnulation(ETAT({ status: 'TRANSMITTED' }));
    expect(obstacles[0]).toContain('rectifie');
  });
});

describe('retard', () => {
  it('ne compte pas en retard un état transmis, même après l’échéance', () => {
    expect(enRetard(ETAT({ status: 'TRANSMITTED' }), '2026-10-01')).toBe(false);
  });

  it('compte en retard un état produit dont l’échéance est passée', () => {
    expect(enRetard(ETAT(), '2026-10-01')).toBe(true);
  });
});

describe('échéances', () => {
  it('sépare ce qui n’est pas produit de ce qui l’est : ce ne sont pas les mêmes gestes', () => {
    const echeances = [
      { declarationCode: 'A', periodEnd: '2026-08-31', dueOn: '2026-09-15', produced: false },
      { declarationCode: 'B', periodEnd: '2026-08-31', dueOn: '2026-09-20', produced: true },
    ];
    expect(aProduire(echeances).map((e) => e.declarationCode)).toEqual(['A']);
  });
});

describe('catalogue', () => {
  it('accepte une déclaration complète', () => {
    expect(obstaclesAuCatalogue(CATALOGUE)).toEqual([]);
  });

  it('exige le délai de dépôt : sans lui, aucun retard ne se constate', () => {
    const obstacles = obstaclesAuCatalogue({ ...CATALOGUE, deadlineDays: null });
    expect(obstacles.some((o) => o.includes('aucun retard'))).toBe(true);
  });

  it('refuse un seuil sur une méthode qui n’en admet pas', () => {
    const obstacles = obstaclesAuCatalogue({ ...CATALOGUE, thresholdAmount: '5000000' });
    expect(obstacles.some((o) => o.includes('jamais lu'))).toBe(true);
  });

  it('exige un seuil sur la centrale des risques', () => {
    const obstacles = obstaclesAuCatalogue({ ...CATALOGUE, method: 'CREDIT_REGISTRY' });
    expect(obstacles.some((o) => o.includes('au-delà d’un seuil'))).toBe(true);
  });
});

describe('règle fiscale', () => {
  it('accepte une taxe complète', () => {
    expect(obstaclesALaRegleFiscale(TAXE)).toEqual([]);
  });

  it('exige le compte de collecte : une retenue se loge quelque part', () => {
    const obstacles = obstaclesALaRegleFiscale({ ...TAXE, collectionAccountId: ' ' });
    expect(obstacles.some((o) => o.includes('compte de collecte'))).toBe(true);
  });

  it('refuse un taux hors de 0 à 100 : 150 % n’est pas un taux', () => {
    expect(obstaclesALaRegleFiscale({ ...TAXE, ratePercent: '150' })
      .some((o) => o.includes('entre 0 et 100'))).toBe(true);
  });
});

describe('périodes closes', () => {
  it('ne propose jamais le mois courant : il n’est pas clos', () => {
    const periodes = periodesCloses('MONTHLY', '2026-09-18', 3);
    expect(periodes[0]).toBe('2026-08-31');
    expect(periodes).toEqual(['2026-08-31', '2026-07-31', '2026-06-30']);
  });

  it('ne propose que des fins de trimestre pour une déclaration trimestrielle', () => {
    expect(periodesCloses('QUARTERLY', '2026-09-18', 3))
      .toEqual(['2026-06-30', '2026-03-31', '2025-12-31']);
  });

  it('ne propose que des fins d’exercice pour une déclaration annuelle', () => {
    expect(periodesCloses('YEARLY', '2026-09-18', 2)).toEqual(['2025-12-31', '2024-12-31']);
  });

  it('tient compte des années bissextiles', () => {
    expect(periodesCloses('MONTHLY', '2024-03-10', 1)).toEqual(['2024-02-29']);
  });
});

describe('libellé de période', () => {
  it('dit un mois à un comptable, pas une date ISO', () => {
    expect(libellePeriode('2026-08-31')).toBe('août 2026');
  });

  it('dit un trimestre et une année quand la fréquence le veut', () => {
    expect(libellePeriode('2026-06-30', 'QUARTERLY')).toBe('T2 2026');
    expect(libellePeriode('2026-12-31', 'YEARLY')).toBe('2026');
  });
});
