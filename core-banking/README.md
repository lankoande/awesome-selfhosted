# Socle core banking — P0 : noyau comptable

Implémentation du cœur décrit dans [`../docs/core-banking`](../docs/core-banking).
Java 21, PostgreSQL 14+, aucune dépendance de framework dans le ledger.

```
core-banking/
├── platform-kernel     Money, devises, identifiants, idempotence — zéro dépendance externe
├── ledger-domain       Comptes, écritures, invariants, contre-passation — Java pur, testable sans base
├── ledger-store        Schéma PostgreSQL, comptabilisation, soldes bitemporels, réconciliation
├── interest-domain     Conventions de jours, barèmes, accruals — arithmétique pure
├── interest-service    Intérêts courus, recalcul rétroactif, calcul par lot
├── fee-domain          Périodicité, assiette, proratisation, fiscalité des commissions
├── fee-service         Perception : échéances, provision, impayés, exonérations
├── loan-domain         Échéanciers, imputation, plan de déblocage, intérêts intercalaires — pur
├── loan-service        Contrats : déblocage, mobilisation, exigibilité, retard, classification
├── schema-engine       Traduction événement métier → écritures, validation par tirage
├── product-catalog     Product factory datée, familles de produit, schémas comptables, barèmes
├── calendar            Jours ouvrés, conventions et conditions de date de valeur
├── security-core       Politique d'habilitation centralisée, catalogue de rôles
├── security-keycloak   Adaptateur vers l'API d'administration Keycloak
├── security-store      Journal d'habilitation
├── tfj                 Traitement de fin de journée : orchestration, reprise, annulation, à blanc
└── benchmark           Mesures de débit et de durée, extrapolées à la volumétrie cible
```

## Lancer les tests

```bash
mvn test
```

PostgreSQL est démarré en embarqué par les tests d'intégration — ni Docker, ni installation locale
requise. Les binaires sont téléchargés au premier lancement. Chaque base de test est montée par
`SchemaMigrator`, le même runner qu'en production : le chemin de déploiement est exercé à chaque
build, pas seulement le jour du déploiement.

**État actuel : 612 tests verts** — 306 sur les domaines purs (dont 11 propriétés, ≈ 4 000 cas
générés), 306 sur PostgreSQL réel, dont l'API de bout en bout, sous le rôle applicatif.

**Mesuré** ([détail](../docs/core-banking/13-mesures.md)) : 1 878 écritures/s, p99 13,4 ms, zéro
interblocage ; TFJ complet — commissions **et** intérêts — à 0,881 ms par compte dans le cas le plus
défavorable, soit **29,4 minutes** extrapolées pour 2 M de comptes contre 90 de fenêtre ;
exigibilité des crédits à 1,788 ms par contrat, charges de retard à 1,585 ms par impayé,
classification et provisionnement à 1,168 ms par crédit, mobilisation et intérêts intercalaires à
0,605 ms par crédit en cours de déblocage.

## Ce que le P0 garantit, et comment c'est prouvé

| Invariant | Où il est tenu | Test |
|---|---|---|
| Journal immuable | Déclencheur `BEFORE UPDATE/DELETE` + droits restreints | `PostingIT.journal_is_immutable` |
| Équilibre par devise | `EntryValidator` + contrainte différée par partition | `EntryValidatorTest`, `PostingIT.database_refuses_unbalanced_entry` |
| Équilibre en contre-valeur | `EntryValidator` | `inconsistent_rates_within_a_currency_are_rejected` |
| Idempotence | Table satellite non partitionnée, réservée en 1ʳᵉ instruction | `PostingIT.replay_is_idempotent` |
| Contre-passation unique, dates de valeur préservées | `journal_reversal` + `Reversals` | `PostingIT.reversal_restores_balance_and_is_unique` |
| Aucune écriture en période fermée | `accounting_period` | `PostingIT.closed_period_is_refused` |
| Disponible jamais négatif | Verrou pessimiste ordonné | `ConcurrencyIT.available_balance_never_goes_negative` |
| Aucun interblocage sur virements croisés | Ordre total sur `account_id` | `ConcurrencyIT.cross_transfers_never_deadlock` |
| Compte chaud exact sous contention | Striping 32 sous-soldes | `ConcurrencyIT.hot_account_stays_exact_under_contention` |
| Soldes = rejeu du journal | `Reconciliation` | asserté dans chaque test d'intégration |
| Accruals sans dérive sur 365 jours | Imputation de l'écart du cumul arrondi | `daily_accrual_over_a_year_does_not_drift` |
| Recalcul rétroactif sur écriture antidatée | `InterestAccrualService.recomputeFrom` | `an_antedated_entry_triggers_retroactive_recompute` |
| Tout mois vaut 30/360, février compris | `DayCountConvention` | `thirty_360_compensates_february` |
| Découpage d'une période sans effet sur le total | Additivité | `le_decoupage_dune_periode_ne_change_pas_le_total` |
| Taux résolu à la date de la journée, pas du traitement | `product_version` daté + `CatalogTermsResolver` | `each_day_uses_the_rate_in_force_that_day` |
| Recalcul rétroactif réappliquant les taux d'époque | idem | `retroactive_recompute_reapplies_historical_rates` |
| Intérêts de crédit reconnus jour après jour, exacts à l'échéance par construction | `LoanInterestAccrualService` — étalement de l'intérêt contractuel, cumul arrondi | `etalementPuisReprise` |
| Intérêts capitalisés au client, nets de retenue, à la fin de période civile | `InterestSettlementService` + `interest_withholding` | `quarterly_capitalisation_pays_net_of_withholding`, `quarter_end_settles_both_sides` |
| Agios à deux taux, dans et au-delà de l'autorisation, arrêtés taxe comprise | `OverdraftRate` + bloc `overdraft.*` | `overdraft_interest_accrues_at_two_rates_and_is_charged_with_tax` |
| Sous-livres = grand livre chaque nuit, écart nommé et bloquant | `Reconciliation.Check` par module | `ecartsNommes`, `a_sub_ledger_gap_blocks_the_day` |
| Un mois ne se clôt que complet et rejoué intégralement, et se rouvre en le disant | `StandardTfm` | `september_is_closed_then_reopened` |
| Jamais deux versions de produit actives simultanées | `EXCLUDE USING gist` | `overlapping_versions_are_rejected` |
| Le rédacteur d'un paramétrage ne l'active pas | `CHECK (approved_by <> created_by)` | `maker_cannot_be_checker` |
| Un type de produit hors catalogue est refusé dès la saisie | `ProductFamilies` | `unknown_family_is_refused_upfront` |
| Un paramétrage incomplet ne s'active pas, et tout ce qui manque est nommé | `ProductFamily.validate` | `incomplete_parameters_are_refused_at_activation` |
| Un paramètre que la famille ne déclare pas est refusé | idem | `parametreEtranger` |
| Une exigence conditionnelle joue aussi sur la valeur par défaut | idem | `assietteParDefaut` |
| Une commission déclarée sans compte de produit ne se déploie pas | Bloc répété du descripteur | `commissionSansCompte` |
| Un fichier de familles incohérent fait échouer le chargement | `ProductFamilies.parse` | `conditionSansDeclencheur`, `marqueurMalPlace` |
| Tout paramètre lu par le code est déclaré par une famille, et réciproquement | Test d'accord par module | `parametresDeCreditDeclares`, `aucunParametreMort` |
| Un compte cité par le paramétrage existe, est général, de l'entité et de la devise du produit | `ProductCatalog.validate` | `an_unknown_account_is_refused_at_activation`, `a_foreign_or_customer_account_is_refused` |
| Un compte ne se rattache pas à un produit absent ou d'une autre devise | `ProductCatalog.assignProduct` | `assignment_checks_currency_and_existence` |
| Un contrat ne se porte que sur des comptes clients de son entité, dans sa devise | `LoanStore.createContract` | `contratSurCompteImpropre` |
| Les scripts s'appliquent en ordre, avec somme de contrôle ; un script modifié ou disparu est refusé | `SchemaMigrator` | `modifiedScriptIsRefused`, `vanishedModuleIsRefused`, `outOfOrderScriptIsRefused` |
| Un déploiement refuse un classpath à trous | `SchemaMigrator.Gaps.REFUSED` | `gaps` |
| La bascule de journée garantit les partitions à trois mois et la période comptable suivante | `OpenNextDayStep` | `the_year_end_run_prepares_january_on_its_own` |
| Une période close n'est pas rouverte par la bascule : le contrôle préalable la nomme | `PreChecksStep` | `a_closed_period_is_named_not_reopened` |
| Une journée ne s'annule pas sous une journée suivante arrêtée | `TfjEngine.cancel` | `cancelling_a_day_behind_a_later_run_is_refused` |
| Un crédit sans échéance à venir ni créance ouverte est clos à l'arrêté, après la classification | `LoanService.closeSettled` | `clotureALaDerniereEcheance`, `clotureParLArreteEtReouverture` |
| Un encours résiduel sur un crédit soldé est nommé, jamais effacé | idem | `encoursResiduelNomme` |
| Toute opération du catalogue est réclamée par un point d'entrée, ou est une consultation | `OperationCoverageTest` | `every_operation_is_claimed` |
| Une règle plafonnée plafonne chacun de ses rôles | idem | `every_ceilinged_rule_covers_all_its_roles` |
| Toute opération protégée porte une règle | Bloc statique de `SecurityConfig` | `the_policy_is_exhaustive` |
| Aucune annotation d'habilitation dans le code | Scan du code de production | `no_authorization_annotation_anywhere` |
| Refus avant tout effet de bord | `UseCaseExecutor`, point unique | `a_denial_happens_before_any_side_effect` |
| Jeton sans entité juridique rejeté | `KeycloakCallerFactory` | `a_token_without_legal_entity_is_rejected` |
| Consultations tracées, refus tracés | `JdbcAuthorizationAudit` | `a_successful_read_is_traced` |
| Catalogue `roles.json` ≡ politique, aucun orphelin | `RoleCatalogue.validateAgainstPolicy` | `constants_and_policy_agree` |
| Provisionnement idempotent et jamais destructeur | `RoleStartupTask` | `an_orphan_realm_role_is_reported_never_deleted` |
| Un refus d'authentification n'est jamais rejoué | `KeycloakAdminProvisioner` | `an_auth_failure_is_never_retried` |
| Un seul TFJ réel par entité et par date | Index unique partiel | `a_failed_run_resumes_at_the_failing_step` |
| Sauter une journée est impossible | Garde sur la date courante | `skipping_a_day_is_impossible` |
| Reprise à l'étape fautive, sans rejouer les précédentes | `TfjEngine.resume` | `a_failed_run_resumes_at_the_failing_step` |
| Annulation = contre-passation intégrale + restauration de la date | `TfjEngine.cancel` | `cancelling_a_run_reverses_its_entries_and_restores_the_date` |
| TFJ à blanc : même chemin de code, aucune trace | Transaction annulée | `a_dry_run_reports_everything_and_leaves_nothing` |
| Calcul par lot ≡ calcul compte par compte | `BatchInterestAccrualService` | `batch_and_per_account_agree_exactly` |
| Un compte sans mouvement mais mal paramétré est signalé | idem | `a_movementless_but_misconfigured_account_is_reported` |
| Date de valeur calculée, jamais fournie ; absence de règle = refus | `ValueDatePolicy` | `a_missing_rule_is_a_refusal` |
| Le calendrier refuse de répondre hors de sa période saisie | `BusinessCalendar` | `the_calendar_refuses_beyond_its_coverage` |
| La journée bascule au jour ouvré ; le lundi rémunère le week-end | `OpenNextDayStep` | `the_day_rolls_to_the_next_business_day_and_monday_pays_the_weekend` |
| Le secret du compte de service ne fuit nulle part | `KeycloakAdminConfig` | `the_service_account_secret_never_leaks` |
| Tout rôle est porté par un poste, sinon inattribuable | `JobProfile` | `every_role_is_carried_by_a_profile` |
| Écart de provisionnement Keycloak détecté | `KeycloakProvisioning.drift` | `a_missing_role_is_reported_as_silently_blocking` |
| Un auditeur qui opère voit son jeton refusé | Ségrégation des tâches | `an_auditor_holding_an_operational_role_is_refused` |
| Schéma déséquilibré par les arrondis refusé au stockage | `SchemaValidator`, tirage déterministe | `an_unbalanced_schema_cannot_even_be_stored` |
| Le moteur n'arrondit jamais à la place de l'auteur | `SchemaEngine` | `a_non_bookable_amount_is_refused_not_silently_rounded` |
| Fonction inconnue rejetée au chargement, pas au TFJ | Analyse de l'expression | `the_function_set_is_deliberately_small` |
| Une échéance au 31 ne dérive pas après février | Périodes calculées depuis l'ancrage | `pas_de_derive_de_fin_de_mois` |
| Une période n'est jamais facturée deux fois | `EXCLUDE USING gist` sur la plage de période | `chevauchement_refuse` |
| Une reprise sous un nouveau run ne refacture pas | Clé d'idempotence dérivée du contenu | `reprise` |
| Une annulation rend la période exigible et la refacturation aboutit | Génération dans la clé | `annulation_rend_la_periode_exigible` |
| La taxe est assise sur le montant réellement porté au compte de produit | `FeeCalculator` | `taxe_assise_sur_le_net_arrondi` |
| Une commission non perçue laisse une trace chiffrée | `fee_charge` renseigné quel que soit le dénouement | `provision_insuffisante_rejet`, `exoneration` |
| La dette la plus ancienne est soldée avant la commission du jour | Ordre de traitement | `impaye_avant_commission_du_jour` |
| Une liquidation est figée, seul son dénouement évolue | Déclencheur `guard_fee_charge` | `liquidation_immuable` |
| Une exonération n'est ni accordée ni validée par la même personne | `CHECK (approved_by <> granted_by)` | `exoneration_sans_separation_des_taches` |
| Le plus fort découvert est constaté en date de valeur | Série reconstituée sur la période | `plus_fort_decouvert` |
| La commission précède les intérêts, qui portent sur le solde diminué | Ordre des étapes du TFJ | `commission_avant_interets` |
| La somme des capitaux amortis égale exactement le capital emprunté | Invariant de `AmortisationSchedule` | `sommeDesCapitaux`, 600 cas générés |
| Une annuité qui n'amortit pas est refusée, pas produite | `ScheduleGenerator` | `annuiteQuiNAmortitPas` |
| L'intérêt d'une échéance = somme des intérêts courus quotidiens | Convention de décompte partagée | `coherenceAvecLeMoteurDAccruals` |
| Une échéance au 31 ne dérive pas après février | `Periodicity` | `echeancesEnFinDeMois` |
| Un ordre d'imputation incomplet est refusé | `AllocationOrder` | `ordreIncomplet` |
| Jamais deux échéanciers en vigueur à la même date | `EXCLUDE USING gist` | `deuxEcheanciersEnVigueur` |
| Un plan de remplacement ne reprend pas d'échéances déjà exigibles | `LoanStore` | `rechelonnementRetroactif` |
| Une créance ne remonte jamais | Déclencheur `guard_loan_receivable` | `creanceNeRemontePas` |
| L'encours ne diminue qu'au règlement, jamais à l'échéance | Schémas comptables du crédit | `exigibilite` |
| L'annulation du TFJ rend l'échéance à nouveau exigible | `TfjEngine.cancel` | `annulationRendLEcheanceExigible` |
| L'intérêt de retard ne porte jamais sur lui-même | Assiette construite sans les créances de retard | `aucuneCapitalisation` |
| Le cumul de retard s'arrondit, pas la journée | Même procédé que les accruals | `aucuneDerive` |
| L'assiette de retard est reconstituée jour par jour | Historique des imputations daté | `assietteReconstituee` |
| Une pénalité est perçue une fois par échéance, pas chaque jour | Index unique partiel | `penaliteUneSeuleFois` |
| Le montant d'une créance ordinaire ne court jamais | Déclencheur `guard_loan_receivable` | `creanceOrdinaireFigee` |
| L'annulation du TFJ reprend exactement l'intérêt de retard imputé | `TfjEngine.cancel` | `annulationReprendLInteretDeRetard` |
| Une grille de déclassement trouée est refusée au chargement | `RiskGrid` | `trouDansLaGrille` |
| Un taux de provision ne décroît jamais avec la dégradation | `RiskGrid` | `tauxDecroissant` |
| La frontière sain / en souffrance ne s'inverse pas | `RiskGrid` | `frontiereInversee` |
| Une garantie ne couvre pas au-delà de ce qu'elle garantit | `Provisioning` | `garantieSurevaluee` |
| La provision ne dote que sa variation, jamais son montant entier | `LoanClassificationService` | `dotationDifferentielle` |
| La contagion déclasse tous les encours du client | idem | `contagionClient` |
| Au franchissement du seuil, les intérêts constatés sortent du résultat | idem | `suspensionDesInterets` |
| Ensuite, les intérêts naissent directement en intérêts réservés | idem | `interetsSuivantsReserves` |
| Chaque intérêt est repris sur le compte où il a été constaté | `LoanSchemas.interestSuspension` | `classificationEtProvision` |
| Une grille altérée en base est refusée à la relecture | `RiskProfiles.resolveAt` | `grilleRevalidee` |
| Le plafond d'usure se contrôle sur le taux effectif, pas sur le nominal | `LoanService.disburse` | `plafondDepasse` |
| Le TEG est arrêté au déblocage avec sa convention | idem | `tegConserve` |
| Un déblocage reste équilibré même si les frais dépassent le capital | `LoanSchemas.disbursement` | `fraisPlafonnesDansLeSchema` |
| L'indemnité de remboursement anticipé est plafondée par les deux limites légales | `Prepayment.indemnity` | `doublePlafond` |
| Un remboursement anticipé est refusé tant que des échéances restent dues | `LoanService.prepay` | `impayesAvantAnticipation` |
| Le plan reconstruit conserve le taux et les accessoires du contrat | `LoanTerms.forRemaining` | `conditionsReconduites` |
| Un crédit régularisé reste déclassé pendant la période d'observation | `RiskGrid.stillUnderObservation` | `periodeDObservation` |
| L'observation ne joue que dans un sens : une dégradation reste immédiate | idem | `degradationImmediate` |
| La quotité d'une sûreté vient du référentiel, jamais de la saisie | `CollateralPolicy` | `quotiteDuReferentiel` |
| Un second rang n'est couvert que par ce que le premier laisse | `CollateralValuation` | `secondRang` |
| Une expertise périmée ne couvre rien, et l'exclusion remonte | idem | `expertisePerimee` |
| Une sûreté partagée n'est comptée qu'à sa quote-part | idem | `suretePartagee` |
| Une sûreté ne peut pas être affectée à plus de 100 % | Déclencheur différé | `quotePartsExcessives` |
| Deux sûretés de même rang sur le même actif sont refusées | `uq_collateral_rank` | `rangsEnDoublon` |
| Les intérêts intercalaires ne courent que sur le capital mobilisé | `InterimInterest` | `assietteMobilisee`, `mobilisationComplete` |
| Une tranche porte intérêt du jour de sa mise à disposition, pas du suivant | idem | `deblocageEnCoursDePeriode` |
| La série des périodes intercalaires couvre la mobilisation sans trou ni recouvrement | `InterimInterest.periodEnds` | `serieContinue` |
| Un plan de déblocage dont le total diffère du capital accordé est refusé | `DisbursementPlan` + déclencheur différé | `totalDifferent`, `planIncoherent` |
| Une tranche ne se débloque ni hors délai, ni hors ordre, ni deux fois | `LoanMobilisationService` | `trancheHorsDelai`, `ordreDesTranches` |
| Verser plus que le montant prévu est refusé | idem | `depassementDuPlan` |
| Aucun échéancier n'existe avant la clôture de la mobilisation | idem | `echeancierALaCloture` |
| Tirer moins que le montant accordé réduit l'échéance, jamais la durée | `LoanTerms.forDrawn` | `tranchePartielle` |
| Une période intercalaire n'est facturée qu'une fois, TFJ rejoué compris | Index unique partiel | `reprise` |
| Le TEG d'un crédit par tranches actualise aussi ce qui est reçu | `EffectiveRate.between` | `tauxEffectifDesTranches` |
| L'annulation du TFJ rouvre la mobilisation et retire l'échéancier publié | `TfjEngine.cancel` | `annulationRouvreLaMobilisation` |

