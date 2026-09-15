# 02 — Moteur comptable (le cœur)

C'est le composant à construire en premier, et le seul qui ne tolère aucun compromis.
Tout le reste du système est remplaçable ; le ledger, non.

---

## 1. Modèle conceptuel

```
LegalEntity 1──* Account *──1 Currency
                    │
                    │ 0..1  (compte client) ou (compte général)
                    │
             JournalLine *──1 JournalEntry *──1 BusinessTransaction
                    │
                    └──► AccountBalance (projection)
                    └──► AccountBalanceDaily (snapshot)
```

### Les quatre entités du cœur

| Entité | Rôle | Mutabilité |
|---|---|---|
| `Account` | Compte imputable : client **ou** général. Un seul type technique. | Statut modifiable, devise et entité figées |
| `JournalEntry` | Écriture comptable équilibrée, 2..n lignes | **Immuable** |
| `JournalLine` | Ligne d'écriture : un compte, un sens, un montant | **Immuable** |
| `BusinessTransaction` | Opération métier, regroupe 1..n écritures | Statut évolutif |

### Le choix fondateur : un seul type de compte

Un compte client (`CLIENT_123456`) et un compte de produits d'intérêts (`70610`) sont deux
lignes de la même table `account`, avec un `account_kind` différent. Ils sont imputables de
la même manière, soumis aux mêmes invariants, agrégés par la même projection.

Conséquence directe : **la comptabilité générale n'a pas besoin d'être alimentée**. Elle est
l'agrégation du ledger par compte général de rattachement. Il n'existe pas d'interface entre
« la gestion » et « la compta », donc pas d'écart possible entre les deux.

C'est le point où la plupart des refontes bancaires se trompent : elles conservent deux
bases et passent ensuite dix ans à réconcilier.

> **Implémenté** — chaque compte porte en outre sa **nature** pour les états de synthèse
> (`account.nature` : `BALANCE_SHEET`, `PROFIT_AND_LOSS`, `OFF_BALANCE_SHEET`, V36). C'est une
> donnée du compte, jamais une convention sur son code : un plan comptable interne ne numérote
> pas forcément comme le plan de référence, et la clôture annuelle ne peut pas deviner ce qu'elle
> doit solder. Les comptes existants sont de bilan par défaut ; les comptes de charges et de
> produits sont à qualifier avant la première clôture ([05](05-batch-arrete.md)).

---

## 2. Représentation des montants

### Décision

```java
public record Money(BigDecimal amount, Currency currency) {
    public Money {
        Objects.requireNonNull(amount);
        Objects.requireNonNull(currency);
        if (amount.scale() > currency.maxScale()) {
            throw new InvalidScaleException(amount, currency);
        }
    }
}
```

- En base : `NUMERIC(23, 5)` — jusqu'à 10^18 unités, 5 décimales.
- En mémoire : `BigDecimal`, **jamais** `double` ni `float`.
- Échelle imposée par devise : XOF/JPY = 0, EUR/USD = 2, TND/BHD = 3.
- Arrondi : `HALF_EVEN` par défaut, paramétrable par produit et par type de calcul.

### Pourquoi 5 décimales alors que les devises en ont 0 à 3

Les calculs intermédiaires — accruals quotidiens d'intérêts, prorata temporis, ventilation
de commissions — produisent des valeurs sub-unitaires. Les tronquer chaque jour génère une
dérive cumulée de plusieurs unités par an et par compte, donc des millions d'unités à
l'échelle du portefeuille.

**Règle** : on accumule en précision étendue, on **arrondit uniquement au moment de la
comptabilisation effective**, et tout écart d'arrondi est imputé sur un compte dédié
`DIFFERENCE_ARRONDI`. Cet écart est suivi et doit rester marginal ; une dérive anormale est
le symptôme d'une erreur de calcul.

### Alternative écartée

Stocker en unités mineures entières (`BIGINT` de centimes). Robuste, mais ingérable en
multi-devises hétérogène (XOF à 0 décimale et BHD à 3 dans la même écriture) et inadapté
aux accruals. `NUMERIC` exact de PostgreSQL offre la même exactitude sans ces contraintes.

---

## 3. Sens, signe et convention

Une ligne porte un `direction` (`DEBIT` / `CREDIT`) et un **montant toujours positif**.
Il n'existe pas de montant négatif dans le journal.

Le solde d'un compte se calcule selon son **sens naturel** (`normal_balance`) :

