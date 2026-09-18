# 17 — Prise en main pour les développeurs

Le point d'entrée du dépôt. Il répond à « comment je démarre » et « où je pose mon
code », pas à « pourquoi c'est construit comme ça » — pour cela il y a
[01-architecture](01-architecture.md) et les documents de décision.

---

## 1. Démarrer

### Ce qu'il faut

| | Version | Pourquoi |
|---|---|---|
| Java | **21** | Records, `switch` à motifs, `sealed` — utilisés partout |
| Maven | 3.9+ | Le socle |
| Node | **≥ 22.22.3** | Exigé par Angular 22 (borne dans les `engines` du paquet) |
| PostgreSQL | *rien à installer* | Démarré en embarqué par les tests |
| Docker | *rien à installer* | Le socle n'en a pas besoin |

### Le socle

```bash
cd core-banking
mvn test          # compile, vérifie le style, joue 699 tests — environ 5 min
```

Le premier lancement télécharge les binaires PostgreSQL embarqués ; les suivants
sont plus rapides. **Chaque base de test est montée par `SchemaMigrator`, le runner
de production** : si une migration est cassée, elle l'est dès le premier `mvn test`,
pas le jour du déploiement.

### Le back-office

```bash
cd core-banking/web
npm ci
npm start         # http://localhost:4200, l'atelier s'ouvre par défaut
```

Il démarre **sans le socle** : `config.json` porte `sourceDonnees: "factice"` et un
jeu de démonstration local répond à la place de l'API. Un bandeau permanent le dit.

---

## 2. La carte du dépôt

```
core-banking/
├── platform-kernel      Money, dates, erreurs, idempotence — aucune dépendance
├── security-core        Opérations, rôles, politique d'habilitation
├── security-keycloak    Adaptateur vers l'API d'administration Keycloak
├── security-store       Journal d'habilitation
├── ledger-domain    ★   Écritures, comptes, invariants, contre-passation — Java pur
├── ledger-store         Persistance du registre, migrations, soldes, restitutions
├── schema-engine        Schémas comptables : un événement métier → des écritures
├── calendar             Jours ouvrés, périodes, exercices
├── interest-domain      Bases de calcul, conventions de jours
├── fee-domain           Barèmes, assiettes
├── loan-domain          Échéanciers, amortissement, classification
├── product-catalog      Product factory : types, paramètres historisés
├── party                Tiers, KYC, documents, bénéficiaires effectifs
├── interest-service     ┐
├── fee-service          │ Les services : ils orchestrent domaine + persistance
├── loan-service         │
├── deposits             │ Comptes, opérations, chèques, prélèvements, DAT
├── compliance           │ LCB-FT : filtrage, surveillance, alertes
├── regulatory           ┘ États réglementaires, fiscalité, liasse
├── tfj              ★   Traitement de fin de journée : 27 étapes, reprise, annulation
├── api                  REST, Spring Boot, OpenAPI, double validation
├── benchmark            Mesures de débit — hors de la suite de tests
└── web                  Le back-office agence (Angular)
```

**Les dépendances vont toujours vers le bas.** `ledger-domain` ne dépend d'aucun
module métier. Les deux ★ sont ceux qu'il faut comprendre avant de toucher au reste :
tout passe par eux.

---

## 3. Les huit règles à connaître avant d'écrire une ligne

Elles ne sont pas négociables, et la plupart sont tenues par une barrière.

**1. Aucun montant en virgule flottante.** `BigDecimal` ou entier de la plus petite
unité. `0,1 + 0,2 ≠ 0,3` en binaire, et un centime perdu par écriture devient un écart
de balance introuvable. *Tenu par Checkstyle* (`MatchXpath`, `IllegalType`).

**2. Une écriture ne se modifie pas.** On ne corrige pas, on **contre-passe** : une
écriture inverse, les deux visibles. Le registre est un journal, pas un état.

**3. Toute écriture s'équilibre, par devise.** Contrôlé par la base, pas seulement par
le code.

**4. Toute opération qui écrit porte une clé d'idempotence.** Un rejeu rend le même
résultat, il n'en produit pas un second.

**5. Le paramétrage est daté.** Un barème, un taux, un schéma comptable ont une période
de validité ; on lit celui qui valait **à la date de l'opération**, jamais le courant.

**6. Ce qui engage se fait à deux.** Activation d'un produit, rééchelonnement, clôture
d'exercice, déclaration : soumission par un, approbation par un autre. Personne
n'approuve sa propre demande.

**7. Une anomalie se nomme.** Pas de `catch` muet, pas de valeur par défaut qui masque.
Le TFJ distingue une étape *bloquante* d'une *anomalie constatée*, et l'écart entre les
deux est une décision métier.