## Les choix qui vont au-delà des progiciels établis

### 1. Ledger bitemporel

Le journal porte **trois dates** — date comptable, date de valeur, instant de connaissance — et les
soldes s'interrogent sur les trois axes.

```java
// « Quel était le solde au 31 décembre, tel qu'on le connaissait le 15 janvier ? »
Balances.asKnownAt(connection, compte, arrete, instantDeLEdition);
```

Quand un état régénéré ne redonne pas le chiffre de l'original, les progiciels établis ne savent que
constater l'écart. Ici l'état édité reste reproductible à l'identique des années après, et la
différence avec le solde courant isole exactement les écritures antidatées arrivées entre-temps.
C'est précisément la question posée en inspection.

Démontré par `BitemporalIT.an_issued_statement_stays_reproducible`.

### 2. Invariants exécutables, pas documentaires

Les règles du §00 du dossier ne sont pas des recommandations : chacune a un test qui échoue si elle
est violée, et les tests de propriétés explorent des combinaisons qu'aucune rédaction manuelle
n'anticipe — même compte débité et crédité dans une écriture, montants extrêmes, écritures à huit
lignes, dates de valeur décalées.

### 3. Des intérêts exacts, y compris rétroactivement

En devise sans subdivision, un accrual quotidien n'est jamais imputable tel quel : 115,068 49 XOF
ne s'écrit pas au journal. Trois stratégies existent, deux sont fausses.

| Stratégie | Verdict |
|---|---|
| Arrondir chaque jour | Dérive de 25 XOF/an/compte — des millions à l'échelle du portefeuille |
| Attendre la capitalisation | Exact, mais les intérêts courus disparaissent du bilan — non conforme |
| **Imputer l'écart du cumul arrondi** | Montant toujours entier, écart au cumul exact < 1 unité en permanence |

La troisième est retenue. `daily_accrual_over_a_year_does_not_drift` le vérifie sur 365 journées
réelles : **42 000 XOF imputés**, là où l'arrondi quotidien aurait donné 41 975.

Et le **recalcul rétroactif** — risque R1 du dossier, celui qui produit des agios faux dès les
premières semaines d'exploitation — extourne les écritures devenues fausses, recalcule sur la série
de soldes corrigée, et conserve la génération précédente pour l'audit. Chaque journée reste
explicable : assiette, taux effectif, fraction d'année.

### 4. Des schémas comptables validés avant d'entrer en base

Un schéma traduit un événement métier en écritures : `MAINTENANCE_FEE` → débit total, crédit
commission, crédit TVA. C'est du paramétrage, pas du code — ajouter une commission ou une taxe ne
demande plus de livraison.

Le langage d'expression est **volontairement minuscule** : quatre opérations, comparaisons,
`round/abs/min/max`. Ni variable affectable, ni boucle, ni appel externe. Un moteur de règles
expressif devient un langage de programmation sans tests ni débogueur, dans lequel la logique de
commissions d'une banque est écrite par des gens qui n'ont jamais vu de compilateur — c'est le
principal facteur d'ingouvernabilité des core banking anciens.

