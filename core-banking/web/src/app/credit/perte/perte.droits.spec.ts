import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AUTHENTIFICATION } from '../../auth/auth.port';
import { AuthentificationDouble } from '../../auth/testing/auth-double';
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

/**
 * Deux actes, deux droits, un écran — le cas que l'habilitation « par écran »
 * rate, et celui qu'on vérifie ici.
 */
describe('crédit — passage en perte et recouvrement : deux droits', () => {
  let socle: CreditDouble;
  let session: AuthentificationDouble;
  let fixture: ComponentFixture<PerteCredit>;

  function html(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }
  function bouton(libelle: string): HTMLButtonElement | undefined {
    return [...html().querySelectorAll('button')].find((b) => b.textContent?.includes(libelle));
  }

  async function monter(): Promise<void> {
    fixture = TestBed.createComponent(PerteCredit);
    fixture.componentRef.setInput('id', CONTRAT_ID);
    await calme(fixture);
  }

  beforeEach(() => {
    socle = new CreditDouble();
    session = new AuthentificationDouble();
    TestBed.configureTestingModule({
      providers: [provideRouter([{ path: '**', children: [] }]),
                  { provide: CREDIT, useValue: socle },
                  { provide: AUTHENTIFICATION, useValue: session }],
    });
  });

  it('propose les deux actes à qui porte les deux droits', async () => {
    session.autoriser('LOAN_WRITE_OFF', 'LOAN_RECOVERY');
    socle.contratRendu = { ...CONTRAT_DOUBLE, status: 'WRITTEN_OFF' };
    socle.perteRendue = { perte: PERTE, recouvrements: [] };
    await monter();
    expect(bouton('Enregistrer un recouvrement')).toBeDefined();
  });

  it('ferme le recouvrement à qui ne l’a pas, et dit à qui il appartient', async () => {
    session.autoriser('LOAN_WRITE_OFF');
    socle.contratRendu = { ...CONTRAT_DOUBLE, status: 'WRITTEN_OFF' };
    socle.perteRendue = { perte: PERTE, recouvrements: [] };
    await monter();
    expect(bouton('Enregistrer un recouvrement')).toBeUndefined();
    expect(html().textContent).toContain('appartient au');
  });

  it('ferme le passage en perte à qui ne l’a pas, sans cacher l’écran', async () => {
    session.autoriser('LOAN_RECOVERY', 'LOAN_READ');
    socle.contratRendu = { ...CONTRAT_DOUBLE, status: 'ACTIVE' };
    socle.perteRendue = { perte: null, recouvrements: [] };
    await monter();
    expect(bouton('Demander le passage en perte')).toBeUndefined();
    // L'écran reste lisible : on explique, on ne fait pas disparaître.
    expect(html().textContent).toContain('ne sort pas un actif des livres');
  });

  it('propose tout tant que les habilitations sont inconnues', async () => {
    socle.contratRendu = { ...CONTRAT_DOUBLE, status: 'ACTIVE' };
    socle.perteRendue = { perte: null, recouvrements: [] };
    await monter();
    expect(bouton('Demander le passage en perte')).toBeDefined();
  });
});