```
sens naturel DEBIT  (actif, charges)   : solde = Σ débits − Σ crédits
sens naturel CREDIT (passif, produits) : solde = Σ crédits − Σ débits
```

Un compte client courant a un sens naturel **CREDIT** : la banque doit l'argent au client.
Un solde négatif signifie donc un découvert — le client doit à la banque.

Cette convention élimine la confusion la plus fréquente dans les systèmes maison, où
« le signe du montant » finit par dépendre du module qui l'a écrit.

---

## 4. Les trois dates

| Champ | Portée | Règle |
|---|---|---|
| `booking_date` | écriture | Date comptable. Doit appartenir à une période ouverte. |
| `value_date` | ligne | Date de valeur. Peut différer par ligne d'une même écriture. |
| `created_at` | écriture | Horodatage UTC d'insertion. Jamais utilisé pour un calcul métier. |

### Pourquoi `value_date` est au niveau de la ligne

Un virement interbancaire peut débiter le client en date de valeur J et créditer le compte
de liaison en date de valeur J+2. Les deux lignes de la même écriture portent des dates de
valeur différentes. Un modèle où la date de valeur est portée par l'écriture rend ce cas
impossible à représenter correctement — et c'est un cas courant.

### Calcul des intérêts

Les intérêts se calculent **exclusivement** sur la série des soldes en date de valeur. Une
écriture antidatée (date de valeur passée) déclenche donc un **recalcul rétroactif** des
intérêts depuis cette date : c'est le mécanisme des agios de rétrocession. Il doit être
prévu dès la conception du moteur d'intérêts, pas ajouté ensuite.

---

## 4 bis. Le troisième axe : l'instant de connaissance

Au-delà des trois dates ci-dessus, le ledger conserve **l'instant où chaque écriture est entrée
dans le système** (`knowledge_time`). Ce n'est pas un simple horodatage technique : c'est un axe
d'interrogation à part entière.

### Le problème que cela résout

Un état arrêté au 31 décembre est édité le 5 janvier. Régénéré en mars, il ne donne pas le même
chiffre : des écritures antidatées sont arrivées entre-temps. La question posée en inspection n'est
pas « quel est le bon chiffre », mais **« pourquoi les deux diffèrent, et lequel a été transmis »**.

Un ledger mono-temporel ne sait que constater l'écart. La pratique courante — archiver le PDF de
l'état — prouve ce qui a été édité, mais ne permet ni de le recalculer, ni d'en isoler la cause.

### Ce que permet le second axe

```
solde au 31/12, tel que connu au 05/01     →   800 000   (l'état transmis, reproductible)
solde au 31/12, tel que connu aujourd'hui  →   950 000   (le solde courant)
                                               ───────
écart entièrement expliqué                     150 000   (écritures antidatées, listables)
```

Les trois valeurs sont calculées depuis le même journal, sans archive parallèle. L'état transmis
reste reproductible à l'identique des années après, et l'écart se décompose écriture par écriture.

### Coût de mise en œuvre

Marginal, **à condition d'y penser dès l'origine** : une colonne horodatée sur la ligne, un index,
une clause supplémentaire dans les requêtes de rejeu. Le journal étant déjà immuable, aucune
information n'a besoin d'être ajoutée — seulement d'être exploitée.

Une contrainte conditionne la justesse de l'ensemble : **toutes les lignes d'une écriture partagent
un seul instant de connaissance**, celui de l'écriture. Laisser chaque ligne prendre son propre
horodatage les placerait après l'écriture qui les porte — l'horloge avance à l'intérieur d'une
transaction — et une requête « tel que connu au moment de l'écriture E » exclurait les lignes de E
elle-même. Une écriture entre dans le système d'un seul tenant ; elle porte donc un seul instant.

---

## 5. Multi-devises

### Structure d'une ligne

Chaque ligne porte trois informations de montant :

| Champ | Sens |
|---|---|
| `amount` | montant dans la devise du compte |
| `currency` | devise du compte |
| `functional_amount` | contre-valeur dans la devise de tenue de compte de l'entité |
| `fx_rate` | cours appliqué, historisé |

### Équilibre

L'invariant `Σ débits = Σ crédits` s'applique **par devise**, sur `amount`. Il s'applique
également sur `functional_amount`, toutes devises confondues.

Une opération de change s'équilibre par deux comptes techniques :

