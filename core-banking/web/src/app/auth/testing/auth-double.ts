import { Authentification, Droit, EtatSession, Habilitations, Portee, Porteur } from '../auth.port';
import { HABILITATIONS_INCONNUES } from '../habilitations';

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
  habilitationsRendues: Habilitations = HABILITATIONS_INCONNUES;

  /** Habilitations connues, limitées à ces opérations, sans second regard ni plafond. */
  autoriser(...operations: readonly string[]): void {
    this.habilitationsRendues = {
      connues: true,
      droits: new Map(operations.map((operation) => [operation, droit(operation)])),
    };
  }

  /** Un droit posé en détail : portée, second regard, plafond. */
  accorder(operation: string, detail: Partial<Omit<Droit, 'operation'>> = {}): void {
    const droits = new Map(this.habilitationsRendues.droits);
    droits.set(operation, { ...droit(operation), ...detail });
    this.habilitationsRendues = { connues: true, droits };
  }

  /** Un plafond, dit comme le socle le dit : par devise. */
  plafonner(operation: string, devise: string, montant: string, horsAgence?: string): void {
    this.accorder(operation, {
      plafonds: new Map([[devise, { amount: montant, currency: devise }]]),
      plafondsHorsAgence: horsAgence
        ? new Map([[devise, { amount: horsAgence, currency: devise }]])
        : new Map(),
    });
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

function droit(operation: string, portee: Portee = 'OWN_ENTITY'): Droit {
  return {
    operation,
    portee,
    secondRegard: false,
    horsAgence: false,
    plafonds: new Map(),
    plafondsHorsAgence: new Map(),
  };
}
