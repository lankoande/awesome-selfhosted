# 07 — Sécurité & conformité

Dans un core banking, la sécurité n'est pas une couche périphérique : elle est dans le
modèle de données et dans le chemin d'exécution.

---

## 1. Authentification

| Population | Mécanisme |
|---|---|
| Agents de la banque | OIDC via Keycloak, fédéré avec l'annuaire, MFA obligatoire |
| Clients (banque en ligne, mobile) | OIDC + MFA, liaison d'appareil, biométrie |
| Systèmes partenaires | mTLS + OAuth2 client credentials, certificats à rotation |
| Batch et automates | Identité de service, secrets Vault à durée de vie courte |

Aucun compte partagé. Aucun secret en fichier de configuration. Toute action est imputable à
une identité nominative — y compris les actions techniques.

---

## 2. Autorisation : politique centralisée, zéro annotation

### Décision

**Toutes les règles d'habilitation vivent dans une seule classe, `SecurityConfig`.** Aucune
annotation (`@PreAuthorize`, `@Secured`, `@RolesAllowed`) n'existe dans le code — une règle de
construction échoue si l'une réapparaît.

Trois raisons :

| Raison | Ce que ça change |
|---|---|
| **Auditabilité** | La matrice des habilitations s'imprime depuis la politique réellement appliquée. Dispersée en annotations, elle se reconstitue à la main — et cette reconstitution est fausse dès la livraison suivante. |
| **Cohérence** | Deux opérations équivalentes finissent toujours par diverger quand leurs règles sont écrites à deux endroits, à six mois d'intervalle, par deux personnes. |
| **Revue** | Un changement d'habilitation apparaît dans le diff d'un seul fichier, qu'on peut exiger de faire relire par le contrôle interne. |

### Le risque que cette centralisation doit neutraliser

Il est réel et il faut le nommer : **une annotation oubliée laisse une méthode ouverte ; une table
centrale incomplète fait exactement la même chose, en moins visible.** La méthode n'apparaît nulle
part, donc personne ne la cherche. Sans compensation, centraliser affaiblit la sécurité au lieu de
la renforcer.

Deux garde-fous répondent à ce risque, et ce sont eux qui rendent l'approche plus sûre que les
annotations :

1. **Le catalogue d'opérations est une énumération.** Un bloc statique refuse de charger
   `SecurityConfig` si une seule valeur n'a pas de règle : **l'application ne démarre pas**. Une
   opération ajoutée sans habilitation ne peut donc pas atteindre la production.
2. **Il n'existe qu'un seul point d'application.** Un cas d'usage ne s'invoque que par
   `UseCaseExecutor`, qui applique la politique avant d'exécuter. Un appel qui l'évite n'est pas un
   contrôle oublié : c'est un cas d'usage inaccessible.

C'est la différence de fond. Une méthode sans annotation reste appelable et s'exécute sans contrôle
— l'oubli est silencieux et se découvre à l'audit, ou après l'incident. Ici, l'oubli possible n'est
pas celui du contrôle mais celui de la **règle**, et cet oubli-là empêche le démarrage.

### Dimensions évaluées

Le RBAC seul est insuffisant en banque : un chargé de clientèle de l'agence A ne doit pas accéder au
portefeuille de l'agence B, alors qu'il a le même rôle.

| Dimension | Exemple | Ordre d'évaluation |
|---|---|---|
| Entité juridique | Cloisonnement filiale | **1** — une tentative transverse est un signal plus grave qu'un défaut de rôle |
| Rôle | `TELLER`, `BRANCH_MANAGER`, `ACCOUNTANT`, `AUDITOR`… | 2 |
| Agence | Périmètre d'affectation de l'agent | 3 |
| Montant | Plafond par rôle | 4 |
| Séparation des tâches | L'auteur ne valide pas | 5 |

> **Implémenté** — le périmètre d'agence est effectif dès que l'objet visé porte une agence, et
> c'est le cas d'usage qui dit laquelle : la caisse pour une opération de guichet, le compte pour
> son ouverture, sa clôture ou son blocage. Une **opération déplacée** — l'objet d'une autre
> agence — n'est admise que si la règle le prévoit (`allowingRemote`), sous son propre plafond
> (`remoteUpTo`), toujours plus bas que l'ordinaire : un guichetier sert un client de passage
> jusqu'à 500 000 XOF, un chef d'agence jusqu'à 5 M ; la lecture d'un solde ou d'un tiers d'une
> autre agence est admise et tracée ; ouverture, clôture, blocage et crédit ne se déplacent pas.
> Row Level Security reste par entité : un périmètre agence en base casserait tout traitement de
> siège. Une règle peut enfin limiter certains rôles à **leurs propres objets** (`ownOnlyFor`) :
> l'objet nomme son titulaire (`AccessTarget.ownedBy`, jamais la requête), et un guichetier
> n'arrête que sa caisse quand le chef d'agence arrête toute caisse de son agence — un objet sans
> titulaire est refusé aux rôles limités.

