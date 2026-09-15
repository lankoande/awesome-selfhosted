# 06 — API & intégrations

---

## 1. API REST interne

### Conventions

| Sujet | Règle |
|---|---|
| Versionnage | Par URI : `/api/v1/...`. Une version majeure reste supportée 24 mois. |
| Idempotence | En-tête `Idempotency-Key` **obligatoire** sur toute méthode non idempotente. |
| Montants | Objet `{ "amount": "1234.56", "currency": "XOF" }` — chaîne, jamais nombre flottant JSON. |
| Dates | `LocalDate` ISO pour les dates métier, `Instant` UTC pour les horodatages techniques. |
| Pagination | Par curseur (`cursor`, `limit`). Pas d'`offset` : il dérive sur des données mouvantes. |
| Erreurs | RFC 7807 (`application/problem+json`) avec un code métier stable. |
| Contexte | `legal_entity_id` déduit du jeton, jamais du corps de la requête. |
| Contrat | OpenAPI généré, publié, et vérifié en CI contre la version précédente. |

### Pourquoi les montants en chaîne

`{"amount": 1234.56}` est parsé en `double` par la plupart des clients JSON, ce qui
réintroduit l'imprécision binaire exactement là où on l'avait éliminée côté serveur. La
chaîne est la seule représentation qui traverse les couches sans perte.

### Exemple

```http
POST /api/v1/accounts/{accountId}/transactions
Idempotency-Key: 7f3a9c11-4e2b-4a7d-9f01-2c8e5b6a1d43
Content-Type: application/json

{
  "type": "DEPOSIT",
  "amount":    { "amount": "250000.00", "currency": "XOF" },
  "valueDate": "2026-09-13",
  "channel":   "BRANCH",
  "narrative": "Versement espèces guichet 12",
  "metadata":  { "tellerId": "T-0442", "branchId": "AG-007" }
}
```

```http
201 Created
{
  "transactionId": "TX-2026-0913-004412",
  "status": "POSTED",
  "bookingDate": "2026-09-13",
  "valueDate":   "2026-09-13",
  "balanceAfter": { "amount": "1750000.00", "currency": "XOF" },
  "journalEntries": ["JE-2026-0913-118304"]
}
```

Un rejeu avec la même `Idempotency-Key` renvoie `200 OK` et le même corps.

### Implémenté — module `api`

Le contrat validé avant construction, tenu par chaque méthode de contrôleur :

```java
Receipt withdraw(Caller caller, UUID legalEntityId, UUID accountId, IdempotencyKey key,
                 WithdrawalRequest body)
```

- **`Caller` vient du jeton**, jamais d'un paramètre : un résolveur d'argument le dérive des
  revendications (`sub`, `preferred_username`, `legal_entity`, `branch`,
  `resource_access.<client>.roles`) par `KeycloakCallerFactory`. L'auteur d'une écriture est le
  sujet du jeton ; l'agence d'un compte ouvert est celle de l'appelant.
- **`IdempotencyKey` vient de l'en-tête** `Idempotency-Key`, obligatoire sur toute opération ;
  absent : `400`. Un rejeu répond `200` avec le premier reçu (`replayed: true`), une opération
  nouvelle `201`.
- **Chaque point d'entrée est un cas d'usage** (`UseCase`) qui déclare son opération du catalogue
  et nomme sa cible — l'agence de la caisse pour une opération de guichet, celle du compte pour
  un acte de gestion, l'indicateur d'opération déplacée quand le client relève d'une autre
  agence — et **ne vérifie rien** : `UseCaseExecutor` applique `SecurityConfig`, seul point de
  contrôle. Le contrôleur ne connaît aucune règle.
- **Les refus sont des réponses** (`application/problem+json`, RFC 9457) : `401` sans jeton
  valide, `403` habilitation refusée ou jeton insuffisant, `404` compte ou traitement inconnu,
  `409` conflit d'état (compte bloqué, disponible insuffisant, tiers non opérable, clôture
  refusée, TFJ refusé, doublon de tiers), `422` requête que le socle ne peut pas honorer
  (devise, montant, condition de date de valeur absente, paramétrage), `500` seulement pour ce qui
  n'est pas prévu — et alors rien n'a été comptabilisé.
