# 7. Messages, refus et états

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
