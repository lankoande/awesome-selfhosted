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
| Pagination | Par pages bornées à ordre total (`page`, `size`) pour les écrans ; par curseur (`after`, `size`) pour les extractions — le grand livre et le journal. |
| Erreurs | RFC 7807 (`application/problem+json`) avec un code métier stable. |
| Contexte | `legal_entity_id` déduit du jeton, jamais du corps de la requête. |
| Contrat | OpenAPI 3.1 **généré depuis les contrôleurs**, versé avec le code (`openapi/openapi.json`), publié par le service (`GET /v1/openapi.json`, sans jeton, hors enveloppe) et vérifié par un test contre la génération : une route qui change sans le contrat est un test rouge. |

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
- **Une seule enveloppe, `ApiResponse`**, sur toute réponse — succès, refus, et jusqu'aux
  `401`/`403` prononcés par la chaîne de sécurité avant tout contrôleur :
  `{ "data": …, "page": …, "error": …, "meta": { "timestamp", "requestId" } }`. `data` porte
  la donnée ou les éléments de la page ; `page` les bornes (`number`, `size`, `totalElements`,
  `totalPages`, `hasNext`, `hasPrevious`) quand la donnée est une liste ; `error` le refus, aux
  champs de RFC 9457 (`type`, `title`, `status`, `detail`, `instance`) ; `meta.requestId`
  reprend l'en-tête `X-Request-Id` du client s'il est sain, sinon en attribue un — il est aussi
  renvoyé en en-tête, et c'est par lui qu'un incident se retrouve dans les journaux. Un
  contrôleur rend sa donnée, jamais l'enveloppe : elle est posée par un `ResponseBodyAdvice`,
  elle ne peut donc pas manquer.
- **Les listes sont paginées**, toutes : `page` (à partir de 0) et `size` (50 par défaut,
  **200 au plus** — au-delà, `400`, jamais un plafond appliqué en silence), lues dans un **ordre
  total** (date comptable, instant de connaissance, écriture, ligne pour un relevé ; référence
  pour les contrats ; nom puis référence pour les tiers) : deux pages successives ne montrent ni
  deux fois la même ligne ni aucune. Pagination par décalage avec décompte, ce qu'attend un
  écran.
- **Les extractions se lisent par curseur** : le grand livre d'un compte
  (`GET /accounts/{id}/ledger`) et le journal de l'entité (`GET /ledger/journal`) prennent
  `after` — le `page.nextCursor` de la page précédente, opaque, rendu tel quel — et `size` ;
  `page` porte alors `nextCursor`, `hasNext`, `hasPrevious`, ni numéro ni décompte. La page
  suivante reprend strictement après une position (date comptable, instant de connaissance,
  écriture, ligne), quatre colonnes de la ligne portées par un index dans cet ordre (V37) : le
  coût d'une page ne dépend pas de ce qui la précède, quoi qu'il ait été comptabilisé
  entre-temps. Un curseur qui ne se relit pas est une requête invalide (`400`), pas une page
  vide.
- **La balance** (`GET /ledger/trial-balance`) est lue dans le journal, à six colonnes par
  compte — solde d'ouverture, mouvements, solde de clôture, chacun au débit ou au crédit —,
  sur une plage de dates comptables (le mois de la date comptable par défaut) ; `kind=CUSTOMER`
  en fait la balance auxiliaire des clients, `branchId` la balance d'agence. Ses totaux
  (`GET /ledger/trial-balance/totals`), une ligne par devise, portent le constat d'équilibre
  colonne à colonne — vrai par construction sur la balance entière, et faux, sans le cacher, sur
  une balance filtrée.
