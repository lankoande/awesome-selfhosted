import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { Acte } from '../modele/paiements.modele';
import { PAIEMENTS } from '../paiements.port';
import { ORDRE_ENREGISTRE, ORDRE_ENVOYE, PaiementsDouble } from '../testing/paiements-double';
import { VirementsEmis } from './virements.page';

async function calme(fixture: ComponentFixture<VirementsEmis>): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

function html(fixture: ComponentFixture<VirementsEmis>): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

interface Ecran {
  ouvrir(ordre: unknown): void;
  demarrer(acte: Acte): void;
  confirmer(): Promise<void>;
  actes(): readonly Acte[];
  motif: { set(valeur: string): void };
  nostro: { set(valeur: string): void };
  peutConfirmer(): boolean;
  ouvrirLaSaisie(): void;
  compte: { set(valeur: string): void };
  montant: { set(valeur: number | null): void };
  beneficiaire: { set(valeur: string): void };
  banque: { set(valeur: string): void };
  compteBeneficiaire: { set(valeur: string): void };
  obstacles(): readonly string[];
  ordonner(): Promise<void>;
}

describe("l'écran des virements émis", () => {
  let source: PaiementsDouble;

  async function monter(): Promise<ComponentFixture<VirementsEmis>> {
    TestBed.configureTestingModule({ providers: [{ provide: PAIEMENTS, useValue: source }] });
    const fixture = TestBed.createComponent(VirementsEmis);
    await calme(fixture);
    return fixture;
  }

  beforeEach(() => {
    source = new PaiementsDouble();
  });

  it('dit ce que chaque état attend, au présent', async () => {
    const fixture = await monter();
    (fixture.componentInstance as unknown as Ecran).ouvrir(ORDRE_ENVOYE);
    await calme(fixture);

    // C'est la phrase que l'agent répétera au client qui appelle.
    expect(html(fixture).textContent).toContain("Il ne s'annule plus");
  });

  it("ne propose pas d'annuler un ordre parti", async () => {
    const fixture = await monter();
    const ecran = fixture.componentInstance as unknown as Ecran;

    ecran.ouvrir(ORDRE_ENVOYE);
    expect(ecran.actes()).not.toContain('ANNULER');

    ecran.ouvrir(ORDRE_ENREGISTRE);
    expect(ecran.actes()).toContain('ANNULER');
  });

  it("n'envoie pas un retour sans motif", async () => {
    const fixture = await monter();
    const ecran = fixture.componentInstance as unknown as Ecran;

    ecran.ouvrir(ORDRE_ENVOYE);
    ecran.demarrer('RETOURNER');
    expect(ecran.peutConfirmer()).toBe(false);

    ecran.motif.set('Compte du bénéficiaire clos');
    expect(ecran.peutConfirmer()).toBe(true);
  });

  it("n'envoie pas un règlement sans nostro", async () => {
    const fixture = await monter();
    const ecran = fixture.componentInstance as unknown as Ecran;

    ecran.ouvrir(ORDRE_ENVOYE);
    ecran.demarrer('REGLER');
    expect(ecran.peutConfirmer()).toBe(false);

    ecran.nostro.set('nostro-xof-1');
    expect(ecran.peutConfirmer()).toBe(true);
  });

  it("porte le motif et le nostro jusqu'au socle, chacun à son acte", async () => {
    const fixture = await monter();
    const ecran = fixture.componentInstance as unknown as Ecran;

    ecran.ouvrir(ORDRE_ENVOYE);
    ecran.demarrer('REGLER');
    ecran.nostro.set('nostro-xof-1');
    await ecran.confirmer();

    expect(source.derniereDecision)
      .toEqual({ acte: 'REGLER', nostroAccountId: 'nostro-xof-1', motif: undefined });
  });

  it('refuse un bénéficiaire incomplet avant de partir', async () => {
    const fixture = await monter();
    const ecran = fixture.componentInstance as unknown as Ecran;

    ecran.ouvrirLaSaisie();
    ecran.compte.set('cpt-1');
    ecran.montant.set(1250000);
    ecran.beneficiaire.set('SANOU Awa');
    ecran.banque.set('BOA Burkina Faso');
    expect(ecran.obstacles()).toHaveLength(1);

    ecran.compteBeneficiaire.set('BF7601001000000123456789');
    expect(ecran.obstacles()).toEqual([]);
  });

  it("dit que le compte est déjà débité quand l'ordre est enregistré", async () => {
    const fixture = await monter();
    const ecran = fixture.componentInstance as unknown as Ecran;

    ecran.ouvrirLaSaisie();
    ecran.compte.set('cpt-1');
    ecran.montant.set(1250000);
    ecran.beneficiaire.set('SANOU Awa');
    ecran.banque.set('BOA Burkina Faso');
    ecran.compteBeneficiaire.set('BF7601001000000123456789');
    await ecran.ordonner();
    await calme(fixture);

    expect(html(fixture).textContent).toContain('Le compte est déjà débité');
  });
});
