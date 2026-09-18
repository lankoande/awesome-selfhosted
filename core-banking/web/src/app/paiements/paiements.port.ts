import { InjectionToken } from '@angular/core';
import {
  Acte, DemandeOrdre, DemandeRemise, OrdrePaiement, Prelevement, Remise, SensPrelevement,
  StatutOrdre, StatutPrelevement, StatutRemise,
} from './modele/paiements.modele';

/** Une page d'une file de travail : le socle pagine, le poste ne charge pas tout. */
export interface Page<T> {
  readonly lignes: readonly T[];
  readonly numero: number;
  readonly taille: number;
  readonly precedent: boolean;
  readonly suivant: boolean;
}

/** Ce qu'un acte demande en plus de l'objet : un nostro, un motif, ou rien. */
export interface Decision {
  readonly acte: Acte;
  readonly nostroAccountId?: string;
  readonly motif?: string;
}

/**
 * Le port des moyens de paiement.
 *
 * Quatre règles du socle que les écrans annoncent sans les tenir :
 *
 *   **un ordre envoyé ne s'annule plus.** Il part au système de paiement, et
 *   la banque ne décide plus seule : il se règle, ou il revient ;
 *
 *   **une remise est créditée sauf bonne fin.** Le solde monte, le disponible
 *   non : le montant reste bloqué jusqu'au règlement de la banque tirée ;
 *
 *   **le poste n'exécute pas un prélèvement.** C'est le traitement de fin de
 *   journée qui l'exécute à l'échéance ; le poste présente, annule, règle ;
 *
 *   **les frais restent acquis à la banque** quand une opération revient : le
 *   service a été rendu, et l'écran le dit plutôt que de le laisser découvrir
 *   sur le relevé.
 */
export interface Paiements {
  /** Les ordres de paiement sortants, par pages, filtrés par état. */
  ordres(legalEntityId: string, statut: StatutOrdre | null, page: number,
         taille: number): Promise<Page<OrdrePaiement>>;

  ordre(legalEntityId: string, orderId: string): Promise<OrdrePaiement>;

  /** Enregistre un ordre : le compte est débité tout de suite, frais compris. */
  ordonner(legalEntityId: string, demande: DemandeOrdre,
           cleIdempotence: string): Promise<OrdrePaiement>;

  /** Envoyer, régler, retourner, annuler — l'acte porte ce qu'il exige. */
  deciderOrdre(legalEntityId: string, orderId: string,
               decision: Decision): Promise<OrdrePaiement>;

  /** Les remises de chèques, par pages, filtrées par état. */
  remises(legalEntityId: string, statut: StatutRemise | null, page: number,
          taille: number): Promise<Page<Remise>>;

  remise(legalEntityId: string, depositId: string): Promise<Remise>;

  /** Remet un chèque tiré sur une autre banque : crédit sauf bonne fin, montant bloqué. */
  remettre(legalEntityId: string, demande: DemandeRemise,
           cleIdempotence: string): Promise<Remise>;

  /** Régler ou retourner impayée. */
  deciderRemise(legalEntityId: string, depositId: string, decision: Decision): Promise<Remise>;

  /** Les prélèvements, par pages, filtrés par sens et par état. */
  prelevements(legalEntityId: string, sens: SensPrelevement | null,
               statut: StatutPrelevement | null, page: number,
               taille: number): Promise<Page<Prelevement>>;

  prelevement(legalEntityId: string, directDebitId: string): Promise<Prelevement>;

  /** Régler, annuler, retourner, rembourser — selon le sens autant que l'état. */
  deciderPrelevement(legalEntityId: string, directDebitId: string,
                     decision: Decision): Promise<Prelevement>;
}

export const PAIEMENTS = new InjectionToken<Paiements>('Paiements');
