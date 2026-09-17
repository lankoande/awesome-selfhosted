import { RefusMetier } from '../guichet/modele/guichet.modele';
import { ValidationFactice } from './validation.factice';

const ENTITE = '00000000-0000-4000-8000-000000000001';

describe('source de démonstration de la file de validation', () => {
  let file: ValidationFactice;

  beforeEach(() => {
    file = new ValidationFactice();
    file.latenceMs = 0;
  });

  it('refuse qu’un demandeur approuve sa propre demande', async () => {
    const moi = await file.identite();
    const page = await file.file(ENTITE, 0, 50);
    const mienne = page.elements.find((o) => o.makerId === moi.id)!;

    await expect(file.approuver(ENTITE, mienne.id)).rejects.toMatchObject({
      code: 'AUTO_APPROBATION_INTERDITE',
      statut: 403,
    });
  });

  it('refuse un rejet sans motif : sans lui, le demandeur resoumet à l’identique', async () => {
    const page = await file.file(ENTITE, 0, 50);
    const autre = page.elements.find((o) => o.status === 'PENDING' && o.makerUsername === 'm.ouedraogo')!;

    await expect(file.rejeter(ENTITE, autre.id, '   ')).rejects.toMatchObject({ code: 'MOTIF_REQUIS' });

    const rejetee = await file.rejeter(ENTITE, autre.id, 'Pièce justificative manquante.');
    expect(rejetee.status).toBe('REJECTED');
    expect(rejetee.decisionReason).toBe('Pièce justificative manquante.');
  });

  it('laisse l’opération en ÉCHOUÉE quand l’exécution refuse après approbation', async () => {
    // La requête est rejouée à l'approbation : l'état du jour a changé.
    await expect(file.approuver(ENTITE, 'PND-000102')).rejects.toBeInstanceOf(RefusMetier);

    const apres = await file.lire(ENTITE, 'PND-000102');
    expect(apres.status).toBe('FAILED');
    expect(apres.error).toContain('remboursement anticipé');
    // La décision reste prise : ce n'est pas au valideur de recommencer.
    expect(apres.decidedBy).toBe('a.kabore');
  });

  it('ne laisse pas décider une opération expirée, et le dit', async () => {
    await expect(file.approuver(ENTITE, 'PND-000104')).rejects.toMatchObject({
      code: 'OPERATION_NON_DECIDABLE',
    });
    expect((await file.lire(ENTITE, 'PND-000104')).status).toBe('EXPIRED');
  });

  it('ne reprend pas une décision déjà prise', async () => {
    await expect(file.approuver(ENTITE, 'PND-000105')).rejects.toMatchObject({
      code: 'OPERATION_NON_DECIDABLE',
    });
  });

  it('exécute une approbation nominale', async () => {
    const apres = await file.approuver(ENTITE, 'PND-000101');
    expect(apres.status).toBe('EXECUTED');
    expect(apres.decidedBy).toBe('a.kabore');
  });

  it('pagine', async () => {
    const premiere = await file.file(ENTITE, 0, 2);
    expect(premiere.elements).toHaveLength(2);
    expect(premiere.suivant).toBe(true);
    expect(premiere.precedent).toBe(false);
  });
});
