# 5. La file de validation — le second regard

Certaines opérations ne se comptabilisent pas sur la décision d'une seule personne : au-delà d'un
seuil, ou par nature, elles passent par une **double validation**. Le guichetier soumet, un
second habilité décide.

L'espace **Validation** porte cette file.

## La liste

Chaque ligne donne : la nature de l'opération, son montant, qui l'a soumise, quand, et son
échéance. Le bouton **Rafraîchir** recharge — la file bouge pendant que vous la lisez.

Traitez par échéance, pas par montant. Une opération qui expire est une opération à resaisir
entièrement par le guichetier, devant un client qui attend.

## Le détail

En ouvrant une ligne, vous voyez **La requête soumise** — exactement ce que le guichetier a
envoyé, avec la mention *« rejouée telle quelle à l'approbation »*.

> **Lisez la requête, pas le résumé.** C'est ce contenu-là qui sera exécuté si vous approuvez,
> caractère pour caractère. Vérifiez le compte, le montant, le sens, le bénéficiaire.

## Approuver

Le bouton **Approuver** déclenche l'exécution réelle de l'opération. Trois issues :

- **Décision enregistrée** et l'opération est comptabilisée — le cas normal.
- **Décision enregistrée**, mais l'exécution est refusée par le système central. L'état devient
  **Échouée** : votre décision reste prise, l'écriture n'est pas passée. C'est au **demandeur** de
  resoumettre après correction — pas à vous de réapprouver.
- L'exécution ne confirme pas. L'état affiché est **Approuvée, non confirmée**. Ce n'est ni un
  succès ni un échec : c'est une anomalie d'exploitation à signaler. L'écran ne la déguise pas en
  succès.

## Rejeter

**Rejeter…** ouvre un champ **Motif du rejet**, obligatoire. Le motif est conservé et remonte au
demandeur.

Écrivez un motif que le guichetier peut utiliser : *« bénéficiaire erroné, le client a dicté le
compte de son épouse »* vaut mieux que *« non conforme »*. Un motif vide de sens fait resoumettre
la même opération.

## Les trois bandeaux qui vous arrêtent

### ⚠️ Vous avez soumis cette opération
Vous ne pouvez pas approuver votre propre demande. C'est tout le sens du second regard, et la
règle n'a pas d'exception — pas même pour un chef d'agence pressé. Passez la main.

### ℹ️ Déjà décidée
Quelqu'un a décidé pendant que vous lisiez. La décision affichée est celle qui compte ;
rafraîchissez la file.

### ⚠️ Échéance dépassée
Le délai a couru sans décision. L'opération est **Expirée** et n'est plus décidable. Prévenez le
demandeur : il doit resoumettre.

## Ce que la file n'est pas

Ce n'est pas une corbeille de notifications, et ce n'est pas une file d'attente qu'on vide en fin
de journée. **Chaque ligne est un client qui attend au comptoir, ou une opération qui va
expirer.** Le rythme de traitement de cette file est un indicateur de la qualité de service de
l'agence.
