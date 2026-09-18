import { DOSSIER_DOUBLE } from '../testing/credit-double';
import {
  Condition, DossierCredit, analyseEnVigueur, engagementsASuivre, etatDeLaDemande,
  graviteDuRetard, obstaclesALaContractualisation,
} from './credit.modele';

const dossier = (retouche: Partial<DossierCredit>): DossierCredit => ({
  ...DOSSIER_DOUBLE, ...retouche,
});
const condition = (r: Partial<Condition>): Condition => ({
  id: 'c', kind: 'PRECEDENT', description: 'Garantie', dueOn: null, clearedOn: null,
  evidence: null, ...r,
});

describe('obstacles à la contractualisation', () => {
  it("retient une demande qui n'est pas accordée", () => {
    const obstacles = obstaclesALaContractualisation(DOSSIER_DOUBLE);
    expect(obstacles.length).toBe(1);
    expect(obstacles[0]).toContain('en instruction');
  });

  it("n'en trouve aucun sur une demande accordée sans suspensive ouverte", () => {
    const ok = dossier({
      demande: { ...DOSSIER_DOUBLE.demande, status: 'APPROVED' },
      conditions: [condition({ id: 'c1', clearedOn: '2026-09-20' })],
    });
    expect(obstaclesALaContractualisation(ok)).toEqual([]);
  });

  it('bloque sur une condition SUSPENSIVE non levée, et la nomme', () => {
    const bloque = dossier({
      demande: { ...DOSSIER_DOUBLE.demande, status: 'APPROVED' },
      conditions: [condition({ id: 'c1', description: 'Hypothèque de premier rang inscrite' })],
    });
    const obstacles = obstaclesALaContractualisation(bloque);
    // « Conditions non levées » ne dit pas au chargé de crédit quoi réclamer.
    expect(obstacles).toContain('Condition suspensive non levée : Hypothèque de premier rang inscrite');
  });

  it("ne bloque PAS sur une condition résolutoire non levée", () => {
    // La confusion des deux natures débloque un crédit sans sa garantie : c'est
    // la faute la plus coûteuse de l'instruction.
    const avecResolutoire = dossier({
      demande: { ...DOSSIER_DOUBLE.demande, status: 'APPROVED' },
      conditions: [condition({ id: 'c2', kind: 'SUBSEQUENT', description: 'Justificatifs annuels' })],
    });
    expect(obstaclesALaContractualisation(avecResolutoire)).toEqual([]);
    expect(engagementsASuivre(avecResolutoire).map((c) => c.id)).toEqual(['c2']);
  });

  it('cumule les obstacles plutôt que de rendre le premier', () => {
    const multiple = dossier({
      demande: { ...DOSSIER_DOUBLE.demande, status: 'SUBMITTED' },
      conditions: [
        condition({ id: 'c1', description: 'Hypothèque' }),
        condition({ id: 'c2', description: 'Assurance' }),
      ],
    });
    // Réparer un obstacle pour en découvrir un autre fait revenir le client.
    expect(obstaclesALaContractualisation(multiple).length).toBe(3);
  });

  it('arrête net sur une demande déjà contractée', () => {
    const faite = dossier({ demande: { ...DOSSIER_DOUBLE.demande, status: 'CONTRACTED' } });
    expect(obstaclesALaContractualisation(faite)).toEqual(['La demande est déjà contractée.']);
  });
});

describe('analyse en vigueur', () => {
  it("rend la dernière versée : c'est elle qui fait foi", () => {
    const avecDeux = dossier({
      analyses: [
        { id: 'a1', assessedOn: '2026-09-04', monthlyIncome: null, monthlyCharges: null,
          existingCommitments: null, downPayment: null, requestedInstalment: null,
          debtServiceRatioPercent: '52', externalScore: null, scoreSource: null, breaches: [] },
        { id: 'a2', assessedOn: '2026-09-09', monthlyIncome: null, monthlyCharges: null,
          existingCommitments: null, downPayment: null, requestedInstalment: null,
          debtServiceRatioPercent: '31', externalScore: null, scoreSource: null, breaches: [] },
      ],
    });
    expect(analyseEnVigueur(avecDeux)?.id).toBe('a2');
  });

  it('rend null quand le dossier n’en porte aucune', () => {
    expect(analyseEnVigueur(DOSSIER_DOUBLE)).toBeNull();
  });
});

describe('gravité du retard', () => {
  it('distingue quatre situations, seuils prudentiels usuels', () => {
    expect(graviteDuRetard(0)).toBe('aucun');
    expect(graviteDuRetard(45)).toBe('surveille');
    expect(graviteDuRetard(104)).toBe('douteux');
    expect(graviteDuRetard(200)).toBe('compromis');
  });

  it('traite un retard négatif comme une avance, pas comme un retard', () => {
    expect(graviteDuRetard(-3)).toBe('aucun');
  });
});

describe('états affichés', () => {
  it('donne des états distincts à ce qui se conduit différemment', () => {
    expect(etatDeLaDemande('APPROVED')).not.toBe(etatDeLaDemande('CONTRACTED'));
    expect(etatDeLaDemande('REJECTED')).not.toBe(etatDeLaDemande('EXPIRED'));
    expect(etatDeLaDemande('SUBMITTED')).not.toBe(etatDeLaDemande('UNDER_REVIEW'));
  });
});
