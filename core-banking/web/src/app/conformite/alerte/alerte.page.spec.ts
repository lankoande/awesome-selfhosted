import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { CONFORMITE } from '../conformite.port';
import { ALERTE_DOUBLE, ALERTE_ID, ConformiteDouble } from '../testing/conformite-double';
import { DossierAlerte } from './alerte.page';

async function calme(fixture: ComponentFixture<DossierAlerte>): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

describe('conformité — dossier d’alerte', () => {
  let socle: ConformiteDouble;
  let fixture: ComponentFixture<DossierAlerte>;

  function html(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }
  function bouton(libelle: string): HTMLButtonElement | undefined {
    return [...html().querySelectorAll('button')].find((b) => b.textContent?.includes(libelle));
  }
  function saisir(id: string, valeur: string): void {
    const champ = html().querySelector<HTMLInputElement | HTMLTextAreaElement>(`#${id}`);
    if (!champ) throw new Error(`champ ${id} absent`);
    champ.value = valeur;
    champ.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }
  function chiffres(): string {
    return (html().textContent ?? '').replace(/[\s  ]/g, '');
  }

  async function monter(): Promise<void> {
    fixture = TestBed.createComponent(DossierAlerte);
    fixture.componentRef.setInput('id', ALERTE_ID);
    await calme(fixture);
  }

  beforeEach(() => {
    socle = new ConformiteDouble();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: CONFORMITE, useValue: socle }],
    });
  });

  it('porte ses pièces et leur total : sans elles, l’instruction serait une intuition', async () => {
    await monter();
    // 2 400 000 + 2 450 000 = 4 850 000
    expect(chiffres()).toContain('4850000');
    expect(html().textContent).toContain('Total des pièces');
  });

  it('offre trois issues, et trois seulement', async () => {
    await monter();
    expect(bouton('Prendre en charge')).toBeDefined();
    expect(bouton('Classer avec motif')).toBeDefined();
    expect(bouton('Rédiger une déclaration')).toBeDefined();
    // Aucun geste sur le compte depuis une alerte : elle constate, elle n'empêche rien.
    expect(bouton('Bloquer')).toBeUndefined();
  });

  it('refuse de classer sans motif écrit', async () => {
    await monter();
    bouton('Classer avec motif')?.click();
    await calme(fixture);
    expect(bouton("Classer l'alerte")?.disabled).toBe(true);
    saisir('motif-classement', 'Vente de véhicule justifiée par acte de cession.');
    expect(bouton("Classer l'alerte")?.disabled).toBe(false);
  });

  it('transmet le motif de classement au socle', async () => {
    await monter();
    bouton('Classer avec motif')?.click();
    await calme(fixture);
    saisir('motif-classement', 'Acte de cession au dossier.');
    bouton("Classer l'alerte")?.click();
    await calme(fixture);
    expect(socle.classements).toEqual([
      { alertId: ALERTE_ID, motif: 'Acte de cession au dossier.' },
    ]);
  });

  it('cite l’alerte ouverte d’office quand on rédige une déclaration', async () => {
    await monter();
    bouton('Rédiger une déclaration')?.click();
    await calme(fixture);
    const cases = html().querySelectorAll<HTMLInputElement>('.citations input[type=checkbox]');
    expect(cases.length).toBeGreaterThan(0);
    expect(cases[0].checked).toBe(true);
  });

  it('ne laisse pas soumettre une déclaration incomplète', async () => {
    await monter();
    bouton('Rédiger une déclaration')?.click();
    await calme(fixture);
    expect(bouton('Soumettre la déclaration')?.disabled).toBe(true);
    saisir('reference-declaration', 'DS-2026-0012');
    saisir('expose-declaration', 'Dépôts d’espèces sans rapport avec l’activité déclarée.');
    expect(bouton('Soumettre la déclaration')?.disabled).toBe(false);
  });

  it('annonce que rien n’est déposé tant qu’un second regard n’a pas approuvé', async () => {
    await monter();
    bouton('Rédiger une déclaration')?.click();
    await calme(fixture);
    saisir('reference-declaration', 'DS-2026-0012');
    saisir('expose-declaration', 'Faits exposés.');
    bouton('Soumettre la déclaration')?.click();
    await calme(fixture);
    expect(socle.redactions.length).toBe(1);
    expect(socle.redactions[0].alertIds).toEqual([ALERTE_ID]);
    expect(html().textContent).toContain("rien n'est déposé");
  });

  it('dit d’une alerte déclarée que son sort est scellé', async () => {
    socle.alerteRendue = { ...ALERTE_DOUBLE, status: 'REPORTED' };
    await monter();
    expect(html().textContent).toContain('deux dossiers pour un seul fait');
    expect(bouton('Classer avec motif')).toBeUndefined();
  });

  it('dit du filtrage que le socle a déjà refusé, de la surveillance que rien n’est bloqué',
     async () => {
    socle.alerteRendue = { ...ALERTE_DOUBLE, origin: 'SCREENING', pieces: [] };
    await monter();
    expect(html().textContent).toContain('le socle a déjà refusé');
  });
});
