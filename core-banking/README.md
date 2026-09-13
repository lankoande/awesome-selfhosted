# Socle core banking — P0 : noyau comptable

Implémentation du cœur décrit dans [`../docs/core-banking`](../docs/core-banking).
Java 21, PostgreSQL 14+, aucune dépendance de framework dans le ledger.

```
core-banking/
├── platform-kernel     Money, devises, identifiants, idempotence — zéro dépendance externe
├── ledger-domain       Comptes, écritures, invariants, contre-passation — Java pur, testable sans base
└── ledger-store        Schéma PostgreSQL, comptabilisation, soldes bitemporels, réconciliation
```

## Lancer les tests

```bash
mvn test
```

PostgreSQL est démarré en embarqué par les tests d'intégration — ni Docker, ni installation locale
requise. Les binaires sont téléchargés au premier lancement.

**État actuel : 72 tests verts** — 44 sur les domaines purs (dont 9 propriétés, ≈ 3 400 cas
générés), 28 sur PostgreSQL réel.

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

## Les trois choix qui vont au-delà des progiciels établis

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

### 4. Le XOF traité comme une vraie contrainte

Échelle nulle native, accumulation en précision étendue, arrondi au seul moment de la
comptabilisation, écart d'arrondi restitué explicitement. `MoneyTest.daily_rounding_drifts_measurably`
mesure la dérive évitée : **25 XOF par an et par compte**, soit 12,5 M XOF sur 500 000 comptes.

## Ce qui n'est pas encore fait

P0 livre le noyau comptable. Restent, dans l'ordre du [plan](../docs/core-banking/10-roadmap.md) :

- API REST et couche Spring Boot (le ledger reste sans framework, c'est délibéré) ;
- schémas comptables paramétrés (événement → écritures) ;
- moteur de TFJ, mode « à blanc », reprise et annulation ;
- snapshots quotidiens et archivage des partitions ;
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
