import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AUTHENTIFICATION } from '../../auth/auth.port';
import { AuthentificationDouble } from '../../auth/testing/auth-double';
import { REGLEMENTAIRE } from '../reglementaire.port';
import { ECHEANCE_DOUBLE, ReglementaireDouble } from '../testing/reglementaire-double';
import { Echeances } from './echeances.page';

async function calme(fixture: ComponentFixture<Echeances>): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

describe('réglementaire — échéances', () => {
  let socle: ReglementaireDouble;
  let session: AuthentificationDouble;
  let fixture: ComponentFixture<Echeances>;

  function html(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }
  function bouton(libelle: string): HTMLButtonElement | undefined {
    return [...html().querySelectorAll('button')].find((b) => b.textContent?.includes(libelle));
  }
  async function monter(): Promise<void> {
    fixture = TestBed.createComponent(Echeances);
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

  it('sépare ce qui n’est pas produit de ce qui est produit mais pas déposé', async () => {
    socle.echeancesRendues = [
      { ...ECHEANCE_DOUBLE, produced: false },
      { declarationCode: 'CR-RISQUES', periodEnd: '2026-08-31', dueOn: '2026-09-20',
        produced: true },
    ];
    await monter();
    const texte = html().textContent ?? '';
    expect(texte).toContain("Rien n'est encore produit");
    expect(texte).toContain('Produit, mais pas déposé');
  });

  it('produit sans redemander la période : elle est celle de l’échéance', async () => {
    await monter();
    bouton("Produire l'état")?.click();
    await calme(fixture);
    expect(socle.productions).toEqual([{ declarationId: 'dec-1', periodEnd: '2026-08-31' }]);
  });

  it('dit qu’une déclaration absente du catalogue ne se produit pas', async () => {
    socle.declarationsRendues = [];
    await monter();
    expect(html().textContent).toContain('déclaration absente du catalogue');
    expect(bouton("Produire l'état")).toBeUndefined();
  });

  it('distingue produire de transmettre, et dit pourquoi', async () => {
    await monter();
    const texte = html().textContent ?? '';
    expect(texte).toContain("Produire n'est pas déposer");
    expect(texte).toContain('ne se décide pas seul');
  });

  it('dit franchement quand rien n’est en retard', async () => {
    socle.echeancesRendues = [];
    await monter();
    expect(html().textContent).toContain('Aucune échéance dépassée');
  });
});
