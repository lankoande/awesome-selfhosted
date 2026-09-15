# 03 — Référentiel & paramétrage

C'est le composant qui rend le socle **générique multi-pays**. Sans lui, chaque nouveau pays
et chaque nouveau produit deviennent une livraison logicielle.

---

## 1. Hiérarchie organisationnelle

```
Group (groupe bancaire, consolidation)
  └── LegalEntity (banque / filiale — une par pays d'implantation)
        ├── devise de tenue de compte     (functional_currency)
        ├── plan comptable                (chart_of_accounts_id)
        ├── calendrier                    (business_calendar_id)
        ├── exercice fiscal               (début, durée)
        ├── profil réglementaire          (regulatory_profile_id)
        ├── fuseau horaire                (heure de cut-off de l'arrêté)
        └── Branch (agence / point de vente)
              └── Till (caisse, guichet)
```

> **Implémenté** — `branch` (siège, région, agence ; un siège par entité, créé avec elle) et
> `branch_liaison` (compte de liaison par agence et par devise, celui de la devise de tenue de
> compte obligatoire). Les comptes clients et internes portent leur agence gestionnaire ; les
> comptes généraux n'en ont pas, leur solde se tient par agence sur chaque ligne. La caisse par
> guichetier est faite (V35, `till`, `till_closure`) : un compte interne d'agence affecté à un
> guichetier, résolu depuis son jeton — il ne la choisit pas — et arrêté chaque jour de service
> ([05](05-batch-arrete.md), [15](15-multi-agences.md)).

`legal_entity_id` est porté par **toute** donnée métier. Le cloisonnement est appliqué à
deux niveaux : filtre applicatif **et** Row Level Security PostgreSQL. Le second est ce qui
protège contre une requête oubliée dans un rapport.

```sql
-- NULL sans réglage : aucune ligne n'est visible tant que l'entité n'est pas posée.
CREATE FUNCTION ledger_current_entity() RETURNS UUID AS $$
    SELECT NULLIF(current_setting('app.entity_id', true), '')::uuid;
$$ LANGUAGE sql STABLE;

CREATE POLICY entity_isolation ON journal_entry
    USING (legal_entity_id = ledger_current_entity())
    WITH CHECK (legal_entity_id = ledger_current_entity());
```

> **Implémenté** — V26 à V34, sur toutes les tables à entité et leurs tables filles ; l'entité est
> posée par transaction par l'API et par le TFJ, le rôle applicatif ne possède aucune table
> ([07](07-securite-conformite.md)).

---

## 2. Plan comptable paramétrable

### Le problème multi-pays

Une banque au sein de l'UEMOA applique le plan comptable bancaire régional. Une filiale
européenne applique un référentiel IFRS. Une entité anglo-saxonne a sa propre codification.
Les codes, les profondeurs de hiérarchie et les règles de classement diffèrent.

### Solution : deux niveaux découplés

```
Compte interne (stable, technique)
        │
        │  gl_mapping — historisé par période de validité
        ▼
Compte réglementaire (propre au référentiel du pays)
```

- Le **compte interne** est l'identifiant stable auquel s'impute le ledger. Il ne change
  jamais, même si le régulateur renumérote son plan.
- Le **compte réglementaire** est la projection vers le référentiel local, utilisée pour la
  balance officielle et les déclaratifs.
- Un compte interne peut se projeter différemment selon le référentiel (local, IFRS,
  consolidation groupe) : la table de mapping porte un `framework`.

Un changement de plan comptable réglementaire devient alors un **import de mapping**, sans
reprise de données ni migration d'écritures.

### Structure d'un compte

