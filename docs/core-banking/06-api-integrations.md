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
