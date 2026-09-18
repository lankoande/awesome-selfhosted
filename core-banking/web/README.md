# Back-office agence — socle visuel

Poste de travail du guichetier, du chargé de clientèle et du chef d'agence, et
écrans de siège qui partagent la même application. Les décisions qui gouvernent
ce code — architecture, sécurité, configurabilité, thèse de design, garde-fous —
sont dans [`docs/core-banking/16-back-office.md`](../../docs/core-banking/16-back-office.md).
Ce README n'explique que la mécanique.

## Ce que contient ce dépôt

Le **socle visuel** — tokens, typographie, densités, thèmes clair et sombre, jeu
fermé de primitives, page atelier — le **guichet** (versement, retrait, virement,
relevé, arrêté de caisse), le **siège** (fin de journée, balance générale) et la
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
| `npm run servir` | Sert `dist/web/browser` sur le port 8181 avec repli SPA — sans lui, les routes profondes répondent 404 et le contrôle de largeurs mesurerait des pages d'erreur |
| `npm run check:largeurs -- <url>` | Ouvre chaque écran à sept largeurs : échoue sur un débordement horizontal ou une cible sous 24 px. Demande un navigateur (`npx playwright install chromium`), donc hors `npm test` |

## Organisation

```
src/styles/     tokens, base, contrôles natifs, polices — l'unique vocabulaire visuel
src/app/core/   configuration de déploiement, apparence (thème, densité), formats
src/app/ui/     le jeu fermé de primitives + son registre
src/app/atelier/ la page atelier, vivante, servie par l'application elle-même
src/app/guichet/ le guichet : modèle, port, implémentations, écrans
src/app/clients/ le référentiel client : recherche, dossier, création, ouverture de compte
src/app/credit/  le crédit : demandes, dossier d'instruction, portefeuille, contrat
src/app/conformite/ la conformité LCB-FT : alertes, déclarations de soupçon, scénarios
src/app/reglementaire/ le réglementaire : échéances, états, catalogue, fiscalité
src/app/validation/ la double validation : le second regard
src/app/caisse/  la caisse du guichetier et son arrêté
src/app/siege/   exploitation comptable et restitutions
src/app/auth/    session OAuth2 PKCE, jeton porté, verrouillage, habilitations
                 (droits complets : portée, second regard, plafonds — voir plus bas)
src/app/api/    le contrat : types générés (`schema.ts`, ne jamais éditer), chemins
                vérifiés à la compilation (`routes.ts`), origine + chemin (`socle.ts`)
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

### Une convention d'apostrophe

L'apostrophe droite `'` dans le texte ; l'apostrophe typographique `’`
uniquement **dans les expressions Angular entre apostrophes droites**, où une
apostrophe droite fermerait la chaîne. Ce n'est pas une incohérence à corriger,
c'est la seule forme qui compile.

### Le référentiel client : ce qu'il annonce sans le tenir

`clients.port.ts` suit la même forme. Deux règles méritent d'être connues avant
d'y toucher :

- **la complétude du dossier commande l'ouverture d'un compte**, pas les comptes
  déjà ouverts. `obstaclesAOuverture()` affiche le refus à l'avance pour éviter
  une saisie perdue, mais c'est le socle qui tranche ;
- **une ouverture de compte part toujours à la validation d'un second.** Le
  contrat ne déclare que `202` et le contrôleur du socle répond `ACCEPTED` sans
  condition. `IssueOuverture` n'a donc qu'une forme : l'identifiant de
  l'opération en attente.

### Le crédit : ce que le socle décide

`credit.port.ts` expose le cycle complet : déposer, analyser, poser et lever
des conditions, décider, contractualiser, débloquer, régler. Trois règles :

- **la décision d'octroi et le déblocage passent par un second regard** (202).
  Celui qui instruit ne décide pas seul, et l'argent ne sort pas sans un
  deuxième porteur ;
- **une condition suspensive bloque la contractualisation, une résolutoire
  non.** `obstaclesALaContractualisation()` ne retient que les premières ;
- **l'imputation d'un règlement appartient au socle.** Le front ne calcule
  jamais à quoi va un paiement : il affiche ce que le socle a imputé.

**Ces deux routes n'honorent pas de clé d'idempotence** — ni le contrat ni la
signature des contrôleurs ne la déclarent. Les écrans ne proposent donc aucun
rejeu quand l'issue est incertaine (réseau coupé, 5xx) : ils renvoient vérifier.
La clé continue d'être envoyée, pour le jour où le socle la reconnaîtra.

### L'authentification : mêmes règles, deux fournisseurs

`auth.port.ts` décrit la session dont l'application a besoin. `auth.keycloak.ts`
l'obtient par OAuth2 **code d'autorisation + PKCE** (S256), sans secret client et
sans BFF ; `auth.factice.ts` ouvre une session locale pour que l'application
tourne sans Keycloak — sans simuler de mot de passe.

Ce qui est vrai des deux côtés :

- **Aucun jeton ne quitte la mémoire.** Ni `localStorage`, ni `sessionStorage`,
  ni cookie posé par le front. Un rechargement de page redemande une session ;
  le rafraîchissement silencieux (`prompt=none`) la rend indolore.
- **Le jeton ne part qu'aux appels du socle** (`jeton.interceptor.ts`, comparaison
  d'origine avec `apiBaseUrl`) — jamais à une autre origine.
- **Un 401 vaut un rafraîchissement, une seule fois**, puis l'erreur remonte.
- **Verrouiller n'est pas déconnecter** : l'application reste montée, la saisie
  intacte. Le délai vient de `config.json` (`auth.verrouillageMinutes`).
