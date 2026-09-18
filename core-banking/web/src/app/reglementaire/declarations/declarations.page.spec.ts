import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AUTHENTIFICATION } from '../../auth/auth.port';
import { AuthentificationDouble } from '../../auth/testing/auth-double';
import { REGLEMENTAIRE } from '../reglementaire.port';
import { DECLARATION_DOUBLE, ReglementaireDouble } from '../testing/reglementaire-double';
import { CatalogueReglementaire } from './declarations.page';

async function calme(fixture: ComponentFixture<CatalogueReglementaire>): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

describe('réglementaire — catalogue', () => {
  let socle: ReglementaireDouble;
  let session: AuthentificationDouble;
  let fixture: ComponentFixture<CatalogueReglementaire>;

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
    fixture = TestBed.createComponent(CatalogueReglementaire);
    await calme(fixture);
  }

  beforeEach(() => {
    socle = new ReglementaireDouble();
    session = new AuthentificationDouble();
    TestBed.configureTestingModule({
      providers: [provideRouter([{ path: '**', children: [] }]), { provide: REGLEMENTAIRE, useValue: socle },
        { provide: AUTHENTIFICATION, useValue: session }],
    });
  });

  it('ne demande un seuil que pour la centrale des risques', async () => {
    await monter();
    bouton('Déclarer au catalogue')?.click();
    await calme(fixture);
    expect(html().querySelector('#seuil-declaration')).toBeNull();
    bouton('Centrale des risques')?.click();
    fixture.detectChanges();
    expect(html().querySelector('#seuil-declaration')).not.toBeNull();
  });

  it('ne gronde pas un formulaire vierge', async () => {
    await monter();
    bouton('Déclarer au catalogue')?.click();
    await calme(fixture);
    expect(html().textContent).not.toContain('Il manque quelque chose');
  });

  it('refuse de soumettre sans délai de dépôt, et dit ce que cela coûte', async () => {
    await monter();
    bouton('Déclarer au catalogue')?.click();
    await calme(fixture);
    saisir('code-declaration', 'SIT-2');
    saisir('libelle-declaration', 'Situation bis');
    bouton('Soumettre la déclaration')?.click();
    await calme(fixture);
    expect(socle.catalogues.length).toBe(0);
    expect(html().textContent).toContain('aucun retard ne se constate');
  });

  it('ne propose que des périodes closes, la plus récente en premier', async () => {
    await monter();
    bouton('Produire un état')?.click();
    await calme(fixture);
    const periodes = [...html().querySelectorAll('.periode')].map((b) => b.textContent?.trim());
    expect(periodes.length).toBeGreaterThan(0);
    // Mensuelle : des mois, jamais le mois courant.
    expect(periodes[0]).not.toContain(String(new Date().getMonth() + 1).padStart(2, '0'));
  });

  it('produit sur la période choisie', async () => {
    await monter();
    bouton('Produire un état')?.click();
    await calme(fixture);
    (html().querySelector('.periode') as HTMLButtonElement).click();
    fixture.detectChanges();
    bouton("Produire l'état")?.click();
    await calme(fixture);
    expect(socle.productions.length).toBe(1);
    expect(socle.productions[0].declarationId).toBe(DECLARATION_DOUBLE.id);
  });

  it('avertit qu’un catalogue vide ne fait constater aucun retard', async () => {
    socle.declarationsRendues = [];
    await monter();
    expect(html().textContent).toContain('aucun retard ne se constate');
  });
});
