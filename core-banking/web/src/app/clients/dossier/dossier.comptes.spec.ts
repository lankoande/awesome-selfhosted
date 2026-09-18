import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { CLIENTS } from '../clients.port';
import { PageComptes } from '../modele/clients.modele';
import { ClientsDouble, COMPTE_DOUBLE, TIERS_DOUBLE_ID } from '../testing/clients-double';
import { DossierClient } from './dossier.page';

async function calme(fixture: ComponentFixture<DossierClient>): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

class SansCompte extends ClientsDouble {
  override async comptes(): Promise<PageComptes> {
    return { comptes: [], page: 0, precedent: false, suivant: false };
  }
}

describe('les comptes au dossier client', () => {
  let source: ClientsDouble;

  async function monter(siege: ClientsDouble = source): Promise<ComponentFixture<DossierClient>> {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: '**', children: [] }]),
        { provide: CLIENTS, useValue: siege },
      ],
    });
    const fixture = TestBed.createComponent(DossierClient);
    fixture.componentRef.setInput('id', TIERS_DOUBLE_ID);
    await calme(fixture);
    return fixture;
  }

  beforeEach(() => {
    source = new ClientsDouble();
  });

  it('demande les comptes du client, pas ceux de la banque', async () => {
    await monter();

    expect(source.questionsComptes).toEqual([{ partyId: TIERS_DOUBLE_ID }]);
  });

  it('montre le numéro, le produit et l’agence', async () => {
    const texte = (await monter()).nativeElement.textContent ?? '';

    expect(texte).toContain('Ses comptes');
    expect(texte).toContain(COMPTE_DOUBLE.code);
    expect(texte).toContain('CPTE-CHQ-PART');
    expect(texte).toContain('00021');
  });

  it('ne montre pas de solde, et dit pourquoi', async () => {
    // Un solde se lit compte par compte, et la lecture est tracée. L'afficher
    // dans une liste ferait tracer une lecture que personne n'a demandée.
    const texte = (await monter()).nativeElement.textContent ?? '';

    expect(texte).toContain('Les soldes ne figurent pas ici');
  });

  it("un client sans compte n'est pas une anomalie", async () => {
    const texte = (await monter(new SansCompte())).nativeElement.textContent ?? '';

    expect(texte).toContain("n'est pas une anomalie");
  });
});
