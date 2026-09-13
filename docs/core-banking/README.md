# Socle Core Banking — Dossier d'architecture

Conception d'une plateforme de gestion bancaire générique, multi-pays et multi-entités,
positionnée sur le même segment fonctionnel qu'Amplitude (SAB), Sopra Banking Platform,
Temenos T24 ou Oracle FLEXCUBE.

**Stack cible** : Java 21 / Spring Boot 3.x / PostgreSQL 16 / Kafka / Kubernetes.

---

## Avertissement de cadrage

Ce dossier décrit un socle **crédible, extensible et industrialisable**, pas une parité
fonctionnelle avec les éditeurs cités. Ces produits représentent 15 à 25 ans de
développement, des centaines d'années-homme et des homologations bancaires pays par pays.

Ce que ce dossier couvre réellement :

- un **cœur comptable** de qualité production (partie double, immuable, multi-devises) ;
- un **paramétrage produit** permettant de couvrir dépôts, épargne et crédits sans recoder ;
- un **moteur d'arrêté** (EOD/EOM/EOY) idempotent et rejouable ;
- les **invariants non négociables** qui distinguent un vrai core banking d'une application
  de gestion classique ;
- une **roadmap de construction** phasée et chiffrable.

Ce qu'il ne couvre pas : les spécificités réglementaires détaillées d'un pays donné, la
monétique certifiée PCI-DSS, le trade finance avancé et la salle des marchés. Ces domaines
sont décrits au niveau des interfaces, pas de l'implémentation.

---

## Sommaire

| # | Document | Objet |
|---|---|---|
| 00 | [Principes directeurs](00-principes.md) | Les 12 invariants non négociables |
| 01 | [Architecture générale](01-architecture.md) | Style, découpage, contextes, déploiement |
| 02 | [Moteur comptable (le cœur)](02-ledger.md) | Partie double, journal immuable, soldes, devises |
| 03 | [Référentiel & paramétrage](03-referentiel-parametrage.md) | Entités, plan comptable, product factory, multi-pays |
| 04 | [Modules métier](04-modules-metier.md) | Clients/KYC, dépôts, crédits, paiements, GL |
| 05 | [Moteur d'arrêté EOD/EOM/EOY](05-batch-arrete.md) | Orchestration, idempotence, reprise, performance |
| 06 | [API & intégrations](06-api-integrations.md) | REST, ISO 20022, événements, canaux |
| 07 | [Sécurité & conformité](07-securite-conformite.md) | RBAC, maker-checker, audit, chiffrement |
| 08 | [Modèle de données](08-modele-donnees.md) | DDL des tables structurantes |
| 09 | [Qualité, tests & exploitation](09-qualite-exploitation.md) | Invariants testés, réconciliation, observabilité |
| 10 | [Roadmap de construction](10-roadmap.md) | Phases, lots, dépendances, risques |

---

## Le point critique

90 % des projets de core banking échouent sur deux composants :

1. **le moteur comptable** — s'il n'est pas immuable, équilibré et réconciliable dès le
   premier jour, aucune correction ultérieure n'est possible ;
2. **l'arrêté de fin de journée** — s'il n'est pas idempotent et rejouable, le premier
   incident de production en exploitation réelle devient une crise comptable.

C'est par là que commence la construction. L'IHM vient en dernier.
