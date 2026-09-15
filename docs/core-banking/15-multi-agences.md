# 15. Multi-agences : comptabilité par agence et compensation inter-agences

> Étude de conception, préalable à la couche API, **implémentée** (V24, `Branches`,
> `InterbranchBridging`, `TfjInterbranchIT`). Les décisions du §4 ont été validées par la banque
> et retenues telles quelles. Trois précisions par rapport à l'étude : l'agence d'une ligne sur
> compte général vient d'abord de la ligne, puis de **l'agence de l'opération portée par la
> commande** (`PostingCommand.withBranch`), ce qui évite aux services de raisonner ligne par
> ligne ; le cliché par agence est une table à part (`branch_balance_daily`), le cliché par
> compte restant intact ; seul le schéma via le siège existe, les deux autres s'ajouteront par
> migration. Un compte d'attente (`SUSPENSE`) peut avoir une agence ou non. Une agence ou une
> région se crée par l'API (`POST /branches`, opération `BRANCH_MANAGE`) : demandée par
> l'exploitation, validée par la comptabilité, avec ses comptes de liaison par devise — celui de
> la devise de tenue de compte est exigé, sans lui aucune opération déplacée ne s'équilibrerait.

---

## 0. Le problème

Une banque est **une** entité juridique — un bilan, un plan comptable, une date comptable — et
**N** agences. Mais chaque agence tient ses comptes : ses caisses, ses clients, ses charges et
ses produits, et sa position vis-à-vis du siège. Quatre exigences en découlent, et aucune n'est
satisfaite par un ledger qui ne connaît que l'entité :

1. **La balance de chaque agence est équilibrée à tout instant.** Une écriture équilibrée pour
   l'entité peut être déséquilibrée pour deux agences : un client de l'agence A qui retire à la
   caisse de l'agence B débite un compte de A et crédite une caisse de B.
2. **Les opérations déplacées existent.** Un client est servi dans n'importe quelle agence de
   sa banque ; c'est même l'argument commercial d'un réseau.
3. **Les comptes de liaison se justifient chaque jour.** La position de chaque agence vis-à-vis
   du siège doit se lire, se rapprocher et s'éliminer à la consolidation : c'est la
   *compensation inter-agences*. Un écart non justifié est, dans la pratique, le lieu de la
   fraude interne la plus classique.
4. **Le périmètre de sécurité est l'agence.** Un guichetier tient la caisse de son agence ; un
   chef d'agence répond des comptes ouverts dans la sienne. `Scope.OWN_BRANCH` existe dans la
   politique, mais rien dans le modèle ne dit à quelle agence un compte appartient.

S'y ajoute une exigence de gestion : la **rentabilité par agence**, que les banques du réseau
attendent du système et non d'un retraitement.

---

## 1. Ce que font les progiciels établis

| Progiciel | Mécanisme |
|---|---|
| Amplitude (Sopra) | Comptabilité par agence ; comptes de liaison inter-agences ; écritures automatiques sur opérations déplacées ; *compensation inter-agences* quotidienne au siège |
| Flexcube (Oracle) | *Inter-Branch accounting* : trois schémas — **Direct** (bilatéral), **Through Head Office**, **Through Regional Office** — ; lignes IB générées par le moteur ; soldes de comptes généraux tenus **par agence** |
| T24 (Temenos) | *Inter-company accounting* : comptes INTERCO automatiques entre « companies » |

Le point commun, et c'est lui que le socle doit reprendre : **l'agence est une dimension de
chaque ligne d'écriture, l'équilibre est exigé par agence, et les lignes de liaison sont
générées par le moteur — jamais saisies.** Une liaison saisie à la main est une liaison qu'on
oublie, et un compte de liaison qui ne s'équilibre pas est un compte qu'on « régularise ».

---

## 2. Modèle proposé

### 2.1 Référentiel