Le cloisonnement par entité est appliqué **deux fois** : dans la politique et par Row Level Security
PostgreSQL. La seconde barrière protège contre le cas réel le plus fréquent — une requête de
reporting écrite sans le filtre d'entité.

> **Implémenté** — V26 à V34 : une politique `entity_isolation` sur chaque table qui porte une
> entité (`legal_entity`, `account`, `branch`, `accounting_period`, journal, idempotence, tiers,
> contrats de crédit et sûretés, opérations en attente, piste d'audit, commissions, retenues,
> versions de produit, règles de date de valeur), et sur leurs tables filles à travers leur
> parent (`EXISTS`). L'entité courante est lue par `ledger_current_entity()` dans le réglage
> `app.entity_id`, **posé par transaction** (`set_config(…, true)`), jamais par connexion : un
> pool partage ses connexions, un réglage de session fuirait vers la requête suivante. **Sans
> entité posée, le rôle applicatif ne voit aucune ligne** — le défaut est l'absence d'accès.
> La portée est posée par la couche qui connaît l'appelant : l'API pour chaque requête, depuis
> l'entité du jeton (`WebConfiguration`), le moteur de fin de journée pour chaque traitement
> (`TfjEngine`) ; `Database.enterEntity` la transmet à chaque transaction, y compris aux
> transactions indépendantes de la piste d'audit, et **refuse de changer d'entité sous une
> transaction ouverte**. Deux comptes de base sont exigés ([01](01-architecture.md) §5) : le
> propriétaire du schéma, qui migre et que les politiques ne concernent pas, et le rôle applicatif
> `corebanking_app` (`ops/roles.sql`), qui ne possède rien. Les partitions mensuelles, que la
> bascule de journée crée, passent par une fonction `SECURITY DEFINER`. Restent hors politique,
> délibérément : les soldes (`account_balance`), les positions d'intérêts et les clichés
> quotidiens — sur le chemin chaud, et jamais lus sans leur compte, lui-même cloisonné. Pour un
> porteur d'une autre entité, une ressource **n'existe pas** (`404`) : la base ne la montre pas,
> et dire qu'elle existe serait déjà une fuite ; la politique tranche avant (`403`) quand le cas
> d'usage n'a pas à la chercher. Prouvé par `RowLevelSecurityIT` (rôle applicatif réel) et par
> `ApiIT`, qui démarre l'API avec ce rôle et migre avec le propriétaire.
>
> Ce qui reste hors politique est désormais **un inventaire épinglé** : `SchemaInvariantsIT`
> applique toutes les migrations du déploiement — sans tolérance de trou, ce qui vérifie au
> passage qu'aucun module ne manque au classpath — puis dresse la liste des tables sans politique
> et la confronte à cette liste, écrite et justifiée famille par famille : l'outillage du schéma,
> le référentiel partagé entre entités, et les tables tenues par une mère elle-même cloisonnée.
> Une table ajoutée sans politique et sans décision fait échouer la construction, au lieu d'être
> découverte à l'audit.

### Keycloak : ce que le jeton dit, et ce qu'il ne dit pas

**Le jeton dit qui vous êtes et où vous travaillez ; la politique dit ce que vous avez le droit de
faire.**

| Porté par le jeton | Porté par `SecurityConfig` |
|---|---|
| `sub`, `preferred_username` | Rôles autorisés par opération |
| `resource_access.<client-backend>.roles` | Périmètre (agence / entité / groupe) |
| `legal_entity`, `branch` | **Plafonds de montant** |
| | Exigence de double validation |
| | Traçage des consultations |

### Rôles de client, pas rôles de royaume

Les habilitations sont portées par les **rôles du client backend**
(`resource_access.<client>.roles`). Les rôles de royaume (`realm_access.roles`) sont **ignorés**,
sauf ceux explicitement déclarés transverses — une liste courte et revue.

