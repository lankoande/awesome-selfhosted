# Chaîne d'intégration

Le pipeline est décrit par [`.gitlab-ci.yml`](../.gitlab-ci.yml), à la racine.

Trois barrières, une par nature de travail. Chacune ne fait que **rendre obligatoire
une commande qui existait déjà** : rien n'a été inventé pour la chaîne, et tout se
rejoue à la main à l'identique. C'est la seule façon qu'une barrière soit tenue
plutôt que contournée.

| Tâche | Image | Ce qu'elle refuse |
|---|---|---|
| `socle` | `maven:3.9-eclipse-temurin-21` | Une compilation cassée, un test rouge |
| `front` | `node:24` | Un test rouge, un dépassement de budget de taille, un débordement horizontal ou une cible sous 24 px |
| `documentation` | `python:3.12-slim` | Un lien interne ou une ancre qui ne résout pas |

Mesuré sur une machine de développement, dépôt Maven et `node_modules` déjà peuplés :
**213 s** pour les 699 tests du socle, **~90 s** pour le front (127 tests, compilation,
neuf écrans à sept largeurs), **< 1 s** pour les liens. Un coureur part d'un cache
froid et télécharge d'abord ; les délais d'expiration sont posés avec de la marge.

## Trois décisions à connaître

### Aucun filtrage par chemin

Les trois tâches tournent à chaque pipeline, même quand la poussée ne touche qu'un
seul domaine. C'est délibéré : `rules:changes` de GitLab **n'admet pas la négation**,
donc « tout `core-banking/` sauf `web/` » s'écrirait en énumérant les modules — et le
jour où un module est ajouté sans être ajouté à cette liste, la barrière du socle est
silencieusement sautée, dans un pipeline qui s'affiche vert.

Quelques minutes de machine contre un trou qui ne se voit pas : le choix n'est pas
serré. Le jour où la suite dépassera le quart d'heure, on découpera par étape — pas
par filtre.

### PostgreSQL n'est pas un service

Les tests d'intégration démarrent PostgreSQL en embarqué (zonky) : ni service
GitLab, ni conteneur annexe, ni installation. Chaque base de test est montée par
`SchemaMigrator`, le runner de production — **le chemin de déploiement est donc
exercé à chaque pipeline**, pas seulement le jour du déploiement.

Le coureur tourne en `root` ; zonky le détecte et lance le serveur sous un compte
non privilégié, PostgreSQL refusant de tourner en `root`. Vérifié : les 699 tests
passent sous `root`.

### Les mesures ne sont pas dans la chaîne

Les classes `*Benchmark.java` ne sont pas prises par le motif de surefire
(`*Test.java`, `*IT.java`). Un pipeline qui échoue sur une mesure de débit —
sensible à la machine, au voisinage, au hasard — est un pipeline qu'on finit par
relancer sans lire, puis par désactiver. Les mesures se lancent à la demande et se
lisent dans [13-mesures.md](../docs/core-banking/13-mesures.md).

## Ce que la chaîne ne fait pas

**Elle ne déploie rien.** Il n'y a pas encore d'environnement à déployer ; le jour où
il y en aura un, ce sera une étape supplémentaire déclenchée par une étiquette, jamais
par une poussée sur une branche.

**Elle ne remplace pas la relecture.** Les captures du guide utilisateur, la cohérence
d'un libellé, le sens d'un refus : rien de cela ne se vérifie mécaniquement.

**Elle ne tient pas encore** la couverture (JaCoCo), les frontières de modules
(ArchUnit), l'analyse statique, la veille de dépendances ni la non-régression de
performance. Chacune demande d'installer un outil **et** de choisir un seuil : deux
décisions, pas une. Le détail est en
[09 §2](../docs/core-banking/09-qualite-exploitation.md#2-qualité-de-code).

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

## Si le dépôt revient un jour sur GitHub

Les trois mêmes barrières ont existé en workflows GitHub Actions, retirées quand
l'automatisation est passée sur GitLab : deux définitions des mêmes barrières
divergent, et celle qui ne tourne pas ment sur l'état du dépôt. Elles restent dans
l'historique (`git log -- .github/workflows`) et se retrouvent par un `git revert`.
