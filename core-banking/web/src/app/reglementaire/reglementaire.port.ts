import { InjectionToken } from '@angular/core';
import {
  Declaration, DemandeDeclaration, DemandeRegleFiscale, DemandeTransmission, DossierEtat,
  Echeance, Etat, RegleFiscale, StatutEtat,
} from './modele/reglementaire.modele';

/** Ce que rend une action soumise à un second regard. */
export interface EnAttente {
  readonly operationId: string;
}

/**
 * Le port du réglementaire.
 *
 * Quatre règles du socle que les écrans annoncent sans les tenir :
 *
 *   **produire est un travail, transmettre est un engagement.** Produire se
 *   fait à un et se refait ; transmettre engage la banque devant son
 *   superviseur et passe par un second regard (202) ;
 *
 *   **un état qui porte des anomalies ne se transmet pas.** Il se produit —
 *   c'est ainsi qu'on voit ce qui ne va pas — mais on ne déclare pas des
 *   comptes dont on sait qu'ils sont faux ;
 *
 *   **ce qui est transmis ne s'annule pas.** On dépose un rectificatif ;
 *
 *   **un état est figé avec son paramétrage.** Le seuil qu'il porte est celui
 *   du jour de sa production, pas celui d'aujourd'hui — sans quoi un état
 *   régénéré six mois plus tard sortirait différent sans qu'on puisse dire si
 *   ce sont les données ou le paramétrage qui ont bougé.
 */
export interface Reglementaire {
  /** Les échéances dépassées à la date comptable. */
  echeances(legalEntityId: string): Promise<readonly Echeance[]>;

  declarations(legalEntityId: string): Promise<readonly Declaration[]>;

  /** Déclarer au catalogue passe par un second regard. */
  declarer(legalEntityId: string, demande: DemandeDeclaration,
           cleIdempotence: string): Promise<EnAttente>;

  /** Produire un état : immédiat, et ça se refait tant que rien n'est parti. */
  produire(legalEntityId: string, declarationId: string, periodEnd: string,
           cleIdempotence: string): Promise<Etat>;

  etats(legalEntityId: string, statut: StatutEtat | null): Promise<readonly Etat[]>;

  /** L'état, ses lignes, et — s'il est transmis — ce que donne son recalcul. */
  etat(legalEntityId: string, filingId: string): Promise<DossierEtat>;

  /** Transmettre passe par un second regard : c'est un engagement. */
  transmettre(legalEntityId: string, filingId: string, demande: DemandeTransmission,
              cleIdempotence: string): Promise<EnAttente>;

  /** Annuler un état produit et non transmis. Le motif reste au dossier. */
  annuler(legalEntityId: string, filingId: string, motif: string,
          cleIdempotence: string): Promise<Etat>;

  reglesFiscales(legalEntityId: string): Promise<readonly RegleFiscale[]>;

  /** Un taux de taxe produit des montants sur des comptes clients : à deux. */
  declarerRegleFiscale(legalEntityId: string, demande: DemandeRegleFiscale,
                       cleIdempotence: string): Promise<EnAttente>;
}

export const REGLEMENTAIRE = new InjectionToken<Reglementaire>('Reglementaire');