**8. Le front ne calcule rien de ce que le socle sait.** Ni frais, ni taxe, ni date de
valeur, ni solde. Un barème recopié dans le navigateur finit par diverger.

---

## 4. Ajouter du code

### Un module Maven

1. Créer le dossier, un `pom.xml` avec le parent, et le déclarer dans
   `<modules>` du `pom.xml` racine.
2. Les dépendances vont vers le bas : un module de service peut dépendre d'un domaine,
   jamais l'inverse.
3. `mvn test` doit passer avant le premier commit — Checkstyle compris.

### Une migration de schéma

**Ce n'est pas Liquibase.** Le runner est `SchemaMigrator` (`ledger-store`) et les
migrations sont du **SQL pur**, un fichier par version :

```
<module>/src/main/resources/db/V<n>__<sujet>.sql
```

- `<n>` est un entier **globalement unique dans tout le dépôt**, pas par module : les
  versions sont appliquées dans l'ordre numérique, tous modules confondus.
- Le contenu est figé par une **somme de contrôle** dès qu'il est appliqué. Modifier un
  script déjà passé fait échouer la montée de version au lieu de diverger en silence.
- Tout s'applique dans **une transaction sous verrou** : deux instances qui démarrent
  ensemble ne se marchent pas dessus.
- Une version absente du classpath est nommée dans l'erreur, pas ignorée.

Il n'y a **pas** de mécanisme de retour en arrière : une migration se répare par une
migration suivante. C'est le même choix qu'en comptabilité — on ne réécrit pas, on
ajoute.

### Un cas d'usage exposé par l'API

1. Le service métier dans son module, testé sans l'API.
2. Le cas d'usage dans `api/usecase`, le contrôleur dans `api/web`.
3. L'opération au catalogue de `security-core` — un test tient l'inventaire des points
   d'entrée et échoue si une route n'est rattachée à aucune opération.
4. Si l'opération engage, la déclarer à double validation.
5. Régénérer le contrat, puis les types du front :
   ```bash
   cd core-banking/web && npm run api:generate
   ```

### Un écran du back-office

Voir le [README du front](../../core-banking/web/README.md) et
[16-back-office](16-back-office.md). Trois choses qui surprennent :

- **Le jeu de primitives est fermé** (17 composants). En ajouter une passe par
  l'atelier et son registre, tenu par un test à double sens.
- **Les adresses viennent du contrat.** `chemin()` n'accepte qu'une clé de
  `schema.ts` ; un chemin inventé ne compile pas.
- **Aucune couleur en dur.** Tout passe par les tokens CSS.

---

## 5. Tests

| Suffixe | Ce que c'est | Base ? |
|---|---|---|
| `*Test.java` | Domaine pur, rapide | non |
| `*Properties.java` | Propriétés (jqwik), ~4 000 cas générés | non |
| `*IT.java` | Intégration sur PostgreSQL réel | oui, embarqué |
| `*Benchmark.java` | Mesures — **hors de la suite** | oui |

Le motif de surefire ne prend que les trois premiers. Les mesures se lancent à la
demande : un build qui échoue sur un débit est un build qu'on finit par désactiver.

**Un invariant se vérifie rouge avant d'être vert.** Un test écrit après le code et
vert du premier coup ne prouve pas grand-chose ; casser volontairement l'invariant
pour voir le test tomber, si. Plusieurs commits de ce dépôt le disent explicitement.

---

## 6. Avant de pousser

```bash
cd core-banking     && mvn -B test
cd core-banking/web && npm ci && npm test && npm run build
npm run servir &     && npm run check:largeurs -- http://127.0.0.1:8181/
python3 docs/outils/liens.py docs && python3 docs/outils/liens.py core-banking
```

C'est exactement ce que joue la [chaîne d'intégration](../../ci/README.md) — ni plus,
ni moins. Une barrière qui ne se rejoue pas à la main est une barrière qu'on subit.

---

## 7. Où lire la suite

| Question | Document |
|---|---|
| Pourquoi ces choix ? | [00-principes](00-principes.md), [01-architecture](01-architecture.md) |
| Comment marche le registre ? | [02-ledger](02-ledger.md) |
| Comment marche l'arrêté ? | [05-batch-arrete](05-batch-arrete.md) |
| Quelles règles UEMOA / BCEAO ? | [11-profil-uemoa-bceao](11-profil-uemoa-bceao.md) |
| Qu'est-ce qui reste à faire ? | [14-audit](14-audit.md) §7 |
| Et l'interface ? | [16-back-office](16-back-office.md) |
| Ce que voit un guichetier | [guide de l'utilisateur](utilisateur/README.md) |

Le [README du socle](../../core-banking/README.md) est la référence détaillée : ce que
chaque garantie promet et quel test la prouve. Il se lit par recherche, pas de bout en
bout.
