import { COMPTES_DEMO, GuichetFactice } from './guichet.factice';
import { DemandeEspeces, RefusMetier } from './modele/guichet.modele';

const ENTITE = '00000000-0000-4000-8000-000000000001';

function demande(accountId: string, montant: number, cle: string): DemandeEspeces {
  return {
    legalEntityId: ENTITE,
    accountId,
    amount: String(montant),
    currency: 'XOF',
    channel: 'BRANCH',
    narrative: "Versement d'espèces",
    cleIdempotence: cle,
  };
}

const compte = (i: number) => COMPTES_DEMO[i]!.accountId;

describe('source de démonstration du guichet', () => {
  let guichet: GuichetFactice;

  beforeEach(() => {
    guichet = new GuichetFactice();
    guichet.latenceMs = 0;
  });

  it('rejoue la même clé sans comptabiliser deux fois', async () => {
    const cle = 'cle-unique';
    const premier = await guichet.verser(demande(compte(0), 50000, cle));
    const second = await guichet.verser(demande(compte(0), 50000, cle));

    expect(premier.genre).toBe('comptabilise');
    expect(second.genre).toBe('comptabilise');
    if (premier.genre !== 'comptabilise' || second.genre !== 'comptabilise') return;

    expect(premier.recu.replayed).toBe(false);
    expect(second.recu.replayed).toBe(true);
    // Même écriture : c'est bien le premier reçu qui revient, pas un second.
    expect(second.recu.entryNumber).toBe(premier.recu.entryNumber);
    expect(second.recu.entryId).toBe(premier.recu.entryId);
  });

  it('refuse au-delà du plafond espèces sur 30 jours, sans rien comptabiliser', async () => {
    const trop = 2500000; // le cumul du compte est déjà à 7 900 000 pour un plafond de 10 000 000
    await expect(guichet.verser(demande(compte(1), trop, 'c1'))).rejects.toThrow(RefusMetier);

    // Le compte n'a pas bougé : un refus ne consomme pas le plafond.
    const apres = await guichet.verser(demande(compte(1), 2000000, 'c2'));
    expect(apres.genre).toBe('comptabilise');
  });

  it('refuse toute opération sur un compte non actif', async () => {
    await expect(guichet.verser(demande(compte(3), 10000, 'c3'))).rejects.toMatchObject({
      code: 'COMPTE_NON_ACTIF',
      statut: 422,
    });
  });

  it('met en attente quand le socle exige un second regard : rien n’est comptabilisé', async () => {
    const issue = await guichet.verser(demande(compte(2), 900000, 'c4'));
    expect(issue.genre).toBe('en-attente');
  });

  it('une panne ne consomme pas la clé : le rejeu aboutit', async () => {
    const cle = 'cle-panne';
    await expect(guichet.verser(demande(compte(4), 75000, cle))).rejects.toMatchObject({
      code: 'RESEAU_INDISPONIBLE',
    });

    const rejeu = await guichet.verser(demande(compte(4), 75000, cle));
    expect(rejeu.genre).toBe('comptabilise');
    if (rejeu.genre !== 'comptabilise') return;
    // Première comptabilisation réussie : ce n'est pas un rejeu de reçu.
    expect(rejeu.recu.replayed).toBe(false);
  });

  it('le disponible retranche le blocage, le solde comptable non', async () => {
    const solde = await guichet.soldes(ENTITE, compte(0));
    expect(Number(solde.current.amount) - Number(solde.available.amount)).toBe(50000);
  });
});

