# Chaîne d'intégration

> Ce fichier est dans `workflows/` et non dans `.github/` : GitHub préfère un
> `.github/README.md` à celui de la racine pour la page d'accueil du dépôt, et
> une note sur l'intégration continue y prendrait la place du README du projet.

Trois barrières, une par nature de travail. Chacune ne fait que **rendre obligatoire
une commande qui existait déjà** : rien n'a été inventé pour la chaîne, et tout se
rejoue à la main à l'identique.

| Workflow | Se déclenche sur | Ce qu'il refuse |
|---|---|---|
| [`socle.yml`](socle.yml) | `core-banking/**` hors `web/` | Une compilation cassée, un test rouge |
| [`front.yml`](front.yml) | `core-banking/web/**` | Un test rouge, un dépassement de budget de taille, un débordement horizontal ou une cible sous 24 px |
| [`documentation.yml`](documentation.yml) | `docs/**`, `core-banking/**/*.md` | Un lien interne ou une ancre qui ne résout pas |

Mesuré sur une machine de développement, dépôt Maven et `node_modules` déjà peuplés :
**213 s** pour les 699 tests du socle, **~40 s** pour le front (tests, compilation,
neuf écrans à sept largeurs), **< 1 s** pour les liens. Un coureur d'intégration part
d'un cache froid et télécharge d'abord ; les délais d'expiration sont posés en
conséquence, avec de la marge.

## Ce qu'elles ne font pas

**Elles ne déploient rien.** Il n'y a pas d'environnement à déployer ; le jour où il y
en aura un, ce sera un quatrième workflow, déclenché par une étiquette et non par un
`push`.

**Elles ne mesurent pas.** Les classes `*Benchmark.java` ne sont pas prises par le
motif de surefire (`*Test.java`, `*IT.java`). C'est délibéré : un build qui échoue
sur une mesure de débit — sensible à la machine, au voisinage, au hasard — est un
build qu'on finit par relancer sans lire, puis par désactiver. Les mesures se
lancent à la demande et se lisent dans [13-mesures.md](../../docs/core-banking/13-mesures.md).

**Elles ne remplacent pas la relecture.** Les captures d'écran du guide utilisateur,
la cohérence d'un libellé, le sens d'un refus : rien de cela ne se vérifie
mécaniquement.

## Pourquoi PostgreSQL n'est pas un service

Les tests d'intégration démarrent PostgreSQL en embarqué (zonky) : ni conteneur de
service, ni installation. Chaque base de test est montée par `SchemaMigrator`, le
runner de production — **le chemin de déploiement est donc exercé à chaque build**,
pas seulement le jour du déploiement.

## Rejouer une barrière à la main

```bash
# Le socle
cd core-banking && mvn -B test

# Le front
cd core-banking/web && npm ci && npm test && npm run build
npm run servir &                                  # repli SPA pour les routes profondes
npm run check:largeurs -- http://127.0.0.1:8181/

# La documentation
python3 docs/outils/liens.py docs
python3 docs/outils/liens.py core-banking
```
