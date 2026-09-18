# Fabrication du guide au format Word

`guide-docx.mjs` assemble les onze chapitres Markdown du dossier parent en un seul
`.docx` : page de garde, sommaire (champ Word, mis à jour à l'ouverture), titres,
tableaux, listes, encadrés, et les captures numérotées avec leur légende.

```bash
npm install docx      # une fois
node guide-docx.mjs   # écrit ../guide-utilisateur-back-office.docx
```

**Le Markdown est la source, le `.docx` en est une sortie.** On corrige le guide dans
les `.md`, puis on régénère — jamais l'inverse : une correction faite dans le Word
serait perdue à la génération suivante, et les deux formats diraient deux choses
différentes au même lecteur.

Les figures aussi viennent des `.md` : toute ligne de la forme
`![` légende `](captures/` fichier `.png)` devient une figure numérotée. Il n'y a donc pas de liste de figures à tenir à jour
dans le script.

## Les captures

Elles sont prises sur l'application réelle en mode démonstration, à 1440 px de large,
puis réduites à 1920 px (densité 2 conservée, soit environ 300 dpi une fois posées
dans la page). Pour les refaire après une évolution d'écran : `npm run build` dans
`core-banking/web`, servir `dist/web/browser`, et rejouer les parcours avec Playwright.

Les noms, les comptes et les montants qui y figurent sont ceux du jeu de démonstration :
**aucune donnée réelle de client n'entre dans ce document.**