```
Achat de 1 000 EUR contre 655 957 XOF (cours 655,957)

  D  Compte client EUR              1 000,00 EUR   (CV  655 957 XOF)
  C  Position de change EUR         1 000,00 EUR   (CV  655 957 XOF)
  D  Contre-valeur de change XOF  655 957,00 XOF
  C  Compte client XOF            655 957,00 XOF
```

Équilibré en EUR, équilibré en XOF, équilibré en contre-valeur. La position de change
mesure l'exposition et est revalorisée à chaque arrêté.

**Jamais** de « conversion puis équilibrage global » : cela dissimule le risque de change
et rend la position inauditable. Une conversion directe à deux lignes — débit EUR, crédit XOF,
sans compte de position — est d'ailleurs rejetée d'office : elle n'est équilibrée dans aucune des
deux devises.

### Équilibre par agence

Chaque ligne porte une **agence comptable** (`journal_line.branch_id`) : celle de son compte pour
un compte client ou interne ; pour un compte général, celle que la ligne précise, à défaut
l'agence de l'opération (`PostingCommand.branchId`), à défaut l'agence unique des comptes à
agence de l'écriture, à défaut le siège. L'invariant `Σ débits = Σ crédits` s'applique alors
aussi **par agence**, par devise et en contre-valeur.

Quand les lignes d'une écriture ne s'équilibrent pas pour une agence, le service d'imputation
la complète par des lignes de liaison (`kind = LIAISON`) sur le compte de liaison de l'agence :
une ligne dans ses livres, sa ligne miroir dans les livres du siège. Elles font partie de
l'écriture, se contre-passent avec elle et n'apparaissent sur aucun relevé. Sans compte de
liaison dans la devise, l'écriture est refusée — jamais équilibrée à défaut. Détail, exemples et
contrôles au [15](15-multi-agences.md).

### Portée exacte du contrôle en contre-valeur

À garder en tête, car elle est facilement surestimée. Une fois l'équilibre par devise acquis, le
contrôle en contre-valeur détecte **des cours incohérents entre lignes d'une même devise** au sein
d'une même écriture — le cas d'un schéma qui applique le cours acheteur d'un côté et le cours de
référence de l'autre, laissant une perte de change non comptabilisée.

Il ne détecte **pas** un cours erroné appliqué uniformément : dans la structure à quatre lignes
ci-dessus, les contre-valeurs des lignes en devise se compensent deux à deux, quel que soit le
cours. Seule la confrontation au cours de référence — table des cours, tolérances, marges — le
détecte. C'est un contrôle du référentiel des cours, pas du ledger, et il doit être prévu comme
tel.

---

## 6. API de comptabilisation

```java
public interface PostingService {

    /**
     * Comptabilise une commande. Idempotent : un rejeu avec la même
     * idempotencyKey renvoie le résultat initial sans nouvelle écriture.
     */
    PostingResult post(PostingCommand command);

    /**
     * Contre-passe intégralement une écriture. Produit une nouvelle
     * écriture inverse liée à l'originale. Ne modifie jamais l'originale.
     */
    PostingResult reverse(JournalEntryId entryId, ReversalReason reason);
}

public record PostingCommand(
    IdempotencyKey idempotencyKey,
    LegalEntityId entity,
    LocalDate bookingDate,
    TransactionType type,
    List<PostingLine> lines,
    Map<String, String> metadata
) {}

public record PostingLine(
    AccountId account,
    Direction direction,
    Money amount,
    LocalDate valueDate,
    String label
) {}
```

### Contrôles appliqués, dans cet ordre

1. **Idempotence** — la clé existe déjà ? Retour du résultat initial, aucun traitement.
2. **Période comptable** — `booking_date` dans une période ouverte pour l'entité.
3. **Existence et statut des comptes** — actifs, non clôturés, imputables.
4. **Cohérence de devise** — la devise de la ligne correspond à celle du compte.
5. **Équilibre** — par devise et en contre-valeur.
5 bis. **Agence** — agence comptable de chaque ligne, lignes de liaison générées, équilibre
   par agence.
6. **Disponible** — pour les comptes à contrôle de solde : `solde − blocages + autorisation
   de découvert ≥ montant`.
7. **Limites et plafonds** — par produit, client, canal, période.
8. **Insertion atomique** — écriture, lignes, mises à jour de soldes, événement outbox,
   dans une seule transaction.

Un échec à n'importe quelle étape annule tout. Il n'existe pas d'état intermédiaire
persisté.

---

## 7. Concurrence et comptes chauds

### Le problème

