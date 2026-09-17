import { ComponentFixture, TestBed } from '@angular/core/testing';
import { GUICHET } from '../guichet.port';
import { RefusMetier } from '../modele/guichet.modele';
import { GuichetDouble, montantDouble } from '../testing/guichet-double';
import { Virement } from './virement.page';

const A = 'compte-a';
const B = 'compte-b';
const EUR = 'compte-eur';

async function calme(fixture: ComponentFixture<Virement>): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

describe('guichet — virement interne', () => {
  let espion: GuichetDouble;
  let fixture: ComponentFixture<Virement>;

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
  async function choisir(selecteur: string, valeur: string): Promise<void> {
    const liste = html().querySelector<HTMLSelectElement>(selecteur)!;
    liste.value = valeur;
    liste.dispatchEvent(new Event('change'));
    await calme(fixture);
  }

  beforeEach(async () => {
    espion = new GuichetDouble();
    espion.comptes = [
      { accountId: A, code: 'BF1200102510000000A', intitule: 'SANKARA Aminata', pourquoi: '' },
      { accountId: B, code: 'BF1200102510000000B', intitule: 'ETS KABORE & Fils', pourquoi: '' },
      { accountId: EUR, code: 'BF1200102510000EUR', intitule: 'Compte en euros', pourquoi: '' },
    ];
    espion.soldesParCompte.set(A, {
      accountId: A, code: 'BF1200102510000000A', currency: 'XOF',
      current: montantDouble(1240500), available: montantDouble(1190500),
      asOf: '2026-09-17', status: 'ACTIVE', branchId: 'OUA2',
    });
    espion.soldesParCompte.set(B, {
      accountId: B, code: 'BF1200102510000000B', currency: 'XOF',
      current: montantDouble(50000), available: montantDouble(50000),
      asOf: '2026-09-17', status: 'ACTIVE', branchId: 'OUA2',
    });
    espion.soldesParCompte.set(EUR, {
      accountId: EUR, code: 'BF1200102510000EUR', currency: 'EUR',
      current: montantDouble(1000), available: montantDouble(1000),
      asOf: '2026-09-17', status: 'ACTIVE', branchId: 'OUA2',
    });
    TestBed.configureTestingModule({ providers: [{ provide: GUICHET, useValue: espion }] });
    fixture = TestBed.createComponent(Virement);
    await calme(fixture);
  });

  it('refuse de virer un compte vers lui-même', async () => {
    await choisir('#v-destination', A);
    saisir('#v-montant', '10 000');
    await calme(fixture);

    expect(html().textContent).toContain('le même compte');
    expect(bouton('Comptabiliser')?.disabled).toBe(true);
    expect(espion.virements).toHaveLength(0);
  });

  it('refuse deux devises différentes : un virement ne fait pas le change', async () => {
    await choisir('#v-destination', EUR);
    saisir('#v-montant', '10 000');
    await calme(fixture);

    expect(html().textContent).toContain('ne fait pas le change');
    expect(bouton('Comptabiliser')?.disabled).toBe(true);
  });

  it('bute sur le disponible du débiteur, pas sur son solde comptable', async () => {
    saisir('#v-montant', '1 200 000'); // couvert par le solde, pas par le disponible
    await calme(fixture);

    expect(html().textContent).toContain('dépasse le disponible du débiteur');
    expect(bouton('Comptabiliser')?.disabled).toBe(true);
  });

  it("n’envoie qu’une demande : une seule écriture, deux comptes", async () => {
    saisir('#v-montant', '100 000');
    await calme(fixture);
    bouton('Comptabiliser')!.click();
    await calme(fixture);

    expect(espion.virements).toHaveLength(1);
    expect(espion.virements[0]!.sourceAccountId).toBe(A);
    expect(espion.virements[0]!.destinationAccountId).toBe(B);
    expect(html().textContent).toContain('Une seule écriture a débité');
  });

  it('impute au débit du donneur d’ordre et au crédit du bénéficiaire', async () => {
    saisir('#v-montant', '100 000');
    await calme(fixture);

    const lignes = [...html().querySelectorAll('cb-imputation .ligne')].map((l) => l.textContent ?? '');
    expect(lignes[0]).toContain('SANKARA Aminata');
    expect(lignes[1]).toContain('ETS KABORE & Fils');
  });

  it("dit de ne pas annoncer le virement tant qu’il attend un second regard", async () => {
    espion.issue = async () => ({ genre: 'en-attente', operationId: 'PND-3', attenduDe: 'un second agent habilité' });
    saisir('#v-montant', '100 000');
    await calme(fixture);
    bouton('Comptabiliser')!.click();
    await calme(fixture);

    expect(html().textContent).toContain("N'annoncez pas le virement");
  });

  it('affiche le refus du socle quand il tranche au bord', async () => {
    espion.issue = async () => {
      throw new RefusMetier(422, 'PROVISION_INSUFFISANTE', 'Le disponible du débiteur ne couvre pas le virement.',
        'Disponible 1 190 500 XOF, débit demandé 1 191 670 XOF.');
    };
    saisir('#v-montant', '1 190 500');
    await calme(fixture);
    bouton('Comptabiliser')!.click();
    await calme(fixture);

    expect(html().textContent).toContain('PROVISION_INSUFFISANTE');
  });
});