```sql
CREATE TABLE branch (
    id                 UUID PRIMARY KEY,
    legal_entity_id    UUID NOT NULL REFERENCES legal_entity(id),
    code               TEXT NOT NULL,
    name               TEXT NOT NULL,
    kind               TEXT NOT NULL CHECK (kind IN ('HEAD_OFFICE','REGION','BRANCH')),
    parent_id          UUID REFERENCES branch(id),          -- région ou siège
    liaison_account_id UUID REFERENCES account(id),         -- compte de liaison de l'agence
    status             TEXT NOT NULL DEFAULT 'ACTIVE',
    opened_on          DATE NOT NULL,
    closed_on          DATE,
    CONSTRAINT uq_branch_code UNIQUE (legal_entity_id, code)
);
-- Un seul siège par entité : c'est lui qui porte le miroir des liaisons.
CREATE UNIQUE INDEX uq_head_office ON branch(legal_entity_id) WHERE kind = 'HEAD_OFFICE';

ALTER TABLE legal_entity ADD COLUMN interbranch_scheme TEXT NOT NULL DEFAULT 'VIA_HEAD_OFFICE'
    CHECK (interbranch_scheme IN ('VIA_HEAD_OFFICE','VIA_REGION','BILATERAL'));
```

La migration crée un siège par entité existante et y rattache tout ce qui existe : une base
mono-agence est une base dont toutes les agences sont le siège, et rien ne change pour elle.

La caisse par guichetier (`till`) est l'étape suivante : un compte interne par guichet, avec
l'arrêté de caisse qui conditionne le TFJ ([05](05-batch-arrete.md), terminologie). Ce document
n'en dépend pas : une caisse est un compte interne rattaché à une agence.

### 2.2 Les comptes : une agence gestionnaire, ou une dimension

```sql
ALTER TABLE account ADD COLUMN branch_id UUID REFERENCES branch(id);
-- Comptes clients et comptes internes (caisses, suspens d'agence) : une agence, toujours.
-- Comptes généraux : aucune — leur solde se tient PAR AGENCE, dimension de la ligne.
ALTER TABLE account ADD CONSTRAINT ck_account_branch CHECK (
    (account_kind IN ('CUSTOMER','INTERNAL') AND branch_id IS NOT NULL)
    OR (account_kind NOT IN ('CUSTOMER','INTERNAL') AND branch_id IS NULL));
```

C'est **le** choix structurant, et il se justifie. Deux modèles existent :

- **Comptes généraux « agencés »** (Amplitude) : un compte de produit d'intérêts *par agence*,
  le numéro portant le code agence. Simple à lire, mais chaque compte cité par le paramétrage
  (`interest.credit_account`, `ops.fee_income_account`, la vingtaine de rôles d'un produit de
  crédit) devrait être résolu par agence ; un produit changerait de comptes à chaque ouverture
  d'agence, et le contrôle des comptes du paramétrage ([12](12-parametrage-inventaire.md)) se
  multiplierait par le nombre d'agences.
- **Un compte général, des soldes par agence** (Flexcube) : le compte est unique, la ligne porte
  l'agence, la balance agence se lit en groupant les lignes. Le paramétrage cite un compte, la
  rentabilité par agence est un `GROUP BY`.

Le second est retenu. Il ne touche pas au catalogue, ne multiplie aucun compte, et rend la
balance par agence gratuite.

### 2.3 Le journal : l'agence sur chaque ligne, l'équilibre par agence

```sql
ALTER TABLE journal_line  ADD COLUMN branch_id UUID NOT NULL;   -- agence comptable de la ligne
ALTER TABLE journal_line  ADD COLUMN kind TEXT NOT NULL DEFAULT 'BUSINESS'
    CHECK (kind IN ('BUSINESS','LIAISON'));                     -- ligne métier ou de liaison
ALTER TABLE journal_entry ADD COLUMN originating_branch_id UUID; -- agence de saisie (jeton) ; nulle en batch
```

**Nouvel invariant, le treizième** ([00](00-principes.md)) : *une écriture est équilibrée par
devise **et par agence***. Comme l'équilibre par devise, il est vérifié deux fois — dans
`EntryValidator`, après génération des lignes de liaison, et par le déclencheur de contrainte
différé des partitions (`trg_balanced_*`, étendu au groupement par `branch_id`). Une écriture
déséquilibrée pour une agence ne peut pas exister en base, quelle que soit la voie d'entrée.