- **Les habilitations inconnues laissent tout voir** : tant que `/v1/me/permissions`
  n'a pas répondu, `connues` vaut faux, le menu montre tout et l'API refuse. Un poste
  qui cacherait par ignorance ferait croire à une fonction absente.

### Les adresses viennent du contrat, pas de la mémoire

`schema.ts` est généré depuis `openapi.json` et porte les 146 chemins du socle
comme clés de type. `chemin()` n'accepte que ces clés, et lit les noms de
variables dans le gabarit lui-même : **un chemin inexistant, une variable mal
nommée ou une variable oubliée sont trois erreurs de compilation**, vérifiées
par `routes.contrat.ts` que `ng build` compile.

Ce n'est pas théorique : la conversion des quatorze URLs écrites à la main a
trouvé trois fautes du premier coup — la file de validation nomme sa variable
`{id}`, pas `{operationId}`.

`Socle.url()` assemble l'origine (déploiement, `config.json`) et le chemin
(contrat). Les deux ne se mélangent pas : `apiBaseUrl` ne porte **pas** `/v1`,
parce que le préfixe de version appartient au contrat et suivrait un passage
en `/v2` sans qu'on ait à redéployer une configuration.

`Socle.sien()` tient la même règle pour l'intercepteur de jeton. Une seule
définition de « cette adresse est-elle celle du socle ? » : la dupliquer la
laisserait diverger, et un jeton porté à la mauvaise origine ne se rattrape pas.

## La barrière d'intégration

Les trois commandes ci-dessus sont obligatoires à chaque poussée :
la tâche `front` de [`.gitlab-ci.yml`](../../.gitlab-ci.yml) joue `npm test`,
`npm run build` (budgets de taille compris) et `npm run check:largeurs`.

Playwright est pour cette raison une **dépendance déclarée** et non un paquet arrivé par
transitivité : une barrière qui repose sur une dépendance qu'on n'a pas demandée tombe en
silence le jour d'une mise à jour sans rapport.

## Les garde-fous, et ce qu'ils empêchent

- **`src/app/ui/registry.ts`** liste les primitives. `registry.spec.ts` vérifie
  les deux sens : toute primitive déclarée a sa section dans l'atelier, et tout
  fichier de `ui/` est déclaré. Ajouter un composant sans le montrer fait
  échouer les tests.
- **Budgets de taille** dans `angular.json` : une régression de poids fait
  échouer la compilation, elle ne se découvre pas en production. Repère actuel :
  396 Ko bruts, 105 Ko transférés pour l'application initiale.

  Ce budget avait été franchi en silence — l'avertissement ne fait pas échouer
  le build. Cause : `app.config.ts` déclarait les fournisseurs des six espaces,
  donc les six sources de démonstration entraient dans le paquet initial, alors
  que la production ne les ouvre jamais. **Les fournisseurs d'un espace vivent
  désormais sur sa coque** (`*.shell.ts`), chargée paresseusement ; la file de
  validation, qui n'a pas de coque, les porte sur sa route (`validation.routes.ts`)
  et non sur son composant — des fournisseurs de composant l'emporteraient sur
  ceux du banc de test, et le double de test ne serait plus jamais vu.
- **Tokens CSS** : rien d'autre que `src/styles/tokens.css` ne définit une
  couleur, une taille ou un espacement. Les composants lisent des variables.
- **Types générés** : `src/app/api/schema.ts` vient du contrat OpenAPI vérifié
  côté socle. Le front ne peut pas diverger de l'API en silence.

## Les habilitations, à la granularité de l'action

`/v1/me/permissions` rend une ligne par opération, avec **portée**, **second regard**,
**plafonds** et **plafond en opération déplacée**. Le poste garde tout : le nom seul ne
permet que de montrer ou cacher un menu, alors qu'un écran porte presque toujours
plusieurs droits — un contrat de crédit en porte cinq, un état réglementaire trois.

Les écrans passent par le service `Droits` (`auth/habilitations.ts`) :

```ts
private readonly droits = inject(Droits);

protected readonly droitDeTransmettre = computed(
  () => this.droits.peut('REGULATORY_REPORT_TRANSMIT'));
protected readonly plafond = computed(
  () => this.droits.plafond('CASH_OPERATION', this.devise()));
```

Trois règles à tenir :

1. **Cacher ou expliquer** se décide par la raison d'être de l'écran. L'écran existe pour
   l'acte → `<cb-interdit>` prend la place du bouton et dit ce que le profil *fait*.
   L'acte n'est qu'une option → il disparaît.
2. **`aDeuxRegards` et `annonceDeuxRegards` n'ont pas le même défaut.** Le premier rend
   faux quand on ne sait pas (question stricte), le second vrai (on prévient plutôt que
   de laisser annoncer au client un compte qui n'existe pas encore).
3. **Un plafond se bloque, jamais à tort.** Celui qu'on lit est le plus favorable, en
   agence ; le socle, qui sait si l'opération est déplacée, tranche toujours.
4. **Une liste ne se filtre pas d'office sur les droits.** La file de validation lit le
   droit de *chaque ligne* (approuver, c'est exécuter) : elle le compte, l'offre en
   filtre, marque la ligne hors droits — mais ne la cache pas, sinon la banque se croit
   à jour. La portée sert de même à **expliquer un vide** : `PARTY_READ` en `OWN_BRANCH`
   fait dire à la recherche sans résultat que le client existe peut-être ailleurs, et
   que le recréer ferait un doublon.

Le profil de démonstration est dans `auth/habilitations.demonstration.ts` — une copie de
`SecurityConfig` pour un chef d'agence, **chargée à la demande** pour rester hors du
paquet initial. `config.json` → `demonstration.droitsRetires` retire des opérations pour
montrer une porte fermée.

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
