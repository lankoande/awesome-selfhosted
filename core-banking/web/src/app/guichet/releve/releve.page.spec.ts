import { ComponentFixture, TestBed } from '@angular/core/testing';
import { GUICHET } from '../guichet.port';
import { LigneReleve } from '../modele/guichet.modele';
import { GuichetDouble, montantDouble } from '../testing/guichet-double';
import { Releve } from './releve.page';

function ligne(n: number, partiel: Partial<LigneReleve> = {}): LigneReleve {
  return {
    entryId: `ecr-${n}`, entryNumber: n, lineNumber: 1, accountCode: 'BF12',
    bookingDate: '2026-09-15', valueDate: '2026-09-15', knowledgeTime: '2026-09-15T09:00:00Z',
    direction: 'CREDIT', amount: montantDouble(100000), label: 'Versement', narrative: null,
    transactionType: 'CASH_DEPOSIT', reversalOf: null, ...partiel,
  };
}

async function calme(fixture: ComponentFixture<Releve>): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

describe('relevé de compte', () => {
  let espion: GuichetDouble;
  let fixture: ComponentFixture<Releve>;

  function html(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  async function poser(lignes: readonly LigneReleve[]): Promise<void> {
    espion.releveRendu = { lignes, numero: 0, taille: 50, precedent: false, suivant: false };
    TestBed.configureTestingModule({ providers: [{ provide: GUICHET, useValue: espion }] });
    fixture = TestBed.createComponent(Releve);
    await calme(fixture);
  }

  beforeEach(() => {
    espion = new GuichetDouble();
  });

  it('montre la contre-passation ET l’écriture contre-passée', async () => {
    // Une contre-passation ne remplace pas : elle s'ajoute. Cacher l'origine
    // ferait disparaître un mouvement qui a bien eu lieu.
    await poser([
      ligne(4104, { direction: 'DEBIT', amount: montantDouble(600000), label: 'Contre-passation', reversalOf: 'ecr-4102' }),
      ligne(4102, { direction: 'CREDIT', amount: montantDouble(600000), label: 'Virement reçu' }),
    ]);

    const lignes = [...html().querySelectorAll('tbody tr')];
    expect(lignes).toHaveLength(2);
    expect(lignes[0]!.textContent).toContain('annule la pièce');
    expect(lignes[1]!.textContent).toContain('Contre-passé');
  });

  it("ne marque contre-passée que ce que la page montre", async () => {
    // L'annulante n'est pas sur la page : on n'affirme pas que l'écriture est
    // intacte, on ne marque simplement rien.
    await poser([ligne(4102, { label: 'Virement reçu' })]);
    expect(html().querySelector('tbody tr')!.textContent).not.toContain('Contre-passé');
  });

  it("signale une écriture passée après le jour qu’elle affecte", async () => {
    await poser([ligne(4105, { bookingDate: '2026-09-14', knowledgeTime: '2026-09-16T16:40:00Z' })]);
    expect(html().textContent).toContain('après le jour qu');
  });

  it('ne signale rien quand la date de connaissance est celle du jour comptable', async () => {
    await poser([ligne(4108, { bookingDate: '2026-09-15', knowledgeTime: '2026-09-15T11:00:00Z' })]);
    expect(html().textContent).not.toContain('après le jour qu');
  });

  it("totalise la page, et dit que ce n’est pas un solde", async () => {
    await poser([
      ligne(1, { direction: 'DEBIT', amount: montantDouble(1170) }),
      ligne(2, { direction: 'CREDIT', amount: montantDouble(2500000) }),
      ligne(3, { direction: 'CREDIT', amount: montantDouble(450000) }),
    ]);

    const pied = html().querySelector('tfoot')!.textContent ?? '';
    expect(pied).toContain("ce n'est pas un solde");
    expect(pied.replace(/\s/g, '')).toContain('2950000');
    expect(pied.replace(/\s/g, '')).toContain('1170');
  });

  it('annonce que la consultation est tracée', async () => {
    await poser([]);
    expect(html().textContent).toContain("journal d'audit");
  });
});
