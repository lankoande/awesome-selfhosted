import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { Acte } from '../modele/paiements.modele';
import { PAIEMENTS } from '../paiements.port';
import { PaiementsDouble, REMISE_A_LENCAISSEMENT } from '../testing/paiements-double';
import { RemisesCheques } from './remises.page';

async function calme(fixture: ComponentFixture<RemisesCheques>): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

function html(fixture: ComponentFixture<RemisesCheques>): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

interface Ecran {
  ouvrir(remise: unknown): void;
  demarrer(acte: Acte): void;
  confirmer(): Promise<void>;
  actes(): readonly Acte[];
  motif: { set(valeur: string): void };
  peutConfirmer(): boolean;
}

describe("l'écran des remises de chèques", () => {
  let source: PaiementsDouble;

  async function monter(): Promise<ComponentFixture<RemisesCheques>> {
    TestBed.configureTestingModule({ providers: [{ provide: PAIEMENTS, useValue: source }] });
    const fixture = TestBed.createComponent(RemisesCheques);
    await calme(fixture);
    return fixture;
  }

  beforeEach(() => {
    source = new PaiementsDouble();
  });

  it('annonce le crédit sauf bonne fin dès la file, pas seulement au détail', async () => {
    // C'est la question que tous les clients posent : pourquoi mon solde monte
    // et mon disponible non.
    const texte = html(await monter()).textContent ?? '';

    expect(texte).toContain('créditée sauf bonne fin');
    expect(texte).toContain('son disponible non');
  });

  it("propose de régler ou de retourner une remise à l'encaissement", async () => {
    const fixture = await monter();
    const ecran = fixture.componentInstance as unknown as Ecran;

    ecran.ouvrir(REMISE_A_LENCAISSEMENT);
    expect(ecran.actes()).toEqual(['REGLER', 'RETOURNER']);
  });

  it('exige un motif pour un impayé, et dit ce que le client verra', async () => {
    const fixture = await monter();
    const ecran = fixture.componentInstance as unknown as Ecran;

    ecran.ouvrir(REMISE_A_LENCAISSEMENT);
    ecran.demarrer('RETOURNER');
    await calme(fixture);

    expect(ecran.peutConfirmer()).toBe(false);
    expect(html(fixture).textContent).toContain('contre-passé');

    ecran.motif.set('Provision insuffisante chez la banque tirée');
    expect(ecran.peutConfirmer()).toBe(true);
  });

  it('dit que le blocage tombe au règlement', async () => {
    const fixture = await monter();
    const ecran = fixture.componentInstance as unknown as Ecran;

    ecran.ouvrir(REMISE_A_LENCAISSEMENT);
    ecran.demarrer('REGLER');
    await calme(fixture);

    expect(html(fixture).textContent).toContain('lève le blocage');
  });
});
