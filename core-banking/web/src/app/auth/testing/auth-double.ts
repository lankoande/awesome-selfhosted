import { Authentification, EtatSession, Habilitations, Porteur } from '../auth.port';

/**
 * Une session sous contrôle, pour les écrans qui lisent les habilitations.
 *
 * Par défaut, les habilitations sont **inconnues** : `autorise()` laisse alors
 * tout passer, ce qui est la règle de l'application — on ne cache rien tant
 * qu'on ne sait pas, parce que cacher au hasard serait pire que ne rien cacher.
 * Un test qui veut éprouver une porte fermée pose `operations` explicitement.
 */
export class AuthentificationDouble implements Authentification {
  etat: EtatSession = 'ouverte';
  porteurRendu: Porteur | null = null;
  habilitationsRendues: Habilitations = { connues: false, operations: new Set() };

  /** Habilitations connues, limitées à ces opérations. */
  autoriser(...operations: readonly string[]): void {
    this.habilitationsRendues = { connues: true, operations: new Set(operations) };
  }

  async reprendre(): Promise<EtatSession> { return this.etat; }
  async ouvrir(): Promise<void> {}
  async retour(): Promise<EtatSession> { return this.etat; }
  jeton(): string | null { return 'jeton-de-test'; }
  async rafraichir(): Promise<boolean> { return true; }
  porteur(): Porteur | null { return this.porteurRendu; }
  habilitations(): Habilitations { return this.habilitationsRendues; }
  verrouiller(): void { this.etat = 'verrouillee'; }
  async deverrouiller(): Promise<boolean> { this.etat = 'ouverte'; return true; }
  async fermer(): Promise<void> { this.etat = 'anonyme'; }
}
