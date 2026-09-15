# Socle Core Banking — Dossier d'architecture

Conception d'une plateforme de gestion bancaire générique, multi-pays et multi-entités,
positionnée sur le même segment fonctionnel qu'Amplitude (SAB), Sopra Banking Platform,
Temenos T24 ou Oracle FLEXCUBE.

**Stack cible** : Java 21 / Spring Boot 3.x / PostgreSQL 16 / Kafka / Kubernetes.
**Profil réglementaire par défaut** : UEMOA / BCEAO — les huit États de l'Union sur une
seule instance, les autres zones s'ajoutant comme profils supplémentaires.

---

## Avertissement de cadrage

Ce dossier décrit un socle **crédible, extensible et industrialisable**, pas une parité
fonctionnelle avec les éditeurs cités. Ces produits représentent 15 à 25 ans de
développement, des centaines d'années-homme et des homologations bancaires pays par pays.

Ce que ce dossier couvre réellement :

- un **cœur comptable** de qualité production (partie double, immuable, multi-devises) ;
- un **paramétrage produit** permettant de couvrir dépôts, épargne et crédits sans recoder ;
- un **moteur de TFJ** (Traitement de Fin de Journée / Mois / Année) idempotent et
  rejouable, avec mode « à blanc » ;
- les **invariants non négociables** qui distinguent un vrai core banking d'une application
  de gestion classique ;
- un **profil réglementaire UEMOA / BCEAO** prêt à caler, séparant ce qui est régional de ce
  qui est national ;
- une **roadmap de construction** phasée et chiffrable.

Ce qu'il ne couvre pas : la monétique certifiée PCI-DSS, le trade finance avancé et la
salle des marchés — décrits au niveau des interfaces, pas de l'implémentation.

Sur le [profil UEMOA / BCEAO](11-profil-uemoa-bceao.md), la **structure** est complète et
les **valeurs chiffrées réglementaires sont explicitement marquées ⚠** : elles doivent être
calées sur les textes en vigueur à la date du projet et validées par le contrôle interne.
Aucune n'est à reprendre telle quelle.

---

## Implémentation

Le noyau comptable, les intérêts, les commissions, les crédits, le référentiel client, les
comptes de dépôt et leurs opérations, le multi-agences, le paramétrage produit, les
habilitations, le calendrier, le TFJ et l'API REST (Spring Boot 4.1) sont implémentés et
testés : [`../../core-banking`](../../core-banking) — Java 21, PostgreSQL, **570 tests verts**. Le dossier ci-dessous reste la référence de conception ;
le code en est la mise en œuvre, et les écarts constatés à l'implémentation y ont été répercutés.

## Sommaire

| # | Document | Objet |
|---|---|---|
| 00 | [Principes directeurs](00-principes.md) | Les 12 invariants non négociables |
| 01 | [Architecture générale](01-architecture.md) | Style, découpage, contextes, déploiement |
| 02 | [Moteur comptable (le cœur)](02-ledger.md) | Partie double, journal immuable, soldes, devises |
| 03 | [Référentiel & paramétrage](03-referentiel-parametrage.md) | Entités, plan comptable, product factory, multi-pays |
| 04 | [Modules métier](04-modules-metier.md) | Clients/KYC, dépôts, crédits, paiements, GL |
| 05 | [Moteur de TFJ (TFJ/TFM/TFA)](05-batch-arrete.md) | Orchestration, idempotence, reprise, TFJ à blanc |
| 06 | [API & intégrations](06-api-integrations.md) | REST, ISO 20022, événements, canaux |
| 07 | [Sécurité & conformité](07-securite-conformite.md) | RBAC, maker-checker, audit, chiffrement |
| 08 | [Modèle de données](08-modele-donnees.md) | DDL des tables structurantes |
| 09 | [Qualité, tests & exploitation](09-qualite-exploitation.md) | Invariants testés, réconciliation, observabilité |
| 10 | [Roadmap de construction](10-roadmap.md) | Phases, lots, dépendances, risques |
| 11 | [Profil UEMOA / BCEAO](11-profil-uemoa-bceao.md) | Configuration de référence : PCB, XOF, classification, TEG, STAR/SICA/GIM, déclaratifs |
| 12 | [Inventaire du paramétrage](12-parametrage-inventaire.md) | Ce qui varie, où c'est stocké, ce qui reste à faire |
| 13 | [Mesures](13-mesures.md) | Débit, latence, durée de TFJ — mesurés, et ce qu'ils ne prouvent pas |
| 14 | [Audit](14-audit.md) | Couverture fonctionnelle et robustesse : ce qui tient, ce qu'il faut corriger, compléter — priorisé |
| 15 | [Multi-agences](15-multi-agences.md) | Comptabilité par agence, lignes de liaison générées, compensation inter-agences, périmètre de sécurité — étude préalable à l'API |

---

## Le point critique

90 % des projets de core banking échouent sur deux composants :

1. **le moteur comptable** — s'il n'est pas immuable, équilibré et réconciliable dès le
   premier jour, aucune correction ultérieure n'est possible ;
2. **le TFJ (traitement de fin de journée)** — s'il n'est pas idempotent et rejouable, le premier
   incident de production en exploitation réelle devient une crise comptable.

C'est par là que commence la construction. L'IHM vient en dernier.
