# 01 — Architecture générale

## 1. Style retenu : monolithe modulaire, puis extraction ciblée

**Décision** : démarrer en **monolithe modulaire** (Spring Modulith), un seul déployable,
une seule base transactionnelle pour le ledger, des modules à frontières strictes.
Extraire ensuite en services uniquement les composants qui le justifient par la charge ou
le cycle de vie.

### Justification

Le cœur bancaire exige une **transactionnalité forte**. Une opération de virement doit
débiter, créditer, prélever la commission et comptabiliser la TVA dans une transaction
atomique. En microservices, cela impose des sagas et de la compensation : on remplace une
garantie ACID gratuite par une machine à états distribuée, coûteuse et source de soldes
faux. Aucun éditeur de core banking n'a pris ce risque sur le ledger.

Les frontières de modules sont néanmoins traitées comme des frontières de services :
chaque module expose une API interne (interface Java publique) et un schéma PostgreSQL
dédié. Aucun module ne lit les tables d'un autre. L'extraction ultérieure devient
mécanique.

### Ce qui est extrait en premier, le moment venu

| Composant | Raison d'extraction |
|---|---|
| Canaux (mobile, web, API partenaires) | Charge très variable, cycle de release rapide |
| Moteur de notification | Latence non critique, forte volumétrie |
| Reporting / datamart | Isolation des lectures lourdes |
| Scoring & décision crédit | Cycle de vie modèle, dépendances data science |
| Filtrage AML / sanctions | Éditeur tiers fréquent, contraintes de latence propres |

Le **ledger, la product factory et le moteur d'arrêté ne sont jamais découpés**.

---

## 2. Découpage en contextes

```
┌─────────────────────────────────────────────────────────────────────┐
│  CANAUX          Agence · Web · Mobile · API partenaires · GAB      │
└───────────────────────────────┬─────────────────────────────────────┘
                                │  API Gateway (authn, quotas, idempotence)
┌───────────────────────────────▼─────────────────────────────────────┐
│  ORCHESTRATION MÉTIER                                               │
│  Cas d'usage · Maker-checker · Limites · Contrôles de conformité    │
└───────────────────────────────┬─────────────────────────────────────┘
                                │
┌──────────────┬──────────────┬─┴────────────┬──────────────┬─────────┐
│  CLIENTS     │  DÉPÔTS      │  CRÉDITS     │  PAIEMENTS   │ TRÉSO.  │
│  KYC/AML     │  Épargne     │ Échéanciers  │ Virements    │ Change  │
│  Relations   │  Découverts  │ Provisions   │ Prélèvements │ Place-  │
│  Mandats     │  Blocages    │ Garanties    │ Mobile money │ ments   │
└──────┬───────┴──────┬───────┴──────┬───────┴──────┬───────┴────┬────┘
       │              │              │              │            │
       └──────────────┴──────┬───────┴──────────────┴────────────┘
                             │   événements métier
┌────────────────────────────▼────────────────────────────────────────┐
│  PRODUCT FACTORY            Schémas comptables · Barèmes · Règles    │
└────────────────────────────┬────────────────────────────────────────┘
                             │   commandes de comptabilisation
┌────────────────────────────▼────────────────────────────────────────┐
│  ★ LEDGER — cœur comptable                                          │
│  Journal immuable · Partie double · Soldes · Multi-devises          │
└────────────────────────────┬────────────────────────────────────────┘
                             │
┌──────────────┬─────────────┴─────────┬───────────────┬──────────────┐
│  COMPTA GÉN. │  MOTEUR DE TFJ        │  RÉGLEMENTAIRE│  RÉFÉRENTIEL │
│  Balance     │  TFJ · TFM · TFA      │  Reporting    │  Entités     │
│  Grand livre │  Intérêts · Provisions│  Déclaratifs  │  Devises     │
│  États fin.  │  Échéances · Change   │  Fiscalité    │  Calendriers │
└──────────────┴───────────────────────┴───────────────┴──────────────┘
```

Le flux est **descendant et unidirectionnel** : un module métier ne comptabilise jamais en
écrivant directement une écriture. Il publie un **événement métier** (`DepositMade`,
`LoanDisbursed`, `FeeCharged`) ; la product factory le traduit en jeu d'écritures via le
schéma comptable du produit ; le ledger l'enregistre.

