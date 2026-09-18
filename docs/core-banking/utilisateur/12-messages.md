# 12. Messages, refus et états

La page à ouvrir quand quelque chose ne va pas. Chaque entrée dit **ce que c'est** et **le geste
à faire**.

---

## Les neuf états d'une opération

Le vocabulaire est le même partout dans l'application, et il correspond un pour un aux états du
système central. Aucun n'est décoratif.

| État | Ce que cela veut dire | Ce que vous faites |
|---|---|---|
| **Brouillon** | Saisie en cours, rien n'est parti | Rien ; complétez |
| **En attente** | Enregistrée, pas comptabilisée : attend un second regard | Le valideur décide ; ni resaisir, ni relancer |
| **Approuvée, non confirmée** | Décidée, mais l'exécution n'a pas confirmé | **Signaler** — anomalie d'exploitation |
| **Comptabilisé** | L'écriture est passée | Imprimer le reçu |
| **Contre-passé** | Annulée par une écriture inverse ; les deux lignes restent | Expliquer au client : on n'efface pas, on contre-passe |
| **Rejeté** | Un valideur a refusé, avec motif | Lire le motif, corriger, resoumettre |
| **Échouée** | Approuvée, mais l'exécution a été refusée | Le **demandeur** corrige et resoumet |
| **Expirée** | Le délai a couru sans décision | Resoumettre l'opération |
| **Bloqué** | Le compte ou l'opération est bloqué | Voir le motif du blocage ; cela ne se lève pas au guichet |

> **« En attente » n'est ni un succès ni un échec.** C'est l'état le plus fréquent d'un
> back-office bancaire, et il a sa propre couleur pour cette raison.

---

## Les refus au guichet

### Provision insuffisante
Le **disponible** ne couvre pas l'opération, frais compris. Vérifiez le bandeau client : si le
disponible est inférieur au solde comptable, une retenue existe et le bandeau la nomme. Annoncez
le disponible au client, pas le solde.

### Plafond dépassé
Un plafond de produit ou de compte s'applique. Ce n'est pas une question de provision : le client
peut avoir l'argent et rester au-dessus du plafond. Le plafond se modifie par le paramétrage, pas
au comptoir.

### Compte non actif
Le compte est clos, dormant ou bloqué ; son statut est affiché à côté du message. Aucune
opération n'y passera tant que son statut n'a pas changé — c'est une opération de gestion de
compte, pas une opération de guichet.

### Opposition
Une opposition judiciaire ou une opposition sur chèque s'applique. **Ne la contournez pas** et ne
proposez pas d'opération alternative pour le même montant : signalez à votre hiérarchie.

### Second regard requis
Ce n'est pas un refus : l'opération est enregistrée et attend une validation. Voir
[chapitre 5](05-validation.md).

### AUTO_APPROBATION_INTERDITE
Vous tentez d'approuver une opération que vous avez soumise. Passez la main à un collègue
habilité.

### OPERATION_NON_DECIDABLE
L'opération est expirée ou déjà décidée. Rafraîchissez la file.

### BALANCE_DESEQUILIBREE
La balance générale ne s'équilibre pas. Ce n'est pas un problème d'affichage. N'exploitez aucun
chiffre qui en découle et remontez au responsable comptable.

---

## Les refus au référentiel client

### Ce client ne peut pas recevoir de nouveau compte
Le client est bloqué, sa connaissance client n'est pas vérifiée, ou son dossier est incomplet. Le
[chapitre 6](06-clients.md) explique ce qui est nommé comme obstacle. **Ses comptes existants
continuent de fonctionner** : c'est l'ouverture qui est arrêtée, pas l'activité du client — un
client vous posera la question.

### Pièce manquante, pièce expirée
Le dossier nomme la pièce et sa nature. Réclamez-la au client ; une fois versée et vérifiée,
l'obstacle disparaît de lui-même.

