import { InjectionToken } from '@angular/core';
import {
  BeneficiaireEffectif, DemandeOuverture, DemandeTiers, Dossier, IssueOuverture,
  PageTiers, Piece, ProduitOuvrable, Tiers,
} from './modele/clients.modele';

/**
 * Le port du référentiel client.
 *
 * Deux règles du socle que l'écran annonce sans les tenir :
 *
 *   **la complétude du dossier commande l'ouverture d'un compte**, pas celle
 *   des comptes déjà ouverts. Le socle refuse (`requireOnboardable`) en nommant
 *   ce qui manque ; l'interface affiche ce refus à l'avance pour éviter une
 *   saisie perdue, mais c'est le socle qui tranche ;
 *
 *   **une pièce remplacée n'est pas effacée.** Le dossier garde l'historique et
 *   désigne la pièce en vigueur : un dossier client se relit des années après,
 *   et une pièce disparue est une question sans réponse.
 */
export interface Clients {
  /** Recherche paginée. Une requête vide rend les premiers tiers, pas une erreur. */
  chercher(legalEntityId: string, q: string, page: number, taille: number): Promise<PageTiers>;

  lire(legalEntityId: string, partyId: string): Promise<Tiers>;

  /** L'état du dossier, calculé par le socle contre la politique KYC en vigueur. */
  dossier(legalEntityId: string, partyId: string): Promise<Dossier>;

  pieces(legalEntityId: string, partyId: string): Promise<readonly Piece[]>;

  /** Vide pour une personne physique : la notion ne s'applique qu'aux personnes morales. */
  beneficiaires(legalEntityId: string, partyId: string): Promise<readonly BeneficiaireEffectif[]>;

  creer(demande: DemandeTiers, cleIdempotence: string): Promise<Tiers>;

  /**
   * Ouvre un compte. Deux issues, comme au guichet : ouvert, ou en attente d'un
   * second regard. Le refus, lui, remonte en exception.
   */
  ouvrirCompte(demande: DemandeOuverture, cleIdempotence: string): Promise<IssueOuverture>;

  /**
   * Les produits ouvrables aujourd'hui. Le socle ne rend que les versions
   * actives en vigueur ; un brouillon ou un produit retiré n'y figure pas.
   */
  produits(legalEntityId: string): Promise<readonly ProduitOuvrable[]>;
}

export const CLIENTS = new InjectionToken<Clients>('Clients');