**Validation par tirage, à l'enregistrement.** Le schéma est évalué sur 300 jeux de valeurs
déterministes, et l'équilibre est vérifié **après arrondi à l'échelle de la devise**. C'est ce
dernier point qui compte : un schéma qui débite `total` et crédite `net` puis `tva`, chacun arrondi
séparément, est exact en arithmétique réelle et **faux en XOF**. Il est refusé au déploiement avec
son contre-exemple, au lieu de produire en production un écart de quelques unités dont l'origine est
introuvable.

Les **dérivations** rendent cela possible : le schéma déclare `total = round(base + base*taux, 0)`,
`tva = round(base*taux, 0)`, `net = total - tva`. Seules les grandeurs libres sont tirées, les autres
calculées — sans quoi des valeurs indépendantes violeraient l'identité et tout schéma, même correct,
semblerait faux.

### 5. Une politique d'habilitation qui ne peut pas être incomplète

Toutes les règles dans une seule classe, **zéro annotation** — un test échoue si l'une réapparaît.

Le risque évident de cette approche est qu'une table centrale incomplète laisse un trou plus
discret qu'une annotation oubliée. Deux garde-fous le neutralisent, et ce sont eux qui rendent
l'approche plus sûre que les annotations :

1. Le catalogue d'opérations est une énumération, et `SecurityConfig` **refuse de charger** si une
   valeur n'a pas de règle : l'application ne démarre pas.
2. Un cas d'usage ne s'invoque que par `UseCaseExecutor`. Un appel qui l'évite n'est pas un contrôle
   oublié, c'est un cas d'usage inaccessible.

Avec des annotations, l'oubli laisse une méthode ouverte et s'exécute sans bruit. Ici, l'oubli
possible porte sur la règle, et il empêche le démarrage.

Keycloak fournit l'identité et le périmètre ; **les plafonds restent dans le code revu**. Un
attribut mal renseigné dans un annuaire ne doit pas pouvoir élever le plafond d'un guichetier.

### 6. Un TFJ à blanc qui emprunte vraiment le même chemin

Le mode simulation n'est pas un second code : le traitement complet s'exécute dans une
**transaction annulée à la fin**. Mêmes contrôles, mêmes calculs, mêmes écritures, mêmes
contraintes de base — et rien n'est conservé. Un mode simulation qui court-circuiterait la
comptabilisation ne testerait pas ce qui casse en production, et ne prouverait donc rien.

Seuls les statuts d'étape sont écrits dans des transactions indépendantes : le rapport doit
survivre à l'annulation.

Contrepartie à connaître : l'ensemble tient dans une seule transaction, donc un seul jeu de
verrous. Sur un portefeuille entier, un TFJ à blanc est long et bloquant — il se lance sur un
échantillon ou hors des heures de service.

### 7. Des dates de valeur calculées, avec leur prix mesuré

La date de valeur ne se fournit pas, elle se calcule. Tant que l'appelant la transmet, chaque canal
applique sa propre lecture des conditions de banque, et l'écart ne se voit pas : l'écriture est
équilibrée, la comptabilité juste, **seuls les agios sont faux**.

Le **sens fait partie de la clé** de la règle, parce que c'est là qu'est le sujet : un retrait porte
souvent une date de valeur antérieure, un versement une date postérieure. Un test chiffre l'enjeu —
sur 10 M XOF à 6 %, deux jours de valeur valent **3 288 XOF par opération**.

L'absence de règle est un refus, jamais un repli sur la date comptable : ce repli serait la forme la
plus discrète de l'erreur, puisqu'il produit un résultat plausible.

### 8. Des commissions dont le non-perçu est chiffré

Une commission qui n'est pas prélevée ne laisse, dans un core banking ordinaire, **aucune trace** :
le journal reste équilibré, la réconciliation passe, et la banque ignore ce que lui coûtent ses
gestes commerciaux et ses comptes non provisionnés. Ici chaque période échue produit une ligne de
registre **avec son montant**, quel que soit le dénouement — perçue, exonérée, reportée, abandonnée,
non due. Le manque à gagner devient une somme SQL.

Trois décisions gouvernent la perception :

| Question | Réponse retenue | Pourquoi |
|---|---|---|
| La provision ne couvre pas | `REJECT`, `FORCE` ou `DEFER`, au paramétrage | Aucune n'est bonne partout : abandonner perd du produit, forcer crée un découvert que la banque a elle-même provoqué, reporter demande un suivi |
| Encaisser à moitié ? | Jamais | La taxe suit la commission ; couper une assiette taxable déclarée rend la déclaration irréconciliable |
| Impayé ou commission du jour d'abord ? | L'impayé | L'ordre inverse ferait vieillir les créances les plus anciennes jusqu'à leur abandon tout en encaissant les plus récentes |

Et la fiscalité est traitée à l'unité près : la taxe est assise sur le **net arrondi**, c'est-à-dire
sur le montant réellement porté au compte de produit. Arrondir le total toutes taxes comprises puis
déduire la taxe par différence donne un franc de plus au client et une base déclarée qui ne
correspond à aucun montant comptabilisé. `taxe_assise_sur_le_net_arrondi` fixe le cas : 1 525 XOF à
18 % donnent 274 et non 275.

La **commission du plus fort découvert** est constatée sur la série des soldes **en date de valeur**,
jour par jour sur la période. Un test le chiffre : un compte qui plonge à 4 000 000 XOF de découvert
du 10 au 19 et finit le mois largement créditeur est commissionné sur le pic, pas sur le solde de
clôture — qui ne facturerait rien.

### 9. Un échéancier qui ferme exactement

En XOF, une annuité de 88 848,7 s'impute à 88 849. Répétée soixante fois, l'unité d'écart se
cumule : la somme des capitaux amortis ne redonne pas le capital emprunté, et il reste au client un
solde résiduel de quelques francs **après sa dernière échéance**. Le défaut est invisible à la
lecture de l'échéancier et se manifeste des années plus tard, par une relance pour un montant que
personne ne sait expliquer.

La règle retenue est la seule qui ferme : **la dernière échéance solde le capital restant dû**. Son
total diffère alors de quelques unités des précédentes — c'est visible, c'est explicable, et c'est
un invariant de construction : un échéancier qui ne le respecte pas ne peut pas être représenté,
qu'il vienne du générateur, d'une reprise de données ou d'un rééchelonnement saisi à la main.

Deux refus qui comptent, tous deux découverts en écrivant les tests :

| Cas refusé | Ce qui se passerait sinon |
|---|---|
| Une annuité qui ne couvre pas les intérêts de la première échéance | Le capital ne diminuerait jamais ; l'échéancier paraît normal sur les premières lignes et la dernière réclame tout. Le cas est réel en micro-crédit : 100 XOF sur trente ans à 12 % donnent une annuité de 1,0286, qui s'impute à 1 — exactement les intérêts du mois |
| Un plan de remplacement qui reprend des échéances déjà exigibles | Régénérer un plan complet depuis l'origine est l'erreur naturelle. Les échéances déjà réclamées y figurent et seraient facturées une seconde fois |

Et chaque méthode porte **sa** convention de taux, ce qui n'est pas un réglage : à annuités
constantes le taux est périodique proportionnel, sans quoi l'échéance ne serait pas constante ;
ailleurs il suit les jours réellement écoulés, et l'intérêt d'une période égale alors *exactement*
la somme des intérêts courus quotidiens que produit le moteur d'accruals. Les deux chiffres
racontent la même histoire — sans quoi le produit constaté au fil de l'eau ne correspondrait pas à
l'intérêt réclamé à l'échéance, et l'écart n'aurait aucune explication comptable.

### 10. Un régime de retard qui ne capitalise pas

Deux prélèvements de nature différente, et les tenir séparés n'est pas une subtilité : l'**intérêt
de retard** court chaque jour sur l'impayé, la **pénalité** se perçoit une fois par échéance. Les
confondre produit soit une pénalité quotidienne, soit aucun intérêt de retard — et les deux se
voient sur le relevé du client bien avant d'être comprises.

Trois propriétés qui distinguent ce moteur :

**L'assiette exclut les créances de retard par construction.** Faire porter intérêt aux intérêts
échus est de l'anatocisme, encadré voire prohibé dans la plupart des droits de la zone. Ce n'est
pas un paramètre que l'on pourrait inverser par inadvertance : les catégories `LATE_INTEREST` et
`PENALTIES` ne sont jamais lues dans l'assiette. L'écart est invisible sur un jour et considérable
sur un contentieux de deux ans.

**L'assiette est reconstituée jour par jour, jamais estimée sur l'état courant.** Un rattrapage de
quinze jours doit facturer chaque journée sur l'impayé tel qu'il était ce jour-là. Un test le
chiffre : un règlement du 31 octobre traité le 15 novembre donne 890 XOF d'intérêts de retard sur
trente et une journées, là où le calcul sur la seule assiette d'aujourd'hui en donnerait 594, et
celui sur la seule assiette d'origine 1 205.

**Le cumul s'arrondit, la journée non.** Soixante journées à 38,884 XOF font 2 333 au cumul exact ;
l'arrondi quotidien en ferait 2 340. Sept francs par contrat et par deux mois de retard,
systématiquement au détriment du client, et l'écart croît linéairement avec la durée du
contentieux.

Enfin, la **contre-passation d'un TFJ reprend exactement l'intérêt qu'il avait imputé**. Sans elle,
l'écriture serait annulée et la créance resterait gonflée d'un montant dont plus aucune écriture ne
rend compte — et la réconciliation ne le verrait pas, puisqu'elle ne porte que sur le journal.

### 11. La suspension des intérêts, que presque personne n'implémente

Le dossier de conception le signale comme « régulièrement omise dans les développements maison », et
c'est exact : au-delà d'un certain niveau de dégradation, les intérêts doivent **cesser d'être
constatés en produits**. Les omettre laisse la banque porter en résultat des intérêts qu'elle ne
percevra pas — le produit net bancaire est surévalué, et la non-conformité est directe.

Ici la suspension fait trois choses, et les trois comptent :

1. **Au franchissement du seuil**, les intérêts déjà constatés et encore impayés sortent du compte
   de résultat vers un compte d'**intérêts réservés**, hors P&L.
2. **Ensuite**, les intérêts constatés y naissent directement — l'échéance suivante ne repasse
   jamais par le compte de produits.
3. **Chaque composante est reprise sur le compte où elle avait été constatée** : les intérêts
   contractuels sur le produit d'intérêts, les intérêts de retard sur le produit sur créances en
   souffrance. Les reprendre en bloc sur un seul compte creuserait un solde négatif sur l'autre —
   défaut trouvé par un test d'intégration, pas à la relecture.

La grille de déclassement est le paramétrage le plus lourd de conséquences du socle : elle décide
du niveau de provision de tout un portefeuille, donc du résultat publié. Quatre défauts y sont
possibles, tous silencieux, et tous refusés au chargement :

| Défaut | Ce qu'il produirait |
|---|---|
| Un trou entre deux classes | Un crédit à 91 jours ne serait classé nulle part, et le traitement échouerait sur ce crédit-là seulement, un soir d'arrêté |
| Un chevauchement | Le classement dépendrait de l'ordre de lecture, et l'arrêté ne serait pas reproductible |
| Un taux qui décroît avec la dégradation | Se lit comme une inversion de deux lignes dans un tableur, et n'a aucun sens prudentiel |
| Une classe saine après une classe douteuse | La frontière commande la suspension des intérêts et la déclaration à la centrale des risques ; elle ne peut pas alterner |

Et la **contagion** déclasse tous les encours d'un client au niveau du plus dégradé : un client qui
ne rembourse plus l'un de ses crédits ne présente pas un risque différent sur les autres. L'ignorer
sous-estime le risque exactement là où il se matérialise.

### 12. Le coût réel du crédit, et le plafond qui porte dessus

