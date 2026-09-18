import {
  Alerte, obstaclesALaDeclaration, obstaclesAuScenario, etatDeLAlerte, aTraiter,
} from './conformite.modele';

const ALERTE = (surcharge: Partial<Alerte> = {}): Alerte => ({
  id: 'a-1', partyId: 'p-1', scenarioCode: 'ESP-5M', origin: 'MONITORING',
  raisedOn: '2026-09-15', detail: 'Espèces cumulées.', amount: null, status: 'OPEN',
  assignedTo: null, closedOn: null, closureReason: null, closedBy: null, reportId: null,
  pieces: [], ...surcharge,
});

const SCENARIO = {
  code: 'ESP-5M', label: 'Espèces au-delà de 5 000 000', method: 'CASH_THRESHOLD' as const,
  thresholdAmount: '5000000', windowDays: 30, minimumCount: null, ratio: null,
  riskRating: null, validFrom: '2026-01-01', validTo: null,
};

describe('obstaclesAuScenario', () => {
  it("ne demande que ce que la méthode exige : un réveil de dormant n'a pas de fenêtre", () => {
    expect(obstaclesAuScenario({
      ...SCENARIO, method: 'DORMANT_REACTIVATION', windowDays: null,
    })).toEqual([]);
  });

  it('nomme le paramètre manquant, et nomme la méthode qui l’exige', () => {
    const obstacles = obstaclesAuScenario({ ...SCENARIO, windowDays: null });
    expect(obstacles.length).toBe(1);
    expect(obstacles[0]).toContain('La fenêtre');
    expect(obstacles[0]).toContain("Seuil d'espèces");
  });

  it('refuse un fractionnement à une seule opération : ce n’est pas un fractionnement', () => {
    const obstacles = obstaclesAuScenario({
      ...SCENARIO, method: 'STRUCTURING', minimumCount: 1,
    });
    expect(obstacles.some((o) => o.includes('deux opérations'))).toBe(true);
  });

  it('refuse une fenêtre au-delà de dix ans : elle archive, elle ne surveille plus', () => {
    const obstacles = obstaclesAuScenario({ ...SCENARIO, windowDays: 4000 });
    expect(obstacles.some((o) => o.includes('3650'))).toBe(true);
  });

  it('refuse un scénario qui cesse avant de commencer', () => {
    const obstacles = obstaclesAuScenario({
      ...SCENARIO, validFrom: '2026-06-01', validTo: '2026-01-01',
    });
    expect(obstacles.some((o) => o.includes('ne cesse pas avant de commencer'))).toBe(true);
  });

  it('refuse un seuil nul ou négatif', () => {
    expect(obstaclesAuScenario({ ...SCENARIO, thresholdAmount: '0' })
      .some((o) => o.includes('positif'))).toBe(true);
  });
});

describe('obstaclesALaDeclaration', () => {
  const alertes = [
    ALERTE({ id: 'a-1', partyId: 'p-1' }),
    ALERTE({ id: 'a-2', partyId: 'p-2' }),
    ALERTE({ id: 'a-3', partyId: 'p-1', status: 'REPORTED' }),
  ];
  const demande = {
    partyId: 'p-1', reference: 'DS-2026-0001', narrative: 'Faits exposés.', alertIds: ['a-1'],
  };

  it('accepte une déclaration complète sur un seul tiers', () => {
    expect(obstaclesALaDeclaration(demande, alertes)).toEqual([]);
  });

  it('refuse une déclaration sans alerte : rien ne la rattacherait à des faits', () => {
    const obstacles = obstaclesALaDeclaration({ ...demande, alertIds: [] }, alertes);
    expect(obstacles.some((o) => o.includes('cite les alertes'))).toBe(true);
  });

  it('refuse de mélanger deux tiers dans une même déclaration', () => {
    const obstacles = obstaclesALaDeclaration({ ...demande, alertIds: ['a-1', 'a-2'] }, alertes);
    expect(obstacles.some((o) => o.includes('ne mélange pas deux dossiers'))).toBe(true);
  });

  it('refuse une alerte déjà déclarée : deux dossiers pour un seul fait', () => {
    const obstacles = obstaclesALaDeclaration({ ...demande, alertIds: ['a-3'] }, alertes);
    expect(obstacles.some((o) => o.includes('déjà couverte'))).toBe(true);
  });

  it("exige l'exposé des faits : c'est lui que la cellule lit", () => {
    const obstacles = obstaclesALaDeclaration({ ...demande, narrative: '  ' }, alertes);
    expect(obstacles.some((o) => o.includes('exposé des faits'))).toBe(true);
  });
});

describe('état d’une alerte', () => {
  it('distingue « déclarée » de « classée » : ce ne sont pas les mêmes issues', () => {
    expect(etatDeLAlerte('CLOSED')).not.toBe(etatDeLAlerte('REPORTED'));
  });

  it('ne compte comme à traiter que ce qui est ouvert ou en instruction', () => {
    expect(aTraiter(ALERTE({ status: 'OPEN' }))).toBe(true);
    expect(aTraiter(ALERTE({ status: 'UNDER_REVIEW' }))).toBe(true);
    expect(aTraiter(ALERTE({ status: 'CLOSED' }))).toBe(false);
    expect(aTraiter(ALERTE({ status: 'REPORTED' }))).toBe(false);
  });
});
