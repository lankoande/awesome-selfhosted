import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FiltreBalance, PageBalance, TotauxBalance } from '../modele/siege.modele';
import { SiegeFactice } from '../siege.factice';
import { SIEGE, Siege } from '../siege.port';
import { SiegeDouble } from '../testing/siege-double';
import { Balance } from './balance.page';

async function calme(fixture: ComponentFixture<Balance>): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

/** Un socle dont on force le déséquilibre : c'est le cas qu'on ne peut pas provoquer autrement. */
class SiegeDesequilibre extends SiegeDouble {
  private readonly vrai = new SiegeFactice();

  constructor() {
    super();
    this.vrai.latenceMs = 0;
  }

  override balance(e: string, f: FiltreBalance, p: number, t: number): Promise<PageBalance> {
    return this.vrai.balance(e, f, p, t);
  }

  override async totauxBalance(): Promise<readonly TotauxBalance[]> {
    const argent = (v: number) => ({ amount: String(v), currency: 'XOF' });
    return [{
      currency: 'XOF', accounts: 15, balanced: false,
      openingDebit: argent(424330000), openingCredit: argent(424330000),
      movementDebit: argent(71347000), movementCredit: argent(71297000),
      closingDebit: argent(495677000), closingCredit: argent(495627000),
    }];
  }
}

describe('siège — balance générale', () => {
  function html(fixture: ComponentFixture<Balance>): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  async function monter(siege: Siege): Promise<ComponentFixture<Balance>> {
    TestBed.configureTestingModule({ providers: [{ provide: SIEGE, useValue: siege }] });
    const fixture = TestBed.createComponent(Balance);
    await calme(fixture);
    return fixture;
  }

  it("annonce l’équilibre avant les chiffres", async () => {
    const source = new SiegeFactice();
    source.latenceMs = 0;
    const fixture = await monter(source);

    expect(html(fixture).textContent).toContain('Balance équilibrée');
    expect(html(fixture).textContent).not.toContain("ne s'équilibre pas");
  });

  it("traite un déséquilibre comme un refus, pas comme une remarque", async () => {
    const fixture = await monter(new SiegeDesequilibre());
    const texte = html(fixture).textContent ?? '';

    expect(texte).toContain("La balance ne s'équilibre pas");
    // Les deux colonnes sont données : c'est une écriture à retrouver.
    expect(texte.replace(/\s/g, '')).toContain('495677000');
    expect(texte.replace(/\s/g, '')).toContain('495627000');
    expect(texte).toContain("c'est une écriture à retrouver");
  });

  it('affiche les totaux par devise, jamais une somme entre devises', async () => {
    const source = new SiegeFactice();
    source.latenceMs = 0;
    const fixture = await monter(source);

    const sections = [...html(fixture).querySelectorAll('cb-section')]
      .map((s) => s.textContent ?? '')
      .filter((t) => t.includes('Totaux'));
    expect(sections).toHaveLength(1);
    expect(sections[0]).toContain('XOF');
  });

  it('montre les comptes avec leur nature et leur rubrique', async () => {
    const source = new SiegeFactice();
    source.latenceMs = 0;
    const fixture = await monter(source);
    const texte = html(fixture).textContent ?? '';

    expect(texte).toContain('Clientèle');
    expect(texte).toContain('Hors bilan');
    expect(texte).toContain('Suspens');
  });
});
