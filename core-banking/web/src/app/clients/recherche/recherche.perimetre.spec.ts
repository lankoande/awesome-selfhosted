import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { CLIENTS } from '../clients.port';
import { ClientsDouble } from '../testing/clients-double';
import { RechercheClient } from './recherche.page';

async function calme(fixture: ComponentFixture<RechercheClient>): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

class SansResultat extends ClientsDouble {
  override async chercher() {
    return { tiers: [], page: 0, precedent: false, suivant: false };
  }
}

describe('la recherche client sans résultat', () => {
  async function monter(): Promise<ComponentFixture<RechercheClient>> {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: '**', children: [] }]),
        { provide: CLIENTS, useValue: new SansResultat() },
      ],
    });
    const fixture = TestBed.createComponent(RechercheClient);
    (fixture.componentInstance as unknown as { q: { set(v: string): void } }).q.set('SANKARA');
    await (fixture.componentInstance as unknown as { charger(p: number): Promise<void> })
      .charger(0);
    await calme(fixture);
    return fixture;
  }

  it('nomme le risque de doublon avant de proposer la création', async () => {
    // Le socle rend les tiers de l'entité entière : la portée OWN_BRANCH de
    // PARTY_READ n'y filtre rien — un tiers ne porte pas d'agence. Le vrai
    // piège est la saisie du nom, pas le périmètre.
    const texte = (await monter()).nativeElement.textContent ?? '';

    expect(texte).toContain('Aucun client ne correspond');
    expect(texte).toContain('référence ou la pièce');
    expect(texte).toContain('doublon');
  });

  it("ne prétend pas que la liste s'arrête à l'agence", async () => {
    const texte = (await monter()).nativeElement.textContent ?? '';

    expect(texte).not.toContain('que les clients de');
  });

  it('propose quand même de créer : un client absent est une réponse', async () => {
    const texte = (await monter()).nativeElement.textContent ?? '';

    expect(texte).toContain('Créer ce client');
  });
});
