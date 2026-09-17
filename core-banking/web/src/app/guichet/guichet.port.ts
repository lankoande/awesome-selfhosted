import { InjectionToken } from '@angular/core';
import {
  ContexteCompte, DemandeEspeces, DemandeVirement, IssueVersement, PageReleve, SoldeCompte,
} from './modele/guichet.modele';

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
   * Virement interne. Une seule écriture, deux comptes : le socle débite l'un
   * et crédite l'autre dans la même transaction — il n'y a jamais un instant où
   * l'argent n'est nulle part.
   */
  virer(demande: DemandeVirement): Promise<IssueVersement>;

  /**
   * Le relevé d'un compte, borné à une période. Paginé par le socle : on ne
   * charge jamais dix mille lignes, et on ne fait pas défiler un journal à
   * l'infini.
   */
  releve(legalEntityId: string, accountId: string, du: string | null, au: string | null,
         page: number, taille: number): Promise<PageReleve>;

  /**
   * Comptes proposés d'emblée. Seule la source de démonstration en offre :
   * avec un socle réel, on cherche un compte, on ne le choisit pas dans une
   * liste — la recherche de compte est une lacune nommée du contrat.
   */
  catalogue?(): Promise<readonly { accountId: string; code: string; intitule: string; pourquoi: string }[]>;
}

export const GUICHET = new InjectionToken<Guichet>('Guichet');
