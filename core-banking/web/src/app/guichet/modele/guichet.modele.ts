/**
 * Le modèle du guichet, aligné sur le contrat OpenAPI du socle.
 *
 * Les montants restent des chaînes à l'échelle de la devise, comme dans le
 * contrat : un montant qui transite par un flottant a déjà perdu. On ne
 * convertit en nombre que pour afficher ou comparer, jamais pour accumuler
 * ce qui sera envoyé.
 */

/** Miroir de `Money` : `{ amount, currency }`. */
export interface Montant {
  readonly amount: string;
  readonly currency: string;
}

/** Miroir de `AccountUseCases.Balance`. */
export interface SoldeCompte {
  readonly accountId: string;
  readonly code: string;
  readonly currency: string;
  readonly current: Montant;
  readonly available: Montant;
  readonly asOf: string;
  readonly status: string;
  readonly branchId: string;
}

/** Miroir de `OperationsService.Receipt` : ce que le socle a réellement passé. */
export interface Recu {
  readonly entryId: string;
  readonly entryNumber: number;
  readonly bookingDate: string;
  readonly valueDate: string;
  readonly amount: Montant;
  readonly fee: Montant;
  readonly tax: Montant;
  readonly balanceAfter: Montant;
  readonly branchId: string;
  readonly remote: boolean;
  /**
   * Vrai quand le socle a reconnu la clé d'idempotence : l'opération était
   * déjà comptabilisée et voici le premier reçu. Ce n'est pas un nouveau
   * succès, et l'écran doit le dire.
   */
  readonly replayed: boolean;
}

/**
 * L'issue d'une opération, telle que le socle la rend. Trois codes, trois
 * sens : 201 comptabilisé, 200 rejeu d'une clé déjà traitée, 202 mis en
 * attente d'un second regard. L'écran doit les distinguer — « comptabilisé »
 * annoncé deux fois fait recompter la caisse, et « comptabilisé » annoncé sur
 * une opération en attente fait remettre les espèces au client.
 */
export type IssueVersement =
  | { readonly genre: 'comptabilise'; readonly recu: Recu }
  | { readonly genre: 'en-attente'; readonly operationId: string; readonly attenduDe: string };

/**
 * Miroir de `Requests.CashOperation` : la même forme sert au versement et au
 * retrait. Le sens n'est pas dans le corps de la requête, il est dans la route
 * — `/deposits` ou `/withdrawals`.
 */
export interface DemandeEspeces {
  readonly legalEntityId: string;
  readonly accountId: string;
  /** Chaîne à l'échelle de la devise, jamais un flottant. */
  readonly amount: string;
  readonly currency: string;
  /** Canal de l'opération. Au guichet : `BRANCH`. Il vient du poste, pas d'un sélecteur. */
  readonly channel: string;
  readonly narrative: string;
  /** Générée par le poste, conservée, rejouée telle quelle par « Réessayer ». */
  readonly cleIdempotence: string;
}

/**
 * Un refus du socle, tel qu'il l'écrit (`ErrorEnvelope` / `Error`). On garde le
 * type et le titre : ce sont eux qui sont faits pour être lus, et le `type`
 * sert au support.
 */
export class RefusMetier extends Error {
  constructor(
    readonly statut: number,
    readonly code: string,
    readonly titre: string,
    readonly detail?: string,
    readonly requestId?: string,
  ) {
    super(titre);
    this.name = 'RefusMetier';
  }

  /** Un conflit d'idempotence n'est pas un refus métier : la saisie est bonne. */
  get rejouable(): boolean {
    return this.statut >= 500 || this.statut === 409 || this.statut === 0;
  }
}

/** Miroir de `Requests.Transfer` : un virement cite ses deux comptes. */
export interface DemandeVirement {
  readonly legalEntityId: string;
  readonly sourceAccountId: string;
  readonly destinationAccountId: string;
  readonly amount: string;
  readonly currency: string;
  readonly channel: string;
  readonly narrative: string;
  readonly cleIdempotence: string;
}

/**
 * Miroir de `Journal.StatementLine` — une ligne de relevé.
 *
 * `reversalOf` désigne l'écriture contre-passée : une contre-passation ne
 * remplace pas l'écriture d'origine, elle s'ajoute. Les deux restent au
 * journal, et le relevé doit les montrer toutes les deux.
 *
 * `knowledgeTime` est la date de connaissance : le socle est bitemporel, et une
 * écriture peut être passée après le jour qu'elle affecte. Quand les deux
 * divergent, c'est une information d'audit, pas un détail.
 */
export interface LigneReleve {
  readonly entryId: string;
  readonly entryNumber: number;
  readonly lineNumber: number;
  readonly accountCode: string;
  readonly bookingDate: string;
  readonly valueDate: string;
  readonly knowledgeTime: string;
  readonly direction: 'DEBIT' | 'CREDIT';
  readonly amount: Montant;
  readonly label: string;
  readonly narrative: string | null;
  readonly transactionType: string;
  readonly reversalOf: string | null;
}

export interface PageReleve {
  readonly lignes: readonly LigneReleve[];
  readonly numero: number;
  readonly taille: number;
  readonly precedent: boolean;
  readonly suivant: boolean;
}

/** Blocage posé sur un compte : il ampute le disponible, pas le solde. */
export interface Blocage {
  readonly id: string;
  readonly motif: string;
  readonly montant: Montant;
  readonly poseLe: string;
  readonly reference?: string;
}

/** Ce qu'un guichetier doit voir avant d'accepter des espèces. */
export interface ContexteCompte {
  readonly intitule: string;
  readonly partyId: string | null;
  readonly reference: string;
  readonly nature: string;
  readonly produit: string;
  readonly ouvertLe: string | null;
  readonly kyc: { readonly etat: string; readonly revuLe: string | null } | null;
  readonly blocages: readonly Blocage[];
  /** Ce que le contrat n'expose pas encore, dit explicitement plutôt que deviné. */
  readonly lacunes: readonly string[];
}
