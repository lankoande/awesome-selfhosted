import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CbConfirm, DemandeConfirmation } from '../../ui';
import { CaisseFactice } from '../caisse.factice';
import { CAISSE } from '../caisse.port';
import { ArreteDeCaisse } from './arrete-caisse.page';

class ConfirmEspion {
  demandes: DemandeConfirmation[] = [];
  reponse = true;
  async demander(demande: DemandeConfirmation): Promise<boolean> {
    this.demandes.push(demande);
    return this.reponse;
  }
}

async function calme(fixture: ComponentFixture<ArreteDeCaisse>): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

describe('arrêté de caisse', () => {
  let fixture: ComponentFixture<ArreteDeCaisse>;
  let confirm: ConfirmEspion;
  let caisse: CaisseFactice;

  function html(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }
  function bouton(libelle: string): HTMLButtonElement | undefined {
    return [...html().querySelectorAll('button')].find((b) => b.textContent?.trim().startsWith(libelle));
  }
  async function compter(coupure: string, nombre: string): Promise<void> {
    const champ = html().querySelector<HTMLInputElement>(`#cpt-${coupure}`)!;
    champ.value = nombre;
    champ.dispatchEvent(new Event('input'));
    await calme(fixture);
  }

  beforeEach(async () => {
    caisse = new CaisseFactice();
    caisse.latenceMs = 0;
    confirm = new ConfirmEspion();
    TestBed.configureTestingModule({
      providers: [
        { provide: CAISSE, useValue: caisse },
        { provide: CbConfirm, useValue: confirm },
      ],
    });
    fixture = TestBed.createComponent(ArreteDeCaisse);
    await calme(fixture);
  });

  it('rappelle que la journée ne se clôt pas sans arrêté', () => {
    expect(html().textContent).toContain('refuse de clore une journée');
  });

  it("n’arrête rien tant que rien n’est compté", () => {
    expect(bouton('Arrêter la caisse')?.disabled).toBe(true);
  });

  it("calcule l’écart au fur et à mesure, et le nomme", async () => {
    // Le solde théorique de la démonstration est de 4 317 500.
    await compter('b10000', '400'); // 4 000 000 : il manque 317 500
    expect(html().textContent).toContain('Manquant');

    await compter('b10000', '500'); // 5 000 000 : il y a 682 500 de trop
    expect(html().textContent).toContain('Excédent');
  });

  it("dit ce qu’un écart déclenche, avant de l’enregistrer", async () => {
    await compter('b10000', '400');
    bouton('Arrêter la caisse')!.click();
    await calme(fixture);

    expect(confirm.demandes).toHaveLength(1);
    expect(confirm.demandes[0]!.consequence).toContain("compte d'écart");
    expect(confirm.demandes[0]!.consequence).toContain('dernier moment pour recompter');
    expect(confirm.demandes[0]!.danger).toBe(true);
    expect(html().textContent).toContain('Caisse arrêtée, avec écart');
  });

  it("n’annonce pas d’écart quand le comptage tombe juste", async () => {
    await compter('b10000', '431');
    await compter('b5000', '1');
    await compter('b2000', '6');
    await compter('p500', '1'); // 4 310 000 + 5 000 + 2 000... ajusté ci-dessous
    // 431×10 000 = 4 310 000 ; +5 000 ; +12 000 ; +500 = 4 327 500 — on corrige :
    await compter('b2000', '1'); // 4 310 000 + 5 000 + 2 000 + 500 = 4 317 500
    expect(html().textContent).toContain('retrouve le solde théorique');

    bouton('Arrêter la caisse')!.click();
    await calme(fixture);
    expect(confirm.demandes[0]!.danger).toBeFalsy();
    expect(html().textContent).toContain('La journée peut être clôturée');
  });

  it("n’enregistre rien si la confirmation est refusée", async () => {
    confirm.reponse = false;
    await compter('b10000', '400');
    bouton('Arrêter la caisse')!.click();
    await calme(fixture);

    expect(html().textContent).not.toContain('Caisse arrêtée');
    expect(bouton('Arrêter la caisse')).toBeTruthy();
  });
});