Le taux nominal ne dit rien du coût d'un crédit. Des frais de dossier prélevés au déblocage
réduisent la somme reçue sans réduire ce qui est remboursé ; une assurance, une taxe, un différé
déplacent les flux. Le **taux effectif global** est le seul chiffre comparable d'un crédit à
l'autre, et c'est lui que la réglementation plafonne.

| Même échéancier, même taux nominal de 12 % | TEG |
|---|---|
| Sans frais, convention proportionnelle | 12,03 % |
| Sans frais, convention actuarielle | 12,71 % |
| Avec 2 % de frais de dossier, proportionnelle | **15,89 %** |
| Avec assurance 0,05 %/mois et TAF 18 %, actuarielle | **16,37 %** |

Deux conséquences que les tests fixent. D'abord, **la convention fait partie du chiffre** : sur un
plafond d'usure à 12,5 %, le même crédit est licite ou ne l'est pas selon qu'on annualise
proportionnellement ou actuariellement. Elle est donc paramétrée par produit et conservée avec le
taux.

Ensuite, **le contrôle porte sur le taux effectif**. Un crédit affiché à 12 % franchit un plafond à
15 % dès qu'on lui prend 2 % de frais — et c'est précisément le montage qu'un contrôle sur le taux
nominal laisse passer. Le refus intervient au déblocage, avant tout versement : après, le
dépassement ne se corrige plus.

La résolution est une **dichotomie**, pas un Newton. La fonction est strictement croissante dès que
l'emprunteur rembourse plus qu'il n'a reçu, donc la dichotomie converge toujours, en un nombre
d'itérations connu d'avance. Newton converge plus vite mais peut diverger sur un échéancier
dégénéré, et un moteur de calcul réglementaire ne peut pas se permettre un cas où il ne rend pas de
réponse. L'intervalle s'élargit tant que la racine n'y est pas : un crédit bonifié, donc à taux
négatif, est chiffré et non refusé.

### 13. Des garanties qui ne couvrent que ce qu'elles couvrent

Surévaluer une sûreté réduit directement la provision, et rien dans l'écriture ne le signale. Quatre
réductions s'appliquent donc, dans cet ordre, et chacune corrige une erreur courante :

| Réduction | L'erreur qu'elle évite |
|---|---|
| **Le rang** | Un second rang compté comme s'il était seul fait garantir deux fois le même immeuble |
| **Le montant garanti** | Une hypothèque de 10 M sur un immeuble qui en vaut 30 ne couvre que 10 — et l'inverse aussi |
| **La quote-part** | Une même hypothèque comptée en entier sur deux crédits divise la provision du client par deux |
| **La quotité réglementaire** | Elle vient du type de sûreté, jamais de la saisie |

Ce dernier point est le plus structurant. Laisser saisir la décote sur le dossier revient à laisser
un agent décider du niveau de provision de son propre portefeuille : une hypothèque retenue à 100 %
au lieu de 50 % divise la provision par deux. La quotité est donc une donnée du référentiel, datée
et sous double validation, comme un barème tarifaire.

Deux exclusions, **toujours signalées** : une expertise périmée — une valeur d'il y a quatre ans
n'est pas une valeur — et un type de sûreté sans quotité paramétrée, écarté plutôt que retenu à
100 %. Une garantie silencieusement exclue laisse croire à une couverture qui n'existe pas, et cela
ne se découvre qu'à la réalisation.

### 14. Un paramétrage qui ne peut pas être incomplet

Le paramétrage produit était un sac de couples clé/valeur, typé à la lecture. Trois conséquences,
toutes silencieuses :

- un produit de crédit sans compte de créances rattachées **s'activait sans rien dire**, et l'erreur
  ne se découvrait qu'au premier TFJ qui en avait besoin — la nuit, sur une étape bloquante, avec un
  arrêté à reprendre ;
- rien n'empêchait un produit d'épargne de porter `loan.penalty_rate` : le paramètre n'était jamais
  lu, et il donnait à son auteur la **certitude d'avoir paramétré une pénalité** qui ne
  s'appliquerait jamais ;
- `product_type` était stocké, relu, et **lu par aucune logique**.

La famille de produit est le contrat manquant. Elle vit dans `resources/product/families.json` —
même régime que `roles.json` : ressource versionnée avec le code, chargée et validée au démarrage,
jamais éditée depuis une console.

```json
{ "code": "TERM_LOAN", "label": "Credit amortissable",
  "required": ["loan.accrued_receivable", "loan.interest_income", "loan.tax_account"],
  "conditions": [
    { "when": "loan.risk_profile", "present": true,
      "require": ["loan.provision_expense", "loan.provision_allowance", "loan.reserved_interest"],
      "because": "classer un credit sans compte de dotation arrete le TFJ a la premiere provision" }
  ] }
```

Quatre mécanismes, chacun pour une classe de faute :

| Mécanisme | La faute qu'il attrape |
|---|---|
| **Exigence simple** | Le compte d'imputation oublié |
| **Exigence conditionnelle** | Le mode de pénalité sans son montant — et, parce qu'une condition porte aussi sur la **valeur par défaut**, la commission forfaitaire sans forfait, qui se percevrait à zéro |
| **Alternative** (`interest.rate` **ou** un barème) | L'exigence qui forcerait à saisir un taux fictif à côté d'un barème |
| **Bloc répété** (une commission par code de `fee.codes`) | La commission déclarée dont l'écriture n'aurait nulle part où aller |

Et une règle négative : **tout paramètre non déclaré est refusé**. C'est le seul moyen de distinguer
une valeur inutile d'une valeur mal nommée.

Le contrôle s'exécute à l'`activate()`, **avant** la double validation : faire valider par un second
regard un paramétrage que la machine sait incomplet lui ferait porter une responsabilité sur une
pièce incomplète. Tous les manques sont restitués d'un coup — s'arrêter au premier obligerait à
redéployer autant de fois qu'il manque de lignes, et le contrôle finirait par être désactivé.

Le fichier vit dans `product-catalog`, dont les modules de service dépendent : le compilateur ne
peut pas vérifier l'accord. Un test par module le fait, et dans les deux sens — `aucunParametreMort`
échoue aussi bien sur un paramètre lu et non déclaré que sur un paramètre déclaré et lu par
personne.

**Trois défauts réels trouvés par ce contrôle, en l'écrivant :** des produits de compte courant sans
paramètres d'intérêts — qui auraient arrêté l'étape d'accrual, bloquante, dès leur premier arrêté ;
un compte de pénalités désigné sans mode de pénalité, c'est-à-dire une pénalité jamais perçue ; et
une commission forfaitaire sans montant, qui se serait perçue à zéro sur tout le portefeuille.

### 15. Le déblocage par tranches, et les intérêts qu'il ne fait pas courir

Un crédit de construction, de campagne ou d'équipement ne verse pas la totalité à la signature : les
fonds suivent l'avancement. Le contournement habituel — tout débloquer sur un compte d'attente puis
« reverser » au fur et à mesure — laisse une comptabilité parfaitement équilibrée et fait payer à
l'emprunteur des fonds qu'il n'a pas reçus.

Sur le dossier de référence du test — 10 M XOF en deux tranches, 4 M à la signature et 6 M deux mois
plus tard, cinq mois de mobilisation à 12 % — l'écart se chiffre :

| Assiette des intérêts intercalaires | Coût pour l'emprunteur |
|---|---|
| Le montant **mobilisé**, jour par jour | 376 110 XOF |
| Le montant **accordé**, dès la signature | 506 302 XOF |

**130 192 XOF de trop**, soit un tiers, sur un seul dossier — et rien dans le journal ne le signale.

Trois moments, trois décisions différentes :

1. **À l'ouverture**, le plan est confronté aux conditions du crédit et le coût prévisionnel au
   plafond d'usure. C'est le seul moment où le refus a un sens : aucun franc n'est sorti.
2. **À chaque tranche**, les fonds sortent. L'ordre, la date limite et le montant prévu sont
   contrôlés ; au-delà, c'est le constat de la condition — « fondations achevées » — qui commande,
   et il est humain. Le socle conserve la condition en clair et ne l'interprète pas : prétendre
   l'automatiser reviendrait à débloquer des fonds sur la foi d'une date.
3. **À la clôture**, le capital tiré est connu. L'échéancier définitif est publié sur lui, et le TEG
   arrêté sur les **dates réelles** de versement.

Ce dernier point compte. Faire comme si tout avait été reçu à l'origine prête à l'emprunteur une
somme qu'il n'avait pas, et **sous-estime** le taux effectif — 11,83 % au lieu de 12,83 % sur le
dossier de référence. L'erreur va exactement dans le sens qui fait passer sous le plafond d'usure un
crédit qui le dépasse.

Trois conséquences qu'un modèle à déblocage unique ne sait pas porter :

- **Aucun échéancier n'existe pendant la mobilisation.** Tant qu'une tranche reste à débloquer, le
  capital à amortir n'est pas connu ; en publier un reviendrait à réclamer l'amortissement d'un
  capital non versé.
- **Tirer moins réduit l'échéance, pas la durée.** Ce que l'emprunteur a signé est un calendrier.
- **La mobilisation se clôt à sa date limite, et à elle seule.** Avoir tout tiré en avance ne
  raccourcit pas la période : c'est le contrat qui fixe le début de l'amortissement, et l'emprunteur
  qui détient les fonds en paie les intérêts jusque-là.

Le capital ne bouge pas : les intérêts intercalaires sont constatés en produits et portés en
créances rattachées, jamais capitalisés dans l'encours — ce qui produirait des intérêts sur des
intérêts, que le socle refuse par construction.

### 16. Le XOF traité comme une vraie contrainte

Échelle nulle native, accumulation en précision étendue, arrondi au seul moment de la
comptabilisation, écart d'arrondi restitué explicitement. `MoneyTest.daily_rounding_drifts_measurably`
mesure la dérive évitée : **25 XOF par an et par compte**, soit 12,5 M XOF sur 500 000 comptes.

### 17. Des intérêts réglés, des deux côtés, et des sous-livres rapprochés chaque nuit

Un moteur d'accruals qui n'est jamais réglé fait croître un compte de courus à l'infini ; c'est
l'état dans lequel le socle était jusqu'à la phase B de l'[audit](../docs/core-banking/14-audit.md).
Trois choses ont changé, et chacune a une règle qui se vérifie.

**Le brut réglé est le cumul exact arrondi à la fin de période, moins ce qui a déjà été réglé.** Le
moteur du cumul arrondi connaît le cumul de n'importe quelle journée ; l'arrêté qui tourne le lundi
règle donc exactement ce qui était couru le samedi, fin de trimestre, et les deux journées suivantes
restent en courus (`settlement_stops_at_the_period_end_even_when_run_later`). Les intérêts
capitalisés portent date de valeur du lendemain : ceux de la fin de période ont été calculés sur le
solde d'avant.

**Les crédits reconnaissent leur intérêt jour après jour**, par étalement linéaire de l'intérêt
contractuel de l'échéance sur les jours de sa période — la seule règle qui vaille pour toutes les
méthodes d'amortissement et qui retombe exactement sur l'échéance. Entre deux échéances, le résultat
d'un mois porte les intérêts de ce mois (`etalementPuisReprise`).

**Chaque module dit ce que le grand livre doit porter, et l'arrêté le vérifie.** Positions
d'intérêts contre comptes de courus, créances contre créances rattachées, capital restant dû contre
compte de prêt, commissions contre écritures : un écart d'un franc nomme le compte et bloque la
journée. C'est la classe de défaut — une écriture sans créance, une créance sans écriture — que la
balance, équilibrée, ne voit jamais, et que les tests avaient déjà trouvée deux fois.

### 18. Un compte qui vit : tiers, opérations, blocages, clôture

