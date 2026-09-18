import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CLIENTS } from '../clients.port';
import { ClientsDouble, TIERS_DOUBLE } from '../testing/clients-double';
import { RechercheClient } from './recherche.page';

async function calme(fixture: ComponentFixture<RechercheClient>): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

describe('clients — recherche', () => {
  let socle: ClientsDouble;
  let fixture: ComponentFixture<RechercheClient>;

  function html(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }
  function bouton(libelle: string): HTMLButtonElement | undefined {
    return [...html().querySelectorAll('button')].find((b) => b.textContent?.includes(libelle));
  }

  async function monter(terme?: string): Promise<void> {
    TestBed.configureTestingModule({ providers: [{ provide: CLIENTS, useValue: socle }] });
    fixture = TestBed.createComponent(RechercheClient);
    if (terme !== undefined) fixture.componentRef.setInput('q', terme);
    await calme(fixture);
  }

  beforeEach(() => {
    socle = new ClientsDouble();
  });

  it("liste les premiers clients à l'ouverture : une recherche vide n'est pas une erreur", async () => {
    await monter();

    expect(socle.recherches.length).toBe(1);
    expect(socle.recherches[0].q).toBe('');
    expect(html().textContent).toContain('SANKARA Aminata');
  });

  it("n'interroge pas le socle à chaque lettre tapée", async () => {
    await monter();
    const champ = html().querySelector<HTMLInputElement>('input')!;
    for (const lettre of 'SANK') {
      champ.value += lettre;
      champ.dispatchEvent(new Event('input'));
      await calme(fixture);
    }

    // Un guichetier tape pendant que le client épelle : interroger à chaque
    // lettre ferait défiler des résultats faux sous ses yeux.
    expect(socle.recherches.length).toBe(1);
  });

  it('amorce la recherche avec le terme passé en paramètre d’URL', async () => {
    await monter('KABORE');

    // L'écran qui renvoie ici pour vérifier un doublon pose la question ;
    // il ne la fait pas retaper.
    expect(socle.recherches[0].q).toBe('KABORE');
  });

  it("traite l'absence de résultat comme une réponse, et propose la création", async () => {
    socle.resultats = [];
    await monter();

    expect(bouton('Créer')).toBeDefined();
  });

  it("montre l'état du client et sa connaissance client dans la liste", async () => {
    socle.resultats = [{ ...TIERS_DOUBLE, status: 'BLOCKED', kycStatus: 'EXPIRED' }];
    await monter();

    // Ouvrir le dossier pour découvrir qu'un client est bloqué fait perdre un
    // aller-retour au guichet.
    const texte = html().textContent ?? '';
    expect(texte).toContain('Bloqué');
    expect(texte).toContain('Revue dépassée');
    // Le vocabulaire est celui du référentiel, pas celui des écritures : un
    // client n'est ni « comptabilisé » ni « expiré ».
    expect(texte).not.toContain('Comptabilisé');
  });
});
