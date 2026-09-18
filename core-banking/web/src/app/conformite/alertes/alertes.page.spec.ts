import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { CONFORMITE } from '../conformite.port';
import { ALERTE_DOUBLE, ConformiteDouble } from '../testing/conformite-double';
import { Alertes } from './alertes.page';

async function calme(fixture: ComponentFixture<Alertes>): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

describe('conformité — file des alertes', () => {
  let socle: ConformiteDouble;
  let fixture: ComponentFixture<Alertes>;

  function html(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }
  function bouton(libelle: string): HTMLButtonElement | undefined {
    return [...html().querySelectorAll('button')].find((b) => b.textContent?.includes(libelle));
  }

  async function monter(): Promise<void> {
    fixture = TestBed.createComponent(Alertes);
    fixture.componentRef.setInput('statut', '');
    await calme(fixture);
  }

  beforeEach(() => {
    socle = new ConformiteDouble();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: CONFORMITE, useValue: socle }],
    });
  });

  it('ouvre sur les alertes ouvertes : c’est ce qu’un analyste vient prendre', async () => {
    fixture = TestBed.createComponent(Alertes);
    await calme(fixture);
    expect(socle.statutDemande).toBe('OPEN');
  });

  it('ne nomme pas le client : une file d’alertes n’est pas un annuaire', async () => {
    await monter();
    expect(html().textContent).not.toContain(ALERTE_DOUBLE.partyId);
  });

  it("dit qu'une alerte ne bloque rien, et que seul le filtrage bloque", async () => {
    await monter();
    const texte = html().textContent ?? '';
    expect(texte).toContain('constate');
    expect(texte).toContain("aucun compte n'est bloqué");
    expect(texte).toContain('Seul le');
  });

  it('rappelle le secret : informer la personne surveillée est un délit', async () => {
    await monter();
    expect(html().textContent).toContain('Informer la personne surveillée est un délit');
  });

  it('prend en charge depuis la file, sans passer par le dossier', async () => {
    await monter();
    bouton('Prendre en charge')?.click();
    await calme(fixture);
    expect(socle.prisesEnCharge).toEqual([ALERTE_DOUBLE.id]);
  });

  it('n’offre la prise en charge que sur une alerte ouverte', async () => {
    socle.alertesRendues = [{ ...ALERTE_DOUBLE, status: 'CLOSED' }];
    await monter();
    expect(bouton('Prendre en charge')).toBeUndefined();
  });

  it('filtre par statut et redemande au socle', async () => {
    await monter();
    bouton('Classées')?.click();
    await calme(fixture);
    expect(socle.statutDemande).toBe('CLOSED');
  });

  it('compte ce qui reste à traiter : le chiffre du matin', async () => {
    socle.alertesRendues = [
      { ...ALERTE_DOUBLE, id: 'a-1', status: 'OPEN' },
      { ...ALERTE_DOUBLE, id: 'a-2', status: 'UNDER_REVIEW' },
      { ...ALERTE_DOUBLE, id: 'a-3', status: 'CLOSED' },
    ];
    await monter();
    expect(html().textContent).toContain('2 à traiter');
  });
});