- **Les montants** sortent en `{ "amount": "20000", "currency": "XOF" }` et entrent de même ;
  une devise qui n'est pas celle du compte est un refus, jamais une conversion.

| Méthode et chemin (`/v1/entities/{entityId}` en préfixe) | Opération | Corps |
|---|---|---|
| `POST /parties` | `PARTY_CREATE` | référence, nature, nom, identifiants |
| `POST /parties/{id}/kyc-verifications` | `KYC_VERIFY` | niveau de risque, date — **202**, en attente d'un checker |
| `GET /parties/{id}` | `PARTY_READ` | — |
| `POST /accounts` | `ACCOUNT_OPEN` | numéro, titulaire, produit, devise — **202**, en attente d'un checker |
| `GET /accounts/{id}/balance` | `ACCOUNT_BALANCE_READ` | — ; déplacée si le compte est d'une autre agence |
| `POST /accounts/{id}/deposits`, `/withdrawals` | `CASH_OPERATION` | montant, caisse, canal ; `Idempotency-Key` |
| `POST /transfers` | `TRANSFER` | émetteur, bénéficiaire, montant ; `Idempotency-Key` |
| `POST /accounts/{id}/blocks`, `.../{blockId}/lift` | `ACCOUNT_BLOCK` | nature, motif — **202** |
| `POST /accounts/{id}/holds`, `.../{holdId}/release` | `ACCOUNT_HOLD` | montant, nature, échéance — **202** |
| `POST /accounts/{id}/closure` | `ACCOUNT_CLOSE` | compte de reversement — **202** |
| `GET /pending-operations`, `GET .../{id}` | celle de l'opération en attente | — |
| `POST /pending-operations/{id}/approve`, `.../reject` | celle de l'opération en attente, en tant que checker | motif pour un rejet |
| `POST /eod/runs`, `GET /eod/runs/{id}`, `POST .../resume` | `TFJ_RUN` | journée, mode |
| `POST /eod/runs/{id}/cancel` | `TFJ_CANCEL` | date de contre-passation, motif |
| `POST /loans` | `LOAN_CONTRACT_CREATE` | référence, produit, devise, compte de prêt, compte de règlement, capital, date de déblocage, client |
| `POST /loans/{id}/disbursement` | `LOAN_DISBURSE` | conditions (taux, périodicité, échéances, différé, première échéance, méthode, base, frais) — **202**, plafond sur le capital |
| `POST /loans/{id}/repayments` | `LOAN_REPAYMENT` | montant, date de valeur ; `Idempotency-Key` ; règlement manuel, l'excédent non affecté est rendu |
| `POST /loans/{id}/prepayments` | `LOAN_PREPAY` | montant, mode (durée ou échéance) ; `Idempotency-Key` — **202**, l'échéancier refait s'approuve à deux |
| `GET /loans/{id}` | `LOAN_READ` | — : contrat, conditions, échéancier en vigueur, créances ouvertes, jours de retard |
| `POST /products` | `PRODUCT_DRAFT` | code, famille, libellé, devise, validité, paramètres, barème |
| `POST /products/{versionId}/activation` | `PRODUCT_ACTIVATE` | — **202**, jamais approuvée par le rédacteur de la version |
| `POST /calendar/value-date-rules` | `CALENDAR_MANAGE` | type d'opération, canal, sens, décalage, unité, convention, validité — **202** |
| `POST /calendar/holidays` | `CALENDAR_MANAGE` | date, libellé — **202** ; l'arrêté du soir relit le calendrier |
| `POST /branches` | `BRANCH_MANAGE` | code, nom, nature, rattachement, ouverture, comptes de liaison par devise — **202** |

