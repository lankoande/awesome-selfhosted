import { InjectionToken } from '@angular/core';
import {
  Contrat, Demande, DemandeAnalyse, DemandeCondition, DemandeContrat, DemandeDeCredit,
  DemandeDecision, DemandeReglement, DossierCredit, Reglement, StatutDemande,
} from './modele/credit.modele';

/** Ce que rend une action soumise à un second regard. */
export interface EnAttente {
  readonly operationId: string;
}

/**
 * Le port du crédit.
 *
 * Trois règles du socle que les écrans annoncent sans les tenir :
 *
 *   **la décision d'octroi passe par un second regard** (202). Celui qui
 *   instruit ne décide pas seul : c'est la séparation qui empêche qu'un dossier
 *   soit monté et accordé par la même main ;
 *
 *   **le déblocage aussi** (202) — c'est le moment où l'argent sort ;
 *
 *   **l'imputation d'un règlement appartient au socle.** Le front ne calcule
 *   jamais à quoi va un paiement : il affiche ce que le socle a imputé, dans
 *   l'ordre que la banque a paramétré.
 */
export interface Credit {
  /** Les demandes, filtrées par statut quand on en donne un. */
  demandes(legalEntityId: string, statut: StatutDemande | null,
           page: number, taille: number): Promise<{
    readonly demandes: readonly Demande[];
    readonly page: number;
    readonly precedent: boolean;
    readonly suivant: boolean;
  }>;

  dossier(legalEntityId: string, applicationId: string): Promise<DossierCredit>;

  deposer(demande: DemandeDeCredit, cleIdempotence: string): Promise<{ id: string }>;

  /** Verse une analyse au dossier. Le socle calcule le ratio et nomme les dépassements. */
  analyser(legalEntityId: string, applicationId: string, analyse: DemandeAnalyse,
           cleIdempotence: string): Promise<void>;

  poserCondition(legalEntityId: string, applicationId: string, condition: DemandeCondition,
                 cleIdempotence: string): Promise<void>;

  /** Lever une condition passe par un second regard : la preuve se vérifie. */
  leverCondition(legalEntityId: string, conditionId: string, preuve: string,
                 cleIdempotence: string): Promise<EnAttente>;

  /** Décider passe par un second regard. Toujours. */
  decider(legalEntityId: string, applicationId: string, decision: DemandeDecision,
          cleIdempotence: string): Promise<EnAttente>;

  contractualiser(legalEntityId: string, applicationId: string, contrat: DemandeContrat,
                  cleIdempotence: string): Promise<{ contractId: string; reference: string }>;

  retirer(legalEntityId: string, applicationId: string, motif: string,
          cleIdempotence: string): Promise<void>;

  // ------------------------------------------------------------------ contrats

  contrats(legalEntityId: string, page: number, taille: number): Promise<{
    readonly contrats: readonly Contrat[];
    readonly page: number;
    readonly precedent: boolean;
    readonly suivant: boolean;
  }>;

  contrat(legalEntityId: string, contractId: string): Promise<Contrat>;

  /** Le déblocage passe par un second regard : c'est l'argent qui sort. */
  debloquer(legalEntityId: string, contractId: string, cleIdempotence: string): Promise<EnAttente>;

  /** Un règlement s'impute immédiatement, dans l'ordre que le socle applique. */
  regler(legalEntityId: string, contractId: string, reglement: DemandeReglement,
         cleIdempotence: string): Promise<Reglement>;
}

export const CREDIT = new InjectionToken<Credit>('Credit');
