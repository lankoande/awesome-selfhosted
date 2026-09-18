import { InjectionToken } from '@angular/core';
import {
  DemandeEtablissement, DemandeRegle, DomaineNumerotation, Etablissement, RegleNumerotation,
} from './modele/etablissement.modele';
import {
  CompteGeneral, EnteteVersion, FamilleProduit, Tranche, VersionComplete, VersionProduit,
} from './modele/produits.modele';
import { FiltreBalance, PageBalance, RunTfj, TotauxBalance } from './modele/siege.modele';

/** Ce que rend une action soumise à un second regard. */
export interface EnAttenteSiege {
  readonly operationId: string;
}

/**
 * Le port du siège : l'exploitation du traitement de fin de journée et les
 * restitutions comptables.
 *
 * Rien ici ne décide de ce que fait le traitement. Le front lance, lit,
 * reprend, annule — et affiche ce que le socle répond, y compris ce qui a
 * échoué et ce qui n'a jamais été tenté.
 */
export interface Siege {
  /**
   * Lance le traitement. `DRY_RUN` n'écrit rien : c'est l'essai qu'un
   * exploitant fait avant d'engager sa journée.
   */
  lancerTfj(legalEntityId: string, journee: string, mode: 'REAL' | 'DRY_RUN'): Promise<RunTfj>;
  lireTfj(legalEntityId: string, runId: string): Promise<RunTfj>;
  /** Reprend à l'étape échouée ; ne vaut que sur un passage en échec. */
  reprendreTfj(legalEntityId: string, runId: string): Promise<RunTfj>;
  /** Annule le passage. Le socle refuse si une journée postérieure a déjà tourné. */
  annulerTfj(legalEntityId: string, runId: string): Promise<RunTfj>;

  balance(legalEntityId: string, filtre: FiltreBalance, page: number, taille: number): Promise<PageBalance>;
  /** Une entrée par devise : une balance ne s'additionne pas entre devises. */
  totauxBalance(legalEntityId: string, filtre: FiltreBalance): Promise<readonly TotauxBalance[]>;

  /** L'établissement : ce qui figure en en-tête de chaque relevé. */
  etablissement(legalEntityId: string): Promise<Etablissement>;

  /**
   * Corriger l'identité passe par un second regard : le code banque est en tête
   * de chaque RIB, et une faute de frappe ne se voit pas à l'écran — elle se
   * voit six mois plus tard, sur un virement reçu qui n'arrive jamais.
   */
  majEtablissement(legalEntityId: string, demande: DemandeEtablissement,
                   cleIdempotence: string): Promise<EnAttenteSiege>;

  /** Le plan de numérotation : brouillons, règle active, règles retirées. */
  regles(legalEntityId: string): Promise<readonly RegleNumerotation[]>;

  /** Le gabarit que le socle propose pour ce domaine. Une proposition, pas un défaut. */
  proposition(legalEntityId: string, domaine: DomaineNumerotation): Promise<DemandeRegle>;

  /** Rédiger : la règle ne numérote rien tant qu'elle n'est pas activée. */
  redigerRegle(legalEntityId: string, demande: DemandeRegle,
               cleIdempotence: string): Promise<{ readonly id: string }>;

  /** Activer décide de l'identité des comptes ouverts demain : à deux. */
  activerRegle(legalEntityId: string, ruleId: string,
               cleIdempotence: string): Promise<EnAttenteSiege>;

  // ---------------------------------------------------------------- paramétrage produit

  /**
   * Les familles de produit et ce que chacune exige.
   *
   * Le socle sert son propre contrat : le poste construit sa saisie à partir de là, et non d'une
   * copie de ces règles. Deux copies divergent, et l'écran finirait par proposer un paramètre que
   * l'activation refuse.
   */
  familles(legalEntityId: string): Promise<readonly FamilleProduit[]>;

  /** Toutes les versions de produit, brouillons compris ; filtrées par code et par état. */
  versions(legalEntityId: string, code: string | null,
           statut: string | null): Promise<readonly VersionProduit[]>;

  /** Une version en entier : en-tête, paramètres, barèmes. */
  version(legalEntityId: string, versionId: string): Promise<VersionComplete>;

  /**
   * Rédige une version. Elle ne résout rien tant qu'elle n'est pas activée : un brouillon a le
   * droit d'être incomplet, c'est ce qui en fait un brouillon.
   */
  redigerVersion(legalEntityId: string, entete: EnteteVersion,
                 parametres: Readonly<Record<string, string>>,
                 baremes: Readonly<Record<string, readonly Tranche[]>>,
                 cleIdempotence: string): Promise<{ readonly id: string }>;

  /** Activer : à deux, et le socle confronte d'abord le paramétrage à sa famille. */
  activerVersion(legalEntityId: string, versionId: string,
                 cleIdempotence: string): Promise<EnAttenteSiege>;

  /**
   * Fermer la validité d'une version en vigueur : à deux.
   *
   * C'est ainsi qu'un produit cesse d'être commercialisé — et c'est aussi ce qui libère son code
   * pour une version suivante.
   */
  fermerVersion(legalEntityId: string, versionId: string, validTo: string,
                cleIdempotence: string): Promise<EnAttenteSiege>;

  /** Retirer un brouillon abandonné. Seul acte du paramétrage produit qui ne soit pas à deux. */
  retirerVersion(legalEntityId: string, versionId: string): Promise<VersionProduit>;

  /** Le plan comptable : les comptes qu'un paramétrage peut désigner. Sans solde. */
  comptesGeneraux(legalEntityId: string, texte: string): Promise<readonly CompteGeneral[]>;
}

export const SIEGE = new InjectionToken<Siege>('Siege');
