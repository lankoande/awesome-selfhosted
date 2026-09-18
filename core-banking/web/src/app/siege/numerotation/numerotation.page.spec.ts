import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { DemandeRegle, RegleNumerotation } from '../modele/etablissement.modele';
import { SIEGE } from '../siege.port';
import { REGLE_COMPTE, SiegeDouble } from '../testing/siege-double';
import { NumerotationPage } from './numerotation.page';

async function calme(fixture: ComponentFixture<NumerotationPage>): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

function html(fixture: ComponentFixture<NumerotationPage>): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

/** Une banque qui n'a encore choisi aucun plan : le socle y refuse de composer. */
class SansAucuneRegle extends SiegeDouble {
  override async regles(): Promise<readonly RegleNumerotation[]> {
    return [];
  }
}

interface Ecran {
  choisirDomaine(domaine: string): void;
  partirDeLActive(): void;
  partirDeZero(): void;
  ajouter(nature: string): void;
  retirer(index: number): void;
  deplacer(index: number, pas: number): void;
  majPortee(valeur: string): void;
  rediger(): Promise<void>;
  obstacles(): readonly string[];
  apercuBrouillon(): string;
  brouillon(): DemandeRegle | null;
}

describe("l'écran de numérotation", () => {
  let source: SiegeDouble;

  async function monter(siege: SiegeDouble = source): Promise<ComponentFixture<NumerotationPage>> {
    TestBed.configureTestingModule({ providers: [{ provide: SIEGE, useValue: siege }] });
    const fixture = TestBed.createComponent(NumerotationPage);
    await calme(fixture);
    return fixture;
  }

  beforeEach(() => {
    source = new SiegeDouble();
  });

  it('montre le numéro que chaque règle produit, pas seulement son gabarit', async () => {
    // Un gabarit se lit mal ; le numéro qu'il produit se lit tout de suite — et
    // la clé modulo 97 ne se calcule pas de tête.
    const texte = (html(await monter()).textContent ?? '').replace(/\s/g, '');

    expect(texte).toContain('1001500001000000000001');
  });

  it("dit ce que rien ne numérote, et pourquoi le socle ne sème rien", async () => {
    const texte = html(await monter(new SansAucuneRegle())).textContent ?? '';

    expect(texte).toContain('Des numéros ne se composent pas encore');
    expect(texte).toContain("rien n'est semé à la création d'un établissement");
  });

  it("l'aperçu du brouillon se recalcule à chaque changement de gabarit", async () => {
    const fixture = await monter();
    const ecran = fixture.componentInstance as unknown as Ecran;

    ecran.partirDeLActive();
    const avant = ecran.apercuBrouillon();
    ecran.retirer(3);
    const apres = ecran.apercuBrouillon();

    // La clé retirée, le numéro raccourcit de deux caractères — et ce n'est
    // plus un RIB.
    expect(avant).toHaveLength(24);
    expect(apres).toHaveLength(22);
  });

  it('refuse une série par agence sans code agence au gabarit', async () => {
    const fixture = await monter();
    const ecran = fixture.componentInstance as unknown as Ecran;

    ecran.partirDeZero();
    ecran.majPortee('BRANCH');

    expect(ecran.obstacles().join(' ')).toContain('même numéro');
  });

  it("ne rédige pas un gabarit que le socle refuserait", async () => {
    const fixture = await monter();
    const ecran = fixture.componentInstance as unknown as Ecran;

    ecran.partirDeZero();
    ecran.ajouter('SEQUENCE');
    await ecran.rediger();

    expect(ecran.obstacles().join(' ')).toContain("ne porte qu'un compteur");
    expect(source.derniereRegle).toBeNull();
  });

  it('rédige le gabarit tel qu’il a été composé, segments compris', async () => {
    const fixture = await monter();
    const ecran = fixture.componentInstance as unknown as Ecran;

    ecran.partirDeLActive();
    await ecran.rediger();

    expect(source.derniereRegle?.segments.map((s) => s.kind))
      .toEqual(REGLE_COMPTE.segments.map((s) => s.kind));
    expect(source.derniereRegle?.sequenceScope).toBe('BRANCH');
  });

  it("dit que rédiger n'active rien", async () => {
    const fixture = await monter();
    (fixture.componentInstance as unknown as Ecran).partirDeZero();
    await calme(fixture);

    expect(html(fixture).textContent).toContain("Rédiger n'active rien");
  });

  it('déplace un segment sans en perdre aucun', async () => {
    const fixture = await monter();
    const ecran = fixture.componentInstance as unknown as Ecran;

    ecran.partirDeLActive();
    ecran.deplacer(0, 1);

    expect(ecran.brouillon()?.segments.map((s) => s.kind))
      .toEqual(['BRANCH_CODE', 'BANK_CODE', 'SEQUENCE', 'CHECK_DIGITS']);
  });
});
