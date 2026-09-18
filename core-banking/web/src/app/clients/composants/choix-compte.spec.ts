import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { CLIENTS } from '../clients.port';
import { COMPTE_DOUBLE, ClientsDouble } from '../testing/clients-double';
import { CbChoixCompte } from './choix-compte';

async function calme(fixture: ComponentFixture<CbChoixCompte>): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

describe('le choix d’un compte', () => {
  let socle: ClientsDouble;

  async function monter(preselection?: string): Promise<ComponentFixture<CbChoixCompte>> {
    TestBed.configureTestingModule({ providers: [{ provide: CLIENTS, useValue: socle }] });
    const fixture = TestBed.createComponent(CbChoixCompte);
    if (preselection !== undefined) {
      fixture.componentRef.setInput('preselection', preselection);
    }
    await calme(fixture);
    return fixture;
  }

  beforeEach(() => {
    socle = new ClientsDouble();
  });

  it('ne cherche rien tant qu’on ne lui demande rien', async () => {
    const fixture = await monter();
    expect(fixture.componentInstance.compteId()).toBe('');
    expect(socle.questionsComptes).toHaveLength(0);
  });

  it('résout un numéro venu d’ailleurs et rend l’identifiant', async () => {
    // L'URL porte un numéro de compte, pas un identifiant technique : il se
    // recopie, se met en favori, se dicte au téléphone.
    const fixture = await monter(COMPTE_DOUBLE.code);

    expect(socle.questionsComptes[0]).toEqual({ texte: COMPTE_DOUBLE.code });
    expect(fixture.componentInstance.compteId()).toBe(COMPTE_DOUBLE.id);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(COMPTE_DOUBLE.code);
  });

  it('laisse choisir quand le numéro ramène plusieurs comptes', async () => {
    // Un numéro désigne un compte et un seul. S'il en ramène plusieurs, ce
    // n'était pas un numéro : retenir le premier ouvrirait le dossier d'un
    // autre client sans le dire.
    socle.comptesRendus = [COMPTE_DOUBLE, { ...COMPTE_DOUBLE, id: 'cpt-2', code: '10015000210…26' }];
    const fixture = await monter('1001');

    expect(fixture.componentInstance.compteId()).toBe('');
  });
});
