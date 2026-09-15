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

**État actuel : 487 tests verts** — 297 sur les domaines purs (dont 11 propriétés, ≈ 4 000 cas
générés), 190 sur PostgreSQL réel.

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

## Ce qui n'est pas encore fait

Restent, dans l'ordre du [plan](../docs/core-banking/10-roadmap.md) :

- API REST et couche Spring Boot (le ledger reste sans framework, c'est délibéré), qui câblera
  `RoleStartupTask`, `UseCaseExecutor` et le serveur de ressources Keycloak — et rendra mécanique
  le rattachement point d'entrée → opération que `OperationCoverageTest` tient à la main ;
- crédit : origination (demande, scoring, décision, conditions suspensives) — le déblocage par
  tranches, lui, est fait ; la commission d'engagement sur la fraction non tirée se paramètre comme
  une commission ordinaire et n'a pas encore de barème dédié ;
- plafonds et limites paramétrés, et le maker-checker généralisé (la table `pending_operation`
  existe, le workflow n'est pas écrit) ;
- capitalisation des intérêts, dormance, découverts et agios côté produit ;
- archivage des partitions (leur création, elle, est garantie par le TFJ) ;
- clôture mensuelle (TFM) : la période suivante s'ouvre seule, sa clôture reste une opération
  comptable sans traitement ;
- contrôle du cours appliqué contre la table de référence — le ledger valide la cohérence des
  contre-valeurs, pas la justesse d'un cours uniforme.

## Décisions techniques notables

| Décision | Raison |
|---|---|
| Pas de JPA sur le chemin de comptabilisation | Le flush implicite et le cache de 1ᵉʳ niveau rendent imprévisible le moment où une instruction atteint la base |
| Numérotation par séquence PostgreSQL, trous acceptés | Un compteur en table poserait un verrou de ligne par entité jusqu'au commit ; la numérotation continue du journal officiel est attribuée au TFJ |
| Contrainte d'équilibre sur les partitions, pas sur la table mère | PostgreSQL n'accepte pas de déclencheur de contrainte différé sur une table partitionnée |
| Idempotence dans une table satellite | Toute contrainte unique d'une table partitionnée doit contenir la clé de partitionnement ; l'unicité doit être globale |
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
