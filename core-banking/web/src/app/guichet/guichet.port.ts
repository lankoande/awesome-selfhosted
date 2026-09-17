import { InjectionToken } from '@angular/core';
import { ContexteCompte, DemandeEspeces, IssueVersement, SoldeCompte } from './modele/guichet.modele';

/**
 * Le port du guichet. Une seule interface, deux implémentations : l'API du
 * socle, et une source de démonstration qui rejoue ses règles localement tant
 * qu'aucun socle n'est branché.
 *
 * Ce que le port n'expose pas, volontairement : aucun calcul de frais, de taxe
 * ou de date de valeur. Ce sont des règles du socle ; les recopier ici
 * garantirait la divergence, et un écart de frais au guichet est un écart de
 * caisse le soir.
 */
export interface Guichet {
  /** Solde comptable, disponible et statut du compte, à la date du socle. */
  soldes(legalEntityId: string, accountId: string): Promise<SoldeCompte>;

  /** Ce qu'un guichetier doit voir avant d'accepter des espèces. */
  contexte(legalEntityId: string, accountId: string): Promise<ContexteCompte>;

  /**
   * Soumet un versement d'espèces. Lève `RefusMetier` sur un refus du socle.
   * Le reçu porte `replayed` quand la clé d'idempotence était déjà connue :
   * l'opération n'a pas été passée deux fois.
   */
  verser(demande: DemandeEspeces): Promise<IssueVersement>;

  /**
   * Retire des espèces. Le socle vérifie le **disponible**, pas le solde
   * comptable : un blocage retient une part du solde, et refuser un retrait sans
   * pouvoir dire pourquoi est un incident client.
   */
  retirer(demande: DemandeEspeces): Promise<IssueVersement>;

  /**
   * Comptes proposés d'emblée. Seule la source de démonstration en offre :
   * avec un socle réel, on cherche un compte, on ne le choisit pas dans une
   * liste — la recherche de compte est une lacune nommée du contrat.
   */
  catalogue?(): Promise<readonly { accountId: string; code: string; intitule: string; pourquoi: string }[]>;
}

export const GUICHET = new InjectionToken<Guichet>('Guichet');
