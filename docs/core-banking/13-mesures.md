# 13 — Mesures

Les cibles du chapitre [01](01-architecture.md) et les critères de sortie P0 du chapitre
[10](10-roadmap.md) étaient affirmés, pas vérifiés. Ce document les confronte à la mesure.

---

## 1. Ce que ces chiffres valent

| | |
|---|---|
| Environnement | PostgreSQL embarqué, conteneur partagé, `fsync=off`, `synchronous_commit=off` |
| Ce que ça vaut | Les **valeurs absolues ne sont pas des chiffres de production** — une base dédiée fait mieux |
| Ce que ça vaut vraiment | Le **coût unitaire** — accès par opération, par compte — et son évolution |

Le coût unitaire est ce qui décide si un TFJ tient dans sa fenêtre. Un traitement qui coûte vingt
requêtes par compte en coûtera vingt sur une base dix fois plus rapide ; seul le facteur change.
C'est donc lui qu'on mesure, et lui que l'optimisation doit faire baisser.

Reproductible :

```bash
mvn test -pl benchmark -Dtest=LedgerBenchmark -Dbench.accounts=2000 -Dbench.ops=8000
mvn test -pl benchmark -Dtest=TfjBenchmark    -Dbench.accounts=10000
```

Les bancs ne tournent pas dans la construction standard — leurs classes sont nommées `*Benchmark`,
hors du motif de la suite de tests.

---

## 2. Comptabilisation — cibles tenues

8 000 virements concurrents, 8 fils, 2 000 comptes, compte de caisse réparti sur 64 stripes.

| Indicateur | Cible | Mesuré | |
|---|---|---|---|
| Débit soutenu | 1 500 op/s | **1 878 op/s** | ✅ |
| Latence p99 | < 150 ms | **13,4 ms** | ✅ |
| p50 / p95 | — | 2,9 / 7,6 ms | |
| Interblocages | 0 | **0** | ✅ |
| Écarts de réconciliation | 0 | **0** | ✅ |

L'ordre de verrouillage total et le striping tiennent leurs promesses : zéro interblocage sur des
virements aléatoires entre 2 000 comptes partageant une caisse unique.

---

## 3. TFJ — cible manquée, puis corrigée

### Mesure initiale

| Étape | Durée (2 000 comptes) |
|---|---|
| PRE_CHECKS | 3 ms |
| **INTEREST_ACCRUAL** | **8 644 ms** |
| BALANCE_SNAPSHOT | 59 ms |
| RECONCILIATION | 18 ms |
| OPEN_NEXT_DAY | 1 ms |

**99 % du temps dans une seule étape.** Coût par compte 4,371 ms → **145,7 minutes** extrapolées
pour 2 M de comptes, contre 90 de fenêtre. Cible manquée d'un facteur 1,6.

Le contraste était déjà parlant : `BALANCE_SNAPSHOT`, ensembliste, traitait les mêmes 2 000 comptes
en 59 ms — **150 fois moins cher par compte**.

### Une hypothèse fausse, écartée par la mesure

Premier soupçon : le coût des transactions. Le moteur d'intérêts enchaîne une dizaine d'accès, et
chacun ouvrait sa propre transaction. Correction appliquée — une transaction par compte au lieu
d'une par requête.

**Gain : 5 %.** 4,371 → 4,158 ms. L'hypothèse était fausse : le pool réutilise les connexions, et
la validation n'était pas le coût dominant.

Le vrai coût était le **nombre de requêtes** : environ 23 par compte, dont une dizaine pour la
seule comptabilisation.

### Correction

Trois changements, du plus structurant au plus modeste :

| Changement | Effet |
|---|---|
| **Lectures par lot** — un accès pour tous les comptes au lieu d'un par compte (devises, cumuls, premières dates de valeur, mouvements) | Supprime ~8 requêtes par compte |
| **Imputation agrégée** — une écriture par couple de comptes d'imputation, pas une par client | Supprime ~10 requêtes par compte |
| **Paramétrage mis en cache par produit** — deux millions de comptes du même produit partagent une lecture | Supprime ~4 requêtes par compte |

L'imputation agrégée mérite une justification : les intérêts courus d'un même produit débitent tous
le même compte de charges et créditent tous le même compte d'intérêts courus. Produire deux millions
d'écritures à deux lignes vers les deux mêmes comptes généraux n'apporte rien au journal. **Le détail
par client reste intégral** dans `interest_accrual` — assiette, taux effectif, fraction d'année, jour
par jour. C'est la pratique du métier, avec une conséquence à connaître : le grand livre du compte
d'intérêts courus porte une écriture par jour et par produit, et la piste vers le client passe par la
table d'intérêts.

### Mesure après correction

| Portefeuille | Coût par compte | Extrapolation 2 M | |
|---|---|---|---|
| 2 000 comptes | 0,188 ms | 6,3 min | ✅ |
| 10 000 comptes | 0,121 ms | 4,0 min | ✅ |
| 10 000, lots de 2 000 | 0,128 ms | 4,3 min | ✅ |

