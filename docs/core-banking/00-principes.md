# 00 — Principes directeurs

Douze invariants. Chacun est vérifié par un test automatisé et par un contrôle en
production. Aucun n'est négociable pour une raison de délai : tous sont impossibles à
rétro-installer une fois le système en exploitation avec des encours réels.

---

## 1. Le journal comptable est immuable

Aucun `UPDATE`, aucun `DELETE` sur les écritures. Une erreur se corrige par
**contre-passation** (écriture inverse) puis réémission. Les droits PostgreSQL de
l'applicatif sur les tables `journal_entry` / `journal_line` sont limités à `INSERT` et
`SELECT`, et un trigger `BEFORE UPDATE OR DELETE` lève une exception.

Conséquence : l'historique est rejouable intégralement. On peut reconstruire n'importe quel
solde, à n'importe quelle date, sans dépendre d'une sauvegarde.

## 2. Toute écriture est équilibrée, par devise

Pour une écriture donnée et pour chaque devise : `Σ débits = Σ crédits`. Contrôlé dans le
domaine, puis re-contrôlé par une contrainte de base (trigger `AFTER INSERT` sur l'écriture
complète, écriture insérée en une seule transaction).

Une écriture multi-devises s'équilibre par un **compte de position de change** et un
**compte de contre-valeur** ; elle ne s'équilibre jamais « globalement » après conversion.

## 3. Les soldes sont dérivés, jamais saisis

Le solde d'un compte est une projection du journal. La table de soldes est un cache
matérialisé, reconstructible par `REPLAY`. Un écart entre le solde matérialisé et le solde
rejoué est une **anomalie bloquante**, détectée quotidiennement.

## 4. Les comptes clients et les comptes généraux vivent dans le même journal

Il n'y a pas un « module comptes » et un « module comptabilité » réconciliés par
interface. Il y a **un seul ledger** dans lequel un compte client et un compte de
charges sont deux instances de la même entité. La comptabilité générale est une **vue
agrégée** du ledger, pas une base parallèle.

C'est le choix structurant qui élimine la classe entière de bugs « le client est débité
mais la compta ne l'a pas vu ».

## 5. Trois dates, jamais confondues

| Date | Sens | Usage |
|---|---|---|
| `booking_date` | date comptable d'imputation | rattachement à l'exercice, arrêtés |
| `value_date` | date de valeur | calcul des intérêts et des agios |
| `created_at` | horodatage technique d'insertion | audit, ordre de traitement |

La date de valeur peut être antérieure ou postérieure à la date comptable. Les intérêts se
calculent **exclusivement** sur les soldes en date de valeur. Confondre les deux est
l'erreur la plus coûteuse d'un core banking : elle se traduit par des agios faux, donc par
un contentieux client et un risque réglementaire.

## 6. Toute opération est idempotente

Chaque commande de comptabilisation porte une `idempotency_key` unique (contrainte
d'unicité en base). Un rejeu — reprise de batch, retry réseau, double clic, redelivery
Kafka — renvoie le résultat initial sans produire de seconde écriture.

## 7. Tout est daté, versionné et auditable

Le paramétrage (taux, barèmes, schémas comptables, plans de comptes) est **historisé par
période de validité** (`valid_from` / `valid_to`). Un arrêté du 31/12/N rejoué en mars N+1
utilise les paramètres en vigueur au 31/12/N, pas ceux d'aujourd'hui.

Aucune donnée métier n'est supprimée : clôture logique (`closed_at`), jamais suppression
physique.

## 8. Séparation des tâches et double validation

Toute opération sensible — au-delà d'un seuil, ou par nature (ouverture de compte,
déblocage de crédit, forçage de solde, modification de paramétrage) — passe par un circuit
**maker-checker**. Le créateur ne peut jamais être le valideur. Le circuit est paramétrable
par entité, rôle et montant.

## 9. Le paramétrage prime sur le code

Un nouveau produit d'épargne, une nouvelle commission, un nouveau pays ne doivent pas
exiger de livraison logicielle. Les règles vivent dans la **product factory** et les
**schémas comptables** : `événement métier → jeu d'écritures`.

Limite assumée : les règles de calcul complexes (méthodes d'amortissement, algorithmes de
provisionnement) restent du code, sélectionné par paramètre. Un moteur de règles totalement
libre est un piège — il devient un langage de programmation non testé.

## 10. Multi-entités, multi-devises, multi-pays dès le modèle

Toute donnée porte une `legal_entity_id`. Chaque entité a sa devise de tenue de compte, son
plan comptable, son calendrier, son exercice fiscal et son profil réglementaire. Le
cloisonnement est appliqué au niveau de la base (Row Level Security) et pas seulement dans
l'applicatif.

Rétro-installer le multi-entités dans un socle mono-entité représente une réécriture.

## 11. Le TFJ est rejouable et redémarrable

Le TFJ est une suite d'étapes ordonnées, chacune idempotente et traçée. Un échec en étape 7
sur 15 se reprend à l'étape 7, pas depuis le début, et ne produit aucun doublon.
Le TFJ d'une date donnée peut être **annulé intégralement** (contre-passation de
l'ensemble des écritures du run) puis rejoué.

## 12. La réconciliation est automatique et quotidienne

Chaque jour, sans intervention :

- somme des soldes clients par classe = solde du compte général de rattachement ;
- somme des mouvements du jour = variation des soldes ;
- soldes matérialisés = soldes rejoués depuis le journal ;
- balance générale équilibrée, par entité et par devise.

Un écart bloque l'ouverture du jour suivant. Un core banking qui « s'arrange » d'un écart
de quelques unités a déjà perdu.

---

## 13. Toute écriture est équilibrée par agence

Une banque est une entité juridique et N agences, et chaque agence tient ses comptes. Une
écriture qui met en jeu deux agences — le client de l'agence A servi à la caisse de l'agence B —
est équilibrée pour l'entité et déséquilibrée pour chacune des deux. Le moteur la **complète**
par des lignes de liaison, via le siège, avant de l'écrire ; il ne les demande jamais à celui
qui saisit. L'invariant est vérifié deux fois, comme l'équilibre par devise : dans le validateur
et par la base. La compensation inter-agences n'est pas un traitement : c'est un contrôle
quotidien qui prouve que les comptes de liaison s'éliminent ([15](15-multi-agences.md)).

---

## Anti-patterns explicitement refusés

| Anti-pattern | Pourquoi il est fatal |
|---|---|
| Solde stocké comme vérité, mouvements comme historique | Toute divergence devient indétectable et non corrigeable |
| Modification d'une écriture passée | Détruit la piste d'audit et la valeur probante |
| Montants en `float` / `double` | Erreurs d'arrondi cumulatives sur des millions d'écritures |
| Comptabilité générale alimentée par interface différée | Écarts structurels entre le client et la compta |
| Batch non idempotent | Le premier incident produit un double arrêté |
| Suppression physique de données métier | Perte de la piste d'audit, non-conformité |
| Paramétrage non historisé | Impossible de rejouer un arrêté passé |
| Fuseau horaire implicite | Écritures rattachées au mauvais jour comptable |
