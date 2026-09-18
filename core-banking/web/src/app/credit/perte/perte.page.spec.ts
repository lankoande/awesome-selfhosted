import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CREDIT } from '../credit.port';
import { CONTRAT_DOUBLE, CONTRAT_ID, CreditDouble } from '../testing/credit-double';
import { PerteCredit } from './perte.page';

async function calme(fixture: ComponentFixture<PerteCredit>): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

const xof = (v: number) => ({ amount: String(v), currency: 'XOF' });

const PERTE = {
  id: 'pe-1', contractReference: 'PR-2023-0117', writtenOffOn: '2026-08-31',
  principalWritten: xof(1450000), receivablesWritten: xof(212800),
  reservedUsed: xof(98400), provisionUsed: xof(1330720), provisionReleased: xof(0),
  lossRecognised: xof(233680), recovered: xof(120000),
  reason: 'Débiteur introuvable.', bucketCode: 'PERTE', daysPastDue: 421,
};

describe('crédit — passage en perte', () => {
  let socle: CreditDouble;
  let fixture: ComponentFixture<PerteCredit>;

  function html(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }
  function bouton(libelle: string): HTMLButtonElement | undefined {
    return [...html().querySelectorAll('button')].find((b) => b.textContent?.includes(libelle));
  }
  function chiffres(): string {
    return (html().textContent ?? '').replace(/[\s  ]/g, '');
  }

  async function monter(): Promise<void> {
    fixture = TestBed.createComponent(PerteCredit);
    fixture.componentRef.setInput('id', CONTRAT_ID);
    await calme(fixture);
  }

  beforeEach(() => {
    socle = new CreditDouble();
    TestBed.configureTestingModule({ providers: [{ provide: CREDIT, useValue: socle }] });
  });

  it('montre la décomposition, pas un total : la perte n’est pas l’exposition', async () => {
    socle.contratRendu = { ...CONTRAT_DOUBLE, status: 'WRITTEN_OFF' };
    socle.perteRendue = { perte: PERTE, recouvrements: [] };
    await monter();

    const texte = html().textContent ?? '';
    expect(texte).toContain('Absorbée par les intérêts réservés');
    expect(texte).toContain('Absorbée par la provision');
    expect(texte).toContain('Perte constatée au résultat');
    // 1 450 000 + 212 800 = 1 662 800 d'exposition, pour 233 680 de perte.
    expect(chiffres()).toContain('1662800');
    expect(chiffres()).toContain('233680');
  });

  it("dit pourquoi les intérêts réservés ne se passent pas en perte deux fois", async () => {
    socle.contratRendu = { ...CONTRAT_DOUBLE, status: 'WRITTEN_OFF' };
    socle.perteRendue = { perte: PERTE, recouvrements: [] };
    await monter();

    expect(html().textContent).toContain('sortis du résultat à la suspension');
    expect(html().textContent).toContain('produit jamais pris');
  });

  it('dit que la créance reste due, et ce qui reste à recouvrer', async () => {
    socle.contratRendu = { ...CONTRAT_DOUBLE, status: 'WRITTEN_OFF' };
    socle.perteRendue = { perte: PERTE, recouvrements: [] };
    await monter();

    expect(html().textContent).toContain('La créance reste due');
    expect(html().textContent).toContain('hors bilan');
    // 1 450 000 + 212 800 - 120 000 = 1 542 800.
    expect(chiffres()).toContain('1542800');
  });

  it("annonce le second regard et ne sort rien des livres à l'envoi", async () => {
    await monter();
    expect(html().textContent).toContain("Ce contrat n'est pas passé en perte");

    bouton('Demander le passage en perte')!.click();
    await calme(fixture);
    const motif = html().querySelector<HTMLInputElement>('#motif-perte')!;
    motif.value = 'Créance irrécouvrable.';
    motif.dispatchEvent(new Event('input'));
    await calme(fixture);
    bouton('Soumettre le passage en perte')!.click();
    await calme(fixture);

    expect(html().textContent).toContain("rien n'est sorti des livres");
    expect(html().textContent).toContain('PND-000901');
    expect(socle.pertes[0].reason).toBe('Créance irrécouvrable.');
  });

  it("ne propose pas de passer en perte un contrat qui n'est pas en cours", async () => {
    socle.contratRendu = { ...CONTRAT_DOUBLE, status: 'CLOSED' };
    await monter();

    expect(bouton('Demander le passage en perte')).toBeUndefined();
    expect(html().textContent).toContain("Seul un contrat en cours se passe en perte");
  });

  it("enregistre un recouvrement sans second regard : l'argent est déjà rentré", async () => {
    socle.contratRendu = { ...CONTRAT_DOUBLE, status: 'WRITTEN_OFF' };
    socle.perteRendue = {
      perte: PERTE,
      recouvrements: [{ id: 'rc-1', recoveredOn: '2026-09-05', amount: xof(80000) }],
    };
    await monter();

    expect(html().textContent).toContain('Recouvrements');
    bouton('Enregistrer un recouvrement')!.click();
    await calme(fixture);

    const montant = html().querySelector<HTMLInputElement>('#montant-recouvre')!;
    montant.value = '40000';
    montant.dispatchEvent(new Event('input'));
    const compte = html().querySelector<HTMLInputElement>('#compte-encaissement')!;
    compte.value = 'CAISSE-OUA2';
    compte.dispatchEvent(new Event('input'));
    await calme(fixture);
    bouton('Enregistrer le recouvrement')!.click();
    await calme(fixture);

    expect(socle.recouvrements[0].amount).toBe('40000');
    expect(socle.recouvrements[0].channelAccountId).toBe('CAISSE-OUA2');
    // Aucune opération en attente : ce n'est pas un acte à deux.
    expect(html().textContent).not.toContain('en attente de validation');
  });
});
