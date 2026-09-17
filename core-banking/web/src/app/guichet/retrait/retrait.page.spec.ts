import { ComponentFixture, TestBed } from '@angular/core/testing';
import { GUICHET } from '../guichet.port';
import { RefusMetier } from '../modele/guichet.modele';
import { GuichetDouble } from '../testing/guichet-double';
import { Retrait } from './retrait.page';



async function calme(fixture: ComponentFixture<Retrait>): Promise<void> {
  for (let i = 0; i < 6; i++) await fixture.whenStable();
  fixture.detectChanges();
}

describe("guichet — retrait d'espèces", () => {
  let espion: GuichetDouble;
  let fixture: ComponentFixture<Retrait>;

  function html(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }
  function bouton(libelle: string): HTMLButtonElement | undefined {
    return [...html().querySelectorAll('button')].find((b) => b.textContent?.includes(libelle));
  }
  function saisir(selecteur: string, valeur: string): void {
    const champ = html().querySelector<HTMLInputElement>(selecteur)!;
    champ.value = valeur;
    champ.dispatchEvent(new Event('input'));
  }

  beforeEach(async () => {
    espion = new GuichetDouble();
    TestBed.configureTestingModule({ providers: [{ provide: GUICHET, useValue: espion }] });
    fixture = TestBed.createComponent(Retrait);
    await calme(fixture);
  });

  it("explique l’écart entre le solde et le disponible plutôt que de le laisser deviner", () => {
    expect(html().textContent).toContain('Une part du solde est retenue');
    expect(html().textContent).toContain("C'est le disponible qui commande");
  });

  it("bloque un montant supérieur au disponible, sans faire l’aller-retour", async () => {
    saisir('#r-montant', '1 200 000'); // couvert par le solde, pas par le disponible
    await calme(fixture);

    expect(html().textContent).toContain('Le montant dépasse le disponible');
    expect(bouton('Comptabiliser')?.disabled).toBe(true);
    expect(espion.especes).toHaveLength(0);

    saisir('#r-montant', '1 000 000');
    await calme(fixture);
    expect(bouton('Comptabiliser')?.disabled).toBe(false);
  });

  it("laisse le socle trancher au bord : les frais peuvent encore faire basculer", async () => {
    // Sous le disponible, le poste envoie — c'est le socle qui connaît le barème.
    espion.issue = async () => {
      throw new RefusMetier(422, 'PROVISION_INSUFFISANTE', 'Le disponible ne couvre pas le retrait.',
        'Disponible 1 190 500 XOF, débit demandé 1 191 670 XOF (dont 1 170 XOF de frais et taxe).');
    };
    saisir('#r-montant', '1 190 500');
    await calme(fixture);
    bouton('Comptabiliser')!.click();
    await calme(fixture);

    expect(espion.especes).toHaveLength(1);
    expect(html().textContent).toContain('PROVISION_INSUFFISANTE');
    expect(html().textContent).toContain('de frais et taxe');
  });

  it("exige l’identité d’un mandataire, et la porte au libellé", async () => {
    saisir('#r-montant', '50 000');
    const porteur = html().querySelector<HTMLSelectElement>('#r-porteur')!;
    porteur.value = 'mandataire';
    porteur.dispatchEvent(new Event('change'));
    await calme(fixture);

    expect(bouton('Comptabiliser')?.disabled).toBe(true);
    saisir('#r-identite', 'TRAORE Moussa · CNIB B1129044');
    await calme(fixture);

    bouton('Comptabiliser')!.click();
    await calme(fixture);
    expect(espion.especes[0]!.narrative).toContain('TRAORE Moussa');
  });

  it("dit de ne pas remettre les espèces tant que l’opération attend un second regard", async () => {
    espion.issue = async () => ({ genre: 'en-attente', operationId: 'PND-9', attenduDe: 'un second agent habilité' });
    saisir('#r-montant', '50 000');
    await calme(fixture);
    bouton('Comptabiliser')!.click();
    await calme(fixture);

    expect(html().textContent).toContain('Ne remettez pas les');
    expect(html().textContent).toContain("Rien n'est comptabilisé");
  });

  it("impute au débit du client et au crédit de la caisse — l’inverse du versement", async () => {
    saisir('#r-montant', '50 000');
    await calme(fixture);

    const lignes = [...html().querySelectorAll('cb-imputation .ligne')].map((l) => l.textContent ?? '');
    expect(lignes[0]).toContain('SANKARA Aminata');
    expect(lignes[1]).toContain("Caisse de l'agence");
  });
});