L'agence d'une ligne se résout ainsi, dans l'ordre :

1. **Compte à agence** (client, interne) : l'agence du compte, sans discussion — la ligne la
   porte en dénormalisation, et une valeur contraire est refusée.
2. **Compte général, agence fournie par le service** (`PostingLine.branchId`) : le service sait
   à qui revient le produit ou la charge — au client (intérêts, commissions périodiques) ou à
   l'agence qui a servi (frais d'une opération déplacée). C'est la voie normale des services.
3. **Compte général, sans indication** : l'agence unique des comptes à agence de l'écriture s'il
   n'y en a qu'une (le cas d'une écriture d'ordre divers sur un client), sinon l'agence de saisie,
   sinon le siège.

### 2.4 Les lignes de liaison

Quand, après résolution, l'écriture n'est pas équilibrée par agence, le service d'imputation
**complète l'écriture** avec des lignes de liaison, selon le schéma de l'entité :

- **Via le siège** (défaut, recommandé) : pour chaque agence B dont le net n'est pas nul, une
  ligne dans les livres de B sur le compte de liaison de B (`LIAISON-B`), qui l'équilibre, et sa
  ligne miroir dans les livres du siège sur le **même** compte. Chaque agence est équilibrée ; le
  siège l'est par construction, puisque l'écriture l'est pour l'entité. Un compte de liaison par
  agence, rapprochement par agence, N comptes pour N agences.
- **Bilatéral** : lignes directes entre A et B sur `LIAISON-A/B`. N² comptes ; ne convient qu'à
  un réseau de deux ou trois agences.
- **Via la région** : deux sauts, agence → région → siège. Pour les réseaux qui consolident par
  direction régionale.