### Bénéficiaire non vérifié
Sur une personne morale. Tant que la conformité n'a pas vérifié un bénéficiaire effectif déclaré,
aucun compte ne s'ouvre. Ce n'est pas une décision du comptoir.

## Les refus au crédit

### Toutes les conditions suspensives ne sont pas levées
Le contrat ne peut pas être établi. Le dossier liste celles qui manquent. Attention : une
condition **résolutoire** non levée ne bloque rien — si le refus persiste alors que vous pensiez
tout avoir levé, vérifiez que vous regardiez bien la bonne section.

### Une décision sans analyse ne se motive pas
Le bouton *Décider* n'apparaît pas tant qu'aucune analyse n'a été versée au dossier. Versez-la
d'abord : sans elle, la décision ne tiendrait pas devant un contrôle.

### Ce contrat est déjà débloqué
Le déblocage a déjà eu lieu, ou une demande est en attente de validation. Regardez la
[file de validation](05-validation.md) avant de recommencer.

### Ce contrat est déjà passé en perte
Un passage en perte ne se fait qu'une fois. Ce qui reste dû se suit au hors bilan, et se
recouvre — voir le [chapitre 7](07-credit.md).

### Un contrat qui n'est pas en cours ne se rembourse pas par anticipation
Un contrat non débloqué, soldé ou passé en perte n'accepte plus les actes de gestion courante.

## Les refus à la conformité

### Le classement d'une alerte porte son motif
Une alerte classée sans raison écrite ne se contrôle pas. Écrivez le motif pour quelqu'un qui
lira le dossier dans trois ans sans rien savoir du contexte.

### Cette alerte est déjà couverte par une déclaration
Elle a été citée par une déclaration de soupçon : son sort est scellé. La déclarer une seconde
fois ferait deux dossiers pour un seul fait. Elle ne se reclasse pas non plus.

### Une déclaration ne mélange pas deux dossiers
Une des alertes citées ne porte pas sur le tiers déclaré. Décochez-la : elle fera l'objet de sa
propre déclaration.

### Une déclaration cite les alertes qu'elle couvre
Aucune alerte cochée : rien ne rattacherait la déclaration à des faits. Cochez au moins celle
depuis laquelle vous rédigez.

### Un scénario porte déjà ce code
Le code d'un scénario figure sur chaque alerte qu'il lève : deux scénarios ne peuvent pas le
partager. Choisissez-en un autre, ou faites cesser l'ancien.

### Ce paramètre est exigé par la méthode
Chaque méthode de surveillance a besoin de ce qui la fait compter — un seuil, une fenêtre, un
nombre minimal d'opérations, un facteur. L'écran ne demande que ceux-là, et les nomme quand il en
manque un. Un scénario incomplet ne surveille rien.

### Cette alerte est fermée
Une alerte classée ou déclarée ne se reprend pas en charge.

## Les refus au réglementaire

### Cet état porte des anomalies
Il a été produit pour qu'on voie ce qui ne va pas, mais il ne se transmet pas : on ne déclare pas
au superviseur des comptes dont on sait qu'ils sont faux. Corriger la comptabilité, reprendre
l'état, puis transmettre.

### Ce qui est transmis ne s'annule pas
Un état déposé se rectifie par un dépôt suivant. Seul un état produit et non transmis se reprend.

### Un état existe déjà pour cette période
Deux états transmis pour le même mois seraient deux déclarations contradictoires. Annulez le
précédent en le motivant, puis reproduisez.

### Cette date ne ferme pas de période
Un état se produit sur la période que le superviseur attend, pas sur un intervalle choisi. Prenez
une des périodes que l'écran propose.

### La déclaration n'est en vigueur qu'à partir du …
On ne produit pas un état sur une période antérieure à l'entrée en vigueur de la déclaration.

### Un état ne se produit pas avant la fin de la période qu'il couvre
La période doit être close. Attendez sa fin.

### La transmission porte la référence rendue par le destinataire
Le récépissé est la preuve du dépôt : sans lui, la banque ne peut pas établir qu'elle a déclaré.

