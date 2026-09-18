import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AUTHENTIFICATION } from '../../auth/auth.port';
import { AuthentificationDouble } from '../../auth/testing/auth-double';
import { REGLEMENTAIRE } from '../reglementaire.port';
import { ReglementaireDouble } from '../testing/reglementaire-double';
import { Fiscalite } from './fiscalite.page';

async function calme(fixture: ComponentFixture<Fiscalite>): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

describe('réglementaire — fiscalité', () => {
  let socle: ReglementaireDouble;
  let session: AuthentificationDouble;
  let fixture: ComponentFixture<Fiscalite>;

  function html(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }
  function bouton(libelle: string): HTMLButtonElement | undefined {
    return [...html().querySelectorAll('button')].find((b) => b.textContent?.includes(libelle));
  }
  function saisir(id: string, valeur: string): void {
    const champ = html().querySelector<HTMLInputElement>(`#${id}`);
    if (!champ) throw new Error(`champ ${id} absent`);
    champ.value = valeur;
    champ.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }
  async function monter(): Promise<void> {
    fixture = TestBed.createComponent(Fiscalite);
    await calme(fixture);
  }

  beforeEach(() => {
    socle = new ReglementaireDouble();
    session = new AuthentificationDouble();
    TestBed.configureTestingModule({ providers: [{ provide: REGLEMENTAIRE, useValue: socle },
        { provide: AUTHENTIFICATION, useValue: session }] });
  });

  it('affiche le taux avec une virgule : la virgule est le séparateur décimal', async () => {
    socle.reglesRendues = [{
      id: 't', code: 'TAF', label: 'Taxe', basis: 'FEES_CHARGED', ratePercent: '17.5',
      collectionAccountId: 'gl-1', validFrom: '2026-01-01', validTo: null,
    }];
    await monter();
    expect(html().textContent).toContain('17,5 %');
  });

  it('exige le compte de collecte, et dit pourquoi', async () => {
    await monter();
    bouton('Déclarer une taxe')?.click();
    await calme(fixture);
    saisir('code-taxe', 'TVA');
    saisir('libelle-taxe', 'Taxe sur la valeur ajoutée');
    saisir('taux-taxe', '18');
    bouton('Soumettre la taxe')?.click();
    await calme(fixture);
    expect(socle.taxes.length).toBe(0);
    expect(html().textContent).toContain('compte de collecte');
  });

  it('refuse un taux hors de 0 à 100', async () => {
    await monter();
    bouton('Déclarer une taxe')?.click();
    await calme(fixture);
    saisir('code-taxe', 'TVA');
    saisir('libelle-taxe', 'Taxe');
    saisir('taux-taxe', '180');
    saisir('compte-taxe', 'gl-4453');
    saisir('effet-taxe', '01/01/2027');
    bouton('Soumettre la taxe')?.click();
    await calme(fixture);
    expect(socle.taxes.length).toBe(0);
    expect(html().textContent).toContain('entre 0 et 100');
  });

  it('accepte une virgule dans le taux et la rend au socle en point', async () => {
    await monter();
    bouton('Déclarer une taxe')?.click();
    await calme(fixture);
    saisir('code-taxe', 'TAF2');
    saisir('libelle-taxe', 'Taxe sur les activités financières');
    saisir('taux-taxe', '17,5');
    saisir('compte-taxe', 'gl-4453');
    saisir('effet-taxe', '01/01/2027');
    bouton('Soumettre la taxe')?.click();
    await calme(fixture);
    expect(socle.taxes[0].ratePercent).toBe('17.5');
  });

  it('avertit quand aucune taxe n’est déclarée', async () => {
    socle.reglesRendues = [];
    await monter();
    expect(html().textContent).toContain('Aucune règle fiscale');
  });

  it('dit qu’une taxe n’est pas un produit de la banque', async () => {
    await monter();
    expect(html().textContent).toContain("n'est pas un produit de la banque");
  });
});
