import { InjectionToken } from '@angular/core';
import {
  DemandeEtablissement, DemandeRegle, DomaineNumerotation, Etablissement, RegleNumerotation,
} from './modele/etablissement.modele';
import {
  CompteGeneral, EnteteVersion, FamilleProduit, Tranche, VersionComplete, VersionProduit,
} from './modele/produits.modele';
import {
  Agence, ConditionsDeBanque, DemandeAgence, DemandeFerie, DemandeHeureLimite,
  DemandeRegleDateValeur,
} from './modele/reseau.modele';
import {
  DemandeFermetureMaquette, EnteteMaquette, EtatProduit, Maquette, MaquetteComplete, NatureEtat,
  RegleSaisie, RubriqueSaisie,
} from './modele/maquettes.modele';
import {
  DemandeFermetureSchema, EnteteSchema, Essai, EvenementSocle, LigneSaisie, SchemaComplet,
  SchemaComptable,
} from './modele/schemas.modele';
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

  // ---------------------------------------------------------------- réseau et calendrier

  /** Les agences de l'entité, siège compris. */
  agences(legalEntityId: string): Promise<readonly Agence[]>;

  /**
   * Crée une agence, à deux.
   *
   * Le code figure dans les numéros de compte qu'elle ouvrira : il ne se change plus ensuite, et
   * c'est pourquoi une création se valide comme une opération.
   */
  creerAgence(legalEntityId: string, demande: DemandeAgence,
              cleIdempotence: string): Promise<EnAttenteSiege>;

  /**
   * Les conditions de banque : calendrier, fériés, règles de date de valeur, heures limites.
   *
   * Une seule lecture pour les quatre : une date de valeur est le produit d'une règle, d'une heure
   * limite et d'un calendrier — les lire séparément n'expliquerait aucune des dates qu'un client
   * conteste.
   */
  conditions(legalEntityId: string): Promise<ConditionsDeBanque>;

  /** Un férié déplace des dates de valeur et des échéances : à deux. */
  ajouterFerie(legalEntityId: string, demande: DemandeFerie,
               cleIdempotence: string): Promise<EnAttenteSiege>;

  /** Une règle de date de valeur déplace des intérêts : à deux. */
  ajouterRegle(legalEntityId: string, demande: DemandeRegleDateValeur,
               cleIdempotence: string): Promise<EnAttenteSiege>;

  /** Une heure limite décide de ce qui passe aujourd'hui et de ce qui passe demain : à deux. */
  ajouterHeureLimite(legalEntityId: string, demande: DemandeHeureLimite,
                     cleIdempotence: string): Promise<EnAttenteSiege>;

  // ---------------------------------------------------------------- schémas comptables

  /**
   * Le catalogue de ce que le socle impute, événement par événement.
   *
   * Il répond à deux questions qu'on ne pouvait poser qu'au code : ce que la banque impute quand
   * un client retire de l'argent, et ce qui se paramètre réellement. La seconde est la plus
   * importante : un schéma rédigé pour un événement que personne ne résout s'activerait à deux et
   * ne serait lu par personne.
   */
  evenementsDuSocle(legalEntityId: string, devise: string | null): Promise<readonly EvenementSocle[]>;

  /** Les schémas de l'entité, brouillons compris ; filtrés par code et par état. */
  schemas(legalEntityId: string, code: string | null,
          statut: string | null): Promise<readonly SchemaComptable[]>;

  /** Un schéma en entier : en-tête, dérivations dans l'ordre, lignes, variables attendues. */
  schema(legalEntityId: string, schemaId: string): Promise<SchemaComplet>;

  /**
   * Essaie un schéma sur un cas, sans rien imputer.
   *
   * Le poste n'interprète aucune expression : il envoie ce qui est écrit et lit ce que le socle
   * en fait. C'est aussi ainsi qu'il apprend les grandeurs à demander — l'essai les rend.
   */
  essayer(legalEntityId: string, evenement: string, devise: string | null,
          lignes: readonly LigneSaisie[], derivations: readonly (readonly [string, string])[],
          valeurs: Readonly<Record<string, string>>): Promise<Essai>;

  /** Essaie un schéma du socle : ce que la banque impute sur cet événement, sur un cas. */
  essayerLeSocle(legalEntityId: string, evenement: string, devise: string | null,
                 valeurs: Readonly<Record<string, string>>): Promise<Essai>;

  /**
   * Rédige un schéma. Il est validé par tirage avant d'entrer en base : un schéma déséquilibré
   * n'y entre jamais.
   */
  redigerSchema(legalEntityId: string, entete: EnteteSchema,
                lignes: readonly LigneSaisie[],
                derivations: readonly (readonly [string, string])[],
                cleIdempotence: string): Promise<{ readonly id: string }>;

  /** Activer : à deux, et jamais par le rédacteur. */
  activerSchema(legalEntityId: string, schemaId: string,
                cleIdempotence: string): Promise<EnAttenteSiege>;

  /**
   * Fermer la validité d'un schéma en vigueur : à deux.
   *
   * C'est ainsi qu'un schéma cesse de s'appliquer — et c'est aussi ce qui permet d'en activer un
   * suivant sous le même code.
   */
  fermerSchema(legalEntityId: string, schemaId: string, demande: DemandeFermetureSchema,
               cleIdempotence: string): Promise<EnAttenteSiege>;

  /** Retirer un brouillon abandonné. Seul acte du paramétrage comptable qui ne soit pas à deux. */
  retirerSchema(legalEntityId: string, schemaId: string): Promise<void>;

  // ---------------------------------------------------------------- maquettes d'états financiers

  /** Les maquettes de l'entité, brouillons compris ; filtrées par nature et par état. */
  maquettes(legalEntityId: string, nature: NatureEtat | null,
            statut: string | null): Promise<readonly Maquette[]>;

  /** Une maquette en entier : rubriques dans l'ordre, règles dans l'ordre de lecture. */
  maquette(legalEntityId: string, layoutId: string): Promise<MaquetteComplete>;

  /**
   * Essaie une maquette sur le journal, sans rien produire d'officiel.
   *
   * C'est la seule façon d'éprouver un brouillon. Les contrôles sont ceux de la production :
   * comptes qu'aucune règle n'affecte, équilibre, résultat antérieur non clos.
   */
  essayerMaquette(legalEntityId: string, layoutId: string, du: string | null,
                  au: string | null): Promise<EtatProduit>;

  /** Rédige une maquette. Elle est vérifiée avant d'entrer en base. */
  redigerMaquette(legalEntityId: string, entete: EnteteMaquette,
                  rubriques: readonly RubriqueSaisie[], regles: readonly RegleSaisie[],
                  cleIdempotence: string): Promise<{ readonly id: string }>;

  /** Activer : à deux, et jamais par le rédacteur. */
  activerMaquette(legalEntityId: string, layoutId: string,
                  cleIdempotence: string): Promise<EnAttenteSiege>;

  /**
   * Fermer la validité d'une maquette en vigueur : à deux.
   *
   * Une seule maquette active par nature d'état et par date : c'est la fermeture qui libère la
   * place pour la suivante.
   */
  fermerMaquette(legalEntityId: string, layoutId: string, demande: DemandeFermetureMaquette,
                 cleIdempotence: string): Promise<EnAttenteSiege>;

  /** Retirer un brouillon abandonné. Seul acte du paramétrage des états qui ne soit pas à deux. */
  retirerMaquette(legalEntityId: string, layoutId: string): Promise<void>;
}

export const SIEGE = new InjectionToken<Siege>('Siege');
