import { HttpClient } from '@angular/common/http';
import { DOCUMENT } from '@angular/common';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AppConfig } from '../core/config/runtime-config';
import { Authentification, EtatSession, Habilitations, Porteur } from './auth.port';
import { DefiPkce, defi, memeState, urlAutorisation } from './pkce';
import { Session } from './session';

interface Decouverte {
  readonly authorization_endpoint: string;
  readonly token_endpoint: string;
  readonly end_session_endpoint?: string;
}

interface Jetons {
  readonly access_token: string;
  readonly refresh_token?: string;
  readonly expires_in: number;
  readonly id_token?: string;
}

const CLE_DEFI = 'cb.pkce';

/**
 * OAuth2 Authorization Code + PKCE contre Keycloak, sans BFF.
 *
 * Ce que cette implémentation assume, et qu'il faut savoir : sans BFF, il n'y a
 * pas de cookie `HttpOnly` que le navigateur nous poserait — le jeton vit donc
 * en mémoire, et le rechargement de page repasse par une **autorisation
 * silencieuse** (`prompt=none`) plutôt que par un jeton stocké. Un BFF
 * supprimerait ce compromis ; c'est un composant d'exploitation de plus, et
 * c'est la décision du §1.
 *
 * Le seul élément qui touche au stockage est le couple `code_verifier`/`state`
 * de la redirection en cours, gardé en `sessionStorage` le temps de l'aller-retour :
 * il ne vaut rien seul, il est à usage unique, et il est effacé au retour.
 */
@Injectable()
export class AuthKeycloak implements Authentification {
  private readonly http = inject(HttpClient);
  private readonly config = inject(AppConfig);
  private readonly document = inject(DOCUMENT);
  private readonly session = new Session();

  private decouverte: Decouverte | null = null;

  get etat(): EtatSession {
    return this.session.etat();
  }

  async reprendre(): Promise<EtatSession> {
    // Une session déjà ouverte côté fournisseur se reprend sans rien demander ;
    // sinon on reste anonyme et l'écran d'accueil propose de se connecter.
    try {
      await this.autoriser(true);
      return this.etat;
    } catch {
      this.session.fermer();
      return 'anonyme';
    }
  }

  async ouvrir(): Promise<void> {
    await this.autoriser(false);
  }

  async retour(parametres: URLSearchParams): Promise<EtatSession> {
    const code = parametres.get('code');
    const state = parametres.get('state');
    const garde = this.lireDefi();
    this.oublierDefi();

    if (!code || !state || !garde || !memeState(garde.state, state)) {
      this.session.fermer();
      return 'anonyme';
    }

    const jetons = await this.echanger({
      grant_type: 'authorization_code',
      code,
      redirect_uri: this.redirection(),
      client_id: this.auth().clientId,
      code_verifier: garde.verifier,
    });
    this.session.ouvrir(jetons.access_token, jetons.refresh_token ?? null, jetons.expires_in,
                        this.porteurDe(jetons));
    this.session.poserHabilitations(await this.lireHabilitations());
    return this.etat;
  }

  jeton(): string | null {
    return this.session.jeton();
  }

  async rafraichir(): Promise<boolean> {
    const refresh = this.session.jetonDeRafraichissement();
    if (!refresh) return false;
    try {
      const jetons = await this.echanger({
        grant_type: 'refresh_token',
        refresh_token: refresh,
        client_id: this.auth().clientId,
      });
      const porteur = this.session.porteur();
      if (!porteur) return false;
      this.session.ouvrir(jetons.access_token, jetons.refresh_token ?? refresh, jetons.expires_in, porteur);
      return true;
    } catch {
      return false;
    }
  }

  porteur(): Porteur | null {
    return this.session.porteur();
  }

  habilitations(): Habilitations {
    return this.session.habilitations();
  }

  verrouiller(): void {
    this.session.verrouiller();
  }

  async deverrouiller(): Promise<boolean> {
    // Le déverrouillage repasse par le fournisseur : c'est lui qui sait
    // vérifier un mot de passe, pas nous. `prompt=login` force la saisie même
    // si la session du fournisseur est encore ouverte.
    this.session.deverrouiller();
    return !this.session.perime() || this.rafraichir();
  }

