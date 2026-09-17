import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AUTHENTIFICATION, Authentification, EtatSession, Habilitations, Porteur } from './auth.port';
import { firstValueFrom } from 'rxjs';
import { jetonInterceptor } from './jeton.interceptor';

const API = '/v1';

class AuthEspion implements Authentification {
  etat: EtatSession = 'ouverte';
  jetons = ['premier', 'second'];
  rafraichissements = 0;
  rafraichissementPossible = true;

  async reprendre(): Promise<EtatSession> { return this.etat; }
  async ouvrir(): Promise<void> {}
  async retour(): Promise<EtatSession> { return this.etat; }
  jeton(): string | null { return this.jetons[0] ?? null; }
  async rafraichir(): Promise<boolean> {
    this.rafraichissements++;
    if (!this.rafraichissementPossible) return false;
    this.jetons.shift();
    return true;
  }
  porteur(): Porteur | null { return null; }
  habilitations(): Habilitations { return { connues: false, operations: new Set() }; }
  verrouiller(): void {}
  async deverrouiller(): Promise<boolean> { return true; }
  async fermer(): Promise<void> {}
}

/** Le rejeu passe par une promesse : il faut laisser tourner la micro-file. */
const tick = () => new Promise((r) => setTimeout(r, 0));

describe('intercepteur de jeton', () => {
  let http: HttpClient;
  let serveur: HttpTestingController;
  let auth: AuthEspion;

  beforeEach(() => {
    auth = new AuthEspion();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([jetonInterceptor])),
        provideHttpClientTesting(),
        { provide: AUTHENTIFICATION, useValue: auth },
      ],
    });
    http = TestBed.inject(HttpClient);
    serveur = TestBed.inject(HttpTestingController);
  });

  afterEach(() => serveur.verify());

  it("porte le jeton sur les appels au socle", () => {
    http.get(`${API}/entities/x/accounts`).subscribe();
    const appel = serveur.expectOne(`${API}/entities/x/accounts`);
    expect(appel.request.headers.get('Authorization')).toBe('Bearer premier');
    appel.flush({});
  });

  it("ne porte jamais le jeton vers une autre origine", () => {
    // Un jeton envoyé ailleurs est un jeton donné — le fournisseur d'identité
    // compris : ses échanges se signent autrement.
    http.get('https://keycloak.test/realms/x/.well-known/openid-configuration').subscribe();
    const appel = serveur.expectOne('https://keycloak.test/realms/x/.well-known/openid-configuration');
    expect(appel.request.headers.has('Authorization')).toBe(false);
    appel.flush({});
  });

  it('rafraîchit une fois sur 401, puis rejoue avec le nouveau jeton', async () => {
    const recu: unknown[] = [];
    http.get(`${API}/entities/x/accounts`).subscribe((reponse) => recu.push(reponse));

    serveur.expectOne(`${API}/entities/x/accounts`).flush('expiré', { status: 401, statusText: 'Unauthorized' });
    await tick();

    const rejeu = serveur.expectOne(`${API}/entities/x/accounts`);
    expect(auth.rafraichissements).toBe(1);
    expect(rejeu.request.headers.get('Authorization')).toBe('Bearer second');
    rejeu.flush({ data: 'ok' });

    expect(recu).toEqual([{ data: 'ok' }]);
  });

  it("ne rejoue pas quand le rafraîchissement échoue : l’écran doit le savoir", async () => {
    auth.rafraichissementPossible = false;
    const statut = firstValueFrom(http.get(`${API}/entities/x/accounts`)).then(
      () => 0,
      (erreur: { status: number }) => erreur.status,
    );

    serveur.expectOne(`${API}/entities/x/accounts`).flush('expiré', { status: 401, statusText: 'Unauthorized' });

    expect(await statut).toBe(401);
    expect(auth.rafraichissements).toBe(1);
  });

  it("ne boucle pas : un second 401 après rejeu remonte", async () => {
    let statut = 0;
    http.get(`${API}/entities/x/accounts`).subscribe({ error: (e) => (statut = e.status) });

    serveur.expectOne(`${API}/entities/x/accounts`).flush('expiré', { status: 401, statusText: 'Unauthorized' });
    await tick();
    serveur.expectOne(`${API}/entities/x/accounts`).flush('toujours', { status: 401, statusText: 'Unauthorized' });
    await tick();

    expect(auth.rafraichissements).toBe(1);
    expect(statut).toBe(401);
  });

  it('laisse passer les autres erreurs sans rien tenter', async () => {
    let statut = 0;
    http.get(`${API}/entities/x/accounts`).subscribe({ error: (e) => (statut = e.status) });

    serveur.expectOne(`${API}/entities/x/accounts`).flush('refus', { status: 422, statusText: 'Unprocessable' });
    await tick();

    expect(auth.rafraichissements).toBe(0);
    expect(statut).toBe(422);
  });
});