| Raison | Ce qu'un rôle de royaume casse |
|---|---|
| Cloisonnement | Il est visible de toutes les applications du royaume. Un `teller` défini au royaume arrive dans le jeton de l'intranet ; il suffit qu'une autre application l'honore pour qu'une habilitation bancaire fuite hors périmètre. |
| Espace de noms | Les rôles de royaume partagent un espace plat : un `admin` créé pour un autre applicatif entre en collision avec celui du core banking. |
| Gouvernance | Les rôles du client suivent le cycle de vie de l'application — ajouter une opération et son rôle reste confiné à un client, revu avec le code. |
| Audience | Un jeton porte les `resource_access` des clients de son audience. Un rôle de royaume arrive dans un jeton émis pour n'importe quel client. |

**Conséquences sur la configuration Keycloak**, souvent oubliées. Pour que
`resource_access.<backend>.roles` figure dans un jeton émis à un client frontal :

1. un **mapper d'audience** sur le client frontal, ajoutant le backend à `aud` ;
2. les rôles du client backend **assignés à l'utilisateur**, directement ou par groupe ;
3. **Full scope allowed désactivé** sur le client frontal, avec un scope dédié portant les rôles du
   backend — sinon le jeton embarque tous les rôles du porteur sur tous les clients, ce qui grossit
   le jeton et expose la cartographie des habilitations.

Cette séparation n'est pas cosmétique. Porter les plafonds dans le jeton confierait une décision
d'habilitation à la configuration d'un annuaire : un attribut mal renseigné dans Keycloak élèverait
le plafond d'un guichetier sans qu'aucune revue applicative ne le voie, et le plafond effectif
serait introuvable ailleurs que dans un jeton expiré.

Trois refus explicites à l'extraction des revendications :

- **Pas d'entité juridique → jeton rejeté.** Aucune valeur par défaut : un jeton sans entité
  interprété comme un accès global est précisément la faille que ce refus prévient.
- **Rôles d'autres clients ignorés.** `resource_access` peut contenir les rôles détenus sur
  d'autres applications ; les accepter laisserait une habilitation accordée ailleurs ouvrir un
  droit ici.
- **Plafond absent dans la devise concernée → refus.** Un plafond n'est jamais converti : le cours
  introduirait une donnée de marché dans une décision d'habilitation, et un plafond qui varie avec
  le change n'est pas un plafond.

### Création des rôles : catalogue déclaratif, provisionné au démarrage

Les rôles sont décrits dans une ressource du produit, `resources/security/roles.json`, versionnée
avec le code et revue comme lui :

```json
{
  "code": "teller",
  "name": "Guichetier",
  "description": "Operations de caisse et consultation des comptes de son agence.",
  "isSystem": true,
  "category": "RESEAU",
  "attributes": { "reviewFrequencyMonths": "6", "requiresBranch": "true" }
}
```

| Champ | Rôle |
|---|---|
| `code` | Identifiant technique — celui du rôle Keycloak et celui cité par `SecurityConfig` |
| `name` | Libellé métier, affiché dans les écrans d'habilitation |
| `description` | Ce que le rôle permet, en langage métier |
| `isSystem` | Livré avec le produit : créé et tenu à jour à chaque démarrage, sa disparition est une anomalie bloquante. Un rôle non système est amorcé une fois puis laissé à la banque. |
| `category` | Regroupement pour la revue : RÉSEAU, SIÈGE, PARAMÉTRAGE, EXPLOITATION, CONTRÔLE |
| `attributes` | Tout le reste, reporté tel quel en attributs de rôle Keycloak |

**Les attributs portent du descriptif, jamais un droit.** Périodicité de revue, exigence d'un
rattachement d'agence, exclusivité. Un attribut qui accorderait un droit reviendrait à déplacer une
décision d'habilitation hors de la politique.

### Deux sources, deux rôles, une cohérence exigée

Le catalogue est **descriptif** ; `SecurityConfig` reste **normatif** — seule une règle ouvre une
opération. D'où deux écarts possibles, tous deux interdits au démarrage :

| Écart | Conséquence |
|---|---|
| Rôle cité par une règle, absent du catalogue | Jamais provisionné. **L'opération devient inaccessible à tous**, sans aucune erreur. |
| Rôle au catalogue, cité par aucune règle | Attribuable et sans effet : il fait croire à un droit qui n'existe pas. |