  async fermer(): Promise<void> {
    const fin = this.decouverte?.end_session_endpoint;
    this.session.fermer();
    if (fin) {
      const parametres = new URLSearchParams({ post_logout_redirect_uri: this.racine() });
      this.document.location.assign(`${fin}?${parametres.toString()}`);
    }
  }

  // ------------------------------------------------------------------ détails

  private auth() {
    return this.config.valeur().auth;
  }

  private async autoriser(silencieux: boolean): Promise<void> {
    const decouverte = await this.decouvrir();
    const garde = await defi();
    this.garderDefi(garde);
    this.document.location.assign(urlAutorisation(decouverte.authorization_endpoint, {
      clientId: this.auth().clientId,
      redirectUri: this.redirection(),
      scope: this.auth().scope,
      defi: garde,
      silencieux,
    }));
  }

  private async decouvrir(): Promise<Decouverte> {
    if (this.decouverte) return this.decouverte;
    const url = `${this.auth().issuer.replace(/\/$/, '')}/.well-known/openid-configuration`;
    this.decouverte = await firstValueFrom(this.http.get<Decouverte>(url));
    return this.decouverte;
  }

  private async echanger(corps: Record<string, string>): Promise<Jetons> {
    const decouverte = await this.decouvrir();
    return firstValueFrom(this.http.post<Jetons>(decouverte.token_endpoint, new URLSearchParams(corps).toString(), {
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    }));
  }

  /**
   * Les opérations autorisées viennent de l'API, jamais du jeton. Quand le
   * socle ne les expose pas encore, on le dit : le menu montrera tout et l'API
   * refusera — ce qui est la seule frontière qui compte.
   */
  private async lireHabilitations(): Promise<Habilitations> {
    const url = `${this.config.apiBaseUrl().replace(/\/$/, '')}/me/permissions`;
    try {
      const reponse = await firstValueFrom(
        this.http.get<{ data: { operations: string[] } }>(url));
      return { connues: true, operations: new Set(reponse.data?.operations ?? []) };
    } catch {
      return { connues: false, operations: new Set() };
    }
  }

  /**
   * Le porteur est lu dans la charge utile du jeton d'identité, pour l'affichage
   * seulement. **Aucune décision ne s'appuie dessus** : une charge utile lue
   * côté navigateur n'est pas une preuve, c'est un affichage.
   */
  private porteurDe(jetons: Jetons): Porteur {
    const charge = this.charge(jetons.id_token ?? jetons.access_token);
    return {
      subjectId: String(charge['sub'] ?? ''),
      username: String(charge['preferred_username'] ?? ''),
      nom: String(charge['name'] ?? charge['preferred_username'] ?? ''),
      roles: Array.isArray(charge['roles']) ? (charge['roles'] as string[]) : [],
      agence: charge['branch'] ? String(charge['branch']) : null,
      caisse: charge['till'] ? String(charge['till']) : null,
    };
  }

  private charge(jeton: string): Record<string, unknown> {
    const morceaux = jeton.split('.');
    if (morceaux.length < 2) return {};
    try {
      const base = morceaux[1]!.replace(/-/g, '+').replace(/_/g, '/');
      return JSON.parse(atob(base)) as Record<string, unknown>;
    } catch {
      return {};
    }
  }

  private redirection(): string {
    return `${this.racine()}auth/retour`;
  }

  private racine(): string {
    const emplacement = this.document.location;
    return `${emplacement.origin}${this.document.baseURI.replace(emplacement.origin, '')}`;
  }

  private garderDefi(garde: DefiPkce): void {
    try {
      sessionStorage.setItem(CLE_DEFI, JSON.stringify(garde));
    } catch {
      /* stockage refusé : la redirection échouera au retour, et c'est dit */
    }
  }

  private lireDefi(): DefiPkce | null {
    try {
      const brut = sessionStorage.getItem(CLE_DEFI);
      return brut ? (JSON.parse(brut) as DefiPkce) : null;
    } catch {
      return null;
    }
  }

  private oublierDefi(): void {
    try {
      sessionStorage.removeItem(CLE_DEFI);
    } catch {
      /* rien à oublier */
    }
  }
}