La phase C de l'[audit](../docs/core-banking/14-audit.md) a donné au moteur ce qui en fait un
système : un client, des opérations, un cycle de vie. Quatre règles y sont tenues par la
structure plutôt que par la vigilance.

**Un doublon de client est refusé par la base, pas détecté après coup.** Les identifiants
officiels d'une entité — CNI, passeport, NIF, RCCM, centrale des risques — sont sous un index
unique partiel : deux dossiers ne peuvent pas porter la même pièce. Le dédoublonnage périodique
devient une vérification, pas un traitement. Et la connaissance client restreint
progressivement : un dossier non vérifié ou dont la revue est dépassée continue d'opérer sur ses
comptes mais rien de nouveau ne s'y ouvre ; bloqué, il n'opère plus (`PartyIT`).

**Un blocage de compte est appliqué par le ledger, à chaque écriture.** Ce n'est pas un statut
— un statut qui refuserait tout ferait perdre au client les fonds qui lui arrivent, et à la
banque la contre-passation d'une erreur — mais un état superposé que le service d'imputation
lit avant d'écrire. Il prime donc sur les prélèvements automatiques sans qu'aucun service n'ait
à y penser (`blocages`, `LoanService.collect` sur le disponible).

**La date de valeur se calcule, elle ne se fournit pas.** Elle vient des conditions de banque de
l'entité, par type d'opération, canal et sens ; sans condition, l'opération est refusée — se
rabattre sur la date comptable produirait un résultat plausible et faux (`dateDeValeur`).

**Une clôture est un solde de tout compte, atomique.** Tout ce qui s'y oppose est nommé d'un
coup ; les intérêts des deux côtés sont calculés jusqu'à la veille et réglés le jour même ; le
solde est versé ; un solde débiteur refuse la clôture et les agios calculés pour elle
disparaissent avec elle (`clotureSurSoldeDebiteur`). Le jour de la clôture n'est pas rémunéré :
le versement du solde porte la même date de valeur qu'un retrait.

### 19. Une banque à N agences, un seul journal

Le multi-agences ([15](../docs/core-banking/15-multi-agences.md)) n'est pas une dimension de
reporting ajoutée après coup : c'est le **treizième invariant**, une écriture est équilibrée par
agence, tenu par le validateur et par la base. Trois règles en découlent.

**Les lignes de liaison sont générées, jamais saisies.** Un client de l'agence A servi à la caisse
de l'agence B produit une écriture équilibrée pour l'entité et déséquilibrée pour A et B ; le
service d'imputation la complète, via le siège, avant de l'écrire, et la contre-passation reprend
ces lignes telles quelles (`retraitDeplace`, `contrePassationExacte`). Sans compte de liaison dans
la devise, l'écriture est refusée — jamais équilibrée à défaut.

**Un compte général n'a pas d'agence, son solde en a une par ligne.** Le paramétrage cite un
compte ; la charge d'intérêts d'un client est dans le résultat de son agence, sans aucune liaison,
parce que le lot agrège une paire de lignes par agence
(`the_batch_posts_one_pair_of_lines_per_branch`). La balance agence est un cliché quotidien, pas
une dimension de plus sur le chemin d'imputation.

**La compensation inter-agences est un contrôle, pas un traitement.** Chaque nuit, chaque compte
de liaison doit se refléter entre son agence et le siège, et s'éliminer en total ; un écart nomme
l'agence et bloque la journée (`branch_balances_and_liaison_mirror`). Dans une même entité, les
liaisons ne se règlent pas, elles s'éliminent.

### 20. Une API qui ne décide rien

Le module `api` (Spring Boot 4.1, Tomcat embarqué) expose le socle, et c'est tout ce qu'il fait.
Chaque méthode de contrôleur a la signature validée avant construction —
`withdraw(Caller caller, UUID legalEntityId, UUID accountId, IdempotencyKey key, WithdrawalRequest body)`
— et trois choses n'y sont jamais demandées au client : **qui il est** (le `Caller` est dérivé du
jeton signé par Keycloak), **quelle est son agence** (celle du jeton, pour ce qui s'ouvre ou se
sert), **s'il a le droit** (`UseCaseExecutor` applique `SecurityConfig` ; le contrôleur ne
connaît aucune règle). La clé d'idempotence est un en-tête obligatoire : un client qui appuie
deux fois reçoit deux fois le même reçu, et n'est débité qu'une fois (`parcours_client`). Les
refus sont des réponses nommées, du `401` au `422`, et un `500` signifie qu'il n'a rien été
comptabilisé (`refus`). Le test fait tout le parcours contre un vrai serveur, une vraie base et
de vrais jetons.

### 21. Des restitutions lues dans le journal, et un résultat qui s'affecte exactement

**La balance est une lecture, pas un état.** Six colonnes par compte — ouverture, mouvements,
clôture —, calculées dans le journal à la date demandée, jamais dans un cliché : regénérée
demain, elle redonne le même chiffre, et ses totaux par devise s'équilibrent par construction,
constat rendu avec elle (`the_balance_has_six_columns_and_balances`). Le ledger ne tenant qu'un
seul type de compte, la balance de tous les comptes est la balance générale ; la balance
auxiliaire des clients et la balance d'agence sont des filtres de la même lecture, et une
balance filtrée dit qu'elle ne s'équilibre pas.

**Le grand livre se lit par curseur, sur un index.** La page suivante reprend strictement après
une position — date comptable, instant de connaissance, écriture, ligne —, quatre colonnes de
la ligne portées par un index dans cet ordre : le coût d'une page ne dépend pas de ce qui la
précède, et deux extractions ne se contredisent pas
(`the_ledger_is_read_by_cursor_in_the_statement_order`). Le curseur est opaque ; un curseur qui
ne se relit pas est une requête invalide, pas une page vide.

**Le résultat s'affecte exactement, là où la clôture l'a porté.** La décision de l'assemblée
devient une écriture, à deux, datée après la fin de l'exercice, qui solde le compte de résultat
agence par agence — les liaisons complétées par le service d'imputation — sur des comptes de
bilan du siège ; la somme est le résultat lu dans les écritures de la clôture, ni plus ni moins,
le report à nouveau étant une destination comme une autre
(`the_result_is_appropriated_exactly_once_and_clears_every_branch`). Une affectation ne s'efface
pas : on contre-passe son écriture pour la refaire ; et un résultat affecté retient la clôture,
qui ne s'annule qu'une fois l'affectation contre-passée.

### 22. Des états financiers qui disent ce qu'ils ne savent pas présenter

**Un état est une maquette appliquée au journal.** Bilan, compte de résultat et hors bilan sont
des paramétrages — rubriques et règles d'affectation — vérifiés avant d'entrer en base, rédigés
puis activés à deux, une seule maquette active par nature d'état et par date. Une règle affecte
un compte selon sa nature, le préfixe de son code et **le sens de son solde** : un compte client
débiteur est un crédit, créditeur un dépôt, ce qu'aucune correspondance compte à compte ne sait
dire (`the_balance_sheet_balances`).

**Le résultat n'est jamais compté deux fois.** Le bilan présente le résultat de l'exercice en
cours dans sa rubrique de résultat, calculé par le socle ; après la clôture, il est au compte de
résultat de l'exercice et la rubrique retombe à zéro. Le compte de résultat ignore les écritures
de clôture : celui d'un exercice clos montre ce que l'exercice a fait, et il vaut exactement le
résultat qui a été affecté (`etats_financiers`).

**Une anomalie est nommée, pas absorbée.** Un compte qu'aucune règle ne reçoit, un résultat
antérieur non clos, un actif qui ne vaut pas le passif : l'état les dit et porte `consistent`
faux, au lieu de forcer un total (`the_statement_names_what_it_cannot_present`). Pour que le
hors bilan s'équilibre par lui-même, le ledger a gagné un invariant : une écriture ne mélange pas
le bilan et le hors bilan, et un engagement s'équilibre dans son agence sans ligne de liaison
(`an_entry_keeps_to_one_world`).

### 23. Des plafonds lus dans le journal, et des paiements qui attendent le correspondant

**Un plafond ne se compte pas, il se lit.** Par opération, par jour, par mois, du produit ou du
compte — posé à deux, et qui l'emporte —, l'usage est la somme des débits du client dans le
journal, frais compris, hors écritures contre-passées : un retrait annulé ne consomme plus rien,
sans compteur à corriger (`product_and_account_limits`).

**Un paiement sortant débite à l'ordre, sur un compte qui dit ce qu'il attend.** Le client est
débité tout de suite, le montant va au compte de règlement sortant du produit ; les fonds ne sont
pas encore chez le correspondant et le bilan le montre. Envoyé, réglé sur le nostro, ou retourné
— les frais restent acquis, le service a été rendu —, l'ordre s'annule avant envoi par
contre-passation et jamais autrement (`order_send_settle`, `cancel_and_refusals`). Un ordre
rejoué avec sa clé rend le même ordre, et rien n'est débité deux fois.

### 24. Des chèques qui se paient une fois, et des remises créditées sauf bonne fin

**Un chèque émis se paie une fois, et l'incident survit au refus.** Le chéquier se délivre à
deux, aux frais du produit ; ses numéros suivent ceux du chéquier précédent, et le schéma interdit
à deux chéquiers d'un compte de partager un numéro. Présenté au guichet — sur la caisse de
l'appelant — ou par compensation, un chèque se paie une fois, dans la limite du disponible ;
rejoué avec sa clé, il rend le même reçu. Présenté sans provision, il est rejeté et l'incident est
enregistré dans sa propre transaction, après celle du refus : parce qu'il fonde l'interdiction
bancaire et la déclaration à la centrale, il doit survivre au refus ; et parce que la transaction
refusée tient encore le chèque tant qu'elle n'est pas défaite, il ne peut pas s'écrire avant
(`pay_stop_reject`). L'opposition n'a que les motifs que la loi admet et n'atteint pas un chèque
payé.

**Une remise crédite le client, mais ne lui donne rien avant la bonne fin.** La valeur va au
compte de chèques à l'encaissement du produit, tenu au siège ; le client est crédité à la date de
valeur des conditions de banque, et un blocage tient le montant hors du disponible jusqu'au
règlement par le correspondant — le blocage tombe, la valeur passe au nostro ; impayée, la remise
est contre-passée et le blocage tombe avec elle (`deposits`). Les chèques ne consomment pas les
plafonds du client : l'instrument est celui d'un tiers porteur, et un refus de plafond ne serait
pas un défaut de provision.

### 25. Des prélèvements qui attendent leur échéance, et un rejet qui est un résultat

**Un prélèvement s'exécute à son échéance, pas à sa présentation.** Présenté sur un mandat que le
client a signé — et qui le borne : validité, plafond, révocation —, il attend ; à l'échéance, tout
de suite si elle est arrivée, par l'arrêté sinon, le débiteur est débité, frais compris, et le
montant attend le correspondant au siège ou va au créancier de la banque. Sans provision, sur un
mandat révoqué, sur un compte qui ne peut pas opérer, il est rejeté : le rejet est un résultat
enregistré avec son motif, que le créancier reçoit, pas une erreur qui arrêterait la journée
(`received`, `direct_debits_follow_the_day`). La comptabilisation se tente sous un point de
sauvegarde : un refus du ledger y ramène, et le rejet s'écrit avec la transaction qui l'a
constaté — réelle, ou à blanc, sans transaction indépendante qu'un TFJ à blanc validerait.

**Un arrêté se défait entièrement, ou refuse.** L'annulation contre-passe les écritures des
prélèvements exécutés, lève leurs blocages et les rend à l'attente, pour que la journée rejouée
les exécute de nouveau sous ses propres clés ; mais si l'un d'eux a été réglé, remboursé ou
retourné depuis, elle refuse avant de rien défaire, parce que la suite s'appuie sur lui. Les
prélèvements émis sont crédités sauf bonne fin, comme les remises de chèques, les frais dans leur
propre écriture pour qu'un retour contre-passe la remise et pas le service (`issued`). Un
prélèvement reçu ne réveille pas un compte dormant et ne compte pas pour sa dormance : l'acte est
celui du créancier, et il se poursuit sur un compte que son titulaire a oublié.

