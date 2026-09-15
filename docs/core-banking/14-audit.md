# 14 — Audit de couverture fonctionnelle et de robustesse

> Établi sur le code à la révision `1b9b37d` (465 tests verts). Chaque constat cite le fichier qui le
> prouve. Un constat sans preuve dans le code n'y figure pas.
>
> Suivi au §7 : phase A livrée (`a2d9c08`), phase B livrée (504 tests verts).

## 0. Comment lire

| Sévérité | Signification |
|---|---|
| **P0** | Empêche tout déploiement, même pilote : la base ne se monte pas, ou la banque s'arrête |
| **P1** | Chiffres faux, ou surface non protégée — inacceptable en production |
| **P2** | Fonction qu'une banque UEMOA ne peut pas ouvrir sans |
| **P3** | Dette : capacité, exploitabilité, fragilité — à traiter avant la montée en charge |

Taille : **S** < 2 jours, **M** < 2 semaines, **L** > 2 semaines.

---

## 1. Verdict

**Le noyau est solide ; la plateforme n'est pas déployable.**

Ce qui est bâti tient : journal immuable et équilibré, idempotence, contre-passation, calculs
d'intérêts et de crédit exacts au franc, TFJ rejouable et annulable, paramétrage daté sous double
validation et sous contrat de famille. Chaque invariant est porté par la base ou par un test, et la
méthode — mesurer, laisser les tests trouver les défauts, refuser plutôt que replier — est la bonne.

Mais cinq constats P0 rendent aujourd'hui impossible de faire tourner la banque une semaine :
**aucun chemin de migration**, **aucune création de partitions**, **aucun cycle de période
comptable**, **une annulation de TFJ qui corrompt les journées suivantes**, et **des crédits qui ne
se clôturent jamais**. Aucun n'est difficile. Tous sont invisibles aux tests, parce que les bases
de test font à la main ce que l'exploitation devra faire seule.

Derrière, trois constats P1 touchent l'exactitude comptable elle-même — intérêts courus non échus
sur crédits absents, intérêts sur dépôts jamais versés, agios impossibles — et un quatrième la
sécurité : **rien n'est protégé**, parce que la politique d'habilitation n'a aucun point d'entrée.

---

## 2. À corriger — défauts

### P0 — bloquant

| # | Constat | Preuve | Conséquence | Correction | Taille |
|---|---|---|---|---|---|
| 1 | **Aucun chemin de migration.** `SchemaMigrator` n'applique que `V1` ; `V2` à `V15` sont appliqués à la main par chaque base de test | `ledger-store/…/SchemaMigrator.java:16` ; `LoanTestBase.java:54-62` | Impossible de monter une base hors des tests. Deux environnements ne peuvent pas être prouvés identiques | Runner de migrations avec table de versions, somme de contrôle, ordre strict, refus en cas de trou (Flyway, ou 150 lignes maison dans le style de `Json`) | S |
| 2 | **Partitions du journal jamais créées en exploitation.** `ensurePartitions` n'a d'appelant que dans les tests | `grep ensurePartitions */src/main` → `SchemaMigrator` seul | Une partition manquante fait échouer *toute* comptabilisation sur la date — le premier jour d'un mois non anticipé arrête la banque | Garantie dans `OPEN_NEXT_DAY` (N+1 à N+3 mois) et contrôle dans `PRE_CHECKS` | S |
| 3 | **Périodes comptables jamais ouvertes ni closes.** `Entities.openPeriod/closePeriod` sans appelant en production ; aucun test ne franchit une fin de mois | `Entities.java:63` ; `grep openPeriod */src/main` vide ; `tfj/src/test` sans franchissement de mois | « Aucune écriture en période fermée » — l'invariant est bon, mais le premier TFJ du mois suivant échoue à sa première écriture | Cycle de période : ouverture anticipée dans le TFJ, clôture dans un TFM avec contrôles ; test de franchissement de mois et d'année | M |
| 4 | **L'annulation d'un TFJ ne vérifie pas les journées suivantes.** `cancel(N)` remet la date à N sans regarder si N+1 a tourné ; rejouer N puis appeler `run(N+1)` rend l'ancien run **terminé** sans rien recalculer | `TfjEngine.java:177-207` (aucune garde) ; `TfjEngine.java:68` `case COMPLETED -> run` | Journée N+1 tenue pour faite sur un état antérieur à l'annulation ; la date comptable reste bloquée sur N+1 | `cancel` refuse tant qu'un run REAL non annulé existe à une date postérieure ; annulation en cascade explicite si voulue ; test | S |
| 5 | **Un crédit intégralement remboursé reste ACTIVE.** Seuls le remboursement anticipé total et la mobilisation sans tirage clôturent | `LoanStore.close` appelé de `LoanService.java:238` et `LoanMobilisationService.java:473` seulement ; `settle` ne clôture jamais | Chaque TFJ ré-examine le contrat à vie (exigibilité, retard, classification) ; le portefeuille « en cours » gonfle ; aucun archivage possible | Clôture dans `settle` quand plus aucune échéance à venir ni créance ouverte ; test sur la dernière échéance | S |

