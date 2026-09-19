import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { SIEGE } from '../siege.port';
import { RESEAU_DOUBLE, SiegeDouble } from '../testing/siege-double';
import { Agences } from './agences.page';

type Fixture = ComponentFixture<Agences>;

async function calme(fixture: Fixture): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

function html(fixture: Fixture): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

interface Ecran {
  code: { set(v: string): void };
  nom: { set(v: string): void };
  ouverteLe: { set(v: string | null): void };
  parent: { set(v: string): void };
  deviseNeuve: { set(v: string): void };
  obstacles(): readonly string[];
  lignesLiaison(): readonly { devise: string; compte: string }[];
  parents(): readonly { id: string }[];
  ouvrirLaSaisie(): void;
  majLiaison(devise: string, compte: string): void;
  ajouterDevise(): void;
  retirerDevise(devise: string): void;
  creer(): Promise<void>;
  acquitte(): string | null;
}

describe("l'écran des agences", () => {
  let socle: SiegeDouble;

  async function monter(): Promise<{ fixture: Fixture; ecran: Ecran }> {
    TestBed.configureTestingModule({ providers: [{ provide: SIEGE, useValue: socle }] });
    const fixture = TestBed.createComponent(Agences);
    await calme(fixture);
    return { fixture, ecran: fixture.componentInstance as unknown as Ecran };
  }

  beforeEach(() => {
    socle = new SiegeDouble();
  });

  it('montre le réseau, siège en tête', async () => {
    const { fixture } = await monter();
    const texte = html(fixture).textContent ?? '';
    expect(texte).toContain('SIEGE');
    expect(texte).toContain('00021');
    expect(texte.indexOf('SIEGE')).toBeLessThan(texte.indexOf('00021'));
  });

  it('ne propose de rattachement qu’au siège et aux régions', async () => {
    // Une agence ne porte pas d'agence : le réseau a trois niveaux, pas une chaîne.
    const { ecran } = await monter();
    expect(ecran.parents().map((p) => p.id)).toEqual(['siege']);
  });

  it('exige au moins un compte de liaison, et le dit', async () => {
    const { ecran } = await monter();
    ecran.ouvrirLaSaisie();
    ecran.code.set('00099');
    ecran.nom.set('Agence neuve');
    ecran.ouverteLe.set('2026-10-01');

    // Ouverte, la saisie pose déjà la devise de tenue — mais sans compte désigné.
    expect(ecran.lignesLiaison().map((l) => l.devise)).toEqual(['XOF']);
    expect(ecran.obstacles().some((o) => o.includes('XOF'))).toBe(true);

    ecran.majLiaison('XOF', 'gl-liaison');
    expect(ecran.obstacles()).toEqual([]);

    // Sans aucune devise, c'est la conséquence qui est nommée, pas le champ.
    ecran.retirerDevise('XOF');
    expect(ecran.obstacles().some((o) => o.includes('opération déplacée'))).toBe(true);
  });

  it('refuse un code déjà porté par une agence du réseau', async () => {
    const { ecran } = await monter();
    ecran.ouvrirLaSaisie();
    ecran.code.set(RESEAU_DOUBLE[1].code);
    ecran.nom.set('Doublon');
    ecran.ouverteLe.set('2026-10-01');
    ecran.majLiaison('XOF', 'gl-liaison');

    expect(ecran.obstacles()).toHaveLength(1);
    await ecran.creer();
    expect(socle.derniereAgence).toBeNull();
  });

  it('ajoute et retire une devise de liaison', async () => {
    const { ecran } = await monter();
    ecran.ouvrirLaSaisie();
    ecran.deviseNeuve.set('eur');
    ecran.ajouterDevise();
    expect(ecran.lignesLiaison().map((l) => l.devise)).toEqual(['XOF', 'EUR']);

    ecran.retirerDevise('EUR');
    expect(ecran.lignesLiaison().map((l) => l.devise)).toEqual(['XOF']);
  });

  it('crée l’agence et dit que son code ne se change plus', async () => {
    const { ecran } = await monter();
    ecran.ouvrirLaSaisie();
    ecran.code.set('00099');
    ecran.nom.set('Agence neuve');
    ecran.ouverteLe.set('2026-10-01');
    ecran.majLiaison('XOF', 'gl-liaison');
    await ecran.creer();

    expect(socle.derniereAgence?.code).toBe('00099');
    expect(socle.derniereAgence?.liaisonAccounts).toEqual({ XOF: 'gl-liaison' });
    expect(ecran.acquitte()).toContain('ne se change plus');
  });
});