Les lignes de liaison font partie de l'écriture (même identifiant, numéros de ligne après les
lignes métier), sont marquées `LIAISON`, portent la date comptable en date de valeur (un compte de
liaison ne porte pas d'intérêts) et un libellé qui nomme les deux agences. Elles sont
contre-passées avec l'écriture, annulées avec l'arrêté, et exclues des relevés clients.

### 2.5 Exemples

Agence A, agence B, siège S. Client de A, solde 100 000. Schéma via le siège.

**Retrait déplacé** — le client retire 20 000 à la caisse de B, frais 500, taxe 90 ; le frais
d'opération déplacée revient à l'agence qui sert (règle 2, décidée par `OperationsService`).

| Agence | Compte | Débit | Crédit |
|---|---|---|---|
| A | Client | 20 590 | |
| B | Caisse B | | 20 000 |
| B | Produits — frais d'opération | | 500 |
| B | Taxe collectée | | 90 |
| *A* | *LIAISON-A* | | *20 590* |
| *S* | *LIAISON-A* | *20 590* | |
| *S* | *LIAISON-B* | | *20 590* |
| *B* | *LIAISON-B* | *20 590* | |

A est équilibrée (20 590 / 20 590), B aussi (20 590 / 20 590), S aussi. Lecture : A doit
20 590 au siège (il a payé son client), le siège les doit à B (qui a sorti les espèces).

**Virement** d'un client de A vers un client de B, 30 000, frais 200 et taxe 36 à l'émetteur ;
le frais revient à l'agence du compte émetteur.

| Agence | Compte | Débit | Crédit |
|---|---|---|---|
| A | Client émetteur | 30 236 | |
| B | Client bénéficiaire | | 30 000 |
| A | Produits — frais de virement | | 200 |
| A | Taxe collectée | | 36 |
| *A* | *LIAISON-A* | | *30 000* |
| *S* | *LIAISON-A* | *30 000* | |
| *S* | *LIAISON-B* | | *30 000* |
| *B* | *LIAISON-B* | *30 000* | |

**Intérêts de la nuit** (agrégés par lot de 5 000 comptes, [05](05-batch-arrete.md)) : chaque
ligne client porte l'agence de son compte ; la ligne de charge, aujourd'hui unique par lot, est
agrégée **par agence** (30 lignes pour 30 agences, pas une). L'écriture est équilibrée par agence
sans aucune ligne de liaison, et la charge d'intérêts est dans le résultat de l'agence du client.
Même chose pour les commissions, les échéances de crédit, les provisions.

**Contre-passation, annulation d'arrêté** : l'écriture entière est contre-passée, lignes de
liaison comprises. Rien à faire de plus — c'est ce que gagne un modèle où la liaison est *dans*
l'écriture et non à côté.

### 2.6 Soldes et balance par agence

Le solde tenu en temps réel (`account_balance`) reste **par compte** : les comptes à agence n'en
ont qu'une, et le contrôle du disponible n'a pas besoin de la dimension. Pour les comptes
généraux, le solde par agence est **arrêté chaque nuit** : `account_balance_daily` reçoit
`branch_id` dans sa clé, alimenté par `BALANCE_SNAPSHOT` avec le même mécanisme incrémental que la
réconciliation ([14](14-audit.md) §7, n° 26). La balance agence à une date est une lecture de ce
cliché ; la balance agence en cours de journée est le cliché de la veille plus les lignes du jour.

Ne pas tenir de solde général par agence en temps réel est un choix : les comptes généraux sont
les comptes chauds du système ([02](02-ledger.md) §7), et une dimension de plus sur le chemin
d'imputation coûterait sur chaque écriture ce qu'aucun contrôle en ligne ne demande.

### 2.7 La compensation inter-agences : un contrôle, chaque nuit

Un nouveau contrôle de `RECONCILIATION`, bloquant, `INTER_AGENCES`, après le cliché :

1. **Le miroir** — pour chaque agence B : solde de `LIAISON-B` dans les livres de B + solde de
   `LIAISON-B` dans les livres du siège = 0.
2. **L'élimination** — la somme de tous les comptes de liaison, toutes agences et siège
   confondus, est nulle : à la consolidation, il ne reste rien.
3. **L'équilibre par agence de chaque écriture du jour**, garanti par le déclencheur et rejoué
   intégralement au TFM (`FULL_RECONCILIATION`).

Un écart nomme l'agence, le compte et le montant, et bloque la journée — comme un écart de
sous-livre. Il n'y a pas d'« apurement » à faire : dans une même entité juridique, les comptes de
liaison ne se règlent pas, ils s'éliminent ; le solde de `LIAISON-B` est la position nette de B
vis-à-vis du siège, et il figure tel quel dans la balance agence.

Ce que ce document n'appelle pas *compensation* : la compensation **interbancaire** (chèques,
virements, SICA-UEMOA, STAR-UEMOA) est un autre sujet — comptes de compensation auprès de la
BCEAO, suspens de compensation, rejets — qui relève du module de paiements
([11](11-profil-uemoa-bceao.md)) et n'est pas commencé.

### 2.8 Sécurité : ce que `OWN_BRANCH` veut dire, opération par opération

Le périmètre agence existe dans la politique ; il devient réel dès que l'objet visé porte une
agence. Mais « l'agence de l'objet » n'est pas la même selon l'opération, et c'est le cas d'usage
qui le dit dans `targetOf` :

| Opération | Agence évaluée | Opération déplacée |
|---|---|---|
| `CASH_OPERATION` | celle de la **caisse** (le guichetier tient sa caisse) | le client peut être d'une autre agence : autorisé, avec un **plafond déplacé** plus bas et un contrôle d'identité obligatoire |
| `ACCOUNT_OPEN`, `ACCOUNT_CLOSE`, `ACCOUNT_BLOCK`, `ACCOUNT_PRODUCT_ASSIGN` | celle du **compte** (agence gestionnaire) | interdit : ces actes reviennent à l'agence qui répond du compte |
| `TRANSFER` | celle du compte **émetteur** | le bénéficiaire peut être partout dans l'entité |
| `LOAN_*` | celle du compte de règlement du contrat | interdit |
| `ACCOUNT_BALANCE_READ`, `PARTY_READ` | celle du compte ou du tiers | lecture déplacée autorisée et **tracée** — c'est le geste du guichetier qui sert un client de passage, et celui de la consultation abusive |

Les profils de siège (`branchId` nul dans le jeton) relèvent des règles `OWN_ENTITY`. La Row
Level Security reste **par entité** : une politique par agence en base casserait tout traitement
de siège et tout arrêté, et le périmètre agence est une règle d'habilitation, pas une frontière
de données.