**Facteur 34** sur le coût par compte. La cible de 90 minutes est tenue avec un facteur 20 de marge.

Le coût baisse entre 2 000 et 10 000 comptes : les coûts fixes s'amortissent. C'est le signe que
l'étape est devenue dominée par le volume et non par le nombre d'aller-retours.

### Le découpage, et pourquoi il coûte 6 %

Le calcul ensembliste charge en mémoire les mouvements des comptes qu'il traite. Sur un portefeuille
entier, tout charger d'un coup épuiserait la mémoire bien avant la fin — l'extrapolation aurait été
malhonnête sans découpage.

Les lots de 2 000 coûtent 6 % de plus que le traitement d'un seul bloc, et bornent la mémoire.
Chaque lot conserve le bénéfice de l'agrégation : deux millions de comptes produisent quelques
centaines d'écritures, pas deux millions.

La marque d'un lot entre dans la clé d'idempotence, et elle est **dérivée de son contenu**, jamais
de son rang. Si un compte est ajouté entre un échec et sa reprise, les découpages se décalent ; une
marque fondée sur le rang ferait alors porter à un lot différent la clé d'un lot déjà imputé, et
l'écriture serait silencieusement omise comme un rejeu.

---

## 4. Commissions — le coût que les intérêts n'avaient pas

L'ajout de l'étape `FEE_CHARGING` a fait repasser le TFJ au-dessus de la cible. La mesure, dans le
cas le plus défavorable — **tous les comptes exigibles le même jour**, ce qui est la situation réelle
d'une banque qui facture la tenue de compte à date fixe :

| | Coût par compte | Extrapolation 2 M | |
|---|---|---|---|
| Perception séquentielle | 3,268 ms | 108,9 min | ❌ manquée d'un facteur 1,2 |
| Perception parallèle (8 fils) | **0,905 ms** | **30,2 min** | ✅ |

**Pourquoi les commissions coûtent ce que les intérêts ne coûtent plus.** L'accrual d'intérêts
s'agrège : deux millions de comptes produisent quelques centaines d'écritures, parce que débit et
crédit visent des comptes généraux communs. Une commission débite un **compte client différent à
chaque fois** : elle ne s'agrège pas. Le coût est donc d'une écriture par compte, soit exactement le
coût unitaire du ledger — 3 ms en séquentiel, ce que le banc de comptabilisation avait déjà mesuré.

**Une écriture par client, et non un bordereau global.** Regrouper deux millions de débits clients
et deux crédits généraux dans une seule écriture serait parfaitement équilibré et beaucoup plus
rapide. C'est refusé : une commission se conteste et se contre-passe **client par client**, et la
contre-passation porte sur l'écriture entière. Le gain de performance se paierait en impossibilité
opérationnelle.

**La parallélisation est donc la seule voie, et elle était disponible.** Les imputations sont
indépendantes d'un compte à l'autre ; le verrouillage ordonné du ledger — déjà mesuré à zéro
interblocage sous contention — les autorise à s'exécuter de front, y compris sur les comptes
généraux de produit et de taxe partagés par toutes les commissions. Le découpage se fait **par
compte** et non par commission : impayés et commissions du jour puisent dans le même disponible et
doivent rester séquentiels entre eux.

Facteur mesuré : **4,2** sur l'étape (6 111 → 1 468 ms sur 2 000 comptes). Le degré de parallélisme
ne doit pas dépasser la taille du pool de connexions du ledger ; au-delà, les tâches attendent une
connexion au lieu de travailler.

**Une hypothèse écartée par la mesure.** Réunir l'imputation et l'enregistrement au registre dans une
seule transaction, au lieu de deux, devait économiser une acquisition de connexion et une validation
par commission. Gain réel : **0,1 %** (6 116 → 6 111 ms). Le coût n'était pas dans les transactions,
il était dans l'écriture elle-même. Le regroupement a été conservé, mais pour une autre raison : une
écriture sans ligne de registre serait refacturée au traitement suivant, et une ligne de registre
sans écriture ferait disparaître une commission facturée.

---

## 5. Crédits — la même forme, le même remède

L'étape `LOAN_SCHEDULE` produit **deux écritures par contrat** : la constatation des charges de
l'échéance, puis le prélèvement. Comme les commissions, elle débite un compte client différent à
chaque fois et ne s'agrège donc pas. Mesure dans le cas le plus défavorable — tous les crédits
échéancent le même jour, tous sont prélevés :

| | Coût par crédit | Extrapolation 200 k | |
|---|---|---|---|
| Séquentiel | 7,556 ms | 25,2 min | ✅ mais sans marge |
| Parallèle (8 fils) | **2,168 ms** | **7,2 min** | ✅ |

Facteur **3,5**. Le gain est plus faible que sur les commissions parce qu'un contrat coûte deux
écritures au lieu d'une, et que le chemin conserve une lecture d'échéancier par contrat.

Le banc a ensuite été étendu à un portefeuille mixte — la moitié prélevée d'office, l'autre non,
donc impayée dès le lendemain — afin de mesurer aussi le chemin de retard :

