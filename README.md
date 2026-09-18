# Socle core banking

Un système bancaire central générique, construit pour le profil **UEMOA / BCEAO** :
registre comptable immuable, dépôts, crédits, paiements, conformité, reporting
réglementaire, et un back-office d'agence.

Java 21 · PostgreSQL 16 · Spring Boot 4.1 (exposition seule) · Angular 22

---

## Où ça en est

| | |
|---|---|
| **Socle** | Fonctionnellement complet — **697 tests verts**, dont l'API de bout en bout sur PostgreSQL réel |
| **Back-office** | Guichet, validation, caisse, siège, clients, crédit, conformité et réglementaire — **29 écrans, 325 tests**, conformes à sept largeurs, habilitations lues à la granularité de l'action |
| **Documentation** | 17 documents de conception + un guide utilisateur illustré de 23 captures |
| **Ce qui manque** | Les bords nommés dans [l'audit §7](docs/core-banking/14-audit.md), l'infrastructure, la reprise de données |

Ce n'est **pas** un système en production. L'infrastructure (haute disponibilité,
sauvegardes, PRA, supervision) et la reprise de données restent entièrement à
instruire.

---

## Démarrer

```bash
# Le socle — compile, vérifie le style, joue 696 tests. Environ 5 min.
cd core-banking && mvn test

# Le back-office — démarre sans le socle, sur un jeu de démonstration.
cd core-banking/web && npm ci && npm start
```

PostgreSQL est démarré **en embarqué** par les tests : ni Docker, ni installation.

La suite — carte des modules, les huit règles à connaître avant d'écrire une ligne,
comment ajouter une migration — est dans
[**17 — Prise en main**](docs/core-banking/17-prise-en-main.md).

---

## Ce qui structure le projet

**Le registre est un journal, pas un état.** Une écriture ne se modifie pas : on
contre-passe. Toute écriture s'équilibre par devise, et la base le contrôle.

**Aucun montant en virgule flottante.** `BigDecimal` ou entier de la plus petite unité.
Un centime perdu par écriture devient un écart de balance introuvable — la règle est
tenue par Checkstyle, pas par la discipline.

**Le paramétrage est daté.** On lit le barème qui valait *à la date de l'opération*,
jamais le courant.

**Ce qui engage se fait à deux.** Et personne n'approuve sa propre demande.

**Le front ne calcule rien que le socle sait.** Ni frais, ni taxe, ni date de valeur,
ni solde : un barème recopié dans le navigateur finit par diverger.

---

## Carte du dépôt

```
core-banking/        Le socle : 23 modules Maven
  schema-db/           Les montées de version : 57 scripts SQL, un changelog Liquibase
  ledger-domain/     ★ Écritures, comptes, invariants — Java pur, sans dépendance
  tfj/               ★ Traitement de fin de journée : 27 étapes, reprise, annulation
  api/                 REST, OpenAPI, double validation
  web/                 Le back-office agence (Angular) : guichet, clients, crédit, siège
docs/core-banking/   Les décisions, et pourquoi elles ont été prises
  utilisateur/         Le guide du guichetier, du valideur, de l'exploitant
ci/                  La chaîne d'intégration, et ce qu'elle refuse
```

---

## Documentation

| Pour | Lire |
|---|---|
| Démarrer et contribuer | [17 — Prise en main](docs/core-banking/17-prise-en-main.md) |
| Comprendre l'architecture | [01 — Architecture](docs/core-banking/01-architecture.md) |
| Comprendre le registre | [02 — Ledger](docs/core-banking/02-ledger.md) |
| Les règles UEMOA / BCEAO | [11 — Profil UEMOA](docs/core-banking/11-profil-uemoa-bceao.md) |
| Savoir ce qui reste à faire | [14 — Audit](docs/core-banking/14-audit.md) |
| Se servir de l'application | [Guide de l'utilisateur](docs/core-banking/utilisateur/README.md) |

L'[index complet](docs/core-banking/README.md) liste les 17 documents.

---

## Licence

**Aucune licence n'est déclarée.** Le dépôt héritait de la licence CC-BY-SA du projet
`awesome-selfhosted` dont il est issu ; elle couvrait la liste de logiciels, pas ce
code, et elle a été retirée avec elle.

En l'absence de licence, le droit d'auteur s'applique par défaut : tous droits
réservés. C'est une décision à prendre, pas un oubli.