**Double validation.** Une opération que la politique soumet à un second regard n'est jamais
exécutée par celui qui la saisit : la requête est gardée telle que reçue (`pending_operation`),
le maker reçoit `202` et un identifiant ; un checker habilité pour la même opération et la même
cible, qui n'est pas le maker — la politique le refuse, et la base aussi — l'approuve, et c'est
alors qu'elle s'exécute, avec le maker pour auteur et le checker pour approbateur, tous deux
sujets de leur jeton. À l'approbation la requête est rejouée contre l'état du moment : un compte
fermé entre-temps la refuse comme au guichet. Un rejet se motive ; une opération non décidée
expire (48 h par défaut). Aucun approbateur ne figure jamais dans un corps de requête.

**Crédit.** Le contrat se crée dans l'agence de son compte de prêt et se rattache à son client.
Le déblocage porte les conditions proposées par le maker ; ce qui n'est pas dit prend la valeur
par défaut des conditions de crédit (mensuel, annuité constante, ACT/365, sans assurance ni
taxe) ; l'échéancier est généré à l'approbation, le taux effectif confronté au plafond d'usure
(`422` au-delà). Un règlement s'impute sur les créances ouvertes, la plus ancienne d'abord ;
sans créance, rien n'est comptabilisé et le montant est rendu non affecté. Un remboursement
anticipé est refusé tant qu'un impayé subsiste (`409`) ; il publie un nouvel échéancier, et un
échéancier s'approuve à deux — la base l'exige, la politique aussi.

**Paramétrage et réseau.** Une version de produit se rédige seul et s'active à deux : le checker
n'est jamais le rédacteur de la version (`409` s'il tente). Règles de date de valeur, jours
fériés et agences suivent le même circuit ; le moteur d'arrêté relit le calendrier à chaque
lancement, un férié déclaré dans la journée vaut pour le soir même.