| Étape | Coût unitaire | Portée |
|---|---|---|
| Exigibilité et prélèvement | 1,788 ms par contrat | la moitié seulement produit une seconde écriture |
| Intérêts de retard et pénalités | 1,585 ms par impayé | reconstitution de l'assiette, journée d'accrual, pénalité, imputation |
| Classification et provisionnement | 1,168 ms par crédit | tout le portefeuille, y compris les crédits sains |
| Mobilisation et intérêts intercalaires | 0,605 ms par crédit en cours de tirage | une écriture et une créance, le jour d'échéance intercalaire |

La classification porte sur **tout** le portefeuille et non sur les seuls impayés : c'est elle qui
établit qu'un crédit est sain. Séquentielle, elle coûtait 3,502 ms par crédit ; le classement reste
global — la contagion regarde tout le portefeuille d'un client — mais le provisionnement de chaque
crédit est indépendant et se parallélise. Facteur mesuré : **2,7**.

Un compteur a été corrigé au passage : le premier arrêté d'un portefeuille signalait un
déclassement général, parce qu'un crédit classé sain pour la première fois était compté comme
dégradé.

Le coût par contrat de l'exigibilité descend à 1,500 ms parce que seule la moitié du portefeuille
est prélevée : les deux chiffres de ce tableau ne se comparent pas à la mesure précédente, qui
prélevait tout le portefeuille.

Une requête a été supprimée au passage : le service demandait d'abord si le contrat portait des
créances ouvertes, puis les relisait pour prélever. Tenter le prélèvement sans condition coûte
moins qu'une requête de plus pour savoir s'il faut le tenter.

La mobilisation est la moins chère des quatre étapes : une écriture par crédit au lieu de deux, et
pas de prélèvement. Elle ne coûte ce prix que les jours d'échéance intercalaire — mensuels, en
général ; les autres jours, elle lit et ne produit rien. Le chiffre est mesuré sur le jour coûteux,
et le portefeuille en mobilisation est par nature une fraction du portefeuille de crédits.

Le TFJ complet, commissions et crédits inclus, se mesure à **0,881 ms par compte**, soit
**29,4 minutes** extrapolées pour 2 M de comptes. L'étape `LOAN_MOBILISATION` y coûte 3 ms fixes
quand aucun crédit n'est en tirage : une requête, pas un balayage.

> À lire correctement : les extrapolations portent sur des volumétries différentes — 2 M de
> comptes, 200 k de crédits — et ne s'additionnent pas telles quelles. Un portefeuille réel les
> cumule : **29 minutes de TFJ plus 7 minutes d'exigibilité** dans l'hypothèse où tout le
> portefeuille de crédits échéance le même jour, ce qui est le pire cas et non le cas courant.

---

## 6. Ce que l'optimisation a coûté, et ce qui l'a rattrapé

Le calcul par lot doit donner **exactement** le même montant que le calcul compte par compte. Un
traitement trente fois plus rapide mais qui arrondit différemment ne serait pas une optimisation :
ce serait un second moteur, aux résultats divergents, et la divergence n'apparaîtrait qu'à la
première réclamation. Un test compare les deux chemins sur des comptes strictement équivalents.

**Une régression a été introduite, et attrapée par la suite existante.** Le chemin par lot écartait
un compte sans mouvement *avant* de résoudre son paramétrage — un compte mal paramétré mais encore
vierge passait donc inaperçu. Il aurait des mouvements le lendemain, et le défaut serait sorti par
la réclamation du client. La résolution du produit a été remontée avant la décision d'écarter.

C'est précisément le genre de silence que le chapitre [00](00-principes.md) proscrit, et il s'est
glissé dans une optimisation de performance. Le rappel est utile : les régressions de correction
n'arrivent pas seulement dans le code de correction.

---

## 7. Ce que ces mesures ne prouvent pas

À traiter avant toute mise en service, et non couvert ici :

| Non mesuré | Pourquoi c'est un risque |
|---|---|
| `fsync=off` | La production écrit vraiment sur disque ; le débit réel sera plus bas |
| Charge soutenue sur plusieurs heures | Gonflement des tables, `VACUUM`, dérive des plans d'exécution |
| TFJ pendant que l'OLTP tourne | Le banc mesure les deux séparément ; ils se disputeront les mêmes verrous |
| Volumétrie réelle du journal | 10 ans d'historique partitionné, pas quelques milliers de lignes |
| Reprise sous charge | Un TFJ repris à mi-parcours sur un portefeuille complet |
| Plusieurs entités simultanées | Le cloisonnement est fonctionnel, sa tenue en charge n'est pas mesurée |
| Perception parallèle sur un pool de production | Mesurée sur 8 connexions ; le point de saturation réel n'est pas connu |

Les cibles du chapitre 01 restent donc des **cibles à re-mesurer sur l'infrastructure cible**. Ce
qui est acquis, c'est le coût unitaire — et il est désormais compatible avec elles.
