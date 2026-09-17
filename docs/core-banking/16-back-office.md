# 16 — Back-office agence : décisions, garde-fous, thèse de design

Ce document fixe ce qui a été décidé avant la première ligne de code du front. Il joue pour
l'interface le rôle que les invariants jouent pour le socle : ce n'est pas une intention, c'est
une contrainte qu'on ne renégocie pas écran par écran.

**Périmètre** : le back-office **agence** — le poste de travail du guichetier, du chargé de
clientèle, du chef d'agence, et les écrans de siège (comptabilité, conformité, exploitation,
paramétrage) qui partagent la même application. Le portail client et le mobile ne sont pas ici.

---

## 1. Décisions d'architecture

| Décision | Raison |
|---|---|
| **Angular + TypeScript, SPA** | L'API est déjà un contrat OpenAPI publié et testé ; le front en dérive ses types au lieu de les retaper |
| **OAuth2 Authorization Code + PKCE** contre Keycloak, sans BFF pour l'instant | Le jeton reste porté par le navigateur ; un BFF s'ajoutera si une banque exige de ne jamais l'exposer — c'est un composant d'exploitation de plus, pas une évidence |
| **Une seule application, deux espaces** — guichet et siège | Deux métiers, deux ergonomies, mais une seule authentification, un seul client d'API, un seul jeu de types. Deux applications les dupliqueraient et les feraient diverger |
| **Nos propres composants, sur `@angular/cdk`** | Le CDK n'apporte aucun CSS : il apporte ce qu'on rate en écrivant soi-même — piège de focus, positionnement d'overlay, navigation clavier, défilement virtuel. Et il suit le train de release d'Angular, donc pas de décalage à la montée de version |
| **Zoneless + signals** | Zone.js relance la détection de changement à chaque événement ; une table dense devient molle. C'est le premier levier de performance, et il est structurant — donc posé au départ |
| **Pas d'écriture hors ligne** | Le registre est central et l'écriture est comptable. On traite la **résilience au réseau instable**, pas le mode déconnecté |

### Ce que « nos propres composants » veut dire

Ce qu'on écrit : tout le markup, tout le CSS, la densité, l'ergonomie clavier.

Ce qu'on ne réécrit pas, et qu'on prend au CDK : `a11y` (piège de focus, `ListKeyManager`,
annonceur), `overlay` (positionnement conscient du bord de l'écran), `scrolling` (défilement
virtuel du journal), `table` (la logique, sans un seul `<td>` imposé).

Ce qu'on ne fait pas du tout : un calendrier complet. Un guichetier tape `15/03/2026` plus vite
qu'il ne clique — champ masqué avec validation, petit calendrier en appoint sur l'overlay du CDK.
Écrire un sélecteur de date accessible et localisé coûte deux à trois semaines pour un gain nul
au guichet.

### Le coût des composants maison, et pourquoi il est borné

Le noyau — bouton, champ texte / nombre / montant / date masquée, combobox, table, dialogue,
tiroir, onglets, notice, badge d'état, pagination, barre d'outils — représente trois à quatre
semaines de travail focalisé. Une bonne part du reste est de toute façon du **domaine**, qu'aucune
librairie ne fournit : champ montant qui connaît l'échelle de la devise, sélecteur de compte qui
montre le disponible, bandeau client avec statut KYC et blocages, récapitulatif d'imputation, file
de validation.

**Un composant maison mal écrit est plus lent qu'un composant de librairie bien écrit.** « Fait
maison » n'est pas synonyme de rapide : ça l'est si, et seulement si, les leviers ci-dessous sont
tenus.

### Les leviers de performance, par ordre d'impact

1. **Zoneless + signals** — seul ce qui a changé se recalcule.
2. **Pagination serveur** — l'API pagine par curseur ; on ne charge jamais dix mille lignes.
3. **Défilement virtuel** sur le journal et le grand livre.
4. **Routes paresseuses par espace** — le guichet n'embarque pas les écrans de paramétrage.

Le poids du bundle vient après : environ 300 Ko gzip pour l'application entière, contre 700 Ko à
1 Mo avec une librairie de composants complète. Réel, pas décisif.

---

## 2. Sécurité

**Le front n'est pas une frontière de sécurité.** L'interface cache ce qui est interdit ; c'est
l'API qui l'empêche. Aucune règle métier de sécurité ne vit côté client.