Ce qui n'est pas encore exposé : le rééchelonnement, les sûretés, les grilles de risque et les
schémas comptables (les services existent), pas de contrat OpenAPI publié ni de pagination
(aucune liste longue n'est exposée). La réservation du disponible par une opération en attente
qui déplacerait des fonds n'existe pas encore : un déblocage approuvé crédite le compte de
règlement à l'approbation, sans réservation préalable.

Le test `ApiIT` fait tout le parcours contre un vrai serveur, une vraie base et de vrais jetons
signés, l'API connectée avec le rôle applicatif : du tiers au retrait, les refus un par un,
l'arrêté lancé par l'exploitant, le crédit du produit au remboursement anticipé, les conditions
de banque et une agence créées à deux.

---

## 2. ISO 20022

Format pivot des échanges interbancaires. Supporté nativement, pas via une passerelle de
conversion.

| Message | Sens | Usage |
|---|---|---|
| `pain.001` | entrant | Ordre de virement client |
| `pain.002` | sortant | Statut de traitement |
| `pacs.008` | bidir. | Virement interbancaire |
| `pacs.004` | bidir. | Retour de paiement |
| `pacs.002` | bidir. | Statut interbancaire |
| `camt.052` | entrant | Relevé intraday |
| `camt.053` | entrant | Relevé de fin de journée (rapprochement nostro) |
| `camt.054` | sortant | Avis de débit/crédit |
| `camt.056` | bidir. | Demande de rappel |

**Décision** : ISO 20022 est le format de référence **en interne**. Les formats hérités
(MT SWIFT, fichiers de compensation nationaux, formats propriétaires de mobile money) sont
convertis en périphérie, dans des adaptateurs dédiés. Le cœur ne connaît qu'un seul modèle
de message.

Ce choix évite la dérive classique : un cœur qui accumule les particularités de chaque
canal devient impossible à faire évoluer.

---

## 3. Événements métier

### Publication transactionnelle (outbox)

Publier sur Kafka pendant une transaction de base est impossible à rendre atomique.
L'événement est donc inséré en base, dans la même transaction que l'écriture, puis relayé.

```java
@Transactional
public PostingResult post(PostingCommand command) {
    var entry  = ledger.record(command);          // écriture
    var result = balances.apply(entry);           // soldes
    outbox.append(new TransactionPosted(entry));  // même transaction
    return result;
}
// Un relais lit l'outbox et publie sur Kafka, avec au-moins-une-fois.
// Les consommateurs sont idempotents.
```

Sans outbox, deux défaillances sont possibles et se produiront : une écriture sans
événement (le consommateur ne voit jamais l'opération), un événement sans écriture (le
consommateur agit sur une opération inexistante).

### Catalogue d'événements

```
party.created            party.kyc.updated           party.screening.hit
account.opened           account.closed              account.blocked
transaction.posted       transaction.reversed        transaction.rejected
loan.disbursed           loan.repaid                 loan.overdue
loan.classified          loan.provisioned
payment.received         payment.executed            payment.returned
eod.started              eod.completed               eod.failed
```

Chaque événement porte : identifiant, type, version de schéma, horodatage, entité juridique,
corrélation, et charge utile. Schémas en Avro, compatibilité ascendante vérifiée par un
registre de schémas.

---

## 4. Intégrations externes

| Système | Protocole | Mode | Criticité |
|---|---|---|---|
| Banque centrale (RTGS, compensation) | ISO 20022 / fichiers | Synchrone + fichiers | Critique |
| SWIFT | MT / MX via passerelle | Asynchrone | Critique |
| Monétique (switch cartes) | ISO 8583 | Synchrone, temps réel | Critique |
| Mobile money | REST propriétaire | Synchrone + réconciliation | Critique |
| Centrale des risques / bureau de crédit | REST / fichiers | À la demande + périodique | Importante |
| Filtrage sanctions | REST | Synchrone | Critique |
| Signature électronique | REST | Synchrone | Importante |
| GED | REST / S3 | Asynchrone | Importante |
| Comptabilité groupe | Fichiers / API | Batch | Importante |
| Datamart / BI | CDC (Debezium) | Flux | Standard |

### Règles de résilience

- **Timeout court et explicite** sur toute intégration synchrone. Jamais de timeout par
  défaut : un appel bloqué sur le chemin de comptabilisation fige la caisse.
- **Coupe-circuit** : un système externe défaillant bascule le canal en mode dégradé
  documenté, il ne fait pas tomber le cœur.
- **Réconciliation systématique** : toute intégration financière (monétique, mobile money,
  compensation) s'accompagne d'un rapprochement quotidien automatique, avec compte de
  suspens et suivi des écarts par ancienneté.
- **Aucun appel externe dans un batch compte par compte** : les enrichissements externes
  sont préchargés en amont de l'étape.

---

## 5. Canaux

```
                    ┌─────────────────────────────┐
                    │      API Gateway            │
                    │  authn · quotas · idempot.  │
                    └──────────────┬──────────────┘
      ┌────────────┬───────────────┼───────────────┬────────────┐
      │            │               │               │            │
 ┌────▼────┐ ┌─────▼─────┐  ┌──────▼─────┐  ┌──────▼────┐ ┌─────▼────┐
 │ Agence  │ │ Banque en │  │  Mobile    │  │ API       │ │   GAB    │
 │ (back-  │ │  ligne    │  │  banking   │  │ partenaire│ │  / TPE   │
 │ office) │ │           │  │            │  │ (open bk) │ │          │
 └─────────┘ └───────────┘  └────────────┘  └───────────┘ └──────────┘
```

Chaque canal a ses propres plafonds, son authentification, son heure de cut-off et son
schéma de commissions. Ces éléments sont du **paramétrage par canal**, pas des constantes
applicatives.

Le back-office agence est le canal le plus exigeant fonctionnellement : opérations de caisse,
double validation, correction d'opération du jour, arrêté de caisse, gestion des valeurs.
C'est aussi celui qui est le plus souvent sous-estimé dans les plannings de projet.