### P1 — exactitude et sécurité

| # | Constat | Preuve | Conséquence | Correction | Taille |
|---|---|---|---|---|---|
| 6 | **Intérêts courus non échus sur crédits absents.** L'intérêt d'un crédit n'est constaté qu'à l'échéance ; aucun accrual quotidien sur les comptes de prêt | `grep interest_accrual loan-service/src/main` vide ; `LoanSchemas.instalmentDue` | Entre deux échéances, produit et actif sont sous-évalués d'un mois d'intérêts au plus. À l'arrêté mensuel, le résultat de la banque est faux | Accrual quotidien sur l'encours (moteur du cumul arrondi, déjà écrit), reprise à l'échéance ; règle à fixer pour l'annuité constante, dont le taux périodique n'est pas le taux journalier | M |
| 7 | **Les intérêts sur dépôts ne sont jamais versés au client.** L'accrual crédite un compte de courus ; aucune capitalisation, aucun règlement | `InterestAccrualService.java:39-40` (le javadoc le nomme) ; `grep capitalis interest-service/src/main` sans code | Le client ne reçoit jamais ses intérêts ; le compte de courus croît indéfiniment ; la retenue à la source (IRVM/IRC) n'existe pas | Étape de capitalisation à périodicité produit, avec retenue à la source paramétrée par profil pays | M |
| 8 | **Agios impossibles sur compte courant.** Un produit porte *un* `interest.side` ; un compte porte *un* produit à la fois | `AccrualSide.java` ; `families.json` ; `account_product` PK `(account_id, valid_from)` | Un compte courant ne peut pas être à la fois rémunéré sur ses jours créditeurs et débité d'agios sur ses jours débiteurs ; `overdraft_limit` (table) n'est lu par aucun code | Produit à deux côtés (taux et comptes créditeurs **et** débiteurs), accrual sur les deux séries ; échelle d'intérêts ; rattachement de `overdraft_limit` | M |
| 9 | **Blocages sans code.** `account_hold` est soustrait du disponible par la base, mais rien ne crée, ne lève ni n'expire un blocage ; `Operation.ACCOUNT_HOLD` existe sans implémentation | `V1__ledger_core.sql:180-192, 309` ; `grep account_hold */src/main/java` vide | Aucune opposition, aucune provision de chèque, aucune retenue de garantie ; l'étape `HOLD_EXPIRY` n'existe pas | Service de blocage (pose, levée, expiration), étape TFJ, prélèvement des échéances sur le **disponible** et non le solde (`LoanService.collect` lit `Balances.current`) | M |
| 10 | **La sécurité n'est branchée nulle part.** `actorId` est un UUID fourni par l'appelant ; `UseCaseExecutor` n'a aucun appelant ; la validation du jeton est déléguée à un serveur de ressources qui n'existe pas ; RLS non activé | `grep UseCaseExecutor */src/main` → `security-core` seul ; `KeycloakCallerFactory.java:13` ; `grep "ROW LEVEL SECURITY"` vide | **Rien n'est protégé.** Toute opération est exécutable par quiconque atteint le code, sous n'importe quelle identité | Couche API (serveur de ressources OAuth2), `Caller` dérivé du jeton et propagé aux services — jamais un paramètre —, RLS par entité ; test d'intégration bout en bout par rôle | L |
| 11 | **Politique d'habilitation en retard sur le périmètre.** `Operation` compte 14 valeurs ; rien pour déblocage, tranche, remboursement anticipé, rééchelonnement, exonération, mainlevée, profil de risque, politique de sûreté | `Operation.java` | L'exhaustivité vérifiée porte sur un enum qui ne couvre plus le code | Une opération par point d'entrée de service ; test qui confronte les méthodes publiques des services aux opérations | S |
| 12 | **Comptes référencés par le paramétrage jamais vérifiés.** Un paramètre `*_account` est un UUID ; ni existence, ni nature GL, ni entité, ni devise ne sont contrôlées à l'activation. `assignProduct` et `createContract` ne vérifient ni l'entité ni la devise | `LoanCatalog.java:211-230` (`requireUuid` seul) ; `ProductCatalog.assignProduct` ; `LoanStore.createContract` | Découvert à la première écriture, la nuit, sur une étape bloquante — la classe de faute que les familles de produit viennent de fermer pour les *noms* | Étendre `ProductFamily.validate` d'un contrôle en base des comptes cités (existence, `GL`, entité, devise, `postable`) ; mêmes contrôles au rattachement et au contrat | S |
| 13 | **Réconciliation limitée au grand livre.** Trois contrôles : équilibre, matérialisé = rejeu, stripes. Aucun rapprochement des sous-livres | `Reconciliation.java:133-139` | Une dérive entre `loan_receivable` et le compte de créances rattachées, entre `fee_charge` et le journal, entre `interest_accrual` et le compte de courus, est **invisible** à l'arrêté — précisément la classe de défaut trouvée deux fois par les tests | Ajouter à `RECONCILIATION` : Σ créances ouvertes = solde créances rattachées ; Σ accruals actifs = solde courus ; Σ encours de prêt = Σ capital restant dû ; Σ commissions perçues = journal | M |
| 14 | **Multi-devise sans cours.** Le cours est fourni par l'appelant ligne par ligne ; aucune table de cours, aucun contrôle, aucune revalorisation | `PostingLine.withFxRate` ; `grep fx_rate */db` vide | Deux écritures du même jour peuvent porter deux cours ; les positions de change ne sont jamais réévaluées | Table de cours datée, contrôle à la comptabilisation, étape `FX_REVALUATION` | M |