### 26. Une heure limite qui déplace la valeur, et des suspens qui ont un âge et un responsable

**L'heure limite se lit à l'horloge, dans le fuseau de l'entité, et déplace la date de
valeur, pas la date comptable.** Au-delà de l'heure limite de son canal, une opération en ligne
prend la valeur calculée depuis le jour ouvré suivant — les conditions de banque s'appliquent
depuis ce jour-là —, et un canal qui ferme refuse ; un traitement de lot n'a pas d'heure limite,
il exécute ce qui est à l'échéance. L'heure limite se déclare à deux, datée, sans chevauchement
par canal, comme une condition de banque : elle déplace des intérêts
(`the_channel_cutoff_shifts_the_value_date_or_closes_the_channel`, `cutoff`).

**Un suspens a un âge en jours ouvrés et un responsable, et son retard se paramètre.** Ce qui
attend le correspondant — ordre non réglé, remise non encaissée, prélèvement non réglé, compte
d'attente non soldé — est passé en revue chaque soir avec son ancienneté et le responsable que
la politique lui donne ; les retards remontent par nature avec le plus ancien, sans bloquer, et
un compte d'attente en retard bloque la journée aux contrôles préalables, avant tout calcul.
L'ancienneté d'un compte d'attente part de son premier mouvement après le dernier jour où il
était soldé, pas de son ouverture. Sans politique, un suspens est listé sans être en retard, et
un compte d'attente non soldé bloque : le paramétrage dit ce qu'il tolère, le système ne le
présume pas (`items_age_in_business_days`, `a_suspense_account_blocks_the_day_beyond_its_tolerance`).

### 27. Un cours qui se contrôle, et une position qui se revalorise

**Le ledger ne voit pas un cours faux.** Une fois l'équilibre par devise acquis, le contrôle en
contre-valeur attrape un cours incohérent entre deux lignes, mais pas un cours faux appliqué
uniformément : les contre-valeurs se compensent alors deux à deux, quel que soit le cours. Le
seul contrôle qui le voie est la confrontation au cours de référence, et il est désormais fait à
la comptabilisation. Trois refus nommés : une devise sans position déclarée — une exposition que
personne ne mesure —, un cours de référence absent — un cours appliqué sans référence ne se
contrôle pas —, un écart au-delà de la marge déclarée. Une contre-passation, elle, garde le cours
d'origine : c'est ce qu'on attend d'elle (`the_applied_rate_is_checked_against_the_reference`).

**Une position a deux comptes, et leur sens est imposé.** Le compte de position, tenu dans la
devise, mesure l'exposition ; son compte de contre-valeur, tenu dans la devise de l'entité, porte
ce qu'elle a coûté. Position créditrice, contre-valeur débitrice : le couple inverse rendrait un
gain là où il y a une perte, à chaque arrêté, sans qu'aucune écriture ne soit déséquilibrée et
sans qu'aucun contrôle de réconciliation ne bronche. Le système refuse donc de l'enregistrer.
L'arrêté exige le cours du jour **avant tout calcul**, puis porte la contre-valeur à ce que la
position vaut au cours de clôture ; l'écart va au résultat de change, et la quantité en devise ne
bouge pas — c'est sa valeur qui a bougé (`revaluation`,
`the_day_needs_its_rates_and_revalues_positions`).


## Ce qui n'est pas encore fait

Restent, dans l'ordre du [plan](../docs/core-banking/10-roadmap.md) :

- API : le contrat OpenAPI est généré et publié ; reste sa vérification de compatibilité
  d'une version à l'autre (le test tient l'égalité au code, pas la non-régression du contrat) ;
- pour les chèques et les prélèvements, l'échange avec la compensation (SICA-UEMOA : fichiers de
  présentation et de rejet, cycles), la déclaration des incidents à la centrale et l'interdiction
  bancaire, les chèques de banque, les frais de rejet, les ordres permanents — les chéquiers, le
  paiement, l'opposition, l'incident, la remise sauf bonne fin, les mandats, les prélèvements
  reçus à l'échéance et émis sauf bonne fin, comme les paiements sortants et les plafonds par
  produit et par compte, eux, sont faits ;