### Une transmission se fait à deux
Produire est un travail, transmettre est un engagement. Un second valideur doit approuver.

### L'annulation d'un état porte son motif
Comme partout : une décision sans raison écrite ne se contrôle pas.

### Une taxe porte son compte de collecte
Une retenue se loge quelque part. Sans compte de collecte, elle serait prise au client sans être
due à personne.

## Les refus d'habilitation

### Le bouton que j'attendais n'est pas là
Votre profil ne porte pas cet acte. Deux cas : soit l'écran affiche la raison à la place du bouton
— lisez-la, elle dit qui le porte —, soit l'acte n'apparaît pas du tout parce qu'il n'était qu'une
option parmi d'autres. Voir [le chapitre 1](01-prise-en-main.md).

### Le montant dépasse votre plafond
Le plafond de votre profil est écrit sous le champ du montant. Au-delà, l'opération remonte à un
profil qui le porte — votre chef d'agence, ou le siège selon l'acte. **Ne découpez pas l'opération
en plusieurs** pour passer dessous : c'est un fractionnement, et la conformité le verra.

### Un plafond plus bas hors de votre agence
Normal : vous opérez sur le compte d'une autre agence sans avoir le dossier sous les yeux. Les
deux plafonds sont écrits côte à côte.

### « Séparation des tâches : l'auteur d'une opération ne peut pas la valider »
Vous avez soumis cette demande : un autre doit l'approuver. Voir
[le chapitre 5](05-validation.md).

## Les incidents techniques

### Le réseau est tombé pendant l'envoi
Bandeau de refus avec le bouton **Réessayer avec la même clé**. Cliquez dessus — c'est exactement
la situation pour laquelle il existe. Le système central reconnaîtra la demande et, si elle est
déjà passée, vous rendra le **premier reçu** avec la mention *« Déjà comptabilisé »*.

**Ne resaisissez jamais l'opération dans ce cas.** Une nouvelle saisie est une nouvelle demande,
et rien n'empêchera alors le double.

### « Déjà comptabilisé — voici le premier reçu »
Tout va bien. Rien n'a été comptabilisé une seconde fois ; vous voyez le reçu d'origine.

### Votre session a expiré
L'application vous renvoie au portail d'identité. Si vous aviez une saisie en cours, elle est
perdue — c'est la seule situation où elle l'est. Verrouiller, lui, ne fait rien perdre.

### « Le fournisseur d'identité n'a pas confirmé la session »
Affiché sur l'écran de verrouillage quand la reprise échoue. Rechargez la page et
reconnectez-vous.

---

## Trois réflexes qui évitent la plupart des incidents

1. **Lire le motif avant de recommencer.** Un refus porte toujours sa raison ; recommencer sans
   la lire reproduit le refus, ou pire, crée un doublon.
2. **Annoncer le disponible, jamais le solde comptable.** La quasi-totalité des litiges de
   comptoir vient de cette confusion.
3. **Arrêter sa caisse avant de partir.** Une caisse non arrêtée bloque la fin de journée de
   toute l'agence.

---

## À qui s'adresser

| Situation | Interlocuteur |
|---|---|
| Refus métier (provision, plafond, opposition) | Votre chef d'agence |
| Écart de caisse | Votre chef d'agence, le jour même |
| État **Approuvée, non confirmée** | Exploitation / siège |
| Balance déséquilibrée | Responsable comptable |
| Bandeau **Données de démonstration** en production | Support applicatif, immédiatement |
| Une opération est comptabilisée deux fois | Support applicatif, avec les deux numéros d'opération |
| Un doute sur une alerte LCB-FT | Le responsable conformité — **jamais** le chargé de clientèle du tiers |
| Un état transmis qui ne se reproduit plus | Responsable comptable, avant le prochain contrôle |
| Une échéance déclarative dépassée | Responsable comptable, le jour même |
