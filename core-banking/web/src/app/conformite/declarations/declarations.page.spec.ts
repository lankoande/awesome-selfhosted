import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CONFORMITE } from '../conformite.port';
import { ConformiteDouble, DECLARATION_DOUBLE } from '../testing/conformite-double';
import { Declarations } from './declarations.page';

async function calme(fixture: ComponentFixture<Declarations>): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

describe('conformité — déclarations de soupçon', () => {
  let socle: ConformiteDouble;
  let fixture: ComponentFixture<Declarations>;

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
    fixture = TestBed.createComponent(Declarations);
    await calme(fixture);
  }

  beforeEach(() => {
    socle = new ConformiteDouble();
    TestBed.configureTestingModule({ providers: [{ provide: CONFORMITE, useValue: socle }] });
  });

  it('ne propose pas de rédiger ici : une déclaration se rédige depuis l’alerte', async () => {
    await monter();
    expect(bouton('Rédiger')).toBeUndefined();
  });

  it('compte ce qui est rédigé et pas encore déposé', async () => {
    await monter();
    expect(html().textContent).toContain('1 non déposée');
  });

  it('exige le récépissé rendu par la cellule pour enregistrer le dépôt', async () => {
    await monter();
    bouton('Enregistrer le dépôt')?.click();
    await calme(fixture);
    const valider = [...html().querySelectorAll('button')]
      .filter((b) => b.textContent?.includes('Enregistrer le dépôt')).pop();
    expect(valider?.disabled).toBe(true);
    saisir('reference-depot', 'CENTIF/2026/2041');
    expect(valider?.disabled).toBe(false);
  });

  it('enregistre le récépissé et le montre sur la ligne', async () => {
    await monter();
    bouton('Enregistrer le dépôt')?.click();
    await calme(fixture);
    saisir('reference-depot', 'CENTIF/2026/2041');
    [...html().querySelectorAll('button')]
      .filter((b) => b.textContent?.includes('Enregistrer le dépôt')).pop()?.click();
    await calme(fixture);
    expect(socle.transmissions[0].reference).toBe('CENTIF/2026/2041');
    expect(html().textContent).toContain('CENTIF/2026/2041');
  });

  it('dit que cet écran ne transmet rien : il constate un dépôt déjà fait', async () => {
    await monter();
    bouton('Enregistrer le dépôt')?.click();
    await calme(fixture);
    expect(html().textContent).toContain('ne transmet rien à la cellule');
  });

  it('ne nomme pas les clients : le secret est la règle de cet espace', async () => {
    await monter();
    expect(html().textContent).not.toContain(DECLARATION_DOUBLE.partyId);
  });
});
