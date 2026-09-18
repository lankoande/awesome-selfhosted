import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { Etablissement } from '../modele/etablissement.modele';
import { SIEGE } from '../siege.port';
import { ETABLISSEMENT_DOUBLE, SiegeDouble } from '../testing/siege-double';
import { EtablissementPage } from './etablissement.page';

async function calme(fixture: ComponentFixture<EtablissementPage>): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

function html(fixture: ComponentFixture<EtablissementPage>): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

class SansCodeBanque extends SiegeDouble {
  override async etablissement(legalEntityId: string): Promise<Etablissement> {
    return { ...ETABLISSEMENT_DOUBLE, id: legalEntityId, bankCode: null };
  }
}

describe("l'écran de l'établissement", () => {
  let source: SiegeDouble;

  async function monter(siege: SiegeDouble = source): Promise<ComponentFixture<EtablissementPage>> {
    TestBed.configureTestingModule({ providers: [{ provide: SIEGE, useValue: siege }] });
    const fixture = TestBed.createComponent(EtablissementPage);
    await calme(fixture);
    return fixture;
  }

  beforeEach(() => {
    source = new SiegeDouble();
  });

  it("montre en lecture ce qui ne se corrige pas", async () => {
    // Le code, le pays et la devise de tenue sont posés dans chaque écriture
    // depuis le premier jour : l'écran les montre sans champ, et dit pourquoi.
    const texte = html(await monter()).textContent ?? '';

    expect(texte).toContain('Ce qui ne se corrige pas');
    expect(texte).toContain('BANQUE-TEST');
    expect(texte).toContain('XOF');
    expect(texte).toContain("réécrire l'histoire comptable");
  });

  it('alerte quand le code banque manque, avant la première ouverture de compte', async () => {
    const texte = html(await monter(new SansCodeBanque())).textContent ?? '';

    expect(texte).toContain('Le code banque manque');
    expect(texte).toContain('refusera à la première ouverture de compte');
  });

  it("n'envoie que ce qui a changé", async () => {
    // Le socle distingue l'absent du vide : renvoyer tous les champs effacerait
    // ce que l'opérateur n'a pas touché.
    const fixture = await monter();
    const page = fixture.componentInstance as unknown as {
      corriger(): void;
      majChamp(cle: string, valeur: string): void;
      soumettre(): Promise<void>;
    };

    page.corriger();
    page.majChamp('phone', '+226 25 30 99 99');
    await page.soumettre();

    expect(source.derniereCorrection).toEqual({ phone: '+226 25 30 99 99' });
  });

  it('efface un champ vidé, et le distingue d’un champ non touché', async () => {
    const fixture = await monter();
    const page = fixture.componentInstance as unknown as {
      corriger(): void;
      majChamp(cle: string, valeur: string): void;
      soumettre(): Promise<void>;
    };

    page.corriger();
    page.majChamp('address', '   ');
    await page.soumettre();

    expect(source.derniereCorrection).toEqual({ address: '' });
  });

  it('annonce que rien n’a changé tant que le socle attend le second regard', async () => {
    const fixture = await monter();
    const page = fixture.componentInstance as unknown as {
      corriger(): void;
      majChamp(cle: string, valeur: string): void;
      soumettre(): Promise<void>;
    };

    page.corriger();
    page.majChamp('legalName', 'Banque de test SARL');
    await page.soumettre();
    await calme(fixture);

    const texte = html(fixture).textContent ?? '';
    expect(texte).toContain('En attente de validation');
    expect(texte).toContain("Rien n'a changé pour l'instant");
  });
});