### 2.9 Ce que cela change à la signature REST

La méthode proposée pour validation ne change pas :

```java
Receipt withdraw(Caller caller, UUID legalEntityId, UUID accountId, IdempotencyKey key,
                 WithdrawalRequest body)
```

Ce qui change est *derrière* : `WithdrawalRequest.cashAccountId` désigne une caisse dont l'agence
doit être celle de l'appelant ; `targetOf` construit `AccessTarget.inBranch(entité, agence de
la caisse).withAmount(montant)` et signale l'opération déplacée quand l'agence du compte n'est
pas celle de la caisse ; `branchOf(accountId)` est `account.branch_id`. L'ouverture de compte
prend l'agence de l'appelant, jamais du corps de la requête.

---

## 3. Impacts sur l'existant

| Où | Quoi |
|---|---|
| `ledger-store` V24 | `branch`, `account.branch_id` + contrainte, `journal_line.branch_id` et `kind`, `journal_entry.originating_branch_id`, `legal_entity.interbranch_scheme`, déclencheur d'équilibre par agence, `account_balance_daily(branch_id)` ; migration : un siège par entité, rattachement de l'existant |
| `ledger-domain` | `Account.branchId`, `PostingLine.branchId` (facultatif), `EntryValidator` : résolution de l'agence, équilibre par agence, refus d'une agence contraire à celle du compte |
| `ledger-store` | `JdbcPostingService` : génération des lignes de liaison selon le schéma ; `Balances` : lecture par agence sur cliché |
| `interest-service`, `fee-service`, `loan-service` | Lignes de compte général agrégées **par agence** dans les écritures de lot ; agence fournie sur les lignes de produit et de charge |
| `deposits` | `AccountLifecycle.open` : agence de l'appelant ; `OperationsService` : agence de la caisse, frais d'opération déplacée à l'agence qui sert, indicateur d'opération déplacée dans le reçu |
| `tfj` | `BALANCE_SNAPSHOT` par (compte, agence) ; contrôle `INTER_AGENCES` dans `RECONCILIATION` ; rejeu par agence au TFM |
| `security-core` | `AccessTarget` : agence de l'objet et indicateur déplacé ; `SecurityConfig` : plafonds déplacés ; `Scope.OWN_BRANCH` effectif |
| `benchmark` | Mesurer le coût des lignes de liaison et de l'agrégation par agence : l'objectif de 0,881 ms par compte au TFJ ([13](13-mesures.md)) doit tenir |
| Documentation | [00](00-principes.md) (invariant 13), [02](02-ledger.md), [03](03-referentiel-parametrage.md), [07](07-securite-conformite.md), [08](08-modele-donnees.md) |

Ordre : ce chantier **précède** la couche API. Il change le modèle du compte et le service
d'imputation ; exposer une API sur un modèle sans agence reviendrait à publier des contrats qu'il
faudrait casser trois semaines plus tard. Estimation : deux à trois semaines, tests compris — le
gros du travail est dans les services de lot (agrégation par agence) et dans les tests, pas dans
le moteur.

---

## 4. Décisions à valider avec la banque

| # | Décision | Proposition |
|---|---|---|
| 1 | Schéma de liaison | **Via le siège**. Bilatéral seulement pour un réseau de deux ou trois agences ; via la région si la banque consolide par direction régionale |
| 2 | À qui revient un produit ou une charge | Au client — donc à son agence — pour tout ce qui découle du compte (intérêts, commissions périodiques, agios, provisions) ; à l'agence qui sert pour les frais d'une opération déplacée |
| 3 | Opérations déplacées | Autorisées pour les opérations de caisse et les virements, avec plafond déplacé par rôle ; interdites pour l'ouverture, la clôture, le blocage, le crédit |
| 4 | Balance agence | Arrêtée chaque nuit (cliché), pas tenue en temps réel |
| 5 | Caisses par guichetier et arrêté de caisse | Étape suivante, après ce chantier |
| 6 | Compensation interbancaire (SICA, STAR) | Hors de ce chantier — module de paiements |
