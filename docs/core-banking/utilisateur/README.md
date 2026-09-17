# Guide de l'utilisateur — back-office agence

Ce guide s'adresse aux personnes qui **se servent** de l'application : guichetier, chef
d'agence, valideur, exploitant comptable. Il ne demande aucune connaissance technique.

Les choix de conception et leurs raisons sont ailleurs, dans
[`16-back-office.md`](../16-back-office.md) ; la mécanique du logiciel est dans le
[README du front](../../../core-banking/web/README.md). Ici, on explique **ce que vous voyez,
ce que vous devez faire, et quoi répondre au client**.

## Qui lit quoi

| Vous êtes | Lisez d'abord | Puis |
|---|---|---|
| Guichetier | [1. Prise en main](01-prise-en-main.md) | [2. Espèces](02-especes.md), [3. Virement et relevé](03-virement-releve.md), [4. Arrêté de caisse](04-arrete-de-caisse.md) |
| Chef d'agence, valideur | [1. Prise en main](01-prise-en-main.md) | [5. La file de validation](05-validation.md) |
| Exploitant comptable, siège | [1. Prise en main](01-prise-en-main.md) | [6. L'espace siège](06-siege.md) |
| Tout le monde, quand ça coince | [7. Messages, refus et états](07-messages.md) | — |

## Trois choses à savoir avant tout le reste

**1. L'application ne calcule rien.** Ni les frais, ni la taxe, ni la date de valeur, ni le
solde. Tout cela vient du système central — le *socle*. Ce que l'écran affiche avant de valider
est annoncé comme une **projection** ; les chiffres qui font foi sont ceux du reçu, après
comptabilisation. Si les deux diffèrent, c'est le reçu qui a raison, et il faut le signaler.

**2. Un refus n'est pas une panne.** Quand le socle refuse, il dit pourquoi, et l'écran vous
montre sa raison telle quelle, avec son code. Le [chapitre 7](07-messages.md) traduit ces codes
en gestes. Ne recommencez pas une opération refusée « pour voir » : lisez le motif.

**3. Le réseau peut tomber sans que l'opération soit perdue.** Chaque demande porte une **clé
d'idempotence** : si l'envoi échoue sans réponse, le bouton *Réessayer avec la même clé* rejoue
**la même** demande. Le socle reconnaît la clé et ne comptabilise jamais deux fois. C'est le
seul bouton à utiliser dans ce cas — refaire la saisie créerait une seconde opération.

## Ce qui n'est pas encore dans l'application

Dit franchement, pour que personne ne le cherche : la **conformité** (alertes LCB-FT,
déclarations) et le **paramétrage produit** ne sont pas encore des écrans. Ils passent
aujourd'hui par le système central directement.