| Attribut | Rôle |
|---|---|
| `code` | Identifiant interne, stable |
| `account_kind` | `CUSTOMER`, `GL`, `INTERNAL`, `NOSTRO`, `SUSPENSE`, `POSITION` |
| `normal_balance` | `DEBIT` / `CREDIT` — sens naturel |
| `currency` | Devise, figée à la création |
| `postable` | Un compte de regroupement n'est pas imputable |
| `control_available` | Le disponible est-il contrôlé à la comptabilisation |
| `hot` / `stripe_count` | Striping de solde (cf. [02](02-ledger.md#7-concurrence-et-comptes-chauds)) |
| `parent_id` | Hiérarchie de restitution |

---

## 3. Product factory

### Principe

Un produit bancaire est une **instance paramétrée d'un type de produit**. Le type est du
code (il définit le comportement) ; le produit est de la donnée (il définit les valeurs).

```
ProductType (code)          Product (données)              Contract (instance)
───────────────────         ──────────────────             ──────────────────
CURRENT_ACCOUNT       ──►   « Compte Courant Pro »   ──►   Compte n° 00123456
SAVINGS_ACCOUNT       ──►   « Épargne Plus 3,5 % »   ──►   Compte n° 00987654
TERM_DEPOSIT          ──►   « DAT 12 mois »          ──►   Dépôt n° DAT-2026-441
AMORTIZING_LOAN       ──►   « Crédit Habitat 15 ans »──►   Dossier n° CR-2026-88
REVOLVING_CREDIT      ──►   « Découvert autorisé »   ──►   Autorisation n° AU-771
```

Ajouter « Épargne Plus 4 % » ne demande **aucune livraison** : c'est une ligne de
paramétrage. Ajouter un type de crédit à annuités progressives demande du code : c'est une
nouvelle méthode d'amortissement.

Cette frontière est assumée. Un moteur de règles totalement libre finit par devenir un
langage de programmation sans tests, sans revue et sans débogueur — c'est le principal
facteur d'ingouvernabilité des core banking anciens.

### Paramètres d'un produit

```yaml
product:
  code: SAVINGS_PLUS
  type: SAVINGS_ACCOUNT
  legal_entity: BANK_CI
  currency: XOF
  valid_from: 2026-01-01
  valid_to: null

  interest:
    rate_source: FIXED            # FIXED | INDEXED | TIERED
    rate: 3.5
    day_count: ACT/365
    basis: MINIMUM_MONTHLY_BALANCE   # DAILY_BALANCE | MIN_MONTHLY | AVG_DAILY
    accrual_frequency: DAILY
    capitalization: QUARTERLY
    rounding: HALF_EVEN

  limits:
    min_opening_balance: 10000
    max_balance: null
    min_balance_for_interest: 10000
    monthly_free_withdrawals: 2

  fees:
    account_maintenance: { amount: 1000, frequency: MONTHLY }
    excess_withdrawal:   { amount: 500,  trigger: WITHDRAWAL_ABOVE_FREE_LIMIT }
    early_closure:       { amount: 5000, condition: CLOSED_WITHIN_6_MONTHS }

  taxation:
    interest_withholding: { rate_ref: WHT_SAVINGS, applies_to: GROSS_INTEREST }

  accounting_schema: SAVINGS_PLUS_SCHEMA
  overdraft_allowed: false
  dormancy_after_months: 24
```

Chaque paramètre est **historisé par période de validité**. Un arrêté rejoué sur une date
passée lit les paramètres en vigueur à cette date. Sans cela, un rejeu produit des montants
différents de l'original — et l'arrêté devient invérifiable.

### Le type de produit est un contrat, pas une étiquette

> **Implémenté.** Le type porte une **famille de produit** qui déclare ce que le produit doit
> porter : paramètres exigés, paramètres admis, exigences conditionnelles, blocs répétés. Elle est
> déclarée dans `resources/product/families.json`, chargée et validée au démarrage, et appliquée à
> l'activation d'une version.
>
> C'est ce qui manquait pour que la frontière code / paramétrage décrite plus haut tienne
> réellement. Sans elle, le type n'était qu'une chaîne libre : le comportement était bien du code,
> mais rien ne vérifiait que le paramétrage fournissait au code ce qu'il allait lire. Un produit de
> crédit sans compte de créances rattachées s'activait sans rien dire, et le manque se découvrait au
> premier traitement de fin de journée qui en avait besoin.
>
> Corollaire : **un paramètre que la famille ne déclare pas est refusé**. Une clé jamais lue est
> indiscernable d'une clé mal nommée, et les deux donnent à leur auteur la certitude d'avoir
> paramétré quelque chose. C'est la règle 9 de la gouvernance du paramétrage, désormais tenue par un
> test plutôt que par la discipline.
>
> Les familles déclarées aujourd'hui sont `CURRENT_ACCOUNT`, `SAVINGS_ACCOUNT` et `TERM_LOAN`.
> `TERM_DEPOSIT` et `REVOLVING_CREDIT` figurent dans le schéma ci-dessus mais n'ont pas de code qui
> les traite : les déclarer sans cela reviendrait à promettre un contrat que personne n'honore.

---

## 4. Schémas comptables

C'est le mécanisme qui traduit un **événement métier** en **jeu d'écritures**. Il est le
pivot entre le métier et le ledger.

```yaml
schema: SAVINGS_PLUS_SCHEMA

events:

  DEPOSIT:
    lines:
      - account: "${contract.account}"     direction: CREDIT  amount: "${event.amount}"
      - account: "${resolve.cash_or_counterparty}" direction: DEBIT amount: "${event.amount}"

  INTEREST_ACCRUAL:
    lines:
      - account: "GL.60110"                direction: DEBIT   amount: "${event.gross}"
        label: "Charges d'intérêts sur dépôts"
      - account: "GL.27110"                direction: CREDIT  amount: "${event.gross}"
        label: "Intérêts courus non échus"

  INTEREST_CAPITALIZATION:
    lines:
      - account: "GL.27110"                direction: DEBIT   amount: "${event.gross}"
      - account: "${contract.account}"     direction: CREDIT  amount: "${event.net}"
      - account: "GL.44210"                direction: CREDIT  amount: "${event.withholding}"
        label: "Retenue à la source"
        condition: "${event.withholding > 0}"

  MAINTENANCE_FEE:
    lines:
      - account: "${contract.account}"     direction: DEBIT   amount: "${event.total}"
      - account: "GL.70611"                direction: CREDIT  amount: "${event.net}"
      - account: "GL.44320"                direction: CREDIT  amount: "${event.vat}"
        condition: "${event.vat > 0}"
```

Propriétés :

- Les comptes généraux sont **résolus par le plan comptable de l'entité** : le même schéma
  fonctionne dans un pays UEMOA et dans une filiale européenne, avec des codes différents.
- Les lignes conditionnelles gèrent les cas où une taxe ne s'applique pas.
- L'équilibre est **validé au chargement du schéma**, pas à l'exécution : un schéma
  déséquilibré est rejeté au déploiement du paramétrage.
- Le schéma est versionné : une écriture référence la version de schéma utilisée, ce qui
  rend l'imputation explicable a posteriori.

### Résolution des comptes

| Notation | Résolution |
|---|---|
| `GL.70611` | Compte général par code interne, dans le plan de l'entité |
| `${contract.account}` | Compte du contrat concerné |
| `${resolve.X}` | Résolveur nommé, code Java (caisse de l'agence, nostro, suspens) |
| `${product.param.Y}` | Compte défini en paramètre de produit |

---

## 5. Devises, cours et arrondis

```
Currency (ISO 4217)
  ├── code, scale, name
  ├── is_functional_for[]     entités dont c'est la devise de tenue de compte
  └── rounding_mode

ExchangeRate
  ├── from, to, rate_type (SPOT | OFFICIAL | BUY | SELL | AVERAGE)
  ├── rate, valid_from (timestamp)
  └── source (banque centrale, fournisseur, saisie manuelle)
```

- Les cours sont **historisés à l'horodatage**, pas à la date : un cours intra-journalier
  est nécessaire pour les opérations de change.
- La revalorisation des positions utilise le cours officiel de clôture ; les opérations
  clients utilisent les cours acheteur/vendeur avec leur marge.
- Une conversion **archive le cours appliqué dans la ligne d'écriture** : la conversion
  reste explicable même si le cours est corrigé ensuite.

---

## 6. Calendriers et périodes comptables

### Calendrier

Par entité : jours ouvrés, jours fériés (fixes et mobiles), heure de cut-off.
Sert au calcul des dates de valeur, des échéances et des délais réglementaires.

Une échéance tombant un jour non ouvré est décalée selon une convention paramétrée :
`FOLLOWING`, `MODIFIED_FOLLOWING`, `PRECEDING`. La convention est un attribut de produit,
pas une constante de code.

> **Implémenté** ([`core-banking/calendar`](../../core-banking/calendar)). Le calendrier porte sa
> **période de saisie** et refuse de répondre au-delà : présumer qu'un jour non saisi est ouvré
> reviendrait à traiter le 1ᵉʳ janvier comme un jour ordinaire dès que la saisie des fériés prend du
> retard, et l'erreur ne se verrait qu'à la réclamation. Les dates de valeur sont calculées par le
> moteur à partir des conditions en vigueur à la date comptable traitée ; l'absence de règle est un
> refus, jamais un repli sur la date comptable — ce repli serait la forme la plus discrète de
> l'erreur, puisqu'il produit un résultat plausible.

### Périodes comptables

```
FiscalYear (exercice)
  └── AccountingPeriod (mois comptable)
        └── statut : OPEN → CLOSING → CLOSED → REOPENED(exceptionnel)
```

- Aucune écriture n'est acceptée dans une période `CLOSED`.
- La réouverture d'une période est une opération exceptionnelle, soumise à double
  validation, tracée, et notifiée. Elle déclenche un recalcul des états déjà produits.
- La clôture annuelle produit les écritures de détermination du résultat et de report à
  nouveau, selon le schéma de l'entité.

> **Implémenté** — `fiscal_year` (V36) : bornes, statut (`OPEN`, `CLOSED`, `REOPENED`), compte de
> résultat de l'exercice — un compte général de bilan de l'entité dans sa devise de tenue de
> compte —, ouvert à deux (`FISCAL_YEAR_MANAGE`), sans chevauchement possible entre exercices. La
> clôture annuelle (`YEAR_CLOSE`) clôt le dernier mois et l'exercice ; son annulation
> (`YEAR_REOPEN`) les rouvre en le disant ([05](05-batch-arrete.md)).

---

## 7. Profil réglementaire par pays

Le point qui rend le socle réellement portable : les règles propres à un pays sont
**données**, pas code.

```yaml
regulatory_profile:
  code: UEMOA_2026
  countries: [CI, SN, BF, ML, TG, BJ, NE, GW]

  accounting_framework: PCB_UEMOA
  reporting_currency: XOF

  loan_classification:
    method: DAYS_PAST_DUE
    buckets:
      - { code: SAIN,          from_days: 0,   to_days: 30,  provision_rate: 0 }
      - { code: SURVEILLANCE,  from_days: 31,  to_days: 90,  provision_rate: 0 }
      - { code: DOUTEUX,       from_days: 91,  to_days: 180, provision_rate: 20 }
      - { code: COMPROMIS,     from_days: 181, to_days: 360, provision_rate: 50 }
      - { code: PERTE,         from_days: 361, to_days: null,provision_rate: 100 }
    collateral_deduction: true
    contagion: PER_CUSTOMER      # déclassement de tous les encours du client

  interest_suspension:
    trigger_bucket: DOUTEUX      # arrêt de la comptabilisation en produits
    account: GL.2971             # intérêts réservés

  usury_rate:
    enabled: true
    reference: BCEAO_TEG_CAP

  reports:
    - { code: BALANCE_MENSUELLE, frequency: MONTHLY,   deadline_days: 15 }
    - { code: ETAT_CREANCES,     frequency: QUARTERLY, deadline_days: 30 }
    - { code: RATIOS_PRUDENTIELS,frequency: QUARTERLY, deadline_days: 45 }
```

Le profil UEMOA / BCEAO livré en configuration de référence est détaillé en
[11](11-profil-uemoa-bceao.md). Il introduit un raffinement du modèle : le profil
réglementaire est **régional** (PCB, provisionnement, ratios, systèmes de paiement) tandis
que la **fiscalité et le droit local sont nationaux**, portés par une `CountryOverlay`. Sans
cette séparation, l'ouverture du deuxième pays de l'Union impose de dupliquer tout le
profil.

Un autre profil (`IFRS9_EU`, `BASEL_III_STANDARD`) décrit une méthode de classification
différente — par exemple par étages de dépréciation (`stage 1/2/3`) avec pertes attendues.

**Frontière assumée** : la *méthode* de classification (`DAYS_PAST_DUE`, `IFRS9_ECL`) est du
code, car elle implique des algorithmes différents. Les *seuils, taux, buckets et
règles de contagion* sont du paramétrage. Cette séparation permet de couvrir un nouveau
pays partageant une méthode existante sans aucune livraison.

---

## 8. Gouvernance du paramétrage

Le paramétrage a la même criticité que le code. Il suit donc le même niveau d'exigence :

| Exigence | Mise en œuvre |
|---|---|
| Versionnage | Paramétrage stocké en base, exporté en YAML versionné en dépôt Git |
| Double validation | Tout changement passe par maker-checker |
| Environnements | Promotion contrôlée dev → recette → production, jamais de saisie directe en production |
| Simulation | Un changement de barème est simulé sur le portefeuille réel avant activation |
| Effet daté | Activation par `valid_from` future, jamais par bascule immédiate |
| Traçabilité | Qui, quand, quoi, valeur avant/après, motif |

Une modification de paramétrage produit directement des montants sur des comptes clients.
La traiter avec moins de rigueur qu'une livraison de code est la cause la plus fréquente
d'incident majeur en production bancaire.
