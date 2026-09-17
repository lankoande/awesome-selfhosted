import { ComponentFixture, TestBed } from '@angular/core/testing';
import { GUICHET, Guichet } from '../guichet.port';
import {
  ContexteCompte, DemandeEspeces, IssueVersement, Recu, RefusMetier, SoldeCompte,
} from '../modele/guichet.modele';
import { Retrait } from './retrait.page';

const COMPTE = '11111111-1111-4111-8111-000000000417';
const montant = (v: number) => ({ amount: String(v), currency: 'XOF' });

const RECU: Recu = {
  entryId: 'e-1', entryNumber: 5001, bookingDate: '2026-09-17', valueDate: '2026-09-17',
  amount: montant(100000), fee: montant(1000), tax: montant(170), balanceAfter: montant(1139330),
  branchId: 'OUA2', remote: false, replayed: false,
};

/** Un compte avec un blocage : c'est là que solde et disponible divergent. */
class GuichetEspion implements Guichet {
  readonly demandes: DemandeEspeces[] = [];
  issue: () => Promise<IssueVersement> = async () => ({ genre: 'comptabilise', recu: RECU });

  async catalogue() {
    return [{ accountId: COMPTE, code: 'BF12001025100000000417', intitule: 'SANKARA Aminata', pourquoi: '' }];
  }
  async soldes(): Promise<SoldeCompte> {
    return {
      accountId: COMPTE, code: 'BF12001025100000000417', currency: 'XOF',
      current: montant(1240500), available: montant(1190500),
      asOf: '2026-09-17', status: 'ACTIVE', branchId: 'OUA2',
    };
  }
  async contexte(): Promise<ContexteCompte> {
    return {
      intitule: 'SANKARA Aminata', partyId: 'CL-0004217', reference: 'BF12001025100000000417',
      nature: 'Particulier', produit: 'Compte chèque particulier', ouvertLe: '2019-06-14',
      kyc: { etat: 'À jour', revuLe: '2026-03-12' }, blocages: [], lacunes: [],
    };
  }
  async verser(demande: DemandeEspeces): Promise<IssueVersement> {
    return this.retirer(demande);
  }
  async retirer(demande: DemandeEspeces): Promise<IssueVersement> {
    this.demandes.push(demande);
    return this.issue();
  }
}

async function calme(fixture: ComponentFixture<Retrait>): Promise<void> {
  for (let i = 0; i < 6; i++) await fixture.whenStable();
  fixture.detectChanges();
}

describe("guichet — retrait d'espèces", () => {
  let espion: GuichetEspion;
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
    espion = new GuichetEspion();
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
    expect(espion.demandes).toHaveLength(0);

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

    expect(espion.demandes).toHaveLength(1);
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
    expect(espion.demandes[0]!.narrative).toContain('TRAORE Moussa');
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