Bénéfice : la logique comptable est centralisée et paramétrée. Ajouter un produit ne touche
ni au ledger, ni à la comptabilité générale.

---

## 3. Modules Maven

```
core-banking/
├── platform/
│   ├── platform-kernel            types monétaires, dates, erreurs, idempotence
│   ├── platform-security          authn/authz, RBAC/ABAC, maker-checker, audit
│   ├── platform-workflow          circuits de validation, états
│   └── platform-batch             socle d'orchestration, reprise, partitionnement
├── reference/
│   ├── reference-entity           entités juridiques, agences, calendriers, exercices
│   ├── reference-currency         devises, cours, règles d'arrondi
│   └── reference-coa              plans comptables, mappings réglementaires
├── ledger/
│   ├── ledger-domain              ★ écritures, comptes, soldes, invariants
│   ├── ledger-posting             API de comptabilisation, idempotence, contre-passation
│   └── ledger-balance             projections, snapshots, rejeu
├── product/
│   ├── product-catalog            product factory, paramètres, périodes de validité
│   ├── product-accounting         schémas comptables (événement → écritures)
│   └── product-pricing            barèmes, commissions, taux, fiscalité
├── party/
│   ├── party-core                 personnes physiques/morales, groupes, relations
│   ├── party-kyc                  dossiers KYC, documents, revue périodique
│   └── party-screening            listes de sanctions, PPE, interface éditeur
├── deposits/                      comptes, opérations, blocages, découverts, épargne
├── lending/                       dossiers, échéanciers, garanties, classification
├── payments/                      ordres, virements, prélèvements, mobile money
├── treasury/                      change, placements, positions
├── accounting/                    balance, grand livre, états financiers
├── eod/                           ★ moteur d'arrêté
├── regulatory/                    reporting réglementaire, déclaratifs
└── api/
    ├── api-rest                   contrôleurs, OpenAPI, versionnage
    ├── api-iso20022               pain/pacs/camt
    └── api-events                 publication Kafka, webhooks
```

Les dépendances vont **toujours vers le bas**. `ledger-domain` ne dépend d'aucun module
métier. Une règle ArchUnit l'impose au build : un cycle casse la CI.

---

## 4. Choix techniques structurants

