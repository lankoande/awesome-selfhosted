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

**État actuel : 159 tests verts** — 121 sur les domaines purs (dont 9 propriétés, ≈ 3 400 cas
générés), 38 sur PostgreSQL réel.

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
| Tout rôle est porté par un poste, sinon inattribuable | `JobProfile` | `every_role_is_carried_by_a_profile` |
| Écart de provisionnement Keycloak détecté | `KeycloakProvisioning.drift` | `a_missing_role_is_reported_as_silently_blocking` |
| Un auditeur qui opère voit son jeton refusé | Ségrégation des tâches | `an_auditor_holding_an_operational_role_is_refused` |
| Schéma déséquilibré par les arrondis refusé au stockage | `SchemaValidator`, tirage déterministe | `an_unbalanced_schema_cannot_even_be_stored` |
| Le moteur n'arrondit jamais à la place de l'auteur | `SchemaEngine` | `a_non_bookable_amount_is_refused_not_silently_rounded` |
| Fonction inconnue rejetée au chargement, pas au TFJ | Analyse de l'expression | `the_function_set_is_deliberately_small` |

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

### 6. Le XOF traité comme une vraie contrainte

Échelle nulle native, accumulation en précision étendue, arrondi au seul moment de la
comptabilisation, écart d'arrondi restitué explicitement. `MoneyTest.daily_rounding_drifts_measurably`
mesure la dérive évitée : **25 XOF par an et par compte**, soit 12,5 M XOF sur 500 000 comptes.

## Ce qui n'est pas encore fait

P0 livre le noyau comptable. Restent, dans l'ordre du [plan](../docs/core-banking/10-roadmap.md) :

- API REST et couche Spring Boot (le ledger reste sans framework, c'est délibéré) ;
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
| Le moteur de schémas refuse un montant non comptabilisable au lieu de l'arrondir | Décider qui supporte l'écart d'arrondi est une décision de gestion, elle appartient à l'auteur du schéma |
| Équilibre vérifié après arrondi, pas en arithmétique réelle | Le déséquilibre coûteux vient de l'arrondi, pas d'une ligne oubliée |