describe('retrait d’espèces', () => {
  let guichet: GuichetFactice;

  beforeEach(() => {
    guichet = new GuichetFactice();
    guichet.latenceMs = 0;
  });

  it('refuse sur le disponible, pas sur le solde comptable', async () => {
    // Le compte 0 a 1 240 500 de solde et 50 000 bloqués : 1 190 500 disponibles.
    const solde = await guichet.soldes(ENTITE, compte(0));
    expect(Number(solde.current.amount)).toBe(1240500);
    expect(Number(solde.available.amount)).toBe(1190500);

    // Un montant couvert par le solde mais pas par le disponible est refusé,
    // et le refus dit explicitement ce qui est retenu.
    await expect(guichet.retirer(demande(compte(0), 1200000, 'r1'))).rejects.toMatchObject({
      code: 'PROVISION_INSUFFISANTE',
    });
    await guichet.retirer(demande(compte(0), 1200000, 'r1')).catch((erreur) => {
      expect(erreur.detail).toContain('retenus par un blocage');
    });
  });

  it('compte les frais dans le disponible exigé', async () => {
    // 1 190 500 disponibles, 1 170 de frais et taxe : 1 190 000 passe, 1 190 500 non.
    await expect(guichet.retirer(demande(compte(0), 1190500, 'r2'))).rejects.toMatchObject({
      code: 'PROVISION_INSUFFISANTE',
    });
    const recu = await guichet.retirer(demande(compte(0), 300000, 'r3'));
    expect(recu.genre).toBe('comptabilise');
  });

  it('applique le plafond de retrait journalier du produit', async () => {
    // Le compte 1 a 3 450 000 disponibles mais un plafond de 2 000 000.
    await expect(guichet.retirer(demande(compte(1), 2500000, 'r4'))).rejects.toMatchObject({
      code: 'PLAFOND_RETRAIT_JOURNALIER_DEPASSE',
    });
  });

  it('rejoue la clé d’idempotence comme le versement', async () => {
    const premier = await guichet.retirer(demande(compte(1), 100000, 'r5'));
    const second = await guichet.retirer(demande(compte(1), 100000, 'r5'));
    if (premier.genre !== 'comptabilise' || second.genre !== 'comptabilise') throw new Error('attendu comptabilisé');
    expect(second.recu.replayed).toBe(true);
    expect(second.recu.entryNumber).toBe(premier.recu.entryNumber);
  });

  it('refuse tout retrait sur un compte non actif', async () => {
    await expect(guichet.retirer(demande(compte(3), 10000, 'r6'))).rejects.toMatchObject({
      code: 'COMPTE_NON_ACTIF',
    });
  });
});

describe('virement interne et relevé', () => {
  let guichet: GuichetFactice;

  beforeEach(() => {
    guichet = new GuichetFactice();
    guichet.latenceMs = 0;
  });

  function virement(source: string, destination: string, valeur: number, cle: string) {
    return {
      legalEntityId: ENTITE, sourceAccountId: source, destinationAccountId: destination,
      amount: String(valeur), currency: 'XOF', channel: 'BRANCH',
      narrative: 'Virement interne', cleIdempotence: cle,
    };
  }

  it('refuse un virement vers le même compte', async () => {
    await expect(guichet.virer(virement(compte(0), compte(0), 10000, 'v1'))).rejects.toMatchObject({
      code: 'COMPTES_IDENTIQUES',
    });
  });

  it('refuse un virement dont un des deux comptes n’est pas actif', async () => {
    await expect(guichet.virer(virement(compte(0), compte(3), 10000, 'v2'))).rejects.toMatchObject({
      code: 'COMPTE_NON_ACTIF',
    });
  });

  it('bute sur le disponible du débiteur', async () => {
    await expect(guichet.virer(virement(compte(0), compte(1), 1200000, 'v3'))).rejects.toMatchObject({
      code: 'PROVISION_INSUFFISANTE',
    });
    const issue = await guichet.virer(virement(compte(0), compte(1), 100000, 'v4'));
    expect(issue.genre).toBe('comptabilise');
  });

  it('rejoue la clé d’idempotence : le bénéficiaire n’est pas crédité deux fois', async () => {
    const premier = await guichet.virer(virement(compte(0), compte(1), 50000, 'v5'));
    const second = await guichet.virer(virement(compte(0), compte(1), 50000, 'v5'));
    if (premier.genre !== 'comptabilise' || second.genre !== 'comptabilise') throw new Error('attendu comptabilisé');
    expect(second.recu.entryNumber).toBe(premier.recu.entryNumber);
    expect(second.recu.replayed).toBe(true);
  });

  it('rend un relevé paginé, avec la contre-passation et son écriture d’origine', async () => {
    const page = await guichet.releve(ENTITE, compte(0), null, null, 0, 50);
    const annulante = page.lignes.find((l) => l.reversalOf !== null);
    expect(annulante).toBeTruthy();
    expect(page.lignes.some((l) => l.entryId === annulante!.reversalOf)).toBe(true);
  });

  it('borne le relevé à la période demandée', async () => {
    const aujourdHui = new Date().toISOString().slice(0, 10);
    const page = await guichet.releve(ENTITE, compte(0), aujourdHui, aujourdHui, 0, 50);
    expect(page.lignes.length).toBeGreaterThan(0);
    expect(page.lignes.every((l) => l.bookingDate === aujourdHui)).toBe(true);
  });
});
