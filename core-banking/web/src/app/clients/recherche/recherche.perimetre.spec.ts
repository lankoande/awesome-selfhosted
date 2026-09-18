import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AUTHENTIFICATION } from '../../auth/auth.port';
import { AuthentificationDouble } from '../../auth/testing/auth-double';
import { CLIENTS } from '../clients.port';
import { ClientsDouble } from '../testing/clients-double';
import { RechercheClient } from './recherche.page';

async function calme(fixture: ComponentFixture<RechercheClient>): Promise<void> {
  for (let i = 0; i < 8; i++) await fixture.whenStable();
  fixture.detectChanges();
}

/**
 * Une recherche vide sous périmètre d'agence est le scénario qui crée les
 * doublons : l'agent conclut que le client n'existe pas, et le recrée.
 */
describe('clients — le périmètre explique une liste vide', () => {
  let socle: ClientsDouble;
  let session: AuthentificationDouble;
  let fixture: ComponentFixture<RechercheClient>;

  function html(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  async function chercher(): Promise<void> {
    fixture = TestBed.createComponent(RechercheClient);
    fixture.componentRef.setInput('q', 'introuvable');
    await calme(fixture);
  }

  beforeEach(() => {
    socle = new ClientsDouble();
    socle.resultats = [];
    session = new AuthentificationDouble();
    TestBed.configureTestingModule({
      providers: [provideRouter([{ path: '**', children: [] }]),
                  { provide: CLIENTS, useValue: socle },
                  { provide: AUTHENTIFICATION, useValue: session }],
    });
  });

  it('avertit du périmètre quand la lecture est bornée à l’agence', async () => {
    session.accorder('PARTY_READ', { portee: 'OWN_BRANCH' });
    await chercher();
    expect(html().textContent).toContain('ne voit que les clients de votre agence');
    expect(html().textContent).toContain('doublon');
  });

  it('ne dit rien quand la lecture porte sur tout l’établissement', async () => {
    session.accorder('PARTY_READ', { portee: 'OWN_ENTITY' });
    await chercher();
    expect(html().textContent).not.toContain('que les clients de');
  });

  it('ne dit rien tant qu’on ne connaît pas la portée', async () => {
    await chercher();
    expect(html().textContent).not.toContain('que les clients de');
  });
});
