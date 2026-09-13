# 05 — Moteur d'arrêté (EOD / EOM / EOY)

Le second composant critique. Un arrêté non idempotent transforme le premier incident de
production en crise comptable.

---

## 1. Modèle d'exécution

```
BatchRun            (entité, date comptable, type, statut)
  └── BatchStep *   (ordre, nom, statut, checkpoint, compteurs)
        └── BatchPartition *  (plage de comptes, statut)
```

### Statuts d'un run

```
PLANIFIÉ → EN_COURS → ┬→ TERMINÉ → (jour suivant ouvert)
                      ├→ EN_ÉCHEC → reprise à l'étape en échec
                      └→ ANNULÉ   → contre-passation intégrale
```

### Garanties

| Garantie | Mécanisme |
|---|---|
| **Unicité** | Verrou en base sur `(legal_entity_id, business_date, run_type)`. Deux arrêtés simultanés sont impossibles, même en cas de double instance du pod. |
| **Idempotence** | Clé de comptabilisation déterministe : `hash(run_id, step, account_id, sequence)`. Un rejeu n'insère rien. |
| **Reprise** | Chaque étape enregistre un checkpoint. Une reprise repart de l'étape en échec, pas du début. |
| **Annulabilité** | Toutes les écritures portent le `batch_run_id`. Une annulation contre-passe l'intégralité du run. |
| **Reproductibilité** | Le paramétrage est lu à la date comptable du run, pas à la date du jour. Un rejeu produit des montants identiques. |

---

## 2. Séquence de l'arrêté quotidien

| # | Étape | Contenu | Bloquant |
|---|---|---|---|
| 1 | `CUT_OFF` | Gel des saisies sur la date comptable, bascule des canaux sur J+1 | ✔ |
| 2 | `PRE_CHECKS` | Suspens non soldés, opérations en attente, disponibilité du paramétrage | ✔ |
| 3 | `FX_RATES` | Chargement et contrôle des cours de clôture | ✔ |
| 4 | `VALUE_DATE_REBUILD` | Reconstruction des soldes en date de valeur, détection des antidatages | ✔ |
| 5 | `INTEREST_ACCRUAL` | Accruals créditeurs et débiteurs, y compris recalculs rétroactifs | ✔ |
| 6 | `LOAN_SCHEDULE` | Échéances du jour, exigibilité, passage en impayé | ✔ |
| 7 | `PENALTIES` | Intérêts de retard, pénalités | |
| 8 | `FEES` | Commissions périodiques, frais de tenue de compte, taxes associées | |
| 9 | `CLASSIFICATION` | Jours de retard, buckets, contagion client | ✔ |
| 10 | `PROVISIONING` | Dotations et reprises, suspension des intérêts | ✔ |
| 11 | `FX_REVALUATION` | Revalorisation des positions de change | ✔ |
| 12 | `DORMANCY` | Détection de dormance, régime de frais associé | |
| 13 | `HOLD_EXPIRY` | Expiration des blocages arrivés à terme | |
| 14 | `KYC_REVIEW` | Échéances de revue périodique, expiration de documents | |
| 15 | `BALANCE_SNAPSHOT` | Snapshot des soldes par date comptable et par date de valeur | ✔ |
| 16 | `RECONCILIATION` | Contrôles d'intégrité (cf. §5) | ✔ |
| 17 | `REPORTING` | États quotidiens, extractions vers le datamart | |
| 18 | `OPEN_NEXT_DAY` | Ouverture de la date comptable suivante | ✔ |

Une étape **bloquante** en échec arrête le run. Les autres consignent une anomalie et
laissent le run se poursuivre, avec restitution à la clôture.

### Ordre non négociable

Trois contraintes d'ordre sont structurelles :

- **4 avant 5** : les accruals se calculent sur les soldes en date de valeur, qui doivent
  être reconstruits d'abord.
- **9 avant 10** : on ne provisionne que ce qui est classé.
- **10 avant 11** : la suspension des intérêts modifie les positions à revaloriser.

### Étapes supplémentaires

**Mensuel (EOM)** : capitalisation des intérêts, échelles et agios, commissions mensuelles,
arrêté de la balance, contrôle de rejeu **intégral** des soldes, états réglementaires,
clôture de la période.

**Annuel (EOY)** : détermination du résultat, affectation, report à nouveau, réouverture des
comptes de bilan, états financiers, liasse réglementaire, archivage de l'exercice.

---

## 3. Performance

Cible : 2 millions de comptes traités en moins de 90 minutes.

### Partitionnement

```java
@Bean
public Step interestAccrualStep(JobRepository repo, PlatformTransactionManager tm) {
    return new StepBuilder("INTEREST_ACCRUAL", repo)
        .partitioner("worker", new AccountRangePartitioner(64))
        .step(interestAccrualWorkerStep(repo, tm))
        .gridSize(64)
        .taskExecutor(batchTaskExecutor())   // pool borné, dimensionné sur la base
        .build();
}
```

