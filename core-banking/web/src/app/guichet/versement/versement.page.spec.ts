import { TestBed } from '@angular/core/testing';
import { ComponentFixture } from '@angular/core/testing';
import { GUICHET, Guichet } from '../guichet.port';
import {
  ContexteCompte,
  DemandeVersement,
  IssueVersement,
  Recu,
  RefusMetier,
  SoldeCompte,
} from '../modele/guichet.modele';
import { Versement } from './versement.page';

const COMPTE = '11111111-1111-4111-8111-000000000417';

function montant(valeur: number) {
  return { amount: String(valeur), currency: 'XOF' };
}

const RECU: Recu = {
  entryId: 'e-1',
  entryNumber: 4128,
  bookingDate: '2026-09-17',
  valueDate: '2026-09-17',
  amount: montant(100000),
  fee: montant(1000),
  tax: montant(170),
  balanceAfter: montant(1339330),
  branchId: 'OUA2',
  remote: false,
  replayed: false,
};

/** Un socle sous contrôle : chaque test décide de l'issue et relit la demande reçue. */
class GuichetEspion implements Guichet {
  readonly demandes: DemandeVersement[] = [];
  issue: (demande: DemandeVersement) => Promise<IssueVersement> = async () => ({
    genre: 'comptabilise',
    recu: RECU,
  });

  async catalogue() {
    return [{ accountId: COMPTE, code: 'BF12001025100000000417', intitule: 'SANKARA Aminata', pourquoi: '' }];
  }

  async soldes(): Promise<SoldeCompte> {
    return {
      accountId: COMPTE,
      code: 'BF12001025100000000417',
      currency: 'XOF',
      current: montant(1240500),
      available: montant(1190500),
      asOf: '2026-09-17',
      status: 'ACTIVE',
      branchId: 'OUA2',
    };
  }

  async contexte(): Promise<ContexteCompte> {
    return {
      intitule: 'SANKARA Aminata',
      partyId: 'CL-0004217',
      reference: 'BF12001025100000000417',
      nature: 'Particulier',
      produit: 'Compte chèque particulier',
      ouvertLe: '2019-06-14',
      kyc: { etat: 'À jour', revuLe: '2026-03-12' },
      blocages: [],
      lacunes: [],
    };
  }

  async verser(demande: DemandeVersement): Promise<IssueVersement> {
    this.demandes.push(demande);
    return this.issue(demande);
  }
}

async function calme(fixture: ComponentFixture<Versement>): Promise<void> {
  for (let i = 0; i < 6; i++) await fixture.whenStable();
  fixture.detectChanges();
}

describe("guichet — versement d'espèces", () => {
  let espion: GuichetEspion;
  let fixture: ComponentFixture<Versement>;

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
    fixture = TestBed.createComponent(Versement);
    await calme(fixture);
  });

  it("n'envoie rien tant que le comptage ne retrouve pas le montant annoncé", async () => {
    saisir('#v-montant', '100000');
    saisir('#cpt-b10000', '9'); // 90 000 comptés pour 100 000 annoncés
    await calme(fixture);

    expect(bouton('Comptabiliser')?.disabled).toBe(true);
    expect(html().textContent).toContain('Le comptage ne retrouve pas le montant annoncé');

    saisir('#cpt-b10000', '10');
    await calme(fixture);
    expect(bouton('Comptabiliser')?.disabled).toBe(false);
  });

  it("exige l’identité du remettant quand le versement vient d’un tiers", async () => {
    saisir('#v-montant', '100000');
    const remettant = html().querySelector<HTMLSelectElement>('#v-remettant')!;
    remettant.value = 'tiers';
    remettant.dispatchEvent(new Event('change'));
    await calme(fixture);

    expect(bouton('Comptabiliser')?.disabled).toBe(true);
    expect(html().textContent).toContain("exige l'identité du remettant");

    saisir('#v-identite', 'OUEDRAOGO Salif · CNIB B0483921');
    await calme(fixture);
    expect(bouton('Comptabiliser')?.disabled).toBe(false);

    bouton('Comptabiliser')!.click();
    await calme(fixture);
    // L'identité part au libellé de l'écriture : c'est une exigence LCB-FT.
    expect(espion.demandes[0]!.narrative).toContain('OUEDRAOGO Salif');
  });

  it('affiche le refus du socle avec son code, et propose le rejeu quand il est rejouable', async () => {
    espion.issue = async () => {
      throw new RefusMetier(0, 'RESEAU_INDISPONIBLE', 'Le socle est injoignable.', 'La saisie est intacte.');
    };
    saisir('#v-montant', '100000');
    await calme(fixture);
    bouton('Comptabiliser')!.click();
    await calme(fixture);

    expect(html().textContent).toContain('RESEAU_INDISPONIBLE');
    expect(bouton('Réessayer avec la même clé')).toBeTruthy();
  });

  it('rejoue la même clé, et la renouvelle dès que la saisie change', async () => {
    espion.issue = async () => {
      throw new RefusMetier(0, 'RESEAU_INDISPONIBLE', 'Le socle est injoignable.');
    };
    saisir('#v-montant', '100000');
    await calme(fixture);
    bouton('Comptabiliser')!.click();
    await calme(fixture);

    bouton('Réessayer avec la même clé')!.click();
    await calme(fixture);

    expect(espion.demandes).toHaveLength(2);
    expect(espion.demandes[1]!.cleIdempotence).toBe(espion.demandes[0]!.cleIdempotence);
    expect(html().textContent).toContain('conservée');

    // La clé couvre UNE demande : si le montant change, la clé doit changer,
    // sinon le socle rejouerait le premier reçu pour une autre opération.
    saisir('#v-montant', '250000');
    await calme(fixture);
    expect(html().textContent).toContain('renouvelée');

    espion.issue = async () => ({ genre: 'comptabilise', recu: RECU });
    bouton('Comptabiliser')!.click();
    await calme(fixture);
    expect(espion.demandes[2]!.cleIdempotence).not.toBe(espion.demandes[0]!.cleIdempotence);
  });

  it("annonce un rejeu comme un rejeu, jamais comme une nouvelle comptabilisation", async () => {
    espion.issue = async () => ({ genre: 'comptabilise', recu: { ...RECU, replayed: true } });
    saisir('#v-montant', '100000');
    await calme(fixture);
    bouton('Comptabiliser')!.click();
    await calme(fixture);

    expect(html().textContent).toContain('Déjà comptabilisé');
    expect(html().textContent).not.toContain("L'écriture est passée");
  });

  it("dit qu’une opération en attente n’est pas comptabilisée", async () => {
    espion.issue = async () => ({ genre: 'en-attente', operationId: 'PND-000042', attenduDe: 'un second agent habilité' });
    saisir('#v-montant', '100000');
    await calme(fixture);
    bouton('Comptabiliser')!.click();
    await calme(fixture);

    expect(html().textContent).toContain('En attente de validation');
    expect(html().textContent).toContain("Rien n'est comptabilisé");
    expect(html().textContent).toContain('PND-000042');
  });

  it("ne montre les frais, la taxe et la date de valeur qu’une fois le socle passé", async () => {
    saisir('#v-montant', '100000');
    await calme(fixture);
    // En projection : aucune commission affichée, et la règle est dite.
    expect(html().textContent).toContain('calculés par le socle à la comptabilisation');
    expect(html().textContent).not.toContain('dont commission');

    bouton('Comptabiliser')!.click();
    await calme(fixture);
    expect(html().textContent).toContain('dont commission');
    expect(html().textContent).toContain('dont taxe sur activités financières');
  });
});