Un compte de contrepartie interne (liaison GAB, suspens de compensation, commissions) est
mouvementé par **toutes** les opérations. Verrouiller sa ligne de solde sérialise
l'intégralité du trafic : le débit maximal du système devient celui d'un seul compte.

### Solution : striping des soldes

Le solde d'un compte marqué `hot` est réparti sur N sous-soldes (`stripe_id` de 0 à N−1).
Chaque écriture met à jour un stripe choisi par hachage ou aléatoirement. Le solde réel est
la somme des stripes.

```sql
-- Mise à jour : contention divisée par N
UPDATE account_balance
   SET balance = balance + :delta, version = version + 1
 WHERE account_id = :id AND stripe_id = :stripe;

-- Lecture : agrégation
SELECT SUM(balance) FROM account_balance WHERE account_id = :id;
```

N = 1 pour les comptes clients (aucun surcoût), N = 32 à 128 pour les comptes chauds.
Le striping est un attribut de compte, modifiable sans migration de données.

### Verrouillage des comptes clients

Verrou pessimiste (`SELECT ... FOR UPDATE`) sur la ligne de solde, **uniquement** pour les
comptes à contrôle de disponible. Ordre de verrouillage **toujours croissant par
`account_id`** : c'est ce qui évite les interblocages sur les virements croisés entre deux
comptes.

```java
List<AccountId> ordered = command.lines().stream()
    .map(PostingLine::account)
    .distinct()
    .sorted()                       // ← l'ordre total évite le deadlock
    .toList();
balanceRepository.lockAll(ordered);
```

Pour les comptes sans contrôle de disponible (comptes de produits, de charges), aucun
verrou : un `UPDATE ... SET balance = balance + delta` suffit, PostgreSQL sérialise la
ligne le temps de l'instruction.

---

## 8. Soldes : projection, snapshot, rejeu

### Trois niveaux

| Niveau | Table | Usage |
|---|---|---|
| Temps réel | `account_balance` | Solde courant, contrôle de disponible |
| Quotidien | `account_balance_daily` | Solde de clôture par date comptable **et** par date de valeur |
| Source de vérité | `journal_line` | Rejeu, audit, reconstruction |

### Solde disponible

```
disponible = solde comptable
           − Σ blocages actifs
           − Σ opérations en cours de validation
           + autorisation de découvert en vigueur
```

Les quatre composantes sont distinctes et traçables. Un blocage (`account_hold`) porte un
montant, un motif, une date d'expiration et une référence d'origine — il expire seul, sans
intervention.

### Rejeu

```java
public Money replayBalance(AccountId account, LocalDate asOf) {
    var snapshot = dailyBalanceRepository.lastSnapshotBefore(account, asOf);
    var delta = journalLineRepository.sumBetween(
        account, snapshot.date().plusDays(1), asOf);
    return snapshot.balance().plus(delta);
}
```

Le rejeu depuis le dernier snapshot rend la reconstruction instantanée même sur dix ans
d'historique. Il est exécuté **quotidiennement en contrôle** sur un échantillon aléatoire
de comptes, et intégralement lors de l'arrêté mensuel. Tout écart bloque l'arrêté.

---

## 9. Contre-passation

Une écriture erronée ne se supprime pas. Elle se contre-passe :

```
Écriture originale #4711 (booking_date 2026-03-10)
  D  Client A     1 000,00
  C  Client B     1 000,00

Contre-passation #4899 (booking_date 2026-03-12, reversal_of = 4711)
  C  Client A     1 000,00
  D  Client B     1 000,00
```

Règles :

- La contre-passation porte la **date comptable du jour**, jamais celle de l'originale —
  sauf si la période de l'originale est encore ouverte et que la politique de l'entité
  l'autorise (paramètre `reversal_in_original_period`).
- Les **dates de valeur sont reprises à l'identique** : sinon les intérêts déjà calculés
  deviennent faux.
- Une écriture ne peut être contre-passée qu'une fois (contrainte d'unicité sur
  `reversal_of`).
- Une contre-passation ne se contre-passe pas : on réémet l'écriture correcte.

---

## 10. Comptabilisation en lot

L'arrêté produit des millions de lignes. Le chemin unitaire (une transaction par écriture)
est inadapté.

```java
public interface BatchPostingService {
    /**
     * Comptabilise un lot homogène en une transaction.
     * Tout ou rien : un rejet invalide le lot entier.
     */
    BatchResult postBatch(BatchPostingCommand command);
}
```