- Partition par **plage de hachage d'identifiant de compte** : réparti uniformément, stable
  d'un run à l'autre, donc rejouable partition par partition.
- Chaque partition est une transaction indépendante avec son propre checkpoint : l'échec
  d'une partition n'invalide pas les autres.
- Le parallélisme est borné par la capacité de la base, pas par le nombre de cœurs
  applicatifs. Sur-paralléliser dégrade le débit par contention sur les comptes généraux.

### Règles d'implémentation

| Règle | Raison |
|---|---|
| Lecture en flux (curseur), jamais `findAll()` | Une liste de 2 M d'objets sature la mémoire |
| Écriture par lots de 5 000 à 10 000 lignes | Optimum mesuré insertion / taille de transaction |
| Agrégation des soldes en mémoire par partition | Un `UPDATE` par compte au lieu d'un par écriture |
| Aucun accès réseau externe dans une étape | Un appel HTTP par compte multiplie la durée par 50 |
| Index dédiés aux requêtes de batch | Les plans d'exécution OLTP ne conviennent pas au batch |
| `COPY` pour les insertions de masse | 3 à 5 fois plus rapide qu'`INSERT` unitaire |

### Comptes de contrepartie chauds

L'accrual d'intérêts impute des millions de lignes sur quelques comptes de charges. Sans
striping ([02](02-ledger.md#7-concurrence-et-comptes-chauds)), ces comptes sérialisent tout le batch. Avec striping à
N = 64, la contention devient négligeable.

---

## 4. Reprise sur incident

### Échec d'une étape

1. Le run passe en `EN_ÉCHEC`, l'étape et la partition fautives sont identifiées.
2. Diagnostic : les compteurs (lus / traités / rejetés) et les enregistrements en anomalie
   sont consultables dans la console d'exploitation.
3. Correction — donnée, paramétrage ou correctif.
4. Reprise : `POST /eod/runs/{id}/resume`. Les étapes terminées ne sont pas rejouées ; les
   partitions terminées de l'étape en échec ne le sont pas non plus.

### Annulation d'un arrêté

```
POST /eod/runs/{id}/cancel
```

- Contre-passation de **toutes** les écritures portant ce `batch_run_id`, en date comptable
  du jour ou de l'originale selon la politique de l'entité.
- Restauration des statuts modifiés (classification, dormance) depuis l'historique.
- Réouverture de la date comptable.
- Opération soumise à double validation, tracée et notifiée.

C'est la fonction qui distingue un arrêté industriel d'un script de batch. Sans elle, une
erreur de paramétrage détectée après l'arrêté impose une correction manuelle compte par
compte — en pratique, plusieurs semaines de travail et un risque d'erreur majeur.

### Retard d'arrêté

Si l'arrêté du jour J n'a pas été exécuté, l'arrêté J+1 est **refusé**. Le rattrapage
s'effectue en exécutant les arrêtés dans l'ordre chronologique, chacun avec sa date
comptable et son paramétrage d'époque. Aucun mécanisme de « fusion » de plusieurs jours :
il produirait des intérêts faux.

---

## 5. Contrôles de réconciliation

Exécutés à l'étape 16, tous bloquants.

```sql
-- 1. Balance générale équilibrée, par entité et par devise
SELECT legal_entity_id, currency,
       SUM(CASE WHEN direction = 'DEBIT'  THEN amount ELSE 0 END)
     - SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE 0 END) AS ecart
  FROM journal_line l JOIN journal_entry e ON e.id = l.entry_id
 WHERE e.booking_date <= :business_date
 GROUP BY legal_entity_id, currency
HAVING SUM(CASE WHEN direction = 'DEBIT'  THEN amount ELSE 0 END)
     <> SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE 0 END);

-- 2. Soldes matérialisés = soldes rejoués depuis le journal
SELECT b.account_id, b.balance AS materialise, r.balance AS rejoue
  FROM account_balance_agg b
  JOIN replay_balance(:business_date) r USING (account_id)
 WHERE b.balance <> r.balance;

-- 3. Σ comptes clients d'une classe = solde du compte général de rattachement
-- 4. Σ stripes = solde agrégé
-- 5. Suspens non soldés au-delà du seuil d'ancienneté
-- 6. Écarts d'arrondi cumulés dans les bornes paramétrées
-- 7. Aucune écriture en période clôturée
```

Un écart, même unitaire, bloque l'ouverture du jour suivant et déclenche une alerte de
niveau critique.

Ce choix est délibéré. La tentation d'un seuil de tolérance est forte en exploitation ; elle
est toujours perdante : un écart accepté aujourd'hui devient un écart de plusieurs milliers
en fin d'exercice, dont l'origine est alors introuvable.