| Règle | Raison |
|---|---|
| Le menu se construit à partir des opérations autorisées **rendues par l'API** | Réimplémenter `SecurityConfig` côté front garantit la divergence. Une source, deux lecteurs |
| **Jamais de jeton en `localStorage`** — jeton d'accès en mémoire, rafraîchissement en cookie `HttpOnly` / `SameSite` | Un XSS de back-office bancaire, c'est une session de guichetier volée |
| **CSP stricte**, pas de `bypassSecurityTrust*` | Angular AOT s'en passe ; l'exception doit être une revue, pas une habitude |
| **Verrouillage sur inactivité, pas déconnexion** | Le guichetier perdrait sa saisie. Re-saisie du mot de passe, la saisie survit |
| **L'agence et la caisse viennent du jeton** | La règle est déjà celle du socle ; l'interface ne doit pas offrir de sélecteur qui laisse croire le contraire |
| **Les exports passent par l'API** | Un CSV construit depuis une liste déjà chargée échappe à l'habilitation et à la trace |
| Les écrans en **lecture tracée** le disent | Le socle trace déjà (LCB-FT, audit, dossier client) ; l'afficher est dissuasif et honnête |
| **Aucun secret dans le bundle** | Client Keycloak public avec PKCE ; seules l'URL de l'API et le realm sont publics |
| **La police est auto-hébergée** | Un réseau de banque bloque souvent les CDN externes, et une requête vers un tiers à chaque ouverture de session est une fuite de métadonnées |

### À ajouter côté API

- `GET /v1/me/permissions` — les opérations autorisées du porteur, dérivées de la politique.
  Sans cela, le front devine, et il devinera faux.

---

## 3. Configurable — les trois niveaux, et le piège

**Le front ne configure rien qui soit déjà configuré ailleurs : il le lit.**

**Niveau 1 — ce que l'interface reflète.** Produits, familles, barèmes, devises et leurs échelles,
calendriers, natures de pièces, motifs d'opposition, scénarios de surveillance, déclarations. Rien
n'est retapé : **les types TypeScript se génèrent depuis `openapi.json`**. Le contrat est déjà
versionné et tenu par un test ; un `npm run api:generate` et le front ne peut plus diverger.

**Niveau 2 — ce qui se configure par déploiement.** Logo, couleur d'accent, libellés, format
d'affichage des comptes, écrans activés, ordre du menu, langue. Chargé au démarrage, sans
recompilation.

**Niveau 3 — ce qui ne doit pas être configurable.** Les contrôles de saisie et les enchaînements
d'écran. Vouloir les paramétrer produit un moteur de workflow maison, non typé et non testé. Un
enchaînement différent, c'est du code — comme une nouvelle méthode de surveillance est une
livraison.

**L'exception qui se justifie** : le formulaire de paramétrage produit. Les familles déclarent déjà
leurs paramètres obligatoires (`product/families.json`). Un formulaire générique y est légitime,
**à condition d'être piloté par un descripteur exposé par l'API** — nom, type, unité, obligatoire,
bornes — et non par une configuration front. La source de vérité reste le socle.

### À ajouter côté API

- Un descripteur de paramétrage par famille de produit, lisible, pour que le formulaire se rende
  sans que le front connaisse les clés.

---

## 4. Thèse de design

La concurrence — T24, Finacle, FLEXCUBE, Amplitude — partage une signature : chrome gris froid,
onglets dans des onglets, modales en cascade, huit couleurs qui ne veulent rien dire, et un code
d'erreur quand ça refuse. Ce sont des formulaires boulonnés sur une base de données.

**On ne se démarque pas en étant spectaculaire. On se démarque en étant calme, dense et honnête.**
Le back-office doit ressembler à ce que le registre croit : rigoureux, explicite, ne cachant jamais
son état.

### 4.1 La typographie fait le produit, pas la couleur

**IBM Plex Sans** pour le texte, **IBM Plex Mono** pour les références, numéros de compte et
identifiants d'écriture. Le choix se justifie sur quatre points : chiffres tabulaires excellents,
caractère institutionnel plutôt que startup, licence libre et **auto-hébergeable** (un réseau de
banque bloque les CDN), et une famille monospace assortie — ce qui compte quand la moitié des
données affichées sont des références.

Chiffres tabulaires partout : `font-variant-numeric: tabular-nums lining-nums`. Une colonne de
montants ne danse jamais.

### 4.2 Notre identité est dans la structure ; l'accent appartient à la banque

Base papier tiède, pas de blanc pur ni de gris bleuté d'entreprise : ces écrans sont regardés huit
heures par jour sous néon. **Une seule couleur d'accent, configurable par déploiement**, et une
palette sémantique stricte réservée aux états. Quand tout est neutre sauf ce qui compte, ce qui
compte se voit.

C'est aussi ce qui rend la thématisation possible sans rien perdre : la banque prend l'accent,
notre signature reste la structure, la typographie et la densité.

### 4.3 Le montant est un objet de première classe