- Insertion par `COPY` ou `INSERT ... VALUES` multi-lignes (5 000 à 10 000 lignes par lot).
- Soldes agrégés **en mémoire** sur le lot, puis un seul `UPDATE` par compte.
- Clé d'idempotence dérivée de façon déterministe :
  `hash(run_id, step, account_id, sequence)` — un rejeu de l'étape ne produit aucun doublon.
- Écritures d'arrêté marquées `source = BATCH` et rattachées à un `batch_run_id`, ce qui
  permet d'**annuler un arrêté entier** par contre-passation de masse.

Performance mesurée sur PostgreSQL 16 avec ce schéma : 40 000 à 80 000 lignes/s en insertion
par lot sur du matériel standard. Le facteur limitant est l'agrégation des soldes, pas
l'insertion.

---

## 11. Invariants vérifiés en continu

| Invariant | Contrôle | Fréquence |
|---|---|---|
| Écriture équilibrée par devise | Trigger base + test domaine | À chaque écriture |
| Journal non modifiable | Trigger `BEFORE UPDATE/DELETE` + droits | Permanent |
| Solde matérialisé = solde rejoué | Job de contrôle | Quotidien (échantillon), mensuel (total) |
| Σ soldes clients = compte général de rattachement | Job de réconciliation | Quotidien, bloquant |
| Balance générale équilibrée par entité et devise | Job de réconciliation | Quotidien, bloquant |
| Aucune écriture en période clôturée | Contrainte + contrôle | À chaque écriture |
| Somme des stripes = solde agrégé | Job de contrôle | Quotidien |
| Écart d'arrondi dans les bornes | Seuil paramétré, alerte | Quotidien |

Un invariant en échec **bloque l'ouverture du jour suivant**. C'est volontaire : un core
banking qui continue à tourner avec un écart comptable accumule une dette impossible à
solder.

---

## 12. Restitutions : balance, grand livre, journal

> **Implémenté** — `TrialBalance`, `Journal`, module `ledger-store` ; exposés par `LEDGER_READ`.

Une restitution est une **lecture du journal**, jamais d'un cliché : ce qu'elle montre est
exactement ce que les écritures disent à la date demandée, et elle se recalcule à l'identique
tant que le journal ne change pas — c'est ce qui la rend opposable.

**La balance** donne, pour chaque compte mouvementé jusqu'à la fin de la plage, six colonnes :
solde d'ouverture (ce qui précède la plage), mouvements débit et crédit, solde de clôture —
chaque solde n'occupant qu'une colonne, au débit ou au crédit. Le ledger tient un seul type de
compte, clients et généraux dans le même journal : la balance de tous les comptes **est** la
balance générale, la balance auxiliaire des clients en est un filtre (`kind`), la balance
d'agence un autre (`branchId`, sur la dimension d'agence des lignes). Les totaux par devise
s'équilibrent par construction — ouverture, mouvements, clôture — et le constat est rendu avec
eux ; sur une balance filtrée il est faux, et il le dit.

**Le grand livre et le journal** se lisent **par curseur**. L'ordre total est celui de quatre
colonnes de la ligne elle-même — date comptable, instant de connaissance, écriture, ligne —,
portées par un index dans cet ordre pour chaque clé de lecture : l'entité
(`idx_line_entity_journal`, V37) et le compte (`idx_line_account_journal`, V38, qui remplace
l'index compte-date qu'il prolonge). La page suivante reprend strictement après une position,
par comparaison de lignes (`(a, b, c, d) > (?, ?, ?, ?)`) que le planificateur sert comme
condition d'index — un test le vérifie sur le plan d'exécution
(`the_cursor_is_served_by_an_index_condition`) —, et son coût ne dépend pas de ce qui la
précède. Dans une journée, l'ordre est celui où les écritures ont été connues — l'ordre
chronologique du journal, celui de l'édition. Le relevé par pages numérotées lit le même ordre :
un écran et une extraction ne se contredisent pas.

**Le coût de la balance est dit, pas caché.** Le solde d'ouverture est une somme sur tout ce qui
précède la plage : la balance coûte l'historique du journal de l'entité, borné par l'élagage des
partitions. C'est le prix de l'exactitude en date comptable : un cliché quotidien ne la donnerait
pas, parce qu'une écriture antidatée dans une période ouverte change le solde d'une date déjà
clichée. L'accélération exacte, quand le volume l'exigera, est un cliché **par date comptable
arrêté à la clôture de période** — une période close n'admet plus d'antidatage — d'où la
balance repartirait ; elle est notée au plan, et ne change ni la forme ni le résultat.

