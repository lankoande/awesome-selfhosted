import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AUTHENTIFICATION } from '../../auth/auth.port';
import { AuthentificationDouble } from '../../auth/testing/auth-double';
import { GUICHET } from '../guichet.port';
import { GuichetDouble } from '../testing/guichet-double';
import { Versement } from './versement.page';

async function calme(fixture: ComponentFixture<Versement>): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

/**
 * Le plafond s'annonce avant la saisie, et arrête la saisie au-delà.
 *
 * Bloquer ici ne peut pas refuser à tort : le plafond en agence est le plus
 * favorable des deux que la politique pose, et le socle tranche de toute façon.
 */
describe('guichet — le plafond du profil', () => {
  let socle: GuichetDouble;
  let session: AuthentificationDouble;
  let fixture: ComponentFixture<Versement>;

  function html(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }
  function texte(): string {
    return (html().textContent ?? '').replace(/[\s  ]/g, '');
  }

  async function monter(): Promise<void> {
    fixture = TestBed.createComponent(Versement);
    await calme(fixture);
  }

  beforeEach(() => {
    socle = new GuichetDouble();
    session = new AuthentificationDouble();
    TestBed.configureTestingModule({
      providers: [{ provide: GUICHET, useValue: socle },
                  { provide: AUTHENTIFICATION, useValue: session }],
    });
  });

  it('annonce le plafond sous le champ, avant toute saisie', async () => {
    session.plafonner('CASH_OPERATION', 'XOF', '2000000', '500000');
    await monter();
    expect(texte()).toContain('plafonddevotreprofil:2000000');
  });

  it('annonce aussi le plafond déplacé quand il diffère', async () => {
    session.plafonner('CASH_OPERATION', 'XOF', '2000000', '500000');
    await monter();
    expect(texte()).toContain('500000horsdevotreagence');
  });

  it('ne dit rien quand aucun plafond ne s’applique', async () => {
    session.autoriser('CASH_OPERATION');
    await monter();
    expect(texte()).not.toContain('plafonddevotreprofil');
  });

  it('ne dit rien tant que les habilitations sont inconnues', async () => {
    await monter();
    expect(texte()).not.toContain('plafonddevotreprofil');
  });
});
