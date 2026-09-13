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

Le cloisonnement par entité est appliqué **deux fois** : dans la politique et par Row Level Security
PostgreSQL. La seconde barrière protège contre le cas réel le plus fréquent — une requête de
reporting écrite sans le filtre d'entité.

### Keycloak : ce que le jeton dit, et ce qu'il ne dit pas

**Le jeton dit qui vous êtes et où vous travaillez ; la politique dit ce que vous avez le droit de
faire.**

| Porté par le jeton | Porté par `SecurityConfig` |
|---|---|
| `sub`, `preferred_username` | Rôles autorisés par opération |
| `realm_access.roles` | Périmètre (agence / entité / groupe) |
| `legal_entity`, `branch` | **Plafonds de montant** |
| | Exigence de double validation |
| | Traçage des consultations |

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