### À valider avec la banque (pas un défaut, une décision non prise)

| Sujet | État | Décision attendue |
|---|---|---|
| Arrondi du XOF | `HALF_EVEN` codé dans `Currencies.XOF` ; paramétrable par devise | Convention de la comptabilité de la banque — nombre de banques UEMOA arrondissent au franc supérieur à partir de 0,5 |
| TEG | Deux conventions d'annualisation paramétrées | Formule exacte de l'instruction BCEAO en vigueur |
| Grilles de classification, taux de provision, seuils | Structure prête, valeurs illustratives | Instruction en vigueur |

---

## 3. À compléter — couverture fonctionnelle

### P2 — indispensable à l'ouverture

| # | Domaine | Manque | Ce qui existe déjà |
|---|---|---|---|
| 15 | **Référentiel client** | Aucune table client, aucun KYC, aucun identifiant centrale des risques ; `customer_id` est un UUID nu sur `loan_contract` ; un compte n'a pas de titulaire | Contagion par client (sur l'UUID) |
| 16 | **Opérations de base** | Dépôt, retrait, virement, chèque comme cas d'usage : date de valeur, frais d'opération, plafonds, contrôle du disponible | `PostingService` (primitive), `ValueDatePolicy` (écrite, **non branchée** : `grep ValueDatePolicy */src/main` hors calendrier vide) |
| 17 | **Cycle de vie des comptes** | Ouverture, clôture, blocage, dormance : aucune transition ; `Accounts` ne sait que créer et lire | `AccountStatus` avec ses valeurs ; `EntryValidator` refuse déjà une écriture sur un compte non actif |
| 18 | **Crédit** | Origination (demande, décision, comité, conditions suspensives) ; passage en perte (`WRITTEN_OFF` sans opération) ; recouvrement et contentieux ; révision de taux (`RATE_REVISION` sans service) ; différé total ; échéances irrégulières ; réalisation de sûreté | Tout le reste du cycle, mesuré |
| 19 | **Hors bilan (classe 9)** | Cautions, avals, crédocs, engagements de financement — l'engagement non tiré d'un crédit par tranches n'apparaît **nulle part** dans le ledger | `AccountKind` sans nature hors bilan ; conçu dans [11](11-profil-uemoa-bceao.md) |
| 20 | **TFM / TFA** | Clôture mensuelle (ICNE, période, états), annuelle (résultat, à-nouveaux) | Le moteur de TFJ, réutilisable tel quel pour d'autres séquences |
| 21 | **Conformité** | Reporting réglementaire, mapping plan comptable interne → PCB, LCB-FT, déclaratifs | Profil pays documenté |
| 22 | **Étapes de TFJ absentes** | `CUT_OFF`, `FX_RATES`, `VALUE_DATE_REBUILD`, `FX_REVALUATION`, `DORMANCY`, `HOLD_EXPIRY`, `KYC_REVIEW`, `REPORTING` | 10 étapes en place, ordre justifié |
| 23 | **Couche API** | Aucune : ni REST, ni Spring Boot, ni validation d'entrée, ni idempotence de bout en bout (`disburse` rejoué après succès échoue au lieu de rendre le même résultat) | Toute la logique, sans point d'entrée |
| 24 | **Archivage** | Aucune politique de rétention ni de détachement de partition | Journal partitionné par mois |