- **Les refus sont des réponses** (`error` dans l'enveloppe, champs de RFC 9457) : `400` requête
  invalide (page hors bornes, paramètre mal formé, clé d'idempotence absente, corps illisible),
  `401` sans jeton valide, `403` habilitation refusée ou jeton insuffisant, `404` compte,
  traitement, exercice ou chemin inconnu, `405`/`415` méthode ou type de contenu non admis,
  `409` conflit d'état (compte bloqué, disponible insuffisant, tiers non opérable, clôture
  refusée, TFJ refusé, doublon de tiers, aucune maquette d'état active à la date), `422` requête que le socle ne peut pas honorer
  (devise, montant, condition de date de valeur absente, paramétrage), `500` seulement pour ce qui
  n'est pas prévu — et alors rien n'a été comptabilisé.
- **Les montants** sortent en `{ "amount": "20000", "currency": "XOF" }` et entrent de même ;
  une devise qui n'est pas celle du compte est un refus, jamais une conversion.
- **Le contrat est généré, pas écrit** : `OpenApiDocument` lit les contrôleurs par réflexion —
  chemins, méthodes, variables de chemin, paramètres de requête, pagination par pages ou par
  curseur, en-têtes `Idempotency-Key` et `X-Request-Id`, corps, statut de succès (`200`, `201`,
  `202` à double validation, `200` de rejeu), refus de `400` à `500` — et projette chaque type
  en schéma : enregistrements en composants, énumérations, `Money` en chaîne et devise,
  enveloppe avec `page` quand la donnée est une liste. Le document est versé dans
  `openapi/openapi.json` et servi tel quel à `GET /v1/openapi.json` ; `OpenApiContractTest` le
  régénère et le compare, en nommant les routes et schémas ajoutés, retirés ou modifiés — une
  évolution voulue se reprend avec `-Dopenapi.update=true`, et se voit dans la revue.

| Méthode et chemin (`/v1/entities/{entityId}` en préfixe) | Opération | Corps |
|---|---|---|
| `POST /parties` | `PARTY_CREATE` | référence, nature, nom, identifiants |
| `POST /parties/{id}/kyc-verifications` | `KYC_VERIFY` | niveau de risque, date — **202**, en attente d'un checker |
| `GET /parties/{id}` | `PARTY_READ` | — |
| `GET /parties?q=&page=&size=` | `PARTY_READ` | recherche par fragment de référence ou de nom, paginée |
| `POST /accounts` | `ACCOUNT_OPEN` | numéro, titulaire, produit, devise — **202**, en attente d'un checker |
| `GET /accounts/{id}/balance` | `ACCOUNT_BALANCE_READ` | — ; déplacée si le compte est d'une autre agence |
| `GET /accounts/{id}/journal?from=&to=&page=&size=` | `ACCOUNT_JOURNAL_READ` | le relevé : mouvements sur une plage de dates comptables (un mois par défaut), dans l'ordre du journal, paginé ; lecture tracée |
| `GET /accounts/{id}/ledger?from=&to=&after=&size=` | `ACCOUNT_JOURNAL_READ` | le grand livre du compte : les mêmes lignes, par curseur, pour les longues plages |
| `GET /ledger/trial-balance?from=&to=&kind=&branchId=&page=&size=` | `LEDGER_READ` | la balance à six colonnes, dans l'ordre des codes de compte ; `kind` (balance auxiliaire) et `branchId` (balance d'agence) en filtres |
| `GET /ledger/trial-balance/totals?from=&to=&kind=&branchId=` | `LEDGER_READ` | les totaux de la même balance, une ligne par devise, avec le constat d'équilibre |
| `GET /ledger/journal?from=&to=&after=&size=` | `LEDGER_READ` | le journal de l'entité, toutes lignes, par curseur : un jour par défaut, la lecture des extractions |
| `GET /statements/balance-sheet?asOf=`, `/off-balance-sheet?asOf=` | `LEDGER_READ` | l'état à la date (la date comptable par défaut) : rubriques, totaux, anomalies nommées, `consistent` ; `409` sans maquette active |
| `GET /statements/income-statement?from=&to=` | `LEDGER_READ` | les mouvements de la plage hors écritures de clôture ; l'exercice en cours par défaut |
| `POST /statement-layouts`, `.../{id}/activation`, `GET .../{id}` | `STATEMENT_LAYOUT_DRAFT`, `STATEMENT_LAYOUT_ACTIVATE` | nature d'état, code, validité, rubriques (rang, code, libellé, niveau, nature, sens, `plus`, `minus`), règles (rang, rubrique, nature de compte, préfixe, sens du solde) ; vérifiée avant d'entrer en base (`422`), activation **202**, jamais par le rédacteur ; lecture par `LEDGER_READ` |
| `POST /accounts/{id}/deposits`, `/withdrawals` | `CASH_OPERATION` | montant, canal ; `Idempotency-Key` — la caisse est celle de l'appelant, résolue depuis son jeton (`409` s'il n'en a pas, ou si elle est arrêtée) |
| `POST /transfers` | `TRANSFER` | émetteur, bénéficiaire, montant ; `Idempotency-Key` |
| `POST /accounts/{id}/payment-orders` | `PAYMENT_ORDER` | montant, bénéficiaire (nom, banque, compte), référence ; `Idempotency-Key` — le client est débité à l'ordre, plafonds du produit et du compte appliqués (`409`) |
| `POST /payment-orders/{id}/send`, `.../settlement`, `.../return`, `.../cancellation` | `PAYMENT_PROCESS` | envoi ; règlement (compte nostro) ; retour (motif) ; annulation avant envoi (motif) — un état qui ne s'y prête pas est un `409` |
| `GET /payment-orders/{id}`, `GET /payment-orders?status=&page=&size=` | `PAYMENT_READ` | — ; lecture tracée |
| `POST /accounts/{id}/cheque-books`, `GET /accounts/{id}/cheque-books` | `CHEQUE_BOOK_ISSUE`, `CHEQUE_READ` | nombre de chèques (1 à 200) — **202**, à deux dans l'agence du compte, aux frais du produit ; les numéros suivent le chéquier précédent |
| `POST /accounts/{id}/cheques/{number}/payment` | `CHEQUE_PAY` | montant, porteur, `mode` (`CASH` par défaut : la caisse de l'appelant ; `CLEARING` : `nostroAccountId`) ; `Idempotency-Key` — payé une fois (`201`, rejeu `200`, déjà payé `409`), refusé sur opposition (`409`), rejeté sans provision avec l'incident enregistré (`409`) ; plafonné par rôle comme une opération de caisse |
| `POST /accounts/{id}/cheques/{number}/stop` | `CHEQUE_STOP` | motif (`LOSS`, `THEFT`, `FRAUDULENT_USE`, `BEARER_INSOLVENCY`) ; jamais sur un chèque payé (`409`) |
| `GET /accounts/{id}/cheques?status=`, `GET /accounts/{id}/cheque-incidents` | `CHEQUE_READ` | — ; lecture tracée |
| `POST /accounts/{id}/cheque-deposits` | `CHEQUE_DEPOSIT` | montant, banque tirée, numéro, tireur ; `Idempotency-Key` — crédité sauf bonne fin à la date de valeur des conditions, bloqué jusqu'au règlement ; `409` si le produit n'a pas de compte d'encaissement |
| `POST /cheque-deposits/{id}/settlement`, `.../return` | `CHEQUE_PROCESS` | règlement (compte nostro) ; impayé (motif) : contre-passation du crédit — un état qui ne s'y prête pas est un `409` |
| `GET /cheque-deposits/{id}`, `GET /cheque-deposits?status=&page=&size=` | `CHEQUE_READ` | — ; lecture tracée |
| `POST /accounts/{id}/limits`, `GET /accounts/{id}/limits` | `ACCOUNT_LIMIT_MANAGE` | nature (`TRANSACTION`, `DAILY`, `MONTHLY`), montant, validité — **202**, à deux dans l'agence du compte ; le plafond du compte l'emporte sur celui du produit |
| `POST /accounts/{id}/blocks`, `.../{blockId}/lift` | `ACCOUNT_BLOCK` | nature, motif — **202** |
| `POST /accounts/{id}/holds`, `.../{holdId}/release` | `ACCOUNT_HOLD` | montant, nature, échéance — **202** |
| `POST /accounts/{id}/closure` | `ACCOUNT_CLOSE` | compte de reversement — **202** |
| `GET /pending-operations?page=&size=`, `GET .../{id}` | celle de l'opération en attente | — ; la page se découpe après le filtre d'habilitation |
| `POST /pending-operations/{id}/approve`, `.../reject` | celle de l'opération en attente, en tant que checker | motif pour un rejet |
| `POST /eod/runs`, `GET /eod/runs/{id}`, `POST .../resume` | `TFJ_RUN` | journée, mode |
| `POST /eod/runs/{id}/cancel` | `TFJ_CANCEL` | date de contre-passation, motif |
| `POST /eom/runs`, `.../{id}/resume`, `.../{id}/cancel` | `PERIOD_CLOSE`, `PERIOD_REOPEN` | journée de fin de période ; contre-passation et motif — **202** chacun, l'arrêté mensuel s'exécute à l'approbation ; `GET /eom/runs/{id}` |
| `POST /eoy/runs`, `.../{id}/resume`, `.../{id}/cancel` | `YEAR_CLOSE`, `YEAR_REOPEN` | journée de fin d'exercice ; contre-passation (à la fin d'exercice) et motif — **202** chacun ; `GET /eoy/runs/{id}` |
| `POST /fiscal-years`, `GET /fiscal-years` | `FISCAL_YEAR_MANAGE` | début, fin, compte de résultat — **202** ; liste paginée avec le statut |
| `GET /fiscal-years/{id}` | `FISCAL_YEAR_MANAGE` | l'exercice, son résultat net une fois clos (positif pour un bénéfice), l'affectation en vigueur et toutes les affectations, contre-passées comprises |
| `POST /fiscal-years/{id}/appropriation` | `RESULT_APPROPRIATION` | date comptable (après la fin de l'exercice), date de la décision, pièce, destinations (compte, montant) — **202** ; à l'approbation, l'écriture solde le compte de résultat agence par agence sur les destinations, au siège ; la somme est le résultat, exactement (`422` sinon), un exercice non clos ou déjà affecté est un conflit (`409`) |
| `POST /loans` | `LOAN_CONTRACT_CREATE` | référence, produit, devise, compte de prêt, compte de règlement, capital, date de déblocage, client |
| `POST /loans/{id}/disbursement` | `LOAN_DISBURSE` | conditions (taux, périodicité, échéances, différé, première échéance, méthode, base, frais) — **202**, plafond sur le capital |
| `POST /loans/{id}/repayments` | `LOAN_REPAYMENT` | montant, date de valeur ; `Idempotency-Key` ; règlement manuel, l'excédent non affecté est rendu |
| `POST /loans/{id}/prepayments` | `LOAN_PREPAY` | montant, mode (durée ou échéance) ; `Idempotency-Key` — **202**, l'échéancier refait s'approuve à deux |
| `GET /loans/{id}` | `LOAN_READ` | — : contrat, conditions, échéancier en vigueur, créances ouvertes, jours de retard |
| `GET /loans?status=&page=&size=` | `LOAN_READ` | les contrats de l'entité, paginés, statut en filtre |
| `POST /loans/{id}/rescheduling` | `LOAN_RESCHEDULE` | nouvelle durée, première échéance, date d'effet, **motif** — **202** ; le nouveau plan porte sur le capital non échu, aux conditions du contrat |
| `POST /products` | `PRODUCT_DRAFT` | code, famille, libellé, devise, validité, paramètres, barème |
| `POST /products/{versionId}/activation` | `PRODUCT_ACTIVATE` | — **202**, jamais approuvée par le rédacteur de la version |
| `POST /calendar/value-date-rules` | `CALENDAR_MANAGE` | type d'opération, canal, sens, décalage, unité, convention, validité — **202** |
| `POST /calendar/holidays` | `CALENDAR_MANAGE` | date, libellé — **202** ; l'arrêté du soir relit le calendrier |
| `POST /branches` | `BRANCH_MANAGE` | code, nom, nature, rattachement, ouverture, comptes de liaison par devise — **202** |
| `POST /collateral-policies`, `.../{id}/activation` | `RISK_PARAMETER_DRAFT`, `RISK_PARAMETER_ACTIVATE` | nature de sûreté, quotité, âge maximal d'expertise, validité ; activation **202**, jamais par le rédacteur |
| `POST /risk-profiles`, `.../{id}/activation` | `RISK_PARAMETER_DRAFT`, `RISK_PARAMETER_ACTIVATE` | grille : classes contiguës (rang, bornes en jours, taux de provision, saine ou non), contagion, suspension, période d'observation ; une grille lacunaire est refusée (`422`) |
| `POST /accounting-schemas`, `.../{id}/activation` | `ACCOUNTING_SCHEMA_DRAFT`, `ACCOUNTING_SCHEMA_ACTIVATE` | code, devise, validité, événements (dérivations ordonnées, lignes `CONTRACT` / `GL:code` / paramètre, conditions) ; validé avant d'entrer en base |
| `POST /collaterals`, `.../{id}/allocations`, `.../{id}/release` | `COLLATERAL_MANAGE` | prise (actif, nature, valeur, montant garanti, rang, date d'expertise), affectation à un contrat (quote-part), mainlevée — **202** chacun |
| `POST /tills` | `TILL_MANAGE` | code, compte de caisse, sujet du guichetier titulaire, compte d'écarts — **202**, un chef d'agence demande, un autre valide |
| `POST /tills/{id}/closure` | `TILL_CLOSE` | especes comptées : solde comptable confronté, écart comptabilisé, journée de caisse close ; le guichetier n'arrête que la sienne |

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
échéancier s'approuve à deux — la base l'exige, la politique aussi. Le **rééchelonnement** suit
le même circuit : le maker propose une durée, une première échéance et une date d'effet, avec un
motif ; à l'approbation, le nouveau plan est généré sur le **capital non échu** (encours moins le
principal déjà exigible) aux conditions financières du contrat — les échéances déjà exigibles
restent dues, jamais reprises dans le nouveau plan, et l'ancien plan est clos à la veille de la
date d'effet, jamais effacé.

**Caisses.** Le guichetier ne choisit pas sa caisse : elle lui est affectée (à deux) et l'API la
résout depuis le sujet de son jeton ; sans caisse, pas d'opération de guichet. L'arrêté de caisse
est le comptage des espèces : le système lit le solde comptable de la caisse, comptabilise l'écart
sur le compte d'écarts de la caisse — excédent au crédit, manquant au débit, jamais ajusté — et
clôt la journée de caisse : ni second arrêté, ni opération ce jour-là. Un guichetier n'arrête que
sa caisse (règle « objet propre » de la politique, [07](07-securite-conformite.md)) ; le chef
d'agence arrête toute caisse de son agence. Une caisse mouvementée non arrêtée fait échouer
l'arrêté de la banque à `PRE_CHECKS`, qui la nomme ; l'exploitant reprend après l'arrêté de caisse.

**Paramétrage et réseau.** Une version de produit se rédige seul et s'active à deux : le checker
n'est jamais le rédacteur de la version (`409` s'il tente). Règles de date de valeur, jours
fériés et agences suivent le même circuit ; le moteur d'arrêté relit le calendrier à chaque
lancement, un férié déclaré dans la journée vaut pour le soir même.

**Arrêtés.** L'arrêté mensuel et la clôture annuelle passent par le même circuit à deux que
tout ce qui ferme ou rouvre une période : la demande est acceptée (`202`), le traitement
s'exécute à l'approbation, et son rapport (`TfjRun`) est le résultat de l'opération en attente ;
un refus du moteur — un mois non terminé, le dernier mois d'un exercice demandé en arrêté
mensuel — est la réponse de l'approbation (`409`), et la demande est à refaire. L'exercice
s'ouvre à deux, avec son compte de résultat ; sa liste dit son statut (`OPEN`, `CLOSED`,
`REOPENED`).

Ce qui n'est pas encore exposé : pas de contrat OpenAPI publié ; pagination par curseur pour les
extractions massives ; l'affectation du résultat. La réservation du disponible par une opération en attente
qui déplacerait des fonds n'existe pas encore : un déblocage approuvé crédite le compte de
règlement à l'approbation, sans réservation préalable.

Le test `ApiIT` fait tout le parcours contre un vrai serveur, une vraie base et de vrais jetons
signés, l'API connectée avec le rôle applicatif : du tiers au retrait, les refus un par un,
la caisse affectée à deux puis arrêtée avant la journée, l'arrêté lancé par l'exploitant, le
crédit du produit au remboursement anticipé puis au rééchelonnement, les conditions de banque et
une agence créées à deux, l'enveloppe et les pages sur le relevé, les contrats, les tiers et les
opérations en attente, l'exercice ouvert puis clos et rouvert à deux, le régime de sûreté, la
grille de risque et le schéma comptable activés à deux, une sûreté prise, affectée et levée.

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