| Sujet | Choix | Justification |
|---|---|---|
| Langage | Java 21 (LTS) | Standard de fait en core banking, maturité transactionnelle, recrutement |
| Framework | **Spring Boot 4.1** (Spring Framework 7, Spring Security 7, Jackson 3, Tomcat 11 embarqué) — couche d'exposition seule ; le socle reste sans framework | Un seul exécutable à déployer ; les frontières de modules sont des modules Maven, vérifiées au build |
| Base | PostgreSQL 16, Patroni HA | ACID strict, `NUMERIC` exact, partitionnement natif, RLS |
| Migrations | **Liquibase**, changelogs en SQL | [La décision, et pourquoi elle a mis si longtemps à être prise](#migrations--la-décision-enfin-prise) |
| Batch | Spring Batch | Reprise, partitionnement, traçabilité native des runs |
| Messagerie | Kafka (+ outbox transactionnel) | Publication atomique avec la transaction métier |
| Cache | Caffeine local ; Redis pour les sessions | Le ledger n'est **jamais** mis en cache |
| Identité | Keycloak (OIDC) | RBAC/MFA standard, fédération avec l'annuaire bancaire |
| Secrets | HashiCorp Vault | Rotation, cloisonnement, pas de secret en configuration |
| Déploiement | Kubernetes, blue/green | Reprise, montée de version sans interruption de service |
| Observabilité | OpenTelemetry, Prometheus, Loki | Traces corrélées de bout en bout |

### Points d'attention explicites

**Pas de JPA sur le chemin de comptabilisation.** Le `EntityManager` (cache de premier
niveau, flush implicite, lazy loading) est une source d'imprévisibilité sur le chemin
critique. Le module `ledger-posting` utilise **JDBC explicite** (`JdbcTemplate` ou jOOQ),
avec des insertions en lot et des requêtes écrites à la main. JPA reste acceptable sur les
modules de gestion (référentiel, dossiers, KYC).

**Pattern outbox obligatoire.** Publier un événement Kafka dans la même transaction que
l'écriture comptable est impossible (deux ressources). L'événement est inséré dans une
table `outbox` de la même base, dans la même transaction, puis relayé. Sans cela, on obtient
des écritures sans événement, ou des événements sans écriture.

**Pas de cache sur les soldes.** Un solde en cache est un solde faux dès la première
écriture concurrente. La performance se traite par snapshots quotidiens et
partitionnement, pas par cache applicatif.

> **Implémenté** — module `api` : Spring Boot 4.1.1, Tomcat embarqué, serveur de ressources
> OAuth2 (jetons signés par le royaume Keycloak, clés publiques JWKS), migrations de schéma au
> démarrage (`SchemaMigrator`, classpath complet exigé), provisionnement des rôles de `roles.json`
> dans Keycloak au démarrage quand l'API d'administration est configurée, `/actuator/health` et
> métriques. Le socle — ledger, intérêts, crédits, dépôts, TFJ — ne dépend pas de Spring : il est
> assemblé dans une configuration, et exposé par des cas d'usage. Le jar exécutable se déploie
> derrière un reverse proxy qui termine le TLS ([06](06-api-integrations.md)).
>
> **Deux comptes de base, jamais un seul.** Le propriétaire du schéma
> (`COREBANKING_SCHEMA_USER` / `_PASSWORD`) exécute les migrations au démarrage puis se retire ;
> l'application se connecte avec le rôle applicatif `corebanking_app`
> (`COREBANKING_DB_USER` / `_PASSWORD`, créé par `ops/roles.sql`), qui ne possède rien — c'est ce
> qui rend effectif le cloisonnement par entité que la base applique elle-même (Row Level
> Security, [07](07-securite-conformite.md)). Sans compte propriétaire distinct, l'application
> migre elle-même et possède tout : le démarrage l'écrit en avertissement, c'est une installation
> de développement.


### Migrations : la décision enfin prise

Ce tableau annonçait Liquibase depuis le premier jour. Le code, lui, utilisait un runner
écrit ici. **Personne n'avait arbitré** — et c'est une dérive, pas un choix :

| Commit | Ce qui s'est passé |
|---|---|
| `b1ce156` | Le dossier d'architecture retient Liquibase, sur le papier |
| `1d75173` | Le premier code du registre écrit `SchemaMigrator` avec ce commentaire : *« En production, Liquibase pilote les montées de version ; ici le script est appliqué tel quel, ce qui suffit au P0 et garde les tests sans dépendance supplémentaire. »* — une béquille de test, assumée comme telle |
| `a2d9c08` | La béquille est durcie en vrai runner (table de versions, sommes de contrôle, ordre global, verrou), et **le commentaire qui annonçait Liquibase disparaît** sans que la décision soit prise ni écrite |
| depuis | 56 migrations écrites dessus |

L'outil temporaire était devenu définitif parce qu'il marchait.

**C'est Liquibase**, et le critère qui tranche n'est pas technique : c'est l'outil que
l'équipe maîtrise. Un outil que les opérateurs connaissent bat un outil techniquement
équivalent qu'ils découvrent à deux heures du matin pendant une bascule.

#### Ce que le changement apporte réellement

Rien du côté de l'écriture : **les changelogs restent du SQL**, en format Liquibase
`formatted sql` — deux lignes d'en-tête par fichier, et le SQL en dessous. L'argument
« un format qu'un arrivant sait déjà lire » ne valait rien, puisque le SQL ne change pas.

Le gain est **opérationnel**, et c'est le seul qui compte :

| Commande | Ce qu'elle débloque |
|---|---|
| `liquibase:status` | Ce qui reste à appliquer. Répond à « prod et pré-prod portent-elles le même schéma ? » |
| `liquibase:updateSQL` | Le SQL qui *s'appliquerait*, sans l'appliquer. Un DBA peut relire 4 500 lignes avant une fenêtre de production, au lieu d'appliquer à l'aveugle |
| `liquibase:changelogSync` | Marquer une base existante à jour sans rejouer son histoire. **Sans cela, la reprise de la base de l'ancien core banking (P5) est impossible** |
| `liquibase:history`, `liquibase:tag` | Ce qui a été appliqué et quand ; nommer un état pour une livraison |

`update` n'est délibérément pas exposé : la montée de version se fait au démarrage de
l'application, par le même chemin que les tests. Deux chemins de déploiement finiraient
par diverger.

#### Ce qui a été conservé

**Chaque base de test est montée par le chemin de production.** C'était la propriété la
plus précieuse du runner maison, et elle survit : `SchemaMigrator` est devenu un
adaptateur de cinquante lignes devant Liquibase, et les 696 tests l'exercent à chaque
build. On a cessé de tester son propre moteur de migration pour en utiliser un éprouvé.

#### Ce que le changement a coûté

Les 56 scripts, jusque-là répartis dans douze modules, vivent maintenant dans un module
`schema-db` unique. Ce n'est pas une contrainte de Liquibase, c'est la vérité sur le
schéma : **la numérotation a toujours été globale** — V9 (crédits) s'appuie sur V8
(commissions), qui s'appuie sur V1 (registre) — et le découpage par module était une
apparence. Chaque base de test applique désormais les 56 scripts au lieu d'un
sous-ensemble : **la suite passe de 280 à 324 secondes**. C'est le prix, et il est dit.

Aucun retour arrière n'est déclaré : une migration se répare par une migration, comme une
écriture se contre-passe. Liquibase sait faire du `rollback` ; il faudrait l'écrire par
changement, et il reste inapplicable à une migration de données.

---

## 5. Déploiement de référence

```
            ┌──────── Load balancer / WAF ────────┐
            │                                      │
      ┌─────▼──────┐                        ┌──────▼─────┐
      │ API Gateway│                        │ Console    │
      │ (Kong/APIM)│                        │ back-office│
      └─────┬──────┘                        └──────┬─────┘
            │                                      │
    ┌───────▼──────────────────────────────────────▼────────┐
    │  Kubernetes — namespace « core »                      │
    │   core-app     (N réplicas, stateless, OLTP)          │
    │   core-batch   (1 réplica actif, leader election)     │
    │   core-api     (N réplicas, canaux externes)          │
    └───────┬───────────────────────────────┬───────────────┘
            │                               │
     ┌──────▼───────┐              ┌────────▼────────┐
     │ PostgreSQL   │  streaming   │ Kafka (3 brokers)│
     │ primaire     ├─────────────►│ outbox, events   │
     │ + 2 réplicas │              └─────────────────┘
     └──────┬───────┘
            │  réplica de lecture dédié
     ┌──────▼───────┐        ┌──────────────┐
     │ Reporting /  │        │ Archivage    │
     │ datamart     │        │ (S3/objet)   │
     └──────────────┘        └──────────────┘
```

**`core-batch` est mono-instance active** (élection de leader). Deux instances d'arrêté
simultanées produiraient un double arrêté. Le verrou est posé en base
(`SELECT ... FOR UPDATE` sur une ligne de contrôle), pas uniquement dans l'orchestrateur.

**Le reporting lit sur un réplica dédié.** Une requête d'état réglementaire sur
plusieurs années ne doit jamais ralentir le chemin de comptabilisation.

---

## 6. Dimensionnement de référence

Cible pour une banque de détail de taille moyenne :

| Indicateur | Cible |
|---|---|
| Comptes actifs | 2 000 000 |
| Écritures / jour | 5 000 000 lignes |
| Débit en pointe | 1 500 opérations/s |
| Latence de comptabilisation (p99) | < 150 ms |
| Durée de l'arrêté quotidien | < 90 min |
| Durée de l'arrêté annuel | < 6 h |
| RPO / RTO | 0 / 15 min |
| Rétention en ligne | 10 ans (partitionné, archivé au-delà de 2 ans) |

Ces chiffres pilotent les choix de partitionnement et de parallélisation décrits en
[05](05-batch-arrete.md) et [08](08-modele-donnees.md).

> **Mesuré.** Le débit de comptabilisation et la durée de TFJ ont été confrontés à ces cibles :
> voir [13 — Mesures](13-mesures.md). Les deux sont tenues sur le banc, après une correction de
> facteur 34 sur le calcul des intérêts. Les valeurs restent à re-mesurer sur l'infrastructure
> cible — le banc tourne sans écriture disque synchrone.