---

## 4. Dette technique — P3

| # | Constat | Preuve | Risque | Correction |
|---|---|---|---|---|
| 25 | `account_balance_daily` et `interest_accrual` **non partitionnées** | `V1:170`, `V2:9` sans `PARTITION BY` | 2 M comptes × 365 = **730 M lignes par an et par table** | Partitionner par mois comme le journal ; rétention |
| 26 | La réconciliation **rejoue tout le journal** chaque nuit | `Reconciliation.materializedMatchesReplay` sans borne de date | O(historique) : quelques heures après deux ans à 2 M de comptes — la mesure actuelle (18 ms) porte sur une journée | Rejeu incrémental depuis le dernier cliché vérifié |
| 27 | **Aucune journalisation applicative**, aucune métrique | `grep LoggerFactory */src/main` → une classe | Un incident de nuit se diagnostique sans aucune trace hors `batch_step` | Journal structuré aux frontières run/étape/contrat en anomalie ; exposition des durées déjà mesurées |
| 28 | `LoanContract.terms` porte une **durée conventionnelle fausse** (`instalmentCount = 1`) | `LoanStore.readTerms` | Tout code qui lirait la durée sans `withTerm`/`forRemaining` calculerait faux en silence | Type distinct sans dimension d'échéancier |
| 29 | `Database` : ni `statement_timeout`, ni `idle_in_transaction_session_timeout`, ni SSL ; pool et `Parallel.defaultDegree` dimensionnés indépendamment | `Database.java:34-42` ; `Parallel.java:36` | Une requête folle bloque un pool ; un degré > pool épuise le pool | Timeouts ; règle pool ≥ degré + marge, vérifiée au démarrage |
| 30 | Couverture de test : aucun franchissement de mois, aucune concurrence **entre entités**, aucune panne injectée en milieu d'étape hors reprise, aucun test d'habilitation en intégration | `tfj/src/test` ; `security-store` seul | Les invariants tiennent dans les cas testés ; les frontières ne le sont pas | Ajouter ces quatre familles de tests |
| 31 | Adaptateur Keycloak non éprouvé contre un serveur réel | documenté | — | Banc avec Keycloak en conteneur |
| 32 | Mesures sur PostgreSQL embarqué, sans historique | [13](13-mesures.md) | Les extrapolations ignorent la croissance des tables | Banc avec deux ans d'historique synthétique |

