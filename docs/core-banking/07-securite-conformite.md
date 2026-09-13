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

## 2. Autorisation : RBAC + ABAC

Le RBAC seul est insuffisant en banque : un chargé de clientèle de l'agence A ne doit pas
accéder au portefeuille de l'agence B, alors qu'il a le même rôle.

```java
@PreAuthorize("hasPermission(#accountId, 'Account', 'READ')")
public AccountView getAccount(AccountId accountId) { ... }
```

Le contrôle combine :

| Dimension | Exemple |
|---|---|
| Rôle | `TELLER`, `BRANCH_MANAGER`, `CREDIT_OFFICER`, `ACCOUNTANT`, `AUDITOR` |
| Entité juridique | Cloisonnement filiale, appliqué aussi en Row Level Security |
| Agence / portefeuille | Périmètre d'affectation de l'agent |
| Montant | Plafond d'opération par rôle et par grade |
| Segment client | Accès restreint aux comptes sensibles (dirigeants, PPE) |
| Horaire | Opérations de caisse limitées aux heures d'ouverture |

### Défense en profondeur

Le cloisonnement par entité est appliqué **deux fois** : dans l'applicatif et par Row Level
Security PostgreSQL. La seconde barrière protège contre le cas réel le plus fréquent — une
requête de reporting écrite sans le filtre d'entité.

---

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
