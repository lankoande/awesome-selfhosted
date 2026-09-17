import { Injectable } from '@angular/core';
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
 * Les habilitations qu'il rend sont marquées « inconnues » — comme le fera le
 * vrai fournisseur tant que le socle n'expose pas les opérations autorisées.
 * L'écran doit apprendre à vivre avec, pas à supposer.
 */
@Injectable()
export class AuthFactice implements Authentification {
  private readonly session = new Session();

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

  private ouvrirSession(): void {
    this.session.ouvrir('jeton-de-demonstration', null, 3600, PORTEUR);
    this.session.poserHabilitations({ connues: false, operations: new Set() });
  }
}
