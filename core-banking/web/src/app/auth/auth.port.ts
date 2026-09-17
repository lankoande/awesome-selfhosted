import { InjectionToken } from '@angular/core';

/** Ce que le porteur du jeton est, tel que le fournisseur le dit. */
export interface Porteur {
  readonly subjectId: string;
  readonly username: string;
  readonly nom: string;
  /** Rôles portés par le jeton. Ils servent l'affichage, jamais la décision. */
  readonly roles: readonly string[];
  readonly agence: string | null;
  readonly caisse: string | null;
}

/**
 * Les opérations autorisées, telles que l'API les rend.
 *
 * `inconnues` quand le socle ne les expose pas : le menu montre alors tout, et
 * l'API refuse ce qui n'est pas permis. Deviner la politique d'habilitation
 * dans le navigateur garantirait la divergence — c'est la règle du §2.
 */
export interface Habilitations {
  readonly connues: boolean;
  readonly operations: ReadonlySet<string>;
}

export type EtatSession = 'inconnue' | 'anonyme' | 'ouverte' | 'verrouillee';

export interface Authentification {
  readonly etat: EtatSession;
  /** Reprend une session existante au démarrage, sans déranger l'opérateur. */
  reprendre(): Promise<EtatSession>;
  /** Envoie vers le fournisseur. Ne rend jamais la main : la page part. */
  ouvrir(): Promise<void>;
  /** Traite le retour d'autorisation (`code`, `state`). */
  retour(parametres: URLSearchParams): Promise<EtatSession>;
  /** Le jeton d'accès, en mémoire uniquement. `null` si la session ne vaut pas. */
  jeton(): string | null;
  /** Rafraîchit le jeton. Rend `false` quand il faut se reconnecter. */
  rafraichir(): Promise<boolean>;
  porteur(): Porteur | null;
  habilitations(): Habilitations;
  /** Verrouille sans déconnecter : la saisie en cours survit. */
  verrouiller(): void;
  deverrouiller(): Promise<boolean>;
  fermer(): Promise<void>;
}

export const AUTHENTIFICATION = new InjectionToken<Authentification>('Authentification');
