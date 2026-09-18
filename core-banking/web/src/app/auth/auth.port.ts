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

/** Un montant, tel que le socle le rend : jamais un nombre flottant. */
export interface Montant {
  readonly amount: string;
  readonly currency: string;
}

/** Jusqu'où l'opération porte. */
export type Portee = 'OWN_BRANCH' | 'OWN_ENTITY' | 'ANY_ENTITY';

/**
 * Ce que la politique du socle dit d'une opération, pour cet appelant.
 *
 * C'est une ligne de `/v1/me/permissions`, rendue entière. Le poste n'en
 * retenait que le nom ; il retient désormais tout, parce que **le nom seul ne
 * suffit pas à proposer honnêtement** :
 *
 *   `secondRegard` change le libellé d'un bouton — « Soumettre à validation »
 *   n'est pas « Valider », et l'opérateur doit le savoir avant de cliquer, pas
 *   après ;
 *
 *   `plafonds` change ce qu'on peut annoncer — un guichetier plafonné à
 *   2 000 000 le lit avant de saisir 5 000 000, pas dans un refus ;
 *
 *   `portee` explique ce qu'un écran ne montre pas : « votre profil ne voit
 *   que son agence » vaut mieux qu'une liste qui paraît incomplète.
 */
export interface Droit {
  readonly operation: string;
  readonly portee: Portee;
  /** L'acte part à la validation d'un second, distinct de l'auteur. */
  readonly secondRegard: boolean;
  /** L'acte est possible hors de l'agence gestionnaire de l'objet. */
  readonly horsAgence: boolean;
  /** Plafond par devise. Vide : aucun plafond ne s'applique. */
  readonly plafonds: ReadonlyMap<string, Montant>;
  /** Plafond par devise en opération déplacée, quand il diffère. */
  readonly plafondsHorsAgence: ReadonlyMap<string, Montant>;
}

/**
 * Les opérations autorisées, telles que l'API les rend.
 *
 * `inconnues` quand le socle ne les expose pas : le menu montre alors tout, et
 * l'API refuse ce qui n'est pas permis. Deviner la politique d'habilitation
 * dans le navigateur garantirait la divergence — c'est la règle du §2.
 *
 * **Ce n'est jamais une décision d'accès.** L'agence, le montant et l'objet
 * visé n'y sont pas connus ; le socle refuse toujours au moment d'agir. Le
 * poste s'en sert pour ne pas proposer une porte qu'il sait fermée, et pour
 * dire ce qu'il sait avant que l'opérateur ne bute dessus.
 */
export interface Habilitations {
  readonly connues: boolean;
  readonly droits: ReadonlyMap<string, Droit>;
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