`RoleCatalogue.validateAgainstPolicy()` s'exécute **avant** tout appel au fournisseur d'identité :
un catalogue incohérent n'est pas poussé dans le royaume, il empêche de servir.

### Le catalogue couvre le périmètre des services

> **Implémenté.** `Operation` compte désormais une opération par point d'entrée de service qui
> change l'état de la banque : crédit (`LOAN_CONTRACT_CREATE`, `LOAN_DISBURSE`, `LOAN_RESCHEDULE`,
> `LOAN_PREPAY`, `LOAN_REPAYMENT`, `COLLATERAL_MANAGE`, `LOAN_READ`), paramétrage
> (`RISK_PARAMETER_*`, `ACCOUNTING_SCHEMA_*`, `CALENDAR_MANAGE`, `BRANCH_MANAGE`, `TILL_MANAGE`, `TILL_CLOSE`, `FEE_EXEMPTION_GRANT`,
> `ACCOUNT_PRODUCT_ASSIGN`), comptabilité (`JOURNAL_ENTRY_MANUAL`, `PERIOD_CLOSE`,
> paiements (`PAYMENT_ORDER` plafonné par rôle comme un virement, `PAYMENT_PROCESS` pour le
> back-office, `PAYMENT_READ` tracé, `ACCOUNT_LIMIT_MANAGE` à deux dans l'agence), chèques
> (`CHEQUE_BOOK_ISSUE` à deux dans l'agence, `CHEQUE_PAY` plafonné par rôle comme une opération de
> caisse, `CHEQUE_DEPOSIT` au guichet, `CHEQUE_PROCESS` pour le back-office, `CHEQUE_STOP` en
> gestion du compte, `CHEQUE_READ` tracé), prélèvements (`MANDATE_REGISTER` à deux dans l'agence,
> `MANDATE_REVOKE` en gestion du compte, `DIRECT_DEBIT_PRESENT` réservé à la compensation — le
> back-office —, `DIRECT_DEBIT_ISSUE` plafonné par rôle pour les remises des créanciers de la
> banque, `DIRECT_DEBIT_PROCESS` pour le back-office, `DIRECT_DEBIT_READ` tracé ; le mandat
> décide de l'opération, pas l'appelant), ordres permanents (`STANDING_ORDER_REGISTER` à deux
> dans l'agence — il engage des virements que personne ne redemandera —, `STANDING_ORDER_CANCEL`
> en gestion du compte, `STANDING_ORDER_READ` tracé ; l'ordre est celui du client, et il consomme
> ses plafonds), dépôts à terme (`TERM_DEPOSIT_SUBSCRIBE` et `TERM_DEPOSIT_BREAK` à deux dans
> l'agence et plafonnés par rôle sur le capital — placer la ressource engage la banque sur un prix
> et sur une durée, et la rompre défait cet engagement —, `TERM_DEPOSIT_READ` tracé à l'échelle de
> l'entité : la comptabilité et l'audit lisent les engagements de la banque au même titre que
> l'agence qui les a placés), suspens (`SUSPENSE_MANAGE` à deux pour le back-office,
> `SUSPENSE_READ` tracé pour l'exploitation et le contrôle), heures limites des canaux sous
> `CALENDAR_MANAGE`, change (`FX_RATE_QUOTE` à deux — le coteur n'est pas le valideur —,
> `FX_POSITION_MANAGE` à deux pour la comptabilité, `FX_READ` tracé), dossier client
> (`PARTY_DOCUMENT` au guichet — déposer une pièce est un acte d'agence —,
> `PARTY_RELATIONSHIP` à deux pour les relations entre tiers et les bénéficiaires effectifs,
> `KYC_POLICY_MANAGE` à deux pour la conformité seule, `PARTY_FILE_READ` tracé à l'échelle de
> l'entité : le guichetier lit la politique pour savoir quelles pièces réclamer, la conformité et
> l'audit lisent la liste des dossiers incomplets, qu'aucun périmètre d'agence ne doit tronquer),
> origination (`LOAN_APPLICATION` en agence — monter et instruire un dossier est un travail
> d'agence —, `LOAN_APPLICATION_DECIDE` à deux et **plafonné par rôle** : décider est une
> délégation, et la délégation se mesure en francs ; au-delà du plafond du responsable des
> engagements, aucun rôle ne porte la décision, elle relève d'un comité et le refus le dit au lieu
> de laisser passer ; `LOAN_CONDITION_CLEAR` à deux, comme une mainlevée de sûreté : lever une
> condition suspensive libère des fonds ; `LENDING_POLICY_MANAGE` à deux pour la conformité), fin
> de vie du crédit (`LOAN_WRITE_OFF` à deux et **plafonné sur l'encours qui sort** — c'est le même
> argent que le déblocage, dans l'autre sens —, `LOAN_RECOVERY` au guichet, `LOAN_RATE_REVISION`
> à deux : elle change ce que le client doit) ;
> `RESULT_APPROPRIATION` — l'affectation du résultat, à deux ; `STATEMENT_LAYOUT_DRAFT` et
> `STATEMENT_LAYOUT_ACTIVATE` — les maquettes d'états financiers, activées à deux) et
> restitutions (`LEDGER_READ` : balance, grand livre, journal de l'entité, états financiers —
> la comptabilité et l'audit, lecture tracée). Deux rôles
> de crédit les portent : `credit_officer` (chargé de crédit, agence) et `credit_manager`
> (responsable des engagements, siège) — la seconde main sur tout ce qui engage la banque.
>
> Tout ce qui fait sortir de l'argent ou modifie une dette se valide à deux ; le déblocage est
> plafonné par rôle — 50 M XOF pour un chef d'agence, 500 M pour le responsable des engagements —,
> au-delà la décision relève d'un comité ; l'origination porte la même délégation sur la
> décision d'octroi, et le refus nomme le plafond franchi.
>
> Le rattachement point d'entrée → opération est tenu à la main dans `OperationCoverageTest`,
> qui refuse une opération que rien ne réclame et vérifie qu'une règle plafonnée plafonne chacun de
> ses rôles. Les cas d'usage (`UseCase.operation()`) le rendront mécanique avec la couche API.
> Restent hors catalogue, délibérément, la création d'une entité ou d'une devise : des actes de
> déploiement, pas des opérations.

### Provisionnement au démarrage

```
roles.json ──► RoleCatalogue ──► validateAgainstPolicy()   ← échec ⇒ l'application ne démarre pas
                     │
                     ▼
              RoleStartupTask ──► RoleProvisioner ──► Keycloak
                     │
                     ├─ absent du royaume      → création
                     ├─ présent et isSystem    → mise à jour (convergence)
                     └─ présent hors catalogue → signalé, jamais supprimé
```

Trois propriétés :

- **Idempotente.** La rejouer converge sans rien casser — indispensable en montée de version
  progressive, où plusieurs versions cohabitent quelques minutes.
- **Convergente.** Les rôles système sont republiés à chaque démarrage : modifier le fichier suffit
  à propager un libellé ou un attribut, sans intervention dans la console.
- **Jamais destructrice.** Supprimer un rôle dans Keycloak le révoque instantanément à tous ses
  porteurs et perd les affectations. Un rôle disparu du catalogue est signalé ; la décision
  appartient à la sécurité opérationnelle.

L'attribut `operations` est **engendré** à chaque provisionnement depuis la politique : un auditeur
qui ouvre la console lit `teller → ACCOUNT_BALANCE_READ, CASH_OPERATION, PARTY_READ, TRANSFER`,
c'est-à-dire la réalité courante, et non un texte figé dans une description. L'ordre de déclaration
du fichier est préservé, de sorte que le fichier d'import engendré soit reproductible et que le
diff d'une livraison ne montre que ce qui a changé.

**Les rôles ne s'attribuent pas à des personnes.** Un groupe Keycloak par **poste**
(`JobProfile`), l'agent est placé dans un groupe. L'attribution individuelle dérive : au bout de
deux ans plus personne ne sait pourquoi tel agent détient tel rôle, et la revue périodique devient
un inventaire de cas particuliers. Avec des groupes, elle porte sur huit postes au lieu de huit
cents agents.

**Aucun poste décliné par entité ni par agence** — ces dimensions viennent des revendications du
jeton. Les faire porter par le rôle produirait `teller_CI`, `teller_SN`,
`teller_CI_agence_007`.

### L'adaptateur d'administration

`KeycloakAdminProvisioner` parle à l'Admin API avec le client HTTP du JDK — aucune dépendance dans
le chemin d'établissement des habilitations. Une couture `HttpExchange` sépare le transport des
décisions, de sorte que celles-ci se testent sans serveur : elles n'ont rien de réseau.

| Décision | Raison |
|---|---|
| Jeton mis en cache, renouvelé 30 s avant terme | Sans cache, provisionner 8 rôles coûterait ~10 authentifications par démarrage, sur chaque instance |
| Identifiant interne du client résolu une fois | Les routes de rôles emploient l'UUID interne, jamais le `clientId` lisible |
| Reprise sur 5xx et 429 uniquement | Dans un orchestrateur, l'application démarre souvent avant que le fournisseur d'identité ne réponde |
| **401/403 jamais rejoué** | Rejouer ne corrigera pas un secret erroné, et la répétition peut verrouiller le compte de service — une erreur de configuration deviendrait une indisponibilité |
| **409 à la création = succès** | Deux instances qui démarrent ensemble tentent la même création ; le conflit est le résultat recherché |
| HTTPS imposé à la construction | Le secret du compte de service et les habilitations transitent par ce canal |
| Secret fourni par `Supplier`, masqué dans `toString()` | Il vient d'un coffre et peut tourner sans redémarrage ; un secret dans une trace d'erreur finit indexé dans un outil de supervision |
| Le corps des requêtes n'est jamais journalisé | Celui de la demande de jeton contient le secret |

**Un échec de provisionnement fait échouer le démarrage.** Une application qui sert alors que ses
rôles n'existent pas refuse toutes les opérations tout en paraissant saine — une instance verte et
inutilisable est pire qu'une instance qui ne démarre pas avec un motif.

**Le provisionnement ne contient ni utilisateur ni affectation.** Rattacher un agent à un groupe
relève de la sécurité opérationnelle, avec double validation et revue périodique. Le faire passer
par un pipeline de livraison sortirait une décision d'habilitation nominative du champ du contrôle
interne.

### Ségrégation : le seul cumul réellement interdit

La liste des cumuls interdits est **courte, et c'est voulu**. Un chef d'agence tient une caisse ;
interdire ce cumul rendrait la plupart des agences inexploitables. La règle du valideur distinct de
l'auteur couvre déjà le risque, et mieux : elle agit au niveau de l'**opération** et non de
l'identité — elle n'empêche pas de travailler, elle empêche de se contrôler soi-même.

Reste un cas qu'aucun contrôle par opération ne peut détecter : **l'auditeur qui opère**. Le
problème n'est pas qu'il valide sa propre écriture, c'est qu'il contrôle a posteriori un périmètre
dont il fait partie. Chacune de ses actions, prise isolément, est régulière.

L'exclusivité est **déclarative** : l'attribut `exclusive` du catalogue produit la règle de
ségrégation. Déclarer un nouveau profil exclusif reste donc du paramétrage. Le cumul `auditor` +
tout autre rôle fait **rejeter le jeton en bloc**, pas seulement les opérations conflictuelles. Un accès partiel rendrait l'anomalie invisible et durable ; le refus
total se corrige en retirant l'agent d'un groupe — à condition que quelqu'un s'en aperçoive.

### Traçabilité des décisions

Tous les refus, sans exception. Et les accès **réussis** aux opérations déclarées sensibles en
lecture — consultation de solde, de journal, de dossier client.

Ce second point est le plus important : un journal limité aux modifications ne voit pas l'agent
habilité qui consulte sans motif les comptes d'un tiers, alors que c'est la forme de fraude interne
la plus courante.

Deux choix d'implémentation à expliciter :

- **La trace est écrite dans sa propre transaction.** Un refus survient avant l'opération et la
  transaction métier est annulée ; si la trace la partageait, elle disparaîtrait avec elle — le
  système n'aurait aucune mémoire des tentatives refusées.
- **Un échec d'écriture de la trace interrompt l'opération.** C'est le contraire de l'usage courant
  en journalisation applicative. Une opération bancaire qui s'exécute sans pouvoir être tracée est
  une opération dont personne ne pourra rendre compte.

## 3. Maker-checker

```
Saisie (maker) → EN_ATTENTE → Validation (checker) → EXÉCUTÉ
                      │
                      └──► REJETÉ (motif obligatoire)
```

### Opérations soumises

| Catégorie | Exemples |
|---|---|
| Référentiel | Création/modification client, changement de coordonnées bancaires |
| Comptes | Ouverture, clôture, blocage, levée de blocage |
| Crédit | Octroi, déblocage, rééchelonnement, abandon de créance |
| Opérations | Au-delà du plafond du rôle, opérations de caisse importantes |
| Corrections | Contre-passation, forçage, opération antidatée |
| Paramétrage | Taux, barèmes, schémas comptables, plafonds |
| Exploitation | Annulation d'arrêté, réouverture de période comptable |

### Règles

- Le maker ne peut jamais être le checker (contrainte applicative **et** contrainte de base).
- Le nombre de valideurs et le niveau requis dépendent du montant (circuits à 2 ou 3 yeux).
- Une opération en attente **réserve le disponible** : sinon le solde peut devenir
  insuffisant entre la saisie et la validation.
- Un délai d'expiration purge automatiquement les opérations non validées.
- Le rejet exige un motif ; il est notifié au maker.

> **Implémenté** — `pending_operation` et `operation_approval` (V25), déclencheur de ségrégation
> en base ; dans l'API, `MakerChecker` : soumission par un maker habilité (`202`), approbation ou
> rejet motivé par un checker habilité pour la même opération et la même cible et qui n'est pas le
> maker (`AccessRule.dualControl` + `AccessTarget.madeBy`), exécution à l'approbation avec le
> maker pour auteur et le checker pour approbateur — les deux sujets de jeton, jamais un champ de
> requête ; expiration ; une décision est immuable. Soumis aujourd'hui : ouverture, clôture,
> blocage et levée, blocage de montant et levée, vérification de la connaissance client. Non fait :
> circuits à trois yeux par montant, réservation du disponible, notification du maker.

---

## 4. Piste d'audit

Deux journaux distincts, aux finalités différentes :

| Journal | Contenu | Rétention |
|---|---|---|
| Audit métier | Qui a fait quoi, sur quelle donnée, valeur avant/après, motif, canal, IP | 10 ans |
| Audit technique | Authentification, autorisation refusée, accès aux données sensibles, export | 5 ans |

### Exigences

- **Immuable** : table en `INSERT ONLY`, droits restreints, aucun `UPDATE`/`DELETE`.
- **Exhaustif sur les données sensibles** : toute consultation d'un compte est tracée, pas
  seulement les modifications. C'est ce qui permet de détecter la consultation abusive par
  un agent — le cas de fraude interne le plus courant.
- **Exportable** : format standard pour l'audit interne, l'inspection et le régulateur.
- **Corrélé** : un identifiant de corrélation relie une action utilisateur à ses écritures,
  ses événements et ses appels externes.

---

## 5. Protection des données

| Mesure | Mise en œuvre |
|---|---|
| Chiffrement au repos | Chiffrement du volume + `pgcrypto` sur les colonnes très sensibles |
| Chiffrement en transit | TLS 1.3 partout, mTLS entre services internes |
| Tokenisation | Numéros de carte, pièces d'identité, remplacés par des jetons |
| Masquage | Restitution partielle selon le rôle (`****4412`) |
| Environnements hors production | Anonymisation irréversible, jamais de copie brute de production |
| Rétention | Politique par catégorie, purge automatisée et tracée |
| Droits des personnes | Accès, rectification, portabilité, effacement dans les limites légales bancaires |

Le point sensible en pratique : les environnements de recette. Une copie de production non
anonymisée y est la fuite de données la plus fréquente du secteur.

---

## 6. Conformité intégrée

### LCB-FT

| Dispositif | Fonctionnement |
|---|---|
| Filtrage | Sanctions, PPE, listes internes — à la création, périodiquement, et sur transaction |
| Scénarios de surveillance | Seuils, fractionnement, atypismes, cohérence avec le profil déclaré |
| Profil de risque client | Recalculé à chaque événement significatif, pilote le niveau de diligence |
| Alertes | File de traitement, investigation, décision, clôture motivée |
| Déclaration de soupçon | Dossier constitué, transmission à la cellule de renseignement financier |
| Conservation | Pièces et justificatifs conservés selon la durée légale |

Les scénarios de surveillance sont **paramétrés**, pas codés : seuils, fenêtres temporelles
et populations concernées évoluent avec la réglementation et les typologies locales.

**Implémenté — module `compliance` (V53).** Ce qui est du code est la *façon de compter* ; tout le
reste est du paramétrage.

| Objet | Ce qu'il porte |
|---|---|
| `monitoring_scenario` | Code, libellé, méthode, seuil, fenêtre, nombre minimal, ratio, population visée (notation de risque), validité, deux signatures |
| `party_activity_profile` | Flux mensuels attendus au crédit et au débit, déclarés au guichet — la référence de l'atypie |
| `aml_alert` | Origine (`SCREENING` / `MONITORING`), scénario, date, détail, montant, statut, affectation, clôture motivée, déclaration, arrêté d'origine |
| `aml_alert_item` | Les écritures qui ont déclenché l'alerte — ses pièces |
| `suspicious_activity_report` | Référence, exposé des faits, deux signatures, date et référence de transmission |

Quatre méthodes sont codées, et en ajouter une est une livraison — elle change ce que la banque
sait regarder :

| Méthode | Ce qu'elle compte |
|---|---|
| `CASH_THRESHOLD` | Espèces cumulées au-delà d'un montant sur une fenêtre, dans les deux sens |
| `STRUCTURING` | Opérations **chacune sous le seuil**, assez nombreuses, dont la somme le franchit |
| `ATYPICAL_ACTIVITY` | Flux hors de proportion avec le profil déclaré, selon un ratio |
| `DORMANT_REACTIVATION` | Un compte dormant qui se remet à bouger au-delà d'un montant |

Trois règles fixent la portée du dispositif :

- **Seul le filtrage bloque.** Opérer avec une personne listée est l'infraction elle-même : le
  dossier est créé bloqué, en attente de levée de doute, et une alerte d'origine `SCREENING` ouvre
  la file d'instruction. La surveillance, elle, ne bloque rien — un compteur statistique ne prouve
  rien, et priver un client de son argent sur une présomption n'est pas défendable.
- **Le même fait ne se réclame pas deux fois.** Une alerte déjà levée sur la fenêtre, ouverte ou
  classée, suffit ; sinon un scénario sur trente jours lèverait trente alertes pour un fait.
- **La déclaration scelle les alertes qu'elle cite.** Elles passent à `REPORTED` et n'en ressortent
  pas : leur sort est fixé par la déclaration, pas par un classement.

L'habilitation dit le secret : `AML_READ` n'est ouvert qu'au responsable des risques et à
l'auditeur, en lecture tracée. Ni le guichet ni la gestion de portefeuille n'accèdent aux alertes
et aux déclarations — informer la personne surveillée est un délit. Le périmètre est l'entité,
jamais l'agence : tronquée par l'agence, la surveillance ne verrait pas le client qui répartit ses
versements sur trois guichets.

| Opération | Portée | À deux | Rôles |
|---|---|---|---|
| `AML_SCENARIO_MANAGE` | Entité | oui | Responsable des risques |
| `AML_PROFILE_DECLARE` | Agence | non | Chargé de clientèle, chef d'agence, responsable des risques |
| `AML_ALERT_REVIEW` | Entité | non | Responsable des risques |
| `AML_REPORT` | Entité | oui | Responsable des risques |
| `AML_READ` | Entité, tracée | non | Responsable des risques, auditeur |

Reste à faire : le connecteur vers un fournisseur de listes (l'interface `Screening` l'attend), le
rescan périodique du portefeuille, et le format de transmission de la cellule nationale.

### Prudentiel et réglementaire

Le moteur de reporting s'appuie sur le profil réglementaire de l'entité
([03](03-referentiel-parametrage.md#7-profil-réglementaire-par-pays)) : classification des créances, provisionnement,
pondération des risques, ratios, états déclaratifs.

Chaque état produit est **archivé avec son jeu de données source et son paramétrage**. Un
état régénéré six mois plus tard doit être identique à l'original : c'est une exigence de
contrôle, et la principale difficulté rencontrée lors des inspections.

### Fiscalité

Retenues à la source sur intérêts, TVA sur commissions, taxes locales sur opérations,
déclarations périodiques. Toutes paramétrées par entité, historisées par période de validité
et intégrées aux schémas comptables — jamais calculées a posteriori.

---

## 7. Continuité d'activité

| Exigence | Cible | Mise en œuvre |
|---|---|---|
| RPO | 0 | Réplication synchrone PostgreSQL sur site secondaire |
| RTO | 15 min | Bascule automatisée (Patroni), testée trimestriellement |
| Sauvegarde | Quotidienne complète + WAL continu | Restauration testée mensuellement |
| Archivage | 10 ans | Stockage objet immuable (verrouillage en écriture) |
| Mode dégradé | Opérations de caisse | Procédure hors ligne avec régularisation tracée |

**Une sauvegarde non testée n'est pas une sauvegarde.** La restauration complète est
exécutée et chronométrée chaque mois, sur un environnement dédié, avec contrôle
d'intégrité comptable après restauration.
