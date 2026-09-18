import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { Acte } from '../modele/paiements.modele';
import { PAIEMENTS } from '../paiements.port';
import {
  PaiementsDouble, PRELEVEMENT_EMIS_EXECUTE, PRELEVEMENT_RECU_EXECUTE,
} from '../testing/paiements-double';
import { Prelevements } from './prelevements.page';

async function calme(fixture: ComponentFixture<Prelevements>): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

function html(fixture: ComponentFixture<Prelevements>): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

interface Ecran {
  ouvrir(prelevement: unknown): void;
  demarrer(acte: Acte): void;
  actes(): readonly Acte[];
  filtrerSens(sens: 'RECEIVED' | 'ISSUED' | null): void;
  lignes(): readonly { id: string }[];
}

describe("l'écran des prélèvements", () => {
  let source: PaiementsDouble;

  async function monter(): Promise<ComponentFixture<Prelevements>> {
    TestBed.configureTestingModule({ providers: [{ provide: PAIEMENTS, useValue: source }] });
    const fixture = TestBed.createComponent(Prelevements);
    await calme(fixture);
    return fixture;
  }

  beforeEach(() => {
    source = new PaiementsDouble();
  });

  it("dit que le poste n'exécute pas : c'est l'arrêté qui exécute", async () => {
    // C'est la première chose qu'un nouvel arrivant essaie de faire.
    expect(html(await monter()).textContent).toContain("Le poste n'exécute pas un prélèvement");
  });

  it('ne propose pas les mêmes actes selon le sens', async () => {
    const fixture = await monter();
    const ecran = fixture.componentInstance as unknown as Ecran;

    // Un reçu se rappelle : c'est le débiteur de la banque qui est en cause.
    ecran.ouvrir(PRELEVEMENT_RECU_EXECUTE);
    expect(ecran.actes()).toEqual(['REGLER', 'ANNULER']);

    // Un émis revient impayé : c'est le débiteur d'ailleurs qui ne paie pas.
    ecran.ouvrir(PRELEVEMENT_EMIS_EXECUTE);
    expect(ecran.actes()).toEqual(['REGLER', 'RETOURNER']);
  });

  it('filtre par sens', async () => {
    const fixture = await monter();
    const ecran = fixture.componentInstance as unknown as Ecran;

    ecran.filtrerSens('ISSUED');
    await calme(fixture);

    expect(ecran.lignes().map((p) => p.id)).toEqual([PRELEVEMENT_EMIS_EXECUTE.id]);
  });

  it('explique à qui revient le montant remboursé', async () => {
    const fixture = await monter();
    const ecran = fixture.componentInstance as unknown as Ecran;

    ecran.ouvrir({ ...PRELEVEMENT_RECU_EXECUTE, status: 'SETTLED' });
    ecran.demarrer('REMBOURSER');
    await calme(fixture);

    const texte = html(fixture).textContent ?? '';
    expect(texte).toContain('les frais restent acquis');
    expect(texte).toContain('le créancier qui devra se retourner');
  });
});
