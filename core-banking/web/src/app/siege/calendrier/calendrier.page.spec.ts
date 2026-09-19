import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { SIEGE } from '../siege.port';
import { SiegeDouble } from '../testing/siege-double';
import { Calendrier } from './calendrier.page';

type Fixture = ComponentFixture<Calendrier>;

async function calme(fixture: Fixture): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

function html(fixture: Fixture): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

interface Ecran {
  onglet: { set(v: string): void };
  dateFerie: { set(v: string | null): void };
  libelleFerie: { set(v: string): void };
  typeOperation: { set(v: string): void };
  canalRegle: { set(v: string): void };
  regleDu: { set(v: string | null): void };
  canalHeure: { set(v: string): void };
  heure: { set(v: string): void };
  heureDu: { set(v: string | null): void };
  obstaclesFerie(): readonly string[];
  obstaclesRegle(): readonly string[];
  obstaclesHeure(): readonly string[];
  weekend(): string;
  majDecalage(v: string): void;
  ouvrirLaSaisie(quoi: 'ferie' | 'regle' | 'heure'): void;
  ajouterFerie(): Promise<void>;
  ajouterRegle(): Promise<void>;
  ajouterHeureLimite(): Promise<void>;
  acquitte(): string | null;
}

describe("l'écran des conditions de banque", () => {
  let socle: SiegeDouble;

  async function monter(): Promise<{ fixture: Fixture; ecran: Ecran }> {
    TestBed.configureTestingModule({ providers: [{ provide: SIEGE, useValue: socle }] });
    const fixture = TestBed.createComponent(Calendrier);
    await calme(fixture);
    return { fixture, ecran: fixture.componentInstance as unknown as Ecran };
  }

  beforeEach(() => {
    socle = new SiegeDouble();
  });

  it('dit que la date de valeur est le produit de trois choses', async () => {
    const { fixture } = await monter();
    expect(html(fixture).textContent).toContain('règle');
    expect(html(fixture).textContent).toContain('heure limite');
    expect(html(fixture).textContent).toContain('calendrier');
  });

  it('nomme le week-end plutôt que d’afficher des numéros', async () => {
    const { ecran } = await monter();
    expect(ecran.weekend()).toBe('samedi et dimanche');
  });

  it('refuse un férié déjà déclaré, ou hors de la période couverte', async () => {
    const { ecran } = await monter();
    ecran.ouvrirLaSaisie('ferie');
    ecran.dateFerie.set('2026-08-05');
    ecran.libelleFerie.set('Fête nationale');
    expect(ecran.obstaclesFerie()).toHaveLength(1);

    ecran.dateFerie.set('2027-01-01');
    ecran.libelleFerie.set('Jour de l’an');
    expect(ecran.obstaclesFerie()).toHaveLength(1);

    await ecran.ajouterFerie();
    expect(socle.dernierFerie).toBeNull();
  });

  it('déclare un férié dans la période', async () => {
    const { ecran } = await monter();
    ecran.ouvrirLaSaisie('ferie');
    ecran.dateFerie.set('2026-12-25');
    ecran.libelleFerie.set('Noël');
    await ecran.ajouterFerie();

    expect(socle.dernierFerie).toEqual({ date: '2026-12-25', label: 'Noël' });
    expect(ecran.acquitte()).toContain('2026-12-25');
  });

  it('affiche une règle en une phrase, pas en six colonnes', async () => {
    const { fixture, ecran } = await monter();
    ecran.onglet.set('regles');
    await calme(fixture);
    const texte = html(fixture).textContent ?? '';
    expect(texte).toContain('TRANSFER');
    expect(texte).toContain('+2 jours ouvrés');
  });

  it('normalise le type d’opération et le canal en majuscules', async () => {
    const { ecran } = await monter();
    ecran.ouvrirLaSaisie('regle');
    ecran.typeOperation.set('transfer');
    ecran.canalRegle.set('clearing');
    ecran.regleDu.set('2026-01-01');
    ecran.majDecalage('2');
    await ecran.ajouterRegle();

    expect(socle.derniereRegleValeur?.operationType).toBe('TRANSFER');
    expect(socle.derniereRegleValeur?.channel).toBe('CLEARING');
    expect(socle.derniereRegleValeur?.offset).toBe(2);
  });

  it('un canal vide vaut « tous canaux », pas une chaîne vide', async () => {
    const { ecran } = await monter();
    ecran.ouvrirLaSaisie('regle');
    ecran.typeOperation.set('CASH_DEPOSIT');
    ecran.regleDu.set('2026-01-01');
    ecran.majDecalage('0');
    await ecran.ajouterRegle();

    expect(socle.derniereRegleValeur?.channel).toBeNull();
  });

  it('refuse une heure limite qui chevauche une existante de même portée', async () => {
    // Le refus du socle arriverait après le second regard : autant le dire à la saisie.
    const { ecran } = await monter();
    ecran.ouvrirLaSaisie('heure');
    ecran.canalHeure.set('CLEARING');
    ecran.heure.set('15:00');
    ecran.heureDu.set('2026-06-01');
    expect(ecran.obstaclesHeure()).toHaveLength(1);

    await ecran.ajouterHeureLimite();
    expect(socle.derniereHeure).toBeNull();

    ecran.canalHeure.set('MOBILE');
    expect(ecran.obstaclesHeure()).toEqual([]);
    await ecran.ajouterHeureLimite();
    expect(socle.derniereHeure?.channel).toBe('MOBILE');
  });
});