- multi-agences : schémas de liaison bilatéral et via la région (le schéma via le siège est
  fait, les caisses par guichetier et l'arrêté de caisse aussi) ;
- référentiel client : documents et leurs échéances, bénéficiaires effectifs, relations entre
  tiers, rescan périodique des listes ;
- crédit : origination (demande, scoring, décision, conditions suspensives) — le déblocage par
  tranches, lui, est fait ; la commission d'engagement sur la fraction non tirée se paramètre comme
  une commission ordinaire et n'a pas encore de barème dédié ;
- circuits de validation à trois yeux par montant et réservation du disponible par une
  opération en attente (le maker-checker à deux et les plafonds, eux, sont faits) ;
- régime de frais de dormance et compte d'abandon (la détection et le réveil sont faits),
  commissions de découvert (mise en place, dépassement), base minimum ou moyenne pour l'épargne
  classique — la capitalisation et les agios, eux, sont faits ;
- archivage des partitions (leur création, elle, est garantie par le TFJ) ;
- clôture annuelle : les modèles de liasse réglementaire à livrer comme maquettes — la
  détermination du résultat, la clôture, l'affectation du résultat et les états financiers
  (bilan, compte de résultat, hors bilan, maquettes à deux), eux, sont faits ;
- contrôle du cours appliqué contre la table de référence — le ledger valide la cohérence des
  contre-valeurs, pas la justesse d'un cours uniforme.

## Décisions techniques notables

| Décision | Raison |
|---|---|
| Pas de JPA sur le chemin de comptabilisation | Le flush implicite et le cache de 1ᵉʳ niveau rendent imprévisible le moment où une instruction atteint la base |
| Numérotation par séquence PostgreSQL, trous acceptés | Un compteur en table poserait un verrou de ligne par entité jusqu'au commit ; la numérotation continue du journal officiel est attribuée au TFJ |
| Contrainte d'équilibre sur les partitions, pas sur la table mère | PostgreSQL n'accepte pas de déclencheur de contrainte différé sur une table partitionnée |
| Idempotence dans une table satellite | Toute contrainte unique d'une table partitionnée doit contenir la clé de partitionnement ; l'unicité doit être globale |
| L'incident de paiement d'un chèque se constate dans sa propre transaction, après celle du refus | La transaction refusée est défaite avec ses verrous, et l'incident doit lui survivre ; écrit pendant elle, il attendrait le verrou du chèque qu'elle tient — un interblocage que le premier test a trouvé |
| Les chèques ne consomment pas les plafonds du client | L'instrument est celui d'un tiers porteur ; un refus de plafond ne serait pas un défaut de provision, et fausserait l'incident |
| L'exécution d'un prélèvement tente la comptabilisation sous un point de sauvegarde | Un refus du ledger laisse une réservation de clé et une transaction à défaire ; le point de sauvegarde y ramène, et le rejet s'écrit avec la transaction qui l'a constaté — la même en ligne, à l'arrêté, et au TFJ à blanc, qu'une transaction indépendante trahirait en validant |
| Un prélèvement reçu ne compte pas pour la dormance | L'acte est celui du créancier ; une assurance qui prélève un compte oublié ne prouve pas que son titulaire est là |
| L'annulation d'un arrêté refuse si un prélèvement exécuté a été réglé, remboursé ou retourné depuis | La suite s'appuie sur l'exécution ; le refus vient avant la première contre-passation, pas au milieu |
| L'heure limite d'un canal ne s'applique qu'aux opérations en ligne | Un arrêté qui exécute des prélèvements à 23 h leur donnerait la valeur du lendemain ; l'échéance, elle, est la bonne date |
| L'horloge des heures limites est une horloge unique, fixable | Les services ne prennent pas d'heure en paramètre — un appelant qui la fournirait choisirait sa date de valeur ; une horloge fixée sert les tests et les simulations |
| Un compte d'attente non soldé bloque sans politique, et au-delà de la tolérance avec | La justification des comptes d'attente conditionne la sincérité de l'arrêté ; la tolérance est une décision de la banque, écrite à deux, pas un défaut du système |
| Le cours appliqué se confronte au référentiel, pas au bon sens | Un cours faux appliqué uniformément laisse l'écriture équilibrée et la comptabilité juste ; seuls les agios, la position et le résultat de change sont faux, et cela se découvre à la réclamation |
| Une devise sans position déclarée ne se comptabilise pas | Une exposition que personne ne mesure n'est revalorisée par personne ; le refus arrive à la première écriture, pas au premier arrêté |
| Le sens des deux comptes d'une position est imposé, pas déduit | Un couple inversé rend un gain là où il y a une perte, et aucun contrôle d'équilibre ne le voit |
| L'arrêté exige les cours avant tout calcul | Découvrir à la revalorisation qu'un cours manque coûte l'annulation de la journée entière ; le coter coûte une minute |
| Un blocage de compte est vérifié par le service avant tout prélèvement, dans les deux sens | Le ledger laisse entrer un crédit de lot sur un compte gelé, parce qu'il le tient pour un acte de la banque ; la remise d'un créancier n'en est pas un |
| La présentation d'un créancier d'ailleurs est réservée à la compensation | Le mandat décide de l'opération : un chargé de clientèle ne présente que pour un créancier de la banque, sous son plafond ; l'appelant ne choisit pas |
| Un seul instant de connaissance par écriture | `clock_timestamp()` avance dans une transaction ; par ligne, il placerait les lignes après leur propre écriture |
| Compte à contrôle de disponible ⇒ une seule stripe | Vérifier un disponible exigerait de verrouiller toutes les stripes, ce qui annulerait la répartition |
| La génération de recalcul entre dans la clé d'idempotence | Sans elle, une réémission après extourne porte la clé de l'écriture d'origine, passe pour un rejeu et n'impute rien |
| La série de soldes est reconstruite à chaque calcul, jamais mise en cache | Une opération antidatée modifie les journées passées ; un cache servirait l'ancienne série |
| Le moteur de schémas refuse un montant non comptabilisable au lieu de l'arrondir | Décider qui supporte l'écart d'arrondi est une décision de gestion, elle appartient à l'auteur du schéma |
| Équilibre vérifié après arrondi, pas en arithmétique réelle | Le déséquilibre coûteux vient de l'arrondi, pas d'une ligne oubliée |
| Les périodes de commission se calculent depuis l'ancrage, jamais de proche en proche | Une échéance au 31 ramenée au 28 en février resterait au 28 ensuite : le contrat changerait de jour d'échéance sans décision |
| La clé d'idempotence d'une commission est dérivée du contenu, pas du run | Une reprise sous un nouvel identifiant refacturerait la période ; la contrainte d'exclusion protège le registre, la clé protège le journal |
| Le rang de refacturation entre dans cette clé | Après annulation, l'écriture d'origine subsiste contre-passée ; réutiliser sa clé ferait passer la refacturation pour un rejeu |
| Une commission n'est jamais encaissée partiellement | La taxe suit la commission qu'elle frappe ; un encaissement partiel scinderait une assiette taxable déclarée |
| Le montant d'un impayé est figé à la liquidation | Le refixer à l'encaissement transformerait une créance en révision tarifaire rétroactive |
| Les perceptions se parallélisent par compte | Une commission débite un compte client différent à chaque fois : elle ne s'agrège pas comme les intérêts, et c'est elle qui dimensionne la fenêtre |
| Une écriture par client, pas un bordereau global | Une commission se conteste et se contre-passe client par client ; la contre-passation porte sur l'écriture entière |
| La date d'ouverture d'un compte est la date comptable, pas l'horloge | Une reprise de portefeuille ouvre des comptes antérieurs à la migration, et cette date décide de la proratisation |
| La dernière échéance d'un crédit solde le capital restant dû | C'est la seule règle qui ferme exactement en devise sans subdivision ; l'écart est rendu visible au lieu de survivre à la fin du crédit |
| Chaque méthode d'amortissement porte sa convention de taux | Une annuité assise sur des mois de 28 à 31 jours ne serait pas constante ; l'appeler « annuité constante » serait un abus de langage |
| L'imputation partielle est admise sur un crédit, refusée sur une commission | Une échéance est une dette qui s'amortit ; une commission porte une assiette taxable déjà déclarée, que l'on ne scinde pas |
| L'encours d'un crédit ne diminue qu'au règlement | L'amortir dès l'exigibilité afficherait un actif inférieur à ce que le client doit, et sous-estimerait l'exposition au moment où elle devient risquée |
| Exigibilité et prélèvement dans la même étape du TFJ | Entre les deux, un compte à jour apparaîtrait en impayé ; sur un TFJ interrompu, ce faux impayé survivrait à la nuit |
| Les créances de retard sont exclues de leur propre assiette, structurellement | L'anatocisme est encadré voire prohibé ; un paramètre inversable mettrait la banque en infraction sans décision |
| Seul l'intérêt de retard voit son montant dû croître | Toute autre créance a un montant fixe dès sa naissance ; le voir bouger révèle une réécriture de l'histoire |
| Montant dû et solde d'une créance bougent du même pas, dans les deux sens | Sinon un accrual ferait redevenir due une part déjà réglée, ou une reprise effacerait une part encore due |
| Les produits de retard s'imputent sur des comptes distincts des intérêts sains | Les produits sur créances en souffrance forment une ligne à part des états réglementaires |
| La contagion se propage sur le rang de dégradation, pas sur le code de classe | Deux crédits d'un même client peuvent relever de profils différents ; les codes varient d'un pays à l'autre, le rang non |
| La classification vient après les charges de retard, et commande la constatation du lendemain | L'inverse serait circulaire : suspendre les intérêts du jour dépendrait de la classe qu'on est en train d'établir |
| Dotation et reprise sont deux événements comptables, pas un seul à montant signé | Le sens est porté par la direction, jamais par le signe ; et les deux flux ne se compensent pas au compte de résultat |
| Une grille relue en base est revalidée | Un correctif manuel sur une ligne de barème s'appliquerait sinon à tout le portefeuille |
| Le TEG se résout par dichotomie, jamais par Newton | La convergence est garantie et bornée ; un moteur réglementaire ne peut pas avoir de cas où il ne rend pas de réponse |
| Les deux plafonds légaux d'indemnité s'appliquent, le plus bas l'emporte | N'en retenir qu'un laisserait passer la moitié des dépassements |
| Un remboursement anticipé ne révise ni le taux ni les accessoires | Les réviser au passage transformerait un droit de l'emprunteur en renégociation, qui demande un autre consentement |
| La période d'observation retient la classe, pas le montant de la provision | L'encours a réellement baissé ; geler aussi le montant surprovisionnerait |
| Les conditions financières sont portées par le contrat, la durée par l'échéancier | La durée change à chaque rééchelonnement ; la stocker deux fois ferait exister deux vérités sur le même sujet |
| La quotité d'une sûreté est une donnée du référentiel, pas du dossier | La saisir revient à laisser un agent décider du niveau de provision de son propre portefeuille |
| Les rangs antérieurs comptent toutes affectations confondues, y compris d'autres banques | Ce qui compte est ce qui reste de l'actif, pas ce que la banque en a déjà pris pour elle |
| Une sûreté écartée est signalée, jamais ignorée | Croire couvrir un encours qu'on ne couvre pas ne se découvre qu'à la réalisation |
| Une mainlevée marque la sûreté, elle ne la supprime pas | L'historique des rangs est une pièce du dossier |
| Le contrat de paramétrage est un fichier, pas du code Java | Les noms de paramètres vivent dans les modules de service, qui dépendent du catalogue ; un descripteur en Java y créerait un cycle. Un test par module tient l'accord, dans les deux sens |
| Le contrôle de complétude précède la double validation | Faire valider par un second regard un paramétrage que la machine sait incomplet lui ferait porter une responsabilité sur une pièce incomplète |
| Un paramètre non déclaré par la famille est refusé, pas ignoré | C'est le seul moyen de distinguer une valeur inutile d'une valeur mal nommée |
| Une condition de paramétrage porte aussi sur la valeur par défaut | Un paramètre absent est un paramètre qu'on a oublié : n'examiner que les valeurs saisies laisserait passer le cas le plus fréquent |
| Un produit non rémunéré se paramètre à taux nul, explicitement | L'accrual visite tout compte rattaché à un produit ; le laisser sauter les produits sans taux ferait payer zéro intérêt à un livret mal paramétré, en silence |
| Chaque module déclare ses scripts de schéma dans `db/migrations.list` | L'énumération d'un répertoire n'est pas portable d'un jar à l'autre ; une déclaration se relit, et un script oublié échoue dans les tests du module qui l'a écrit |
| La montée de version refuse un classpath à trous, sauf demande explicite | Un module absent du déploiement se voit au démarrage, pas au premier appel qui le demande ; les tests d'un module, qui ne voient que ses dépendances, s'en dispensent en le disant |
| Toute la montée de version tient dans une transaction | PostgreSQL rend le DDL transactionnel : un échec à mi-chemin ne laisse rien, et deux instances démarrées ensemble se sérialisent sur un verrou consultatif |
| La bascule de journée garantit partitions et période, mais ne rouvre pas une période close | Créer ce que le système sait créer ; rouvrir est une décision comptable |
| Les journées s'annulent de la plus récente à la plus ancienne | Restaurer la date à N sous un N+1 arrêté laisserait N+1 tenue pour faite sur un état que ses écritures ne décrivent plus |
| Un crédit soldé est clos à l'arrêté, après la classification, jamais au dernier règlement | La classification reprend la provision d'un encours devenu nul ; un contrat clos avant elle emporterait sa provision hors du portefeuille |
| Un encours résiduel sur un crédit soldé bloque l'arrêté | C'est un écart entre le compte de prêt et le sous-livre — celui que le rapprochement du grand livre ne voit pas |
| La création d'une entité ou d'une devise n'est pas une opération du catalogue | Ce sont des actes de déploiement, pas d'exploitation ; une opération à périmètre « toute entité » n'aurait pas de règle sensée |
| Aucun échéancier n'est publié pendant la mobilisation | Le capital à amortir n'est pas connu ; en publier un réclamerait l'amortissement d'un capital non versé |
| La mobilisation se clôt à sa date limite, même si tout est tiré | C'est le contrat qui fixe le début de l'amortissement, pas le rythme du chantier |
| Le montant mobilisé n'est pas stocké, il se lit sur les tranches débloquées | Le dénormaliser ferait exister deux vérités sur le capital, et rien ne garantirait que celle qui commande l'échéancier soit la bonne |
| Les frais de dossier sont retenus sur la première tranche, et sur elle seule | Les étaler ferait dépendre leur montant du nombre de tranches réellement tirées |
| Un dépassement d'usure constaté à la clôture est bloquant, pas un fait de gestion | Les fonds sont versés : la banque est en infraction tant que la ristourne n'est pas décidée |
| Une tranche non tirée à la date limite n'est pas une anomalie | Un chantier qui n'a pas avancé n'a pas à bloquer l'arrêté de la banque |
| L'état d'un calcul d'intérêts est une position, pas une somme sur l'historique | Deux ans à deux millions de comptes font un milliard et demi de lignes à relire chaque nuit pour retrouver ce que l'on savait la veille ; la position se reconstruit depuis les journées actives après toute annulation |
| Le brut capitalisé se lit au cumul de la fin de période, pas à celui du jour du traitement | Un trimestre qui finit un samedi est réglé le lundi, pour ce qui était couru le samedi |
| Les intérêts capitalisés portent date de valeur du lendemain | Ceux de la fin de période ont été calculés sur le solde d'avant ; les dater du même jour ferait rémunérer, à tout recalcul, un solde que le compte n'avait pas |
| L'intérêt d'une échéance de crédit est étalé linéairement sur sa période | À annuité constante, le taux périodique n'est pas un taux journalier ; l'étalement retombe exactement sur l'échéance, quelle que soit la méthode |
| Un échéancier remplacé emporte ses courus | Ses échéances ne seront jamais réclamées ; le nouvel échéancier repart de sa première période |
| Le cliché des soldes est incrémental, le rejeu intégral mensuel | O(journée) chaque nuit, O(historique) une fois par mois ; par récurrence, la même garantie |
| La réconciliation rafraîchit le cliché avant de contrôler | La correction passée entre un échec et la reprise est comptabilisée sur la journée ; le cliché arrêté à l'étape précédente ne la porte pas |
| L'entité est posée par transaction, jamais par connexion | Un pool partage ses connexions : un réglage de session fuirait vers la requête suivante ; `set_config(…, true)` tombe avec la transaction, validée ou annulée |
| L'ordre du journal par curseur est fait de colonnes de la ligne, pas du numéro d'écriture | Le numéro est sur l'écriture : l'ordre exigerait une jointure que l'index ne porte pas, et chaque page relirait tout ce qui la précède ; l'instant de connaissance est sur la ligne, et il est chronologique |
| La balance est lue dans le journal, jamais dans un cliché | Un cliché est un cache : regénérée, une balance doit redonner le même chiffre, et ses totaux doivent s'équilibrer par construction, pas par rapprochement |
| La balance auxiliaire et la balance d'agence sont des filtres de la balance | Un seul type de compte, un seul journal : une seconde lecture ferait exister deux balances à rapprocher |
| Le résultat s'affecte en totalité, ou pas | Une affectation partielle laisserait un solde en instance dont personne ne porte la décision ; le report à nouveau est une destination comme une autre |
| Le résultat net se lit dans les écritures de la clôture, pas dans une colonne | Un montant stocké serait une seconde vérité ; l'exercice rouvert n'a plus de résultat, et il n'a rien à effacer |
| L'affectation solde le compte de résultat agence par agence | Le résultat a été porté par agence ; le solder au siège seul laisserait chaque agence porter son résultat pour toujours |
| Un résultat affecté retient l'annulation de la clôture | Annuler la clôture défait le résultat ; le défaire sous une affectation laisserait des réserves dotées d'un résultat qui n'existe plus |
| Affectation et annulation de clôture s'exécutent sous le verrou de l'exercice | Deux affectations concurrentes du même résultat doteraient deux fois les réserves ; un contrôle sans verrou ne voit pas ce qui n'est pas encore validé |
| Un mois ne se rouvre pas sous un exercice clos | Le résultat a été déterminé avec ce mois ; le rouvrir changerait ce que la clôture a déjà constaté, sans passer par elle |
| L'ordre par curseur a un index par clé de lecture, vérifié sur le plan | Un ordre total que l'index ne porte pas relit à chaque page tout ce qui la précède ; le test le prouve sur le plan d'exécution, pas sur une intention |
| Le coût de la balance est dit, pas caché | L'ouverture est une somme sur l'historique ; un cliché quotidien serait faux sous une écriture antidatée, et l'accélération exacte est un cliché arrêté à la clôture de période, notée au plan |
| Une règle d'affectation peut lire le sens du solde | Un compte client débiteur change de rubrique ; une correspondance compte à compte le mettrait au passif avec un solde négatif |
| Les règles se lisent dans l'ordre, la première l'emporte | La précédence est écrite par l'auteur ; refuser tout chevauchement rendrait les règles générales impossibles |
| Le compte de résultat ignore les écritures de clôture | Elles soldent les comptes de résultat sans être de l'activité ; les lire ferait valoir zéro à tout exercice clos |
| Le résultat au bilan est calculé par le socle, jamais par une règle | Une règle sur les comptes de résultat le compterait une seconde fois après la clôture, quand il est déjà au compte de résultat de l'exercice |
| Un état anomal reste produit, avec ses anomalies nommées | Forcer un total masquerait le compte oublié ; refuser l'état priverait le comptable de ce qui lui permet de le corriger |
| Une écriture ne mélange pas le bilan et le hors bilan | Un engagement contre un compte de bilan fausserait les deux états à la fois, et aucun ne s'équilibrerait |
| L'usage d'un plafond se lit dans le journal, jamais dans un compteur | Un compteur se désynchronise à la première annulation ; le journal est exact par construction, et l'exclusion des contre-passations est une clause |
| Le plafond du compte remplace celui du produit, dans les deux sens | Un plafond négocié est une décision sur ce client ; prendre le plus strict des deux la rendrait inopérante |
| Un paiement sortant débite le client à l'ordre | Réserver sans débiter laisserait le client disposer de fonds déjà engagés ; le compte de règlement montre ce que la banque doit encore livrer |
| Les frais d'un paiement retourné restent acquis | Le service a été rendu ; seule l'annulation avant envoi, où rien n'est parti, rend tout par contre-passation |
| Sous un plafond cumulé, les débits d'un compte se suivent sur le verrou du compte | Deux débits concurrents liraient chacun un usage sans l'autre et passeraient tous deux ; le test le prouve à deux fils |
| Le compte de règlement sortant et le nostro sont tenus au siège | C'est le siège qui livre au correspondant ; un règlement au siège contre un ordre en agence laisserait chaque agence porter un solde de règlement qu'elle ne réglera jamais |
| Le contrat OpenAPI est généré depuis les contrôleurs, versé et comparé par un test | Un contrat écrit à la main ment dès la deuxième route ; généré à la volée, il ne se relit pas en revue. Versé et tenu égal au code, il se voit changer |
| Un paramètre que le contrat ne sait pas décrire fait échouer la génération | Une route exposée sans être décrite est un contrat faux ; l'erreur nomme le paramètre au lieu de l'omettre |
| Sans entité posée, le rôle applicatif ne voit rien | Le défaut est l'absence d'accès : une requête écrite sans filtre renvoie zéro ligne, pas toutes les entités |
| Une transaction ne change pas d'entité | La base a déjà reçu l'entité de la transaction ; une unité de travail qui en attendrait une autre lirait à côté de ce qu'elle croit — refusé en Java, avant la base |
| Deux comptes de base, propriétaire et applicatif | Le propriétaire des tables n'est soumis à aucune politique ; avec un seul compte, la Row Level Security serait décorative |
| Les partitions se créent par une fonction `SECURITY DEFINER` | Le rôle applicatif ne possède pas les tables ; la bascule de journée doit pouvoir créer des partitions, et rien d'autre |
| Soldes, positions d'intérêts et clichés restent hors politique | Ils sont sur le chemin chaud et ne se lisent jamais sans leur compte, lui-même cloisonné |
| Une ressource d'une autre entité est inconnue (`404`), pas interdite (`403`) | La base ne la montre pas ; répondre qu'elle existe serait déjà une fuite |
| Un remboursement anticipé s'approuve à deux | Le droit du client n'est pas discuté ; l'échéancier qu'il engendre l'est, comme tout échéancier — la base exige déjà deux signatures |
| Les conditions d'un déblocage voyagent avec la demande, l'échéancier naît à l'approbation | Ce que le checker approuve est ce que le maker a saisi ; l'échéancier est un calcul, pas une saisie |
| Le moteur d'arrêté relit le calendrier à chaque lancement | Un férié déclaré dans la journée vaut pour le soir même ; un moteur mis en cache aurait arrêté la banque sur un calendrier périmé |
| Le guichetier ne choisit pas sa caisse : elle est la sienne, résolue depuis son jeton | Une caisse choisie dans la requête est une caisse que n'importe qui peut mouvementer ; l'affectation est un acte à deux |
| Un écart de caisse se comptabilise, il ne s'ajuste pas | Ajuster le solde au comptage effacerait la seule trace d'un manquant ; l'écart va sur son compte, où il se justifie |
| Une caisse mouvementée non arrêtée bloque l'arrêté de la banque | Des espèces non confrontées à leur solde sont un solde non prouvé ; le contrôle nomme la caisse, l'exploitant reprend après l'arrêté de caisse |
| Un guichetier n'arrête que sa caisse, le chef d'agence toute caisse de son agence | Le titulaire vient de l'objet, jamais de la requête ; une règle « objet propre » le dit dans la politique, pas dans un cas d'usage |
| Une seule enveloppe de réponse, succès et refus compris, posée par un advice | Un client ne lit qu'une forme ; un contrôleur ne peut pas oublier l'enveloppe puisqu'il ne la construit jamais |
| Une page au-delà du plafond est refusée, pas ramenée au plafond | Un client qui demande dix mille lignes doit le savoir ; un plafond silencieux fabrique des extractions tronquées sans que personne ne le voie |
| Toute liste paginée est lue dans un ordre total | Sans lui, deux pages successives peuvent montrer deux fois la même ligne, ou n'en montrer aucune |
| Le rééchelonnement porte sur le capital non échu, aux conditions du contrat | Reprendre des échéances exigibles les réclamerait deux fois ; réviser le taux au passage serait une renégociation, qui demande un autre consentement |
| La nature d'un compte est une donnée du compte, pas une convention sur son code | Un plan comptable interne ne numérote pas forcément comme le plan de référence ; la clôture ne peut pas deviner ce qu'elle doit solder |
| La clôture annuelle clôt elle-même le dernier mois de l'exercice | Les écritures de résultat doivent être imputées avant que la période ne se ferme ; un arrêté mensuel séparé les rendrait impossibles ou les daterait faux |
| Le résultat se détermine dans le journal en date de fin d'exercice, jamais dans un cliché | Le cliché est incrémental et vit sa vie ; le journal est la vérité, et l'écriture de clôture doit l'être aussi |
| L'annulation d'une clôture annuelle se contre-passe à la date de fin d'exercice | Datée plus tard, elle laisserait les comptes de résultat soldés au 31 : la clôture rejouée ne trouverait rien à solder |
| Pas d'à-nouveaux : le journal est continu | Les soldes de bilan se reportent d'eux-mêmes ; générer des à-nouveaux serait écrire deux fois la même vérité |
| Un arrêté mensuel ou annuel se lance à deux par l'API, et son rapport est le résultat de l'approbation | Fermer ou rouvrir une période fixe ce que la banque présente ; le refus du moteur est la réponse de l'approbation, jamais un état ambigu |
| Les tables partitionnées sont déclarées dans un registre | Une table partitionnée que la bascule ne connaît pas n'a ses partitions créées par personne |
| Le TFM porte la date de fin de période et ne touche pas à la date comptable | Il porte sur un mois déjà arrêté jour par jour ; son annulation rouvre la période en le disant |
| Le dédoublonnage des tiers est un index unique partiel, pas un traitement | Un doublon découvert après coup a déjà faussé les plafonds d'engagement ; refusé à la saisie, il n'existe jamais |
| Un blocage de compte est un état superposé, appliqué par le ledger, jamais un statut | Un statut qui refuse tout ferait perdre au client les fonds qui lui arrivent, et à la banque la contre-passation d'une erreur ; lu à l'écriture, il prime sur les prélèvements automatiques sans qu'aucun service n'y pense |
| Un blocage de montant expire par date comptable, pas par horloge | C'est l'arrêté qui le lève et son annulation qui le repose ; un disponible rejoué sur une journée passée rend ce qu'il rendait ce jour-là |
| La date de valeur d'une opération se calcule, elle ne se fournit pas | Sans condition de banque, l'opération est refusée : se rabattre sur la date comptable produirait un résultat plausible et faux |
| Les frais d'opération s'imputent dans l'écriture de l'opération | Un frais prélevé à part est un frais qu'on oublie, et qu'on contre-passe séparément |
| La dormance se constate sur l'absence d'opération du client, pas d'écriture | Les intérêts et commissions de la banque ne sont pas des opérations du client ; c'est la source de l'écriture qui décide |
| Le jour de la clôture d'un compte n'est pas rémunéré | Le solde versé porte la date de valeur d'un retrait, et un retrait ne rémunère pas la journée où il est fait ; les intérêts sont réglés jusqu'à la veille, le jour même |
| Une clôture est une transaction : tout ou rien | Un solde débiteur découvert après le règlement des agios refuse la clôture, et les agios calculés pour elle disparaissent avec elle |
| La connaissance client restreint progressivement, jamais brutalement | Un dossier expiré n'ouvre plus rien mais ses comptes fonctionnent ; couper les opérations d'un client sur une revue en retard serait un blocage non annoncé |
| Une écriture est équilibrée par agence, et la base le vérifie aussi | Un déséquilibre par agence est invisible de la balance de l'entité, et c'est là que se loge l'écart de liaison qu'on « régularise » |
| Les lignes de liaison sont générées par le moteur, jamais saisies | Une liaison saisie est une liaison qu'on oublie ; générée dans l'écriture, elle se contre-passe avec elle |
| L'agence d'un compte général est une dimension de la ligne, pas un compte par agence | Le paramétrage cite un compte ; le multiplier par le nombre d'agences ferait de chaque ouverture d'agence un changement de produit |
| L'agence de l'opération est portée par la commande | Les services savent à qui revient un produit ou une charge — au client, ou à l'agence qui sert — sans raisonner ligne par ligne |
| Le lot d'intérêts agrège par agence | Une ligne de charge par lot mettrait tout le résultat au siège, et chaque nuit produirait autant de liaisons que d'agences |
| La balance agence est un cliché quotidien, pas un solde tenu en temps réel | Les comptes généraux sont les comptes chauds ; une dimension de plus sur le chemin d'imputation coûterait sur chaque écriture ce qu'aucun contrôle en ligne ne demande |
| Les comptes de liaison s'éliminent, ils ne se règlent pas | Dans une même entité juridique, la position d'une agence vis-à-vis du siège n'est pas une dette : un écart est une écriture qui manque |
| Une opération déplacée porte son propre plafond, plus bas | Servir un client de passage est le métier d'un réseau ; le faire sous le plafond ordinaire, sans contrôle d'identité renforcé, est l'angle mort de la fraude au guichet |
| Le socle ne dépend pas de Spring ; seul le module `api` en dépend | Le ledger, les intérêts et le TFJ se testent et se mesurent sans conteneur ; le framework est une couche d'exposition, remplaçable, pas une fondation |
| L'appelant vient du jeton, jamais d'un paramètre | Un `actorId` fourni par le client est une identité déclarative ; le sujet d'un jeton signé est une identité établie |
| L'agence d'un compte ouvert est celle du jeton | Un corps de requête qui choisirait l'agence permettrait d'ouvrir des comptes dans une agence dont on ne répond pas |
| Un refus est une réponse au format problème, jamais une trace de pile | Le client sait ce qu'il doit corriger ; un `500` dit une seule chose, que rien n'a été comptabilisé |
| Aucun approbateur dans un corps de requête : le checker est le sujet de son jeton | Un identifiant d'approbateur fourni par le maker est une double validation que le maker fait seul |
| Une opération en attente garde la requête, pas une commande construite | À l'approbation, la requête est rejouée contre l'état du moment : un compte fermé entre-temps refuse l'opération comme il l'aurait refusée au guichet |
| Le maker ne valide pas sa propre opération : refusé par la politique et par la base | Deux barrières indépendantes, parce que c'est la fraude interne la plus simple à commettre |
