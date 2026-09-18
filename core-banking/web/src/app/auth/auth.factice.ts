import { Injectable, inject } from '@angular/core';
import { AppConfig } from '../core/config/runtime-config';
import { Authentification, EtatSession, Habilitations, Porteur } from './auth.port';
import { Session } from './session';

const PORTEUR: Porteur = {
  subjectId: 'u-kabore',
  username: 'a.kabore',
  nom: 'Abdoulaye Kaboré',
  roles: ['TELLER', 'BRANCH_MANAGER'],
  agence: 'OUA2',
  caisse: 'OUA2-C02',
};

/**
 * Fournisseur d'identité de démonstration.
 *
 * Il ouvre une session sans réseau pour que l'application soit utilisable sans
 * Keycloak. Il ne simule aucun contrôle : **il n'y a pas de mot de passe ici**,
 * et le bandeau de démonstration le dit déjà pour toute l'application.
 *
 * Les habilitations qu'il rend viennent de `habilitations.demonstration.ts` :
 * une copie de la politique du socle pour le profil de démonstration. Toutes
 * les opérations sont accordées — une démonstration où la moitié des écrans est
 * invisible devient un appel au support — mais les **plafonds** et les
 * **seconds regards** sont ceux du profil, et ce sont eux qui portent la
 * granularité. `config.json` permet d'en retirer pour montrer une porte fermée.
 */
@Injectable()
export class AuthFactice implements Authentification {
  private readonly session = new Session();
  private readonly config = inject(AppConfig);

  get etat(): EtatSession {
    return this.session.etat();
  }

  async reprendre(): Promise<EtatSession> {
    this.ouvrirSession();
    return this.etat;
  }

  async ouvrir(): Promise<void> {
    this.ouvrirSession();
  }

  async retour(): Promise<EtatSession> {
    this.ouvrirSession();
    return this.etat;
  }

  jeton(): string | null {
    return this.session.jeton();
  }

  async rafraichir(): Promise<boolean> {
    this.ouvrirSession();
    return true;
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
    this.session.deverrouiller();
    return true;
  }

  async fermer(): Promise<void> {
    this.session.fermer();
  }

  /**
   * La politique de démonstration est **chargée à la demande**.
   *
   * Elle fait quatre-vingt-dix-sept lignes de paramétrage qu'un déploiement
   * branché sur le socle n'ouvrira jamais : l'importer ici la mettrait dans le
   * paquet initial, que tout le monde télécharge. La session s'ouvre d'abord —
   * l'écran n'attend pas —, les droits arrivent ensuite ; ils sont portés par
   * un signal, donc la barre et les boutons se rouvrent quand ils arrivent.
   */
  private ouvrirSession(): void {
    this.session.ouvrir('jeton-de-demonstration', null, 3600, PORTEUR);
    void import('./habilitations.demonstration').then(({ habilitationsDeDemonstration }) => {
      this.session.poserHabilitations(habilitationsDeDemonstration(this.config.droitsRetires()));
    });
  }
}
