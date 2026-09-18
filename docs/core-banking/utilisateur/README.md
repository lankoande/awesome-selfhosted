# Guide de l'utilisateur — back-office agence

Ce guide s'adresse aux personnes qui **se servent** de l'application : guichetier, chef
d'agence, valideur, exploitant comptable. Il ne demande aucune connaissance technique.

Les choix de conception et leurs raisons sont ailleurs, dans
[`16-back-office.md`](../16-back-office.md) ; la mécanique du logiciel est dans le
[README du front](../../../core-banking/web/README.md). Ici, on explique **ce que vous voyez,
ce que vous devez faire, et quoi répondre au client**.

## Le guide au format Word

Le même contenu, assemblé en un seul document avec les captures d'écran :
[`guide-utilisateur-back-office.docx`](guide-utilisateur-back-office.docx). Il se
régénère depuis ces `.md` — voir [`outils/`](outils/README.md). Le Markdown est la
source ; une correction faite dans le Word serait perdue à la génération suivante.

## Qui lit quoi

| Vous êtes | Lisez d'abord | Puis |
|---|---|---|
| Guichetier | [1. Prise en main](01-prise-en-main.md) | [2. Espèces](02-especes.md), [3. Virement et relevé](03-virement-releve.md), [4. Arrêté de caisse](04-arrete-de-caisse.md) |
| Chargé de clientèle | [1. Prise en main](01-prise-en-main.md) | [6. Les clients](06-clients.md) |
| Chargé de crédit | [1. Prise en main](01-prise-en-main.md) | [7. Le crédit](07-credit.md), [6. Les clients](06-clients.md) |
| Chef d'agence, valideur | [1. Prise en main](01-prise-en-main.md) | [5. La file de validation](05-validation.md) |
| Exploitant comptable, siège | [1. Prise en main](01-prise-en-main.md) | [8. L'espace siège](08-siege.md) |
| Analyste conformité, responsable LCB-FT | [1. Prise en main](01-prise-en-main.md) | [9. La conformité](09-conformite.md) |
| Tout le monde, quand ça coince | [10. Messages, refus et états](10-messages.md) | — |

## Trois choses à savoir avant tout le reste

**1. L'application ne calcule rien.** Ni les frais, ni la taxe, ni la date de valeur, ni le
solde. Tout cela vient du système central — le *socle*. Ce que l'écran affiche avant de valider
est annoncé comme une **projection** ; les chiffres qui font foi sont ceux du reçu, après
comptabilisation. Si les deux diffèrent, c'est le reçu qui a raison, et il faut le signaler.

**2. Un refus n'est pas une panne.** Quand le socle refuse, il dit pourquoi, et l'écran vous
montre sa raison telle quelle, avec son code. Le [chapitre 10](10-messages.md) traduit ces codes
en gestes. Ne recommencez pas une opération refusée « pour voir » : lisez le motif.

**3. Le réseau peut tomber, et l'écran vous dira quoi faire.** Selon l'opération, deux conduites,
et **l'écran choisit pour vous** — suivez ce qu'il propose, ne cherchez pas l'autre.

- **Au guichet** (espèces, virement), chaque demande porte une **clé d'idempotence**. Si l'envoi
  échoue sans réponse, le bouton *Réessayer avec la même clé* rejoue **la même** demande ; le
  socle reconnaît la clé et ne comptabilise jamais deux fois. C'est le seul bouton à utiliser —
  refaire la saisie créerait une seconde opération.
- **Ailleurs** (créer un client, ouvrir un compte, déposer une demande de crédit), le socle ne
  reconnaît pas encore les envois en double. L'écran **ne vous propose donc aucun rejeu** : il
  vous renvoie vérifier — à la recherche, ou à la file de validation. Renvoyer à l'aveugle
  créerait un doublon, et un doublon de client ou de compte se paie ensuite en corrections
  manuelles.

Quand l'écran affiche « La demande a peut-être été enregistrée », c'est ce second cas : allez
voir avant de recommencer.

## Ce qui n'est pas encore dans l'application

Dit franchement, pour que personne ne le cherche : le **reporting réglementaire** (états BCEAO,
échéances, fiscalité), le **profil d'activité déclaré** et le **consentement au bureau
d'information**, le **paramétrage produit**, les **sûretés** et les **moyens de paiement**
(chèques, prélèvements, virements sortants) ne sont pas encore des écrans. Ils passent
aujourd'hui par le système central directement.

La **conformité LCB-FT** — alertes, déclarations de soupçon, scénarios de surveillance — l'est
désormais : voir le [chapitre 9](09-conformite.md).