---

## 5. Ce qui est solide — et doit le rester

Ne rien reprendre ici sans raison. Chaque ligne est un invariant porté par la base ou par un test
nommé dans le [README du socle](../../core-banking/README.md).

- **Journal** : immuable, équilibré par devise et en contre-valeur, idempotent, contre-passation
  unique, période fermée refusée, disponible jamais négatif, aucun interblocage, compte chaud exact —
  `PostingIT`, `ConcurrencyIT`, `BitemporalIT`.
- **Comptabilisation** : `EntryValidator` refuse un compte inconnu, d'une autre entité, non
  imputable, fermé, ou d'une autre devise — vérifié en relisant le code pour cet audit.
- **Intérêts** : cumul arrondi sans dérive, recalcul rétroactif, lot ≡ compte par compte, taux à la
  date traitée.
- **Crédit** : échéancier qui ferme exactement (600 cas générés), imputation exhaustive, créance
  qui ne remonte jamais, retard sans anatocisme, suspension des intérêts par composante, TEG par
  dichotomie, double plafond légal, période d'observation, sûretés à quatre réductions, tranches
  avec intercalaires — chacun trouvé et corrigé par un test avant d'être livré.
- **TFJ** : unique par entité et par date, reprise à l'étape fautive, à blanc sur le même chemin,
  annulation qui neutralise chaque sous-livre (à l'exception du constat 4).
- **Paramétrage** : daté, sans chevauchement, sous double validation, journal immuable, familles
  de produit vérifiées au déploiement, accord code/fichier testé dans les deux sens.
- **Habilitation** : politique exhaustive, aucune annotation, refus avant effet, catalogue de rôles
  cohérent — **prête à être branchée**, ce qu'elle n'est pas (constat 10).

---

## 6. Plan d'action ordonné

**A — Avant tout déploiement, même pilote** — ✅ **livrée**
1, 2, 3, 4, 5, 12, 11. Une base se monte par `SchemaMigrator`, le TFJ franchit une fin d'année sans
intervention, et un paramétrage qui cite un compte inexistant ne s'active pas. Détail au §7.

**B — Exactitude comptable** — ✅ **livrée**
6, 7, 8, 13, 20, 25, 26. Le résultat d'un mois porte les intérêts de ce mois, crédits compris ; le
client reçoit ses intérêts net de retenue ; un compte courant produit des agios ; l'arrêté
rapproche les sous-livres chaque nuit et rejoue tout au mois. Détail au §7.

**C — Périmètre bancaire minimal et sécurité** — ✅ **livrée**
15, 16, 17, 9, 27, puis 23 et 10 livrés : référentiel client avec dédoublonnage garanti par la base et
restriction progressive, opérations de base avec dates de valeur calculées et frais du produit,
cycle de vie des comptes jusqu'au solde de tout compte, blocages de montant et de compte appliqués
par le ledger, journal applicatif aux frontières du TFJ. Détail au §7. L'API REST (module `api`, Spring Boot 4.1) expose les services par des cas
d'usage, sur la signature validée ; le `Caller` vient du jeton et traverse `UseCaseExecutor`,
seul point de contrôle. Un préalable était apparu à l'étude de la signature : le
**multi-agences** ([15](15-multi-agences.md)) — livré avant l'API (§7, ligne MA). Les deux
compléments de cette phase ont suivi : le circuit maker-checker (V25) et la Row Level Security par
entité (V26 à V34), tous deux au §7, ligne 10.

**D — Couverture UEMOA** (à planifier avec le profil réglementaire)
18, 19, 14, 21, 22, 24, et les décisions du §2 « à valider ».

**Continu** : 29, 30, 31, 32.

---

## 7. Suivi

| # | Constat | État | Preuve |
|---|---|---|---|
| 1 | Aucun chemin de migration | ✅ `SchemaMigrator` : déclarations par module, ordre, somme de contrôle, continuité par défaut, une transaction sous verrou ; toutes les bases de test passent par lui | `SchemaMigratorIT` |
| 2 | Partitions jamais créées | ✅ `OPEN_NEXT_DAY` garantit N+1 à N+3 mois, `PRE_CHECKS` le mois traité ; `ledger_ensure_partitions` idempotente sous verrou (V16) | `the_year_end_run_prepares_january_on_its_own`, `partitionsAreIdempotent` |
| 3 | Périodes jamais ouvertes | ✅ La bascule ouvre le mois civil suivant s'il n'est pas couvert ; une période close est nommée, jamais rouverte. Clôture (TFM) : phase B | `TfjPeriodIT` |
| 4 | Annulation sous une journée suivante | ✅ `cancel` refuse tant qu'un run réel non annulé existe à une date postérieure | `cancelling_a_day_behind_a_later_run_is_refused` |
| 5 | Crédit soldé jamais clos | ✅ Étape `LOAN_CLOSURE` après la classification ; encours résiduel signalé comme écart de sous-livre ; clôture annulable avec l'arrêté (V17) | `clotureALaDerniereEcheance`, `encoursResiduelNomme`, `clotureParLArreteEtReouverture` |
| 11 | Politique en retard sur le périmètre | ✅ 15 opérations ajoutées, 2 rôles de crédit, 2 postes ; inventaire des points d'entrée tenu par test — le rattachement mécanique viendra avec les cas d'usage | `OperationCoverageTest` |
| 12 | Comptes du paramétrage non vérifiés | ✅ Existence, nature GL, entité, devise, imputabilité à l'activation ; devise et existence du produit au rattachement ; comptes clients de l'entité au contrat | `ProductCatalogIT`, `contratSurCompteImpropre` |
| 6 | ICNE sur crédits absents | ✅ Étape `LOAN_INTEREST_ACCRUAL` : l'intérêt de l'échéance est étalé linéairement sur les jours de sa période, cumul arrondi ; la créance le reprend à l'échéance ; échéancier remplacé repris, crédit suspendu en intérêts réservés, annulation avec l'arrêté (V20). Règle retenue pour l'annuité constante : l'étalement de l'intérêt contractuel, exact à l'échéance par construction | `LoanInterestAccrualIT`, `classificationEtProvision` |
| 7 | Intérêts sur dépôts jamais versés | ✅ `INTEREST_SETTLEMENT` : capitalisation à la fin de période civile du produit, brut = cumul arrondi à la fin de période, retenue à la source par entité et datée, date de valeur du lendemain ; position par compte (`interest_position`) | `InterestSettlementIT`, `quarter_end_settles_both_sides`, `the_year_end_run_prepares_january_on_its_own` |
| 8 | Agios impossibles | ✅ Bloc `overdraft.*` : deux côtés calculés séparément, taux de dépassement sur la part au-delà de l'autorisation du compte (`overdraft_limit` rattaché), arrêté taxe comprise | `overdraft_interest_accrues_at_two_rates_and_is_charged_with_tax`, `quarter_end_settles_both_sides` |
| 13 | Réconciliation limitée au grand livre | ✅ `Reconciliation.Check` par module : courus des dépôts et agios, créances de crédit, encours par contrat, ICNE des crédits, commissions du traitement ; un écart nomme le compte et bloque la journée | `ecartsNommes`, `a_manual_entry_on_the_accrued_account_is_a_named_discrepancy`, `a_sub_ledger_gap_blocks_the_day` |
| 20 | TFM absent | ✅ `StandardTfm` sur le même moteur : mois complet jour par jour, rejeu intégral et sous-livres, clôture ; annulation = `REOPENED` ; refus d'un mois non terminé. TFA : non fait | `TfmIT` |
| 25 | Tables quotidiennes non partitionnées | ✅ Registre `ledger_partitioned_table` ; `account_balance_daily`, `interest_accrual`, `loan_interest_accrual` mensuelles ; partitions garanties par la bascule pour toutes (V18, V19, V20) | `SchemaMigratorIT`, toutes les bases de test |
| 26 | Réconciliation en O(historique) | ✅ Cliché incrémental depuis la dernière journée arrêtée ; contrôles quotidiens sur la journée, rafraîchis à la reprise ; rejeu intégral réservé au TFM ; état des intérêts lu dans la position, plus sommé sur l'historique | `a_sub_ledger_gap_blocks_the_day`, `TfmIT` |
| 15 | Référentiel client | ✅ Module `party` (V22) : tiers par entité, identifiants officiels **dédoublonnés par index unique partiel**, titulaires datés, KYC en quatre états avec restriction progressive (un dossier non vérifié ou expiré opère mais n'ouvre rien ; bloqué n'opère plus), vérification à deux fixant la revue par niveau de risque, `KYC_REVIEW` au TFJ, interface de filtrage ; `loan_contract.customer_id` référence le tiers (V23) et un crédit exige un tiers vérifié. Non fait : documents, bénéficiaires effectifs, relations, rescan | `PartyIT`, `TfjDepositsIT`, `LoanClassificationIT` |
| 16 | Opérations de base | ✅ `OperationsService` : versement, retrait, virement interne ; date de valeur **calculée** depuis les conditions de banque (`ValueDatePolicy` enfin branchée), refus sans condition ; frais et taxe du produit dans la même écriture ; rejeu idempotent de bout en bout ; titulaires opérables exigés ; disponible et blocages contrôlés par le ledger. Non fait : chèques, plafonds par produit ou client, paiements sortants | `OperationsIT` |
| 17 | Cycle de vie des comptes | ✅ `AccountLifecycle` : ouverture à deux sur un tiers vérifié et un produit de dépôt ; blocage en débit ou total, état superposé appliqué par le ledger, sans changement de statut ; dormance sur l'absence d'opération **du client** (`DORMANCY`), réveil à la première opération ; clôture atomique — obstacles nommés d'un coup, intérêts des deux côtés réglés jusqu'à la veille, solde versé à un compte de reversement, solde débiteur refusé, produit et titulaires fermés (V21). Non fait : régime de frais de dormance, compte d'abandon | `LifecycleIT`, `DormancyIT` |
| 9 | Blocages sans code | ✅ `Holds` : pose avec nature, référence et expiration en date comptable ; levée ; `HOLD_EXPIRY` avant tout prélèvement, reposé par l'annulation ; `available_balance(compte, date)` par date comptable ; `LoanService.collect` prélève sur le **disponible** | `HoldsIT`, `TfjDepositsIT` |
| 27 | Aucune journalisation applicative | ✅ SLF4J : lancement, reprise, chaque étape (volumes, durée, anomalies), fin, annulation du TFJ ; transitions de statut des comptes. Métriques : non fait | `TfjEngine`, `AccountLifecycle` |
| 23 | Couche API | ✅ Module `api` : Spring Boot 4.1.1, Tomcat embarqué, serveur de ressources OAuth2 Keycloak, migrations au démarrage, provisionnement des rôles ; un cas d'usage par point d'entrée, `Idempotency-Key` obligatoire, rejeu `200`, refus en `problem+json` ; tiers, comptes, opérations, blocages, clôture, TFJ exposés ([06](06-api-integrations.md)). Non fait : crédit, paramétrage, OpenAPI publié, pagination | `ApiIT` |
| 10 | Sécurité branchée nulle part | ✅ `Caller` dérivé du jeton signé (clés publiques du royaume), jamais d'un paramètre ; `UseCaseExecutor` sur chaque point d'entrée ; l'auteur d'une écriture est le sujet du jeton ; opérations déplacées sous plafond propre ; piste d'audit des habilitations en base (`JdbcAuthorizationAudit`) ; **maker-checker** : les actes à double validation sont soumis (`202`) puis approuvés par un second porteur habilité, jamais le maker (politique et base), exécutés avec les deux sujets de jeton (V25, `MakerChecker`) ; **Row Level Security** par entité (V26–V34) : politiques sur toutes les tables à entité et leurs tables filles, entité posée par transaction par l'API (jeton) et par le TFJ, rien de visible sans entité, rôle applicatif non propriétaire (`ops/roles.sql`), partitions par fonction `SECURITY DEFINER` ; pour une autre entité, la ressource n'existe pas (`404`) | `ApiIT.parcours_client`, `ApiIT.refus` (API sous le rôle applicatif), `RowLevelSecurityIT`, `a_remote_operation_has_its_own_lower_ceiling` |
| MA | Multi-agences ([15](15-multi-agences.md)) | ✅ V24 : `branch`, siège par entité, comptes de liaison par devise ; agence gestionnaire sur comptes clients et internes ; agence comptable sur chaque ligne, agence de l'opération sur l'écriture ; **invariant 13** vérifié par le validateur et par la base ; lignes de liaison générées via le siège, contre-passées telles quelles ; services de lot par agence (intérêts agrégés, ICNE, provisions, retard, frais d'opération déplacée à l'agence qui sert) ; cliché par agence et contrôle `LIAISON_AGENCE_MIROIR` / `LIAISON_ELIMINATION` / `CLICHE_AGENCES_VS_COMPTE` chaque nuit, rejoué au TFM ; opérations déplacées sous plafond propre. Non fait : caisses par guichetier, schémas bilatéral et régional | `InterbranchIT`, `TfjInterbranchIT`, `retraitDeplace`, `the_batch_posts_one_pair_of_lines_per_branch`, `a_remote_operation_has_its_own_lower_ceiling` |

Deux limites nommées, à porter en phase D : l'assiette de la suspension des intérêts comprend la
taxe portée par la créance d'intérêts (la créance ne ventile pas intérêt et taxe) ; les intérêts
intercalaires d'une mobilisation restent constatés à la facturation de la période, pas étalés.

Phase C, une règle tranchée qu'il faut connaître : **le jour de la clôture d'un compte n'est pas
rémunéré**. Le solde versé ce jour-là porte la même date de valeur qu'un retrait, et un retrait
ne rémunère pas la journée où il est fait ; les intérêts sont calculés jusqu'à la veille et réglés
le jour de la clôture, date de valeur comprise — exactement comme un règlement périodique.

Trois fixtures de test ont dû changer, et c'est le contrôle qui l'a exigé : des comptes de
paramétrage tirés au hasard, des rattachements à des produits inexistants — des situations que le
socle refuse maintenant à la saisie.

---

## 8. Ce que cet audit ne couvre pas

Il ne dit rien de l'infrastructure (haute disponibilité de PostgreSQL, sauvegardes, PRA), de
l'exploitation (ordonnanceur, supervision, astreinte), ni de la conformité juridique des
traitements (protection des données, conservation). Ce sont des conditions de production à part
entière, à instruire séparément.