Chiffres tabulaires, alignement sur le dernier chiffre, devise en graisse légère, signe explicite.
**Jamais de rouge par défaut sur un solde négatif** : un découvert autorisé n'est pas une alarme.
Le disponible est affiché à côté du solde comptable, avec le détail des blocages — un guichetier
qui refuse un retrait sans pouvoir dire pourquoi, c'est un incident client.

### 4.4 « En attente de validation » est un état à part entière

C'est l'état le plus fréquent d'un back-office bancaire, et personne ne le traite : ni succès, ni
échec. Il a sa couleur, sa place, et un vocabulaire d'états identique partout — **brouillon, en
attente, comptabilisé, contre-passé, rejeté, bloqué**.

Le maker-checker occupe deux écrans : « soumis, en attente » côté demandeur, une file côté
valideur. Et l'interface dit ce que le socle fait — **la requête est rejouée à l'approbation**,
donc l'exécution peut refuser ce que la saisie acceptait.

### 4.5 Pas de modale pour travailler : un tiroir de contexte

Il glisse à droite, la liste reste visible, l'opérateur ne perd jamais où il est. La modale est
réservée à une seule chose : confirmer l'irréversible.

### 4.6 La palette de commandes comme chemin le plus rapide

`Ctrl+K` : « versement », « CLI-0042 », « arrêté de caisse ». Courant dans les outils de
développeur, inexistant en banque. Un différenciateur réel, et il sert l'exigence de vitesse — un
guichetier fait cent cinquante opérations par jour et connaît ses écrans par cœur. On n'optimise
pas la découverte, on optimise la répétition.

### 4.7 Le refus est un moment de design

Le socle écrit déjà des refus faits pour être lus. Ils méritent mieux qu'un toast rouge qui
disparaît : la raison, la règle, et quoi faire — à un endroit conçu pour ça. C'est là que le
produit se distinguera le plus vite.

### 4.8 Deux détails qui pèsent lourd

**Densité au choix** — compacte ou confortable, persistée par utilisateur. Et **mode sombre
dessiné**, pas inversé : les arrêtés se font le soir.

### 4.9 Le réseau instable est un cas nominal

La clé d'idempotence est générée côté client et **conservée**. « Réessayer » rejoue la même clé ;
on ne re-saisit jamais une opération dont on ne sait pas si elle est partie.

---

## 5. Ce qu'on ne fait pas

- Pas d'animation décorative — 120 ms, fonctionnel, rien qui rebondit.
- Pas de bouton à icône seule hors barre d'outils ; le texte mène, l'icône accompagne.
- Pas de dégradé, pas d'ombre portée molle, pas de carte arrondie flottante : ce vocabulaire dit
  « tableau de bord marketing », pas « salle des comptes ».
- Pas de défilement infini sur une liste comptable — la pagination à curseur existe.
- Pas de toast éphémère pour confirmer une écriture : il faut une trace persistante, avec son
  numéro.
- Pas de graphique tant qu'il n'y a rien à comprendre d'un coup d'œil.
- Pas de modales imbriquées.

---

## 6. Les garde-fous qui tiennent tout cela dans le temps

Du même ordre que le test de contrat OpenAPI : des mécanismes, pas des intentions.

