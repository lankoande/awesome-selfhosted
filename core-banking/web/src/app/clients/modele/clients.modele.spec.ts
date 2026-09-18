import { DOSSIER_DOUBLE, TIERS_DOUBLE } from '../testing/clients-double';
import { Dossier, Tiers, etatDuKyc, etatDuTiers, obstaclesAOuverture } from './clients.modele';

const tiers = (retouche: Partial<Tiers>): Tiers => ({ ...TIERS_DOUBLE, ...retouche });
const dossier = (retouche: Partial<Dossier>): Dossier => ({ ...DOSSIER_DOUBLE, ...retouche });

describe("obstacles à l'ouverture d'un compte", () => {
  it("n'en trouve aucun sur un client actif, vérifié, au dossier complet", () => {
    expect(obstaclesAOuverture(TIERS_DOUBLE, DOSSIER_DOUBLE)).toEqual([]);
  });

  it('nomme chaque pièce manquante et chaque pièce expirée, une par une', () => {
    const obstacles = obstaclesAOuverture(TIERS_DOUBLE, dossier({
      complete: false,
      missing: ['ADDRESS_PROOF'],
      expired: ['IDENTITY'],
    }));

    // Un « dossier incomplet » ne dit pas au guichetier quoi réclamer au client.
    expect(obstacles).toContain("Pièce manquante : justificatif de domicile.");
    expect(obstacles).toContain("Pièce expirée : pièce d'identité.");
  });

  it('nomme chaque bénéficiaire effectif non vérifié', () => {
    const obstacles = obstaclesAOuverture(tiers({ kind: 'LEGAL_PERSON' }), dossier({
      complete: false,
      beneficialOwnersMissing: false,
      unverifiedOwners: ['KABORE Adama', 'KABORE Boureima'],
    }));

    expect(obstacles).toContain('Bénéficiaire non vérifié : KABORE Adama.');
    expect(obstacles).toContain('Bénéficiaire non vérifié : KABORE Boureima.');
  });

  it('retient le client bloqué et la connaissance client non vérifiée', () => {
    expect(obstaclesAOuverture(tiers({ status: 'BLOCKED' }), DOSSIER_DOUBLE).length).toBe(1);
    expect(obstaclesAOuverture(tiers({ kycStatus: 'EXPIRED' }), DOSSIER_DOUBLE).length).toBe(1);
    expect(obstaclesAOuverture(tiers({ kycStatus: 'PENDING' }), DOSSIER_DOUBLE).length).toBe(1);
  });

  it("ne bloque rien quand le dossier n'a pas été lu : le socle refusera", () => {
    // Le poste ne bloque que ce qui est certain. Un dossier absent n'est pas un
    // dossier incomplet.
    expect(obstaclesAOuverture(TIERS_DOUBLE, null)).toEqual([]);
  });

  it('cumule les obstacles plutôt que de rendre le premier', () => {
    const obstacles = obstaclesAOuverture(tiers({ status: 'BLOCKED', kycStatus: 'PENDING' }),
                                          dossier({ complete: false, missing: ['IDENTITY'] }));
    // Réparer un obstacle pour en découvrir un autre fait revenir le client.
    expect(obstacles.length).toBe(3);
  });
});

describe('états affichés', () => {
  it("donne au client bloqué et à la connaissance client expirée des états distincts", () => {
    expect(etatDuTiers('ACTIVE')).not.toBe(etatDuTiers('BLOCKED'));
    expect(etatDuKyc('VERIFIED')).not.toBe(etatDuKyc('EXPIRED'));
    expect(etatDuKyc('PENDING')).not.toBe(etatDuKyc('VERIFIED'));
  });
});
