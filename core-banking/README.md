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
├── loan-domain         Échéanciers d'amortissement, imputation d'un règlement — arithmétique pure
├── loan-service        Contrats : déblocage, exigibilité, prélèvement, retard, rééchelonnement
├── schema-engine       Traduction événement métier → écritures, validation par tirage
├── product-catalog     Product factory datée, schémas comptables, barèmes
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
requise. Les binaires sont téléchargés au premier lancement.

**État actuel : 322 tests verts** — 207 sur les domaines purs (dont 11 propriétés, ≈ 4 000 cas
générés), 115 sur PostgreSQL réel.

**Mesuré** ([détail](../docs/core-banking/13-mesures.md)) : 1 878 écritures/s, p99 13,4 ms, zéro
interblocage ; TFJ complet — commissions **et** intérêts — à 0,809 ms par compte dans le cas le plus
défavorable, soit **27,0 minutes** extrapolées pour 2 M de comptes contre 90 de fenêtre ;
exigibilité des crédits à 1,500 ms par contrat et charges de retard à 1,500 ms par impayé.

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

### 11. Le XOF traité comme une vraie contrainte

Échelle nulle native, accumulation en précision étendue, arrondi au seul moment de la
comptabilisation, écart d'arrondi restitué explicitement. `MoneyTest.daily_rounding_drifts_measurably`
mesure la dérive évitée : **25 XOF par an et par compte**, soit 12,5 M XOF sur 500 000 comptes.

## Ce qui n'est pas encore fait

Restent, dans l'ordre du [plan](../docs/core-banking/10-roadmap.md) :

- API REST et couche Spring Boot (le ledger reste sans framework, c'est délibéré), qui câblera
  `RoleStartupTask`, `UseCaseExecutor` et le serveur de ressources Keycloak ;
- crédit : classification, provisionnement, suspension des intérêts (les échéanciers,
  l'exigibilité, l'imputation et le régime de retard sont faits) ;
- plafonds et limites paramétrés, et le maker-checker généralisé (la table `pending_operation`
  existe, le workflow n'est pas écrit) ;
- capitalisation des intérêts, dormance, découverts et agios côté produit ;
- archivage des partitions ;
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