| Garde-fou | Ce qu'il empêche |
|---|---|
| **Un jeu fermé de primitives**, documenté | Quarante variantes de bouton au bout d'un an |
| **Une page atelier vivante dans l'application** — toutes les primitives, tous les états, les deux densités, les deux thèmes | Un composant qui n'y figure pas n'existe pas |
| **Un budget de taille dans le build** (`budgets` d'`angular.json`) | Une régression de poids fait échouer la compilation, elle ne se découvre pas en production |
| **Des tokens CSS** (propriétés personnalisées), pas d'utilitaires épars | Sur dix ans, ça se relit et ça se rethématise |
| **Les types générés depuis le contrat** | Le front ne peut pas diverger de l'API en silence |

---

## 7. Ordre de construction

1. **Socle visuel** : tokens, typographie, densités, thèmes clair et sombre, page atelier.
2. **Premier écran de bout en bout** : guichet — **versement d'espèces**. Il contient tout ce que
   la thèse doit prouver : bandeau client, solde et disponible, récapitulatif d'imputation avec
   date de valeur et frais, clé d'idempotence, reçu, refus métier lisible.
3. **File de validation** (maker-checker), qui éprouve le vocabulaire d'états.
4. Le reste des écrans du guichet, puis l'espace siège.

L'avancement réel est au §8.

## 8. État de la construction

Le code du front est dans [`core-banking/web`](../../core-banking/web) ; son README décrit la
mécanique (commandes, organisation, garde-fous). Node ≥ 22.22.3 est requis par Angular 22.

Ce document dit **pourquoi** les écrans sont ce qu'ils sont. Ce qu'un guichetier, un valideur ou
un exploitant doit faire devant eux est dans le [guide de l'utilisateur](utilisateur/README.md),
écrit pour être lu sans aucune connaissance technique.

| Étape du §7 | État |
|---|---|
| 1. Socle visuel | **Livré** — tokens, deux thèmes, deux densités, jeu fermé de 17 primitives, page atelier, budgets, types générés |
| 2. Guichet — versement d'espèces | **Livré** — bandeau client, billetage BCEAO contrôlé, imputation en projection puis reçu, idempotence conservée, refus lisible |
| 3. File de validation | **Livré** — file paginée, détail de la requête soumise, approbation qui exécute, rejet motivé, auto-approbation signalée, échec d'exécution après approbation |
| 4. Reste du guichet, puis siège | **En cours** — guichet complet (versement, retrait, virement, relevé, arrêté de caisse) ; siège ouvert (fin de journée, balance générale) |
| 5. Authentification et habilitations | **Livré** — OAuth2 PKCE contre Keycloak, jeton porté aux seuls appels du socle, rafraîchissement silencieux, verrouillage du poste, menu filtré par habilitations |

Rien n'est figé : ce qui suit est ce qu'on sait aujourd'hui, pas un engagement. Les décisions
prises pendant la construction du socle sont consignées ici pour qu'on puisse les défaire en
connaissance de cause.

### Ce que la construction a appris

**L'accent est déclaré par thème, pas dérivé.** Une couleur lisible sur papier tiède ne l'est pas
toujours sur fond sombre. `config.json` porte donc un accent clair et, facultativement, un accent
sombre ; les valeurs sont écrites dans une règle CSS par thème — un style en ligne sur `<html>`
l'emporterait sur `[data-theme='dark']` et figerait l'accent clair dans le thème sombre. Et parce
que `config.json` est du contenu de déploiement et non du code, seules les valeurs qui ont la
forme d'une couleur sont recopiées dans la feuille de style.

**Le tiroir de contexte est modal aujourd'hui.** Le `Dialog` du CDK piège le focus : la liste
reste lisible, pas manipulable. Le jour où un écran demandera de travailler la liste tiroir
ouvert, ce sera un `Overlay` sans piège de focus — pas un `Dialog` auquel on retire le voile en
faisant semblant.

**Les polices sont réduites au latin.** Le cyrillique, le grec et le vietnamien triplaient le
poids embarqué pour rien dans une agence de l'UEMOA. IBM Plex Sans est pris en fonte variable
(une requête pour toutes les graisses), IBM Plex Mono en trois graisses fixes.

**Le démarrage a son propre test.** Un initialiseur qui appelle `inject()` après un `await`
compile, passe les tests de composants, et casse l'application au premier chargement (NG0203).
Seule l'exécution de la séquence de démarrage le montre — elle est donc jouée par un test.

### Responsivité — les largeurs d'un poste d'agence

Ce n'est pas un site : personne ne fait un versement sur un téléphone. Mais les postes d'agence
sont souvent des 1366×768, le chef d'agence consulte sur tablette, et un écran qui casse à 1024
est un écran qu'on n'ouvre pas.

| Seuil | Ce qui change |
|---|---|
| ≥ 1280 | Deux colonnes : la saisie à gauche, l'imputation et l'action à droite, épinglées |
| 1280 | Le suivi repasse sous la saisie |
| 1100 | La barre se resserre ; la recherche garde son icône et perd son libellé |
| 768 | Une colonne ; le sommaire de l'atelier et les libellés secondaires tombent |
| < 768 | Consultation : lisible, jamais de défilement horizontal, ni clé d'idempotence ni raccourci clavier |

Une table comptable ne se replie jamais en cartes : elle défile dans son conteneur, colonnes
alignées. Un relevé lu en cartes empilées n'est plus un relevé.

`npm run check:largeurs` ouvre chaque écran à sept largeurs et échoue sur un débordement
horizontal ou une cible sous 24 px (WCAG 2.2, critère 2.5.8). Il demande un navigateur, donc il
n'est pas dans `npm test` ; il devient une barrière d'intégration le jour où le front en a une.

---

## 9. L'écran de versement : ce qu'il a tranché

**Le poste ne calcule ni les frais, ni la taxe, ni la date de valeur.** Ce sont des paramètres du
socle. L'écran montre donc l'imputation en deux temps : en projection, les deux lignes certaines
— débit caisse, crédit client — et une mention explicite ; après la comptabilisation, les chiffres
du reçu. Un barème recopié dans le navigateur finit par diverger, et l'écart se paie à l'arrêté de
caisse.

**Le billetage est un contrôle, pas une commodité.** Le montant annoncé et le comptage doivent
tomber juste ; l'écart bloque l'envoi et se voit à la remise, pas le soir quand il faudra rappeler
le client. Le comptage reste facultatif — un guichetier qui n'a pas encore compté saisit le
montant — mais dès la première coupure, il est contrôlé. Les coupures sont celles de la BCEAO,
billets et pièces, le 500 des deux côtés parce qu'un caissier les range séparément.

**La clé d'idempotence couvre une demande, pas une session.** Elle est conservée tant que la
saisie ne change pas : « Réessayer » rejoue la même clé, et un réseau qui tombe ne peut pas
produire un double versement. Dès que le montant, le compte ou le libellé changent, la clé est
renouvelée — sinon le socle rejouerait le premier reçu pour une opération différente, et le
guichetier croirait avoir passé la seconde.

**Les trois issues du socle sont trois messages distincts.** 201 comptabilisé, 200 rejeu d'une clé
déjà traitée, 202 en attente d'un second regard. « Comptabilisé » annoncé deux fois fait recompter
la caisse ; annoncé sur une opération en attente, il fait remettre les espèces au client.

**L'identité du remettant part au libellé de l'écriture.** Le contrat n'a pas de champ dédié, et
c'est de toute façon ce qu'un libellé d'écriture porte dans une agence. La lacune est nommée
ci-dessous.

**Une source de démonstration, jamais déguisée.** Tant qu'aucun socle n'est branché
(`sourceDonnees: "factice"`), un bandeau permanent le dit : rien de ce qui s'affiche ne vient d'un
registre. Basculer sur un socle réel est une ligne de `config.json`.

### Lacunes du contrat, relevées en construisant l'écran

Aucune n'a été comblée côté front : une règle devinée dans le navigateur est une divergence
programmée.

- **Cotation d'une opération** — frais, taxe et date de valeur avant comptabilisation. Sans elle,
  le guichetier ne peut pas annoncer au client ce qui sera prélevé.
- **Lien compte → titulaire** — le solde ne porte pas le tiers ; le bandeau client ne peut pas
  afficher le nom sans un appel qui n'existe pas.
- **Lecture des blocages d'un compte** — leur pose est exposée, pas leur lecture. L'écart entre
  solde et disponible reste donc inexpliqué face à un socle réel.
- **Recherche de compte** — `GET /parties` cherche des tiers, pas des comptes. Le guichet cherche
  un compte.
- **Identité du remettant, structurée** — exigence LCB-FT que `Requests.CashOperation` ne porte pas.
- **Lignes d'écriture du reçu** — le reçu donne les montants, pas les lignes. Un
  `GET /entries/{entryId}` permettrait d'afficher l'imputation réelle, pas seulement ses totaux.

---

## 10. La file de validation : ce qu'elle a tranché

**Le vocabulaire d'états passe de six à neuf.** Le socle a six statuts d'opération en attente —
`PENDING`, `APPROVED`, `REJECTED`, `EXPIRED`, `EXECUTED`, `FAILED` — et en écraser trois dans les
six états d'origine cacherait exactement ce qu'un exploitant doit voir. Les trois nouveaux :

| État | Ce qu'il dit |
|---|---|
| **approuvée, non confirmée** | Décidée, exécution non confirmée. Une anomalie d'exploitation ; la fondre dans « comptabilisé » la rendrait invisible le jour où elle compte |
| **échouée** | Approuvée, mais l'exécution a refusé. La décision reste ; c'est le demandeur qui resoumet |
| **expirée** | Le délai a couru sans que personne ne décide |

Un rejet humain et un échec d'exécution partagent la couleur mais pas le libellé : dans les deux
cas ça n'est pas passé, mais l'un se discute avec le valideur et l'autre avec l'état du compte.

**L'avertissement de rejeu est sur la confirmation, pas en note de bas de page.** L'approbation
exécute la requête telle qu'elle a été soumise ; le socle la rejoue, donc l'exécution peut refuser
ce que la saisie acceptait. C'est écrit là où le valideur clique.

**Une exécution refusée après approbation n'est pas un échec de l'approbation.** La décision est
prise et reste tracée ; l'opération passe en échouée et l'écran le dit explicitement : ce n'est pas
au valideur de corriger, c'est au demandeur de resoumettre. L'écran relit l'opération après le
refus pour montrer son état réel, pas celui d'avant le clic.

**L'échéance est calculée à l'affichage.** Le socle n'expire que paresseusement — il marque
`EXPIRED` au moment où quelqu'un tente de décider. Une ligne dont l'échéance est passée reste donc
`PENDING` en base, et l'afficher « en attente » enverrait un valideur sur une opération que plus
personne ne peut décider. L'interface calcule l'état affiché ; elle ne réécrit rien.

**Un rejet se motive, et le motif est la seule chose que le demandeur verra.** Le champ est
obligatoire côté écran comme côté socle : sans motif, le demandeur resoumet la même demande.

**L'auto-approbation est signalée avant le clic et refusée par l'API.** L'interface cache ce qui
est interdit ; c'est l'API qui l'empêche. Quand le contrat ne dit pas qui porte le jeton, l'écran
ne devine pas : il laisse l'API refuser.

### Lacune du contrat ajoutée à la liste

- **`GET /v1/me`** — le porteur du jeton. Sans lui, l'interface ne peut pas signaler
  l'auto-approbation avant le clic, ni construire le menu à partir des opérations autorisées
  (§2). Déduire l'identité du JWT côté front ferait du navigateur une source d'identité.

---

## 11. Retrait et arrêté de caisse : ce qu'ils ont tranché

**Ce qui est identique entre deux opérations de guichet ne s'écrit qu'une fois.** La clé
d'idempotence, son renouvellement, le rejeu et la distinction des trois issues du socle vivent
dans une seule classe (`Soumission`), partagée par le versement et le retrait. C'est là qu'on se
trompe ; le versement y est passé sans qu'un test bouge, ce qui est la meilleure preuve que la
mécanique était bien isolée.

**Au retrait, c'est le disponible qui commande, pas le solde comptable.** Un blocage retient une
part du solde ; l'écran l'affiche en clair, avec le montant retenu et le renvoi au tiroir de
contexte. Un guichetier qui refuse un retrait sans pouvoir dire pourquoi est un incident client.

**Le poste ne bloque que sur ce qu'il sait avec certitude.** Un montant supérieur au disponible
sera refusé quoi qu'il arrive : inutile de faire l'aller-retour. En dessous, les frais peuvent
encore faire basculer — et c'est le socle qui tranche, pas le navigateur. La limite entre
« empêcher » et « laisser refuser » est exactement là : le poste empêche ce qui est certain, il ne
devine jamais un barème.

**En attente de validation, on ne remet pas les espèces.** Un retrait mis en attente n'est pas
comptabilisé ; l'écran le dit en toutes lettres, parce que c'est le seul écran où une mauvaise
lecture fait sortir de l'argent du tiroir.

**L'imputation connaît son sens.** Débit d'abord : caisse puis client au versement, client puis
caisse au retrait. Les frais sont toujours au débit du compte client — le client paie la
commission, qu'il verse ou qu'il retire.

### L'arrêté de caisse

C'est l'écran qui relie le guichet au cycle comptable : **le traitement de fin de journée refuse
de clore une journée dont une caisse mouvementée n'a pas été arrêtée**. Tant que le comptage n'est
pas fait, l'agence entière attend — et l'écran commence par le dire.

Le solde théorique vient du registre, le comptage des doigts du guichetier, l'écart de la
soustraction des deux. Il s'affiche au fur et à mesure, nommé : excédent quand il y a plus en
caisse que ce que le registre annonce, manquant dans l'autre sens.

**Un écart n'empêche pas l'arrêté — le cacher serait pire.** Mais il se confirme explicitement :
l'écart s'impute au compte d'écart de caisse, reste au nom de celui qui a compté, et se justifie.
La confirmation le dit, et rappelle que c'est le dernier moment pour recompter. C'est le seul
usage légitime d'une modale : confirmer l'irréversible.

### Lacunes du contrat ajoutées à la liste

- **`GET /v1/me/till`** — la caisse du porteur. Le contrat crée une caisse et l'arrête, mais ne la
  lit pas.
- **Solde théorique d'une caisse** — sans lui, l'arrêté ne peut pas être présenté. Le recalculer
  côté poste serait réécrire le registre dans le navigateur ; l'implémentation HTTP refuse donc
  explicitement plutôt que de deviner, et l'écran affiche ce refus.

---

## 12. Virement, relevé, et la barre qui a dû grandir

**La barre du haut porte les espaces, pas les écrans.** À sept entrées elle tronquait déjà le
dernier nom — et une entrée de menu tronquée fait disparaître un écran. Les cinq écrans du guichet
vivent donc sous une barre d'espace, et la barre du haut est revenue à trois entrées : Guichet,
Validation, Atelier. C'est la structure annoncée au §1 — une application, deux espaces — appliquée
au moment où elle devenait nécessaire plutôt qu'au moment où elle était théorique.

### Le virement interne

**Une seule écriture, deux comptes.** Le socle débite et crédite dans la même transaction : il n'y
a jamais un instant où l'argent n'est nulle part. L'écran ne fait donc jamais deux appels, et le
reçu parle d'une écriture, pas de deux.

Comme au retrait, **le disponible du débiteur commande**. Et comme partout, le poste n'empêche que
ce qui est certain : deux fois le même compte, deux devises différentes — un virement interne ne
fait pas le change. Le reste appartient au socle.

L'imputation reprend le même composant, avec la contrepartie paramétrée : la caisse au guichet, le
compte bénéficiaire au virement. Débit d'abord, toujours.

### Le relevé de compte

Trois choses qu'un relevé doit dire et que la plupart taisent.

**Une contre-passation ne remplace pas l'écriture d'origine, elle s'ajoute.** Les deux restent au
journal et le relevé les montre toutes les deux : l'annulée marquée comme telle, l'annulante
citant le numéro de pièce qu'elle annule. Et parce qu'on n'a lu qu'une page, on ne marque
« contre-passée » que ce que la page montre — affirmer qu'une écriture est intacte sur la foi
d'une page serait une affirmation qu'on ne peut pas tenir.

**Une écriture peut être passée après le jour qu'elle affecte.** Le socle est bitemporel ; quand
la date de connaissance diffère du jour comptable, la ligne le dit. C'est ce qui explique un solde
qui a « changé » hier.

**Les totaux sont ceux de la page, jamais un solde** — et le pied de table l'écrit. Un total de
page présenté comme un solde est un mensonge par cadrage.

La consultation d'un relevé est tracée par le socle ; l'écran l'affiche. C'est honnête, et
dissuasif.

### Un double de test partagé

Les écrans de guichet parlent tous au même port. Leur double de test vit désormais dans un seul
fichier : une classe à faire suivre quand le port grandit, au lieu d'une par écran qui diverge.
Son compte par défaut porte un blocage — c'est là que solde et disponible divergent, et c'est ce
que les écrans doivent savoir montrer.

---

## 13. L'espace siège : exploitation et balance

### Le traitement de fin de journée

L'écran le plus lourd de conséquences de l'application, et il tient sur trois idées.

**L'essai à blanc n'écrit rien, et c'est lui qui trouve le blocage.** Un exploitant passe en essai
avant d'engager sa journée : les étapes s'exécutent, les anomalies sortent, le registre ne bouge
pas. La distinction est portée par un bandeau permanent, pas par une case à cocher qu'on oublie.
C'est pour cela que la source de démonstration fait échouer le **premier** passage, essai compris :
un essai qui ne trouverait rien n'aurait aucune raison d'exister.

**Un échec bloquant arrête la chaîne, et les étapes suivantes ne sont pas « en attente ».** Elles
n'ont jamais été tentées. L'écran l'écrit, et compte combien : lire « en attente » sur un
traitement terminé fait croire qu'il reste du travail en cours, alors qu'il n'y a plus rien qui
tourne. La reprise repart de l'étape échouée, pas du début — l'écran le dit aussi, parce que c'est
la première question qu'on se pose avant de cliquer.

**Une anomalie non bloquante n'arrête rien et doit être lue.** Elle revient le lendemain, en plus
gros. Elles sont comptées dans le bandeau du passage et détaillées sur leur étape.

Deux gardes : **on n'annule pas un essai à blanc** — il n'a rien écrit, et le proposer laisserait
croire le contraire ; et l'annulation d'un passage réel rappelle que le socle la refuse dès qu'une
journée postérieure a tourné.

L'écran relit le passage tant qu'il tourne, et seulement tant qu'il tourne : interroger un
traitement terminé n'apprend rien.

Les 27 étapes affichées sont **celles du socle, dans son ordre**, repris du test qui les épingle.
Un exploitant qui apprend l'écran doit reconnaître son traitement, pas une liste plausible.

Et la boucle se ferme : le refus de démonstration sur `PRE_CHECKS` est une caisse mouvementée non
arrêtée. C'est exactement l'écran d'arrêté de caisse du guichet qui le débloque.

### La balance générale

**L'équilibre est l'information de tête, pas une colonne de plus.** Une balance qui ne s'équilibre
pas veut dire que le registre ne se tient pas, et rien de ce qu'on en tire — état financier,
déclaration réglementaire — ne vaut tant que ce n'est pas réglé. L'écran l'annonce avant les
chiffres, et donne les deux colonnes : ce n'est pas un écran à corriger, c'est une écriture à
retrouver.

**Les totaux sont rendus par devise.** Une balance ne s'additionne pas entre devises ; le faire
produirait un nombre qui ne veut rien dire. Le socle totalise, le poste n'additionne rien.

---

## 14. L'authentification : ce qu'elle a tranché

L'écran ne garde aucun secret durable. Le jeton d'accès et le jeton de rafraîchissement vivent en
mémoire, dans un service, et disparaissent au rechargement — **jamais dans `localStorage`**, qui
survit à la fermeture du navigateur et se lit depuis n'importe quel script chargé par la page. Le
prix est connu : un rafraîchissement de page redemande une session. Le silencieux
(`prompt=none` contre Keycloak) le rend indolore quand la session du fournisseur est encore
ouverte, et honnête quand elle ne l'est plus.

**PKCE est écrit ici, pas importé.** Le calcul tient en une soixantaine de lignes — un aléa de 32
octets, son empreinte SHA-256, le tout en base64url — et il est testable sans navigateur. Embarquer
une bibliothèque d'authentification pour cela contredirait le jeu fermé de primitives du §3 et
ajouterait au poids embarqué ce qu'on refuse ailleurs. Le `state` est comparé **à temps constant** :
un écart de durée sur une comparaison de chaîne est une fuite, petite mais gratuite à éviter.

**Le jeton ne part qu'au socle.** L'intercepteur compare l'origine de chaque requête à
`apiBaseUrl` et n'ajoute l'en-tête `Authorization` que si elles coïncident. Un intercepteur qui
signe tout laisse filer le jeton vers le premier service tiers que le front appellera un jour ;
c'est le genre de fuite qu'on n'écrit pas volontairement et qu'on constate trop tard.

**Un 401 vaut un rafraîchissement, une fois.** Le jeton est renouvelé puis la requête rejouée ;
si le renouvellement échoue, ou si le rejeu échoue encore, l'erreur remonte à l'écran. Il n'y a
pas de seconde tentative, parce qu'une boucle de rafraîchissement sur un jeton mort tape le
fournisseur d'identité en rafale et masque à l'opérateur ce qui se passe réellement.

**Verrouiller n'est pas déconnecter.** Un guichetier qui s'absente verrouille ; l'application reste
montée derrière le voile, la saisie en cours intacte, et elle repart où elle était. Une
déconnexion perdrait le versement à moitié saisi, donc personne ne verrouillerait, donc le poste
resterait ouvert — c'est ainsi qu'une mesure de sécurité se retourne. Le délai d'inactivité vient
de `config.json` (`auth.verrouillageMinutes`) : une agence de quartier et un siège n'ont pas la
même exposition.

**Les habilitations inconnues laissent tout voir.** Le socle n'expose pas encore les opérations
autorisées pour l'appelant (lacune de contrat n° 1, §8). Tant qu'il ne les expose pas, `autorise()`
répond vrai : le menu montre tout et **l'API refuse**. Un menu deviné qui cache un écran auquel
l'agent a droit produit un ticket de support ; un menu qui montre un écran auquel il n'a pas droit
produit un refus explicite du socle. Le second est le bon défaut, et il disparaîtra le jour où le
socle répondra. La table `OPERATION_PAR_ECRAN` — chaque écran vers l'opération réelle qu'il
appelle (`CASH_OPERATION`, `TRANSFER`, `ACCOUNT_JOURNAL_READ`, `TILL_CLOSE`, `PERIOD_CLOSE`,
`LEDGER_READ`) — est déjà écrite et testée : seule la source des droits manque.

**Le mode démonstration ne simule pas un mot de passe.** Le fournisseur factice ouvre une session
sans réseau et le dit ; il n'invente pas un écran de connexion qui accepterait n'importe quoi. Le
bandeau de démonstration, lui, ne bouge pas.

**La recherche cède avant la navigation.** L'arrivée du porteur et du bouton *Verrouiller* dans la
barre a coupé « Atelier » à 1440 : la recherche et la navigation se serraient à poids égal. Le
poids de rétrécissement est désormais explicite (20 contre 1), ce qui rend vraie la phrase que le
code affirmait déjà. Le contrôle de largeurs ne l'attrape pas — il mesure le débordement, pas la
troncature — c'est la relecture des captures qui l'a vu.

### Lacune du contrat ajoutée à la liste

- **`GET /v1/me/permissions`** — les opérations autorisées pour l'appelant. C'est la lacune qui
  coûte le plus cher aujourd'hui : elle est la raison pour laquelle le menu montre tout. Le socle
  connaît déjà la réponse — les habilitations sont dans sa base et il les applique à chaque appel
  — il ne l'expose simplement pas. Le jour où il l'expose, `autorise()` cesse de répondre vrai par
  défaut et rien d'autre ne bouge dans le front : c'est déjà branché.

Rappel du principe qui rend cette lacune vivable : **déduire les droits du JWT côté navigateur
serait faire du poste une source d'habilitation.** Les rôles Keycloak (`TELLER`,
`BRANCH_MANAGER`) servent au fournisseur d'identité, pas au contrôle d'accès du socle ; les
confondre donnerait un front qui se croit autorisé et un socle qui refuse, ou pire, l'inverse.
