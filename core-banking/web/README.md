# Back-office agence — socle visuel

Poste de travail du guichetier, du chargé de clientèle et du chef d'agence, et
écrans de siège qui partagent la même application. Les décisions qui gouvernent
ce code — architecture, sécurité, configurabilité, thèse de design, garde-fous —
sont dans [`docs/core-banking/16-back-office.md`](../../docs/core-banking/16-back-office.md).
Ce README n'explique que la mécanique.

## Ce que contient ce dépôt

Le **socle visuel** — tokens, typographie, densités, thèmes clair et sombre, jeu
fermé de primitives, page atelier — le **guichet** (versement d'espèces) et la
**file de validation**. Pas encore d'authentification : elle vient avec la file de
validation, dans l'ordre fixé au §7 du document de décisions.

Tant qu'aucun socle n'est branché (`sourceDonnees: "factice"` dans
`public/config.json`), les données viennent d'une source de démonstration locale
et un bandeau permanent le dit. `"api"` bascule sur le socle réel, sans
recompilation.

## Prérequis

Node **≥ 22.22.3** (ou 24.15+), imposé par Angular 22. `npm ci` suffit ensuite.

## Commandes

| Commande | Effet |
|---|---|
| `npm start` | Serveur de développement sur `http://localhost:4200`, l'atelier s'ouvre par défaut |
| `npm run build` | Build de production, budgets de taille appliqués |
| `npm test` | Tests unitaires, **garde-fous de l'atelier compris** |
| `npm run api:generate` | Régénère `src/app/api/schema.ts` depuis `openapi.json` |
| `npm run format` | Prettier |
| `npm run check:largeurs -- <url>` | Ouvre chaque écran à sept largeurs : échoue sur un débordement horizontal ou une cible sous 24 px. Demande un navigateur (`npx playwright install chromium`), donc hors `npm test` |

## Organisation

```
src/styles/     tokens, base, contrôles natifs, polices — l'unique vocabulaire visuel
src/app/core/   configuration de déploiement, apparence (thème, densité), formats
src/app/ui/     le jeu fermé de primitives + son registre
src/app/atelier/ la page atelier, vivante, servie par l'application elle-même
src/app/guichet/ le guichet : modèle, port, implémentations, écrans
src/app/validation/ la double validation : le second regard
src/app/api/    types générés depuis le contrat OpenAPI — ne jamais éditer à la main
scripts/        contrôles qui demandent un navigateur
```

### Le guichet : un port, deux implémentations

`guichet.port.ts` décrit ce dont un écran a besoin. `guichet.api.ts` l'implémente
sur les routes réelles du socle (`Idempotency-Key`, `X-Request-Id`, enveloppe
`{ data, error, meta }`). `guichet.factice.ts` rejoue le comportement du socle
localement pour que l'écran soit jugeable sans serveur ; son catalogue de comptes
existe pour montrer chaque issue — nominal, plafond dépassé, second regard requis,
compte bloqué, réseau tombé.

**Le port n'expose aucun calcul de frais, de taxe ou de date de valeur.** Ce sont
des paramètres du socle ; les recopier ici garantirait la divergence.

## Les garde-fous, et ce qu'ils empêchent

- **`src/app/ui/registry.ts`** liste les primitives. `registry.spec.ts` vérifie
  les deux sens : toute primitive déclarée a sa section dans l'atelier, et tout
  fichier de `ui/` est déclaré. Ajouter un composant sans le montrer fait
  échouer les tests.
- **Budgets de taille** dans `angular.json` : une régression de poids fait
  échouer la compilation, elle ne se découvre pas en production. Repère actuel :
  366 Ko bruts, 97 Ko transférés pour l'application initiale.
- **Tokens CSS** : rien d'autre que `src/styles/tokens.css` ne définit une
  couleur, une taille ou un espacement. Les composants lisent des variables.
- **Types générés** : `src/app/api/schema.ts` vient du contrat OpenAPI vérifié
  côté socle. Le front ne peut pas diverger de l'API en silence.

## Les trois axes de réglage, qui ne se mélangent pas

| Axe | Où | Quand |
|---|---|---|
| **Thème** clair / sombre | `[data-theme]` sur `<html>` | choix de l'utilisateur, persisté |
| **Densité** confortable / compacte | `[data-density]` sur `<html>` | choix de l'utilisateur, persisté |
| **Accent, libellés, écrans** | `public/config.json` | déploiement, sans recompilation |

Le thème sombre est **dessiné**, pas inversé : la base reste tiède, les traits
restent lisibles, les états gardent la même hiérarchie. Les arrêtés se font le soir.

`config.json` est lu avant le premier rendu : l'accent de la banque ne doit pas
apparaître après coup. Une configuration absente ou illisible ne bloque pas le
démarrage — le back-office d'une agence ne tombe pas parce qu'un fichier de
déploiement manque.

## Ajouter une primitive

1. Le fichier dans `src/app/ui/`, nommé comme son identifiant (`state-badge.ts`).
2. L'entrée dans `registry.ts` et l'export dans `index.ts`.
3. La section de démonstration dans `atelier.html`, avec tous ses états.

Sauter l'étape 3 fait échouer `npm test`. C'est voulu.

## Ce qui n'est pas là, et ne le sera pas par accident

Pas de librairie de composants, pas de calendrier, pas d'écriture hors ligne, pas
de moteur de workflow paramétrable. Le `@angular/cdk` apporte ce qu'on rate en
écrivant soi-même — piège de focus, overlay, clavier, défilement virtuel — et
aucun CSS.
