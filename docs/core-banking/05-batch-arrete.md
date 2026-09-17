# 05 — Moteur de TFJ (Traitement de Fin de Journée)

Le second composant critique. Un TFJ non idempotent transforme le premier incident de
production en crise comptable.

---

> **État d'implémentation.** Le moteur d'orchestration est construit et testé
> ([`core-banking/tfj`](../../core-banking/tfj)) : unicité, garde d'ordre des journées, reprise à
> l'étape fautive, annulation par contre-passation intégrale, TFJ à blanc par transaction annulée.
> Cinq étapes tournent — contrôles préalables, intérêts courus, arrêté des soldes, réconciliation,
> bascule de journée. Les étapes restantes de la séquence ci-dessous s'insèrent sans toucher au
> moteur.

## 0. Terminologie

Le vocabulaire du marché francophone est retenu comme terminologie de référence du projet.

| Sigle | Signification | Équivalent international |
|---|---|---|
| **TFJ** | Traitement de Fin de Journée | EOD — End of Day |
| **TFM** | Traitement de Fin de Mois | EOM — End of Month |
| **TFT** | Traitement de Fin de Trimestre | EOQ — End of Quarter |
| **TFA** | Traitement de Fin d'Année | EOY — End of Year |
| **TDJ** | Traitement de Début de Journée | SOD — Start of Day |

Notions associées, employées telles quelles dans la suite du dossier :

| Notion | Définition retenue |
|---|---|
| **Journée comptable** | Période rattachée à une `booking_date`. Elle ne coïncide pas nécessairement avec la journée calendaire : un TFJ lancé à 22 h ou à 2 h du matin porte la date comptable de la journée qu'il clôture. |
| **Bascule de journée** | Changement de date comptable de l'entité, opéré par la dernière étape du TFJ. C'est le seul mécanisme autorisé à modifier `legal_entity.current_business_date`. |
| **Arrêté de caisse** | Contrôle et clôture des caisses par guichetier en agence. Il précède le TFJ et le conditionne : une caisse non arrêtée bloque le traitement. *Implémenté* : le guichetier compte, le système confronte au solde comptable, l'écart est comptabilisé sur le compte d'écarts de la caisse (jamais ajusté) ; `PRE_CHECKS` refuse la journée tant qu'une caisse mouvementée n'est pas arrêtée ; une caisse arrêtée ne sert plus ce jour-là. |
| **TFJ à blanc** | Exécution complète du TFJ en simulation, sans comptabilisation. Produit tous les états et tous les contrôles, ne crée aucune écriture. |
| **Chaîne de nuit** | Enchaînement TFJ → états → sauvegarde → alimentation du décisionnel → TDJ. |
| **Comptes d'attente / suspens** | Comptes techniques dont le solde doit être justifié à chaque TFJ. Leur apurement est un contrôle bloquant. |

### TFJ à blanc

Fonction attendue par les exploitants, à prévoir dès la conception : le moteur s'exécute en
mode `dry_run`, écrit ses écritures dans une table de simulation au lieu du journal, et
produit l'intégralité des états et des compteurs.

Trois usages : valider un changement de paramétrage avant activation (nouveau barème,
nouveau taux), vérifier l'impact d'une reprise de données, et former les équipes
d'exploitation sans risque.

Le mode est un drapeau du run, **pas un second code** : un TFJ à blanc qui n'emprunte pas
exactement le même chemin que le TFJ réel ne prouve rien.

---

## 1. Modèle d'exécution

```
BatchRun            (entité, date comptable, type, statut)
  └── BatchStep *   (ordre, nom, statut, checkpoint, compteurs)
        └── BatchPartition *  (plage de comptes, statut)
```

### Statuts d'un run

```
PLANIFIÉ → EN_COURS → ┬→ TERMINÉ → (jour suivant ouvert)
                      ├→ EN_ÉCHEC → reprise à l'étape en échec
                      └→ ANNULÉ   → contre-passation intégrale
```

### Garanties

| Garantie | Mécanisme |
|---|---|
| **Unicité** | Verrou en base sur `(legal_entity_id, business_date, run_type)`. Deux arrêtés simultanés sont impossibles, même en cas de double instance du pod. |
| **Idempotence** | Clé de comptabilisation déterministe : `hash(run_id, step, account_id, sequence)`. Un rejeu n'insère rien. |
| **Reprise** | Chaque étape enregistre un checkpoint. Une reprise repart de l'étape en échec, pas du début. |
| **Annulabilité** | Toutes les écritures portent le `batch_run_id`. Une annulation contre-passe l'intégralité du run. |
| **Reproductibilité** | Le paramétrage est lu à la date comptable du run, pas à la date du jour. Un rejeu produit des montants identiques. |

---

## 2. Séquence du TFJ

| # | Étape | Contenu | Bloquant |
|---|---|---|---|
| 1 | `CUT_OFF` | Gel des saisies sur la date comptable, bascule des canaux sur J+1 | ✔ |
| 2 | `PRE_CHECKS` | Comptes d'attente non soldés au-delà de l'ancienneté tolérée, caisses non arrêtées, période comptable, partitions | ✔ |
| 3 | `FX_RATES` | Chargement et contrôle des cours de clôture | ✔ |
| 4 | `VALUE_DATE_REBUILD` | Reconstruction des soldes en date de valeur, détection des antidatages | ✔ |
| 5 | `INTEREST_ACCRUAL` | Accruals créditeurs et débiteurs, y compris recalculs rétroactifs | ✔ |
| 5b | `INTEREST_SETTLEMENT` | Capitalisation nette de retenue, arrêté des agios taxe comprise, aux fins de période civiles | ✔ |
| 6 | `LOAN_MOBILISATION` | Intérêts intercalaires, clôture de la mobilisation, échéancier définitif | ✔ |
| 6b | `LOAN_SCHEDULE` | Échéances du jour, exigibilité, prélèvement, passage en impayé | ✔ |
| 6c | `LOAN_INTEREST_ACCRUAL` | Intérêts courus non échus des crédits : étalement de l'intérêt de l'échéance en cours | ✔ |
| 7 | `LOAN_LATE_CHARGES` | Intérêts de retard, pénalités | ✔ |
| 8 | `FEE_CHARGING` | Commissions périodiques, frais de tenue de compte, taxes associées | ✔ |
| 9 | `LOAN_CLASSIFICATION` | Jours de retard, buckets, contagion, provision, suspension | ✔ |
| 9b | `LOAN_CLOSURE` | Clôture des crédits sans échéance à venir ni créance ouverte ; encours résiduel signalé | ✔ |
| 10 | *(fusionné dans `LOAN_CLASSIFICATION`)* | Dotations et reprises, suspension des intérêts | ✔ |
| 11 | `FX_REVALUATION` | Revalorisation des positions de change | ✔ |
| 12 | `DORMANCY` | Détection de dormance (délai du produit, sur l'absence d'opération du client) ; régime de frais : non fait | ✔ (non bloquante) |
| 13 | `HOLD_EXPIRY` | Expiration des blocages de montant arrivés à terme, en date comptable | ✔ |
| 13b | `DIRECT_DEBITS` | Prélèvements à l'échéance : débit du débiteur, crédit sauf bonne fin du créancier, rejets nommés (provision, mandat révoqué, compte inopérable) | ✔ |
| 13d | `TERM_DEPOSIT_ACCRUAL` | Intérêts courus des dépôts à terme, au taux de chaque contrat | ✔ |
| 13e | `TERM_DEPOSIT_MATURITY` | Échéances des dépôts à terme : intérêts servis, terme dénoué (versement ou reconduction au taux du jour) | ✔ |
| 13c | `STANDING_ORDERS` | Ordres permanents à l'échéance : virement interne ou dépôt d'un ordre de paiement, rejets nommés retentés un nombre borné de fois | ✔ |
| 14 | `KYC_REVIEW` | Échéances de revue périodique de la connaissance client ; expiration de documents : non fait | ✔ (non bloquante) |
| 14b | `SUSPENSE_REVIEW` | Revue des suspens : ordres, remises, prélèvements non réglés, comptes d'attente non soldés, par ancienneté en jours ouvrés et responsable ; les retards en anomalies | ✔ (non bloquante) |
| 14c | `AML_MONITORING` | Surveillance LCB-FT : les scénarios actifs appliqués à la journée arrêtée, alertes levées avec leurs pièces | ✔ (non bloquante) |
| 15 | `BALANCE_SNAPSHOT` | Snapshot des soldes par date comptable et par date de valeur, et par agence | ✔ |
| 16 | `RECONCILIATION` | Contrôles d'intégrité (cf. §5), compensation inter-agences comprise | ✔ |
| 17 | `REPORTING` | États quotidiens, extractions vers le datamart | |
| 18 | `OPEN_NEXT_DAY` | Ouverture de la date comptable suivante | ✔ |

Une étape **bloquante** en échec arrête le run. Les autres consignent une anomalie et
laissent le run se poursuivre, avec restitution à la clôture.

> **Implémenté** — la séquence effective est aujourd'hui `PRE_CHECKS` → `FX_RATES` → `HOLD_EXPIRY` →
> `DIRECT_DEBITS` → `STANDING_ORDERS` → `FEE_CHARGING` → `LOAN_MOBILISATION` → `LOAN_SCHEDULE` → `LOAN_INTEREST_ACCRUAL` →
> `LOAN_LATE_CHARGES` → `LOAN_CLASSIFICATION` → `LOAN_CLOSURE` → `TERM_DEPOSIT_ACCRUAL` →
> `TERM_DEPOSIT_MATURITY` → `INTEREST_ACCRUAL` →
> `INTEREST_SETTLEMENT` → `FX_REVALUATION` → `DORMANCY` → `KYC_REVIEW` → `DOCUMENT_EXPIRY` →
> `OFFER_EXPIRY` → `SUSPENSE_REVIEW` → `AML_MONITORING` → `BALANCE_SNAPSHOT` →
> `RECONCILIATION` → `OPEN_NEXT_DAY`. Les étapes absentes s'insèrent sans toucher au moteur.
>
> `FX_RATES` vient avant tout calcul : une journée qui comptabiliserait des intérêts en devise,
> puis découvrirait à la revalorisation qu'il lui manque un cours, aurait à être annulée en
> entier, quand le cours manquant se cote en une minute. Elle bloque sur une position sans cours
> du jour — la revalorisation ne se fait pas au cours de la veille — et sur une devise détenue
> sans position déclarée, que personne ne revaloriserait. `FX_REVALUATION` vient après tous les
> traitements comptables et avant le cliché : elle revalorise ce que la journée a laissé, et le
> cliché doit refléter la journée arrêtée, revalorisation comprise ; son écart va au résultat de
> change, et l'annulation de l'arrêté le contre-passe.
>
> `HOLD_EXPIRY` vient avant tout prélèvement : commissions et échéances se prélèvent sur le
> disponible de la journée arrêtée, blocages expirés compris. `DIRECT_DEBITS` vient juste après,
> avant les commissions et les échéances de crédit de la banque : le prélèvement est un engagement
> du client envers un tiers, pris à date, et son rejet lui est opposable chez le créancier, quand
> commission et échéance ont leur régime de report et de retard ; un rejet est enregistré, pas
> une anomalie, et seul un défaut technique ou de paramétrage — condition de date de valeur
> absente, compte de règlement inconnu — arrête la journée. L'annulation de l'arrêté contre-passe
> les écritures des prélèvements exécutés, lève leurs blocages et les rend à l'attente ; elle est
> refusée, avant de rien défaire, si l'un d'eux a été réglé, remboursé ou retourné depuis. Le TFJ
> à blanc les exécute et n'en laisse rien : l'exécution s'écrit avec la transaction qui la porte.
>
> `STANDING_ORDERS` vient juste après les prélèvements, et avant les commissions : un
> prélèvement est l'engagement du client envers un créancier, dont le rejet lui est opposable ;
> un ordre permanent est son propre ordre, qu'il peut révoquer. Quand la provision ne suffit pas
> aux deux, c'est celui qu'il a donné qui cède. L'échéance rejetée — provision, plafond, compte
> bloqué — se retente le jour ouvré suivant, un nombre borné de fois, puis est abandonnée ;
> seul un défaut technique ou de paramétrage arrête la journée. Vers l'extérieur, l'étape dépose
> un ordre de paiement plutôt que de comptabiliser elle-même. L'annulation de l'arrêté rend
> chaque ordre à l'échéance qu'il a trouvée, sans tentative consommée, et solde l'ordre de
> paiement déposé ; elle est refusée, avant de rien défaire, si celui-ci a été envoyé ou réglé
> depuis. Les écritures de l'étape portent l'identifiant du traitement — un virement de lot non
> rattaché à son arrêté survivrait à son annulation —, et leur clé d'idempotence aussi : rejoué,
> l'arrêté annulé réécrit au lieu de croire avoir viré.
>
> `TERM_DEPOSIT_ACCRUAL` et `TERM_DEPOSIT_MATURITY` viennent avant les intérêts sur dépôts, et
> dans cet ordre : ce qui est servi au client au terme est ce qui a été constaté, journée du terme
> comprise ; et les intérêts qu'un terme verse sur un compte courant entrent dans le solde sur
> lequel ce compte est rémunéré le même jour — l'ordre inverse rémunérerait un solde que le client
> n'a pas encore. Les comptes de dépôt à terme sont **écartés** de `INTEREST_ACCRUAL` : leur
> intérêt n'est pas la rémunération d'un solde au barème du jour, c'est l'exécution d'un contrat à
> taux figé ; les rémunérer là les paierait deux fois, et au mauvais prix. Les deux étapes sont
> bloquantes — un intérêt non constaté surévalue le résultat, un terme non dénoué laisse l'argent
> du client bloqué un jour de plus. L'annulation de l'arrêté efface ses journées d'intérêts,
> ramène le cumul à ce que les journées restantes disent, défait les échéances qu'il a servies et
> supprime la reconduction qu'il a créée : un sous-livre qui survivrait à la contre-passation de
> ses écritures ferait échouer le rapprochement du lendemain.
>
> `DOCUMENT_EXPIRY` suit `KYC_REVIEW` : les deux constatent la même chose — un dossier qui s'est
> périmé pendant la nuit — et ne comptabilisent rien. La pièce expirée est constatée **une seule
> fois** : le constat vit au dossier, et l'étape ne remonte que ce qui n'y figure pas déjà, sinon
> chaque arrêté rejouerait la même alerte jusqu'au renouvellement. Elle ne bloque pas la journée :
> le compte continue de fonctionner, mais plus rien ne s'ouvre sur ce dossier tant que la pièce
> n'est pas renouvelée — la restriction est progressive, et elle s'annonce. L'annulation de
> l'arrêté efface ses constats, et la journée se rejoue à l'identique.
>
> `OFFER_EXPIRY` éteint les accords de crédit non contractualisés dont la validité est passée :
> une décision prise sur une situation ancienne n'est plus une décision, et le dossier se
> réinstruit. L'étape ne comptabilise rien — aucun engagement n'était porté — et ne bloque pas la
> journée ; l'annulation de l'arrêté rend les offres à l'accord.
>
> `SUSPENSE_REVIEW` passe en revue ce qui attend le correspondant — ordres, remises,
> prélèvements non réglés, comptes d'attente non soldés — avec l'ancienneté en jours ouvrés et le
> responsable de la politique, et rend les retards en anomalies non bloquantes, par nature, avec
> le plus ancien ; ce qui bloque, un compte d'attente au-delà de l'ancienneté tolérée — ou non
> soldé, sans politique —, est dit par `PRE_CHECKS`, avant tout calcul.
> `DORMANCY` et `KYC_REVIEW` ne
> comptabilisent rien et ne bloquent pas la journée — un dossier de revue en retard n'empêche pas
> la banque d'arrêter ses comptes ; les dossiers expirés sont rendus en anomalies non bloquantes,
> liste de travail du lendemain. Les trois sont défaites par l'annulation de l'arrêté.
>
> `AML_MONITORING` vient avec les revues, et pour la même raison : un client suspect n'est pas une
> panne de la banque, et une alerte qui empêcherait l'arrêté ferait de la conformité le premier
> obstacle à la comptabilité — l'étape ne bloque donc pas. Elle vient **après** les traitements
> comptables : ce qu'elle regarde est la journée telle qu'elle a été arrêtée, prélèvements et
> échéances comprises ; un scénario qui tournerait avant ignorerait la moitié des mouvements du
> jour. Ce qui fait anomalie ici n'est pas une alerte, c'est un scénario qui ne s'exécute pas —
> un défaut de paramétrage que personne ne verrait autrement avant l'inspection. L'annulation de
> l'arrêté efface les alertes qu'il a levées, **sauf celles déjà prises en instruction ou
> déclarées** : une alerte sans fait derrière elle n'a plus d'objet, mais défaire le travail de
> la conformité parce qu'une journée est rejouée serait pire que de la garder.
>
> Chaque frontière — lancement, reprise, étape, fin, annonce, annulation — est journalisée (SLF4J)
> avec entité, journée, identifiant du traitement, étape, volumes lus et écrits, durée et
> anomalies. Le rapport en base (`batch_step`) reste la référence ; le journal est ce que
> l'astreinte lit en premier.
>
> `BALANCE_SNAPSHOT` tient aussi le **cliché par agence** (`branch_balance_daily`, incrémental,
> rejoué depuis l'origine pour un couple compte-agence qui apparaît) ; `RECONCILIATION` y ajoute
> la **compensation inter-agences** : miroir de chaque compte de liaison entre son agence et le
> siège, élimination totale, et cliché par agence égal au cliché par compte. Un écart nomme
> l'agence et bloque la journée ; le TFM rejoue le même contrôle sur le journal entier.
>
> `LOAN_INTEREST_ACCRUAL` étale l'intérêt contractuel de chaque échéance en cours sur les jours de
> sa période — cumul arrondi, jamais de dérive — et le constate en produits ; à l'échéance, la
> créance reprend les courus. Il vient juste après l'exigibilité, pour que l'échéance réclamée
> aujourd'hui soit complétée dans le même arrêté, et avant la classification, qui commande le
> compte de produit du lendemain. Un échéancier remplacé emporte ses courus ; un crédit suspendu
> les constate en intérêts réservés.
>
> `INTEREST_ACCRUAL` calcule les deux côtés : le côté principal du produit pour tous les comptes
> rattachés, les agios pour ceux dont le produit en déclare — deux séries, deux positions, qui ne
> se compensent jamais. `INTEREST_SETTLEMENT` règle toute position dont une fin de période civile
> est atteinte et pas encore réglée : capitalisation nette de retenue à la source, ou arrêté des
> agios taxe comprise, jusqu'à la fin de période même quand l'arrêté tourne quelques jours après
> elle, date de valeur du lendemain.
>
> `BALANCE_SNAPSHOT` est incrémental : le cliché du jour repart du cliché de la dernière journée
> arrêtée et n'y ajoute que la journée — par sa partition. `RECONCILIATION` contrôle la journée
> contre ce cliché, puis rapproche les sous-livres du grand livre (§5). Le rejeu intégral du journal
> est l'affaire du TFM.
>
> `PRE_CHECKS` garantit la partition du journal pour le mois traité — sa création est idempotente
> et ne préjuge de rien — et refuse une journée qu'aucune période ne couvre, ou dont la période est
> close, en le disant. `OPEN_NEXT_DAY` garantit à la journée suivante ce que sa première écriture
> exigera : les partitions des trois mois à venir et une période comptable qui la couvre. Une
> période close n'est pas rouverte par la bascule : c'est une décision comptable.
>
> `LOAN_CLOSURE` vient après la classification, et pour cette raison : c'est elle qui reprend la
> provision d'un encours devenu nul. Un crédit clos avant elle emporterait sa provision hors du
> portefeuille classé. Un encours résiduel sur un crédit dont tout est réclamé et réglé n'est pas
> clos : c'est un écart entre le compte de prêt et le sous-livre, et l'étape — bloquante — le
> nomme.
>
> Classification et provisionnement sont **une seule étape** et non deux : la provision se calcule
> à partir de la classe, et les séparer laisserait entre elles un instant où le portefeuille est
> classé mais non provisionné — état qu'un TFJ interrompu rendrait durable.
>
> `LOAN_CLASSIFICATION` vient après les charges de retard, et sa décision commande la constatation
> des intérêts du **lendemain**. L'inverse serait circulaire : suspendre les intérêts du jour
> dépendrait de la classe qu'on est en train d'établir.
>
> `LOAN_LATE_CHARGES` suit le prélèvement : un compte provisionné a déjà été débité de son échéance
> et n'a rien à payer au titre du retard. L'ordre inverse pénaliserait un client qui paie.
>
> `FEE_CHARGING` est **bloquante**, contrairement à ce que prévoyait le tableau ci-dessus. Une
> commission non perçue ne laisse aucune trace comptable : l'arrêté reste équilibré, les contrôles
> passent, et le défaut de paramétrage tarifaire ne se découvre qu'à la revue des produits, sans
> moyen de rattraper les périodes écoulées. En revanche une **provision insuffisante** n'est pas une
> anomalie : c'est un fait de gestion, traité selon la politique du produit et consigné liquidation
> par liquidation. La signaler arrêterait le TFJ de la banque entière parce qu'un client est à
> découvert.
>
> `LOAN_MOBILISATION` précède `LOAN_SCHEDULE` parce que c'est elle qui publie l'échéancier
> définitif d'un crédit débloqué par tranches. Une échéance ne se rend pas exigible sur un plan qui
> n'existe pas encore, et l'ordre inverse reporterait d'une journée entière la première échéance de
> tout crédit mobilisé — un décalage invisible, qui ne se verrait qu'au rapprochement des dates de
> valeur. L'étape est **bloquante** : une mobilisation qui ne se clôt pas laisse un crédit sans
> échéancier, donc rien à réclamer, rien en retard, rien à déclasser — le portefeuille paraît sain
> et la comptabilité reste équilibrée. Une tranche non tirée à la date limite, en revanche, n'est
> pas une anomalie : c'est un chantier qui n'a pas avancé, et il n'a pas à bloquer l'arrêté de la
> banque.
>
> `LOAN_SCHEDULE` fait deux choses dans la même étape : rendre l'échéance exigible, puis la
> prélever quand le produit le prévoit. Les séparer ferait apparaître en impayé, entre les deux,
> un compte parfaitement à jour — et sur un TFJ interrompu, ce faux impayé survivrait à la nuit et
> déclencherait des relances.
>
> `FEE_CHARGING` et `LOAN_SCHEDULE` précèdent `INTEREST_ACCRUAL` : une commission s'impute en date de valeur du jour et
> entre donc dans le solde sur lequel les intérêts de ce jour se calculent. `TfjFeeIT` chiffre
> l'écart — sur 10 M XOF et 118 000 de commission, l'ordre inverse rémunérerait 1 644 XOF au lieu de
> 1 624, et l'écart se reporterait sur toute la série des jours suivants puisque le cumul des
> intérêts courus est reconduit de jour en jour. Le prélèvement d'une échéance de crédit produit
> exactement le même effet sur le solde du compte de règlement.

### Ordre non négociable

Trois contraintes d'ordre sont structurelles :

- **4 avant 5** : les accruals se calculent sur les soldes en date de valeur, qui doivent
  être reconstruits d'abord.
- **9 avant 10** : on ne provisionne que ce qui est classé.
- **10 avant 11** : la suspension des intérêts modifie les positions à revaloriser.

### Étapes supplémentaires

**TFM (mensuel)** : arrêté de la balance, contrôle de rejeu **intégral** des soldes, états
réglementaires, clôture de la période. Capitalisation, agios et commissions mensuelles sont des
effets de fin de période des TFJ, pas du TFM : à la fin du mois, ils sont déjà dans les comptes.

> **Implémenté** — `StandardTfm`, sur le même moteur (`RunType.TFM`) : `MONTH_COMPLETE` (chaque
> jour ouvré de la période, depuis la première journée jamais arrêtée par l'entité, a un TFJ
> terminé — les journées manquantes sont nommées), `FULL_RECONCILIATION` (rejeu intégral du journal
> et sous-livres), `PERIOD_CLOSE`. Le traitement porte la date du dernier jour de la période, ne
> touche pas à la date comptable, refuse un mois non terminé ou une date qui n'est pas une fin de
> période. Son annulation rouvre la période en le disant : `REOPENED`, pas `OPEN`.

**TFA (annuel)** : détermination du résultat, affectation, report à nouveau, réouverture des
comptes de bilan, états financiers, liasse réglementaire, archivage de l'exercice.

> **Implémenté** — `StandardTfa`, sur le même moteur (`RunType.TFA`) : `MONTH_COMPLETE`,
> `YEAR_COMPLETE` (chaque mois de l'exercice hors le dernier est clos par un arrêté mensuel, les
> manquants sont nommés), `RESULT_DETERMINATION`, `FULL_RECONCILIATION` (rejeu intégral,
> écritures de résultat comprises), `PERIOD_CLOSE`, `FISCAL_YEAR_CLOSE`. Le traitement porte la
> date de fin de l'exercice (`fiscal_year`, V36 : bornes, statut, compte de résultat, ouvert à
> deux) et **clôt lui-même le dernier mois** : les écritures de résultat lui sont imputées avant
> que la période ne se ferme, et l'arrêté mensuel refuse ce mois-là (« il se clôt par la
> clôture annuelle »). La détermination du résultat solde chaque compte de **nature** résultat
> (`account.nature` : bilan, résultat, hors bilan — une donnée du compte, pas une convention sur
> son code) par agence et par devise sur le compte de résultat de l'exercice, une écriture par
> devise et par agence, équilibrée dans les deux dimensions, lue dans le journal en date de fin
> d'exercice, jamais dans un cliché ; un compte de résultat tenu dans une autre devise que le
> compte de résultat est nommé, jamais soldé en silence. Les soldes de bilan se reportent d'eux-
> mêmes : le journal est continu, il n'y a pas d'à-nouveaux à générer. L'annulation contre-passe
> le résultat **à la date de fin d'exercice**, dans la période rouverte pour cela — datée plus
> tard, elle laisserait les comptes de résultat soldés au 31 et la clôture rejouée ne trouverait
> rien —, et rouvre l'exercice en le disant (`REOPENED`).
>
> **L'affectation du résultat** est faite (V37, `FiscalYears.appropriate`, `RESULT_APPROPRIATION`
> à deux) : la décision de l'assemblée — date, pièce, destinations — devient une écriture datée
> après la fin de l'exercice, qui solde le compte de résultat **là où la clôture l'a porté**,
> agence par agence, sur les comptes que la décision désigne, au siège ; le service
> d'imputation complète les liaisons. Le résultat net se lit dans les écritures de
> détermination de la clôture qui a clos l'exercice, jamais dans un cliché ; les destinations
> sont des comptes généraux de bilan de l'entité, en devise de tenue de compte, et leur somme est
> exactement le résultat : une affectation partielle n'existe pas, le report à nouveau est une
> destination comme une autre. Une affectation ne s'efface pas : pour la refaire, on contre-passe
> son écriture, et le journal le dit. Un résultat affecté retient la clôture : l'annulation du
> TFA est refusée tant que l'affectation n'est pas contre-passée ; affectation et annulation
> s'exécutent sous le **verrou de l'exercice**, deux décisions concurrentes se suivent et la
> seconde voit la première (`concurrent_appropriations_are_serialised`). Un mois ne se rouvre
> pas non plus sous un exercice clos : l'annulation d'un arrêté mensuel est refusée tant que la
> clôture annuelle n'est pas annulée, parce que le résultat a été déterminé avec ce mois.
>
> **Les états financiers** sont faits ([02 §13](02-ledger.md#13-états-financiers)) : bilan,
> compte de résultat et hors bilan sont des maquettes paramétrées, activées à deux, appliquées
> au journal en devise de tenue de compte à toute date — avant comme après la clôture. La
> liasse réglementaire est un jeu de maquettes : ses modèles se chargent comme des maquettes,
> ils ne sont pas du code.

---

## 3. Performance

Cible : 2 millions de comptes traités en moins de 90 minutes, fenêtre de TFJ comprise.

### Partitionnement

```java
@Bean
public Step interestAccrualStep(JobRepository repo, PlatformTransactionManager tm) {
    return new StepBuilder("INTEREST_ACCRUAL", repo)
        .partitioner("worker", new AccountRangePartitioner(64))
        .step(interestAccrualWorkerStep(repo, tm))
        .gridSize(64)
        .taskExecutor(batchTaskExecutor())   // pool borné, dimensionné sur la base
        .build();
}
```

- Partition par **plage de hachage d'identifiant de compte** : réparti uniformément, stable
  d'un run à l'autre, donc rejouable partition par partition.
- Chaque partition est une transaction indépendante avec son propre checkpoint : l'échec
  d'une partition n'invalide pas les autres.
- Le parallélisme est borné par la capacité de la base, pas par le nombre de cœurs
  applicatifs. Sur-paralléliser dégrade le débit par contention sur les comptes généraux.

### Règles d'implémentation

| Règle | Raison |
|---|---|
| Lecture en flux (curseur), jamais `findAll()` | Une liste de 2 M d'objets sature la mémoire |
| Écriture par lots de 5 000 à 10 000 lignes | Optimum mesuré insertion / taille de transaction |
| Agrégation des soldes en mémoire par partition | Un `UPDATE` par compte au lieu d'un par écriture |
| Aucun accès réseau externe dans une étape | Un appel HTTP par compte multiplie la durée par 50 |
| Index dédiés aux requêtes de batch | Les plans d'exécution OLTP ne conviennent pas au batch |
| `COPY` pour les insertions de masse | 3 à 5 fois plus rapide qu'`INSERT` unitaire |

### Comptes de contrepartie chauds

L'accrual d'intérêts impute des millions de lignes sur quelques comptes de charges. Sans
striping ([02](02-ledger.md#7-concurrence-et-comptes-chauds)), ces comptes sérialisent tout le batch. Avec striping à
N = 64, la contention devient négligeable.

---

## 4. Reprise sur incident

### Échec d'une étape

1. Le run passe en `EN_ÉCHEC`, l'étape et la partition fautives sont identifiées.
2. Diagnostic : les compteurs (lus / traités / rejetés) et les enregistrements en anomalie
   sont consultables dans la console d'exploitation.
3. Correction — donnée, paramétrage ou correctif.
4. Reprise : `POST /eod/runs/{id}/resume`. Les étapes terminées ne sont pas rejouées ; les
   partitions terminées de l'étape en échec ne le sont pas non plus.

### Annulation d'un TFJ

```
POST /eod/runs/{id}/cancel
```

- Contre-passation de **toutes** les écritures portant ce `batch_run_id`, en date comptable
  du jour ou de l'originale selon la politique de l'entité.
- Restauration des statuts modifiés (classification, dormance) depuis l'historique.
- Réouverture de la date comptable.
- Opération soumise à double validation, tracée et notifiée.

> **Implémenté** — l'annulation neutralise chaque sous-livre : échéances rendues à nouveau
> exigibles, créances annulées, intérêts de retard repris, classifications neutralisées,
> mobilisations rouvertes, **crédits clos par le traitement rendus actifs**, blocages de montant
> reposés, dormances défaites, revues de connaissance client restaurées. Et elle est
> **ordonnée** : une journée ne s'annule pas tant qu'une journée suivante est arrêtée. Restaurer la
> date à J sous un J+1 arrêté laisserait J+1 tenue pour faite sur un état que ses écritures ne
> décrivent plus ; les journées s'annulent de la plus récente à la plus ancienne.

C'est la fonction qui distingue un TFJ industriel d'un script de batch. Sans elle, une
erreur de paramétrage détectée après l'arrêté impose une correction manuelle compte par
compte — en pratique, plusieurs semaines de travail et un risque d'erreur majeur.

### Retard de TFJ

Si le TFJ du jour J n'a pas été exécuté, le TFJ de J+1 est **refusé**. Le rattrapage
s'effectue en exécutant les TFJ dans l'ordre chronologique, chacun avec sa date comptable et
son paramétrage d'époque. Aucun mécanisme de « fusion » de plusieurs jours : il produirait
des intérêts faux.

Corollaire d'exploitation : un TFJ en retard se rattrape en enchaînant autant de TFJ que de
jours manqués. La fenêtre de nuit doit donc pouvoir absorber deux à trois TFJ consécutifs —
c'est le dimensionnement à retenir, pas la durée d'un TFJ isolé.

---

## 5. Contrôles de réconciliation

Exécutés à l'étape `RECONCILIATION`, tous bloquants. Le rejeu intégral du journal coûte
O(historique) ; il est réservé au TFM. Chaque nuit, le contrôle porte sur la **journée** et, par
récurrence depuis le dernier rejeu intégral vérifié, donne la même garantie.

> **Implémenté** — chaque nuit : `BALANCE_EQUILIBREE` sur les lignes comptabilisées depuis le
> cliché précédent, `SOLDE_MATERIALISE_VS_CLICHE` (solde matérialisé = cliché du jour, plus les
> lignes datées après la journée), `STRIPES_COMPLETES` ; et les sous-livres, chacun apporté par son
> module par `Reconciliation.Check` : `SOUS_LIVRE_INTERETS_COURUS` (Σ imputé − réglé des positions
> = solde de chaque compte de courus), `SOUS_LIVRE_CREANCES_CREDIT` (créances ouvertes hors capital
> = compte de créances rattachées), `SOUS_LIVRE_ENCOURS_CREDIT` (par crédit : compte de prêt =
> capital restant dû de l'échéancier + capital échu impayé, ou tranches versées), `SOUS_LIVRE_ICNE_CREDIT`
> (courus des échéances en cours = compte de courus), `SOUS_LIVRE_COMMISSIONS` (chaque commission
> perçue par le traitement = total débité par son écriture). Au TFM : `BALANCE_EQUILIBREE` et
> `SOLDE_MATERIALISE_VS_REJOUE` depuis l'origine, puis les mêmes sous-livres. Un écart nomme le
> compte, le crédit ou la commission, l'attendu et le constaté.

```sql
-- 1. Balance générale équilibrée, par entité et par devise
SELECT legal_entity_id, currency,
       SUM(CASE WHEN direction = 'DEBIT'  THEN amount ELSE 0 END)
     - SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE 0 END) AS ecart
  FROM journal_line l JOIN journal_entry e ON e.id = l.entry_id
 WHERE e.booking_date <= :business_date
 GROUP BY legal_entity_id, currency
HAVING SUM(CASE WHEN direction = 'DEBIT'  THEN amount ELSE 0 END)
     <> SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE 0 END);

-- 2. Soldes matérialisés = soldes rejoués depuis le journal
SELECT b.account_id, b.balance AS materialise, r.balance AS rejoue
  FROM account_balance_agg b
  JOIN replay_balance(:business_date) r USING (account_id)
 WHERE b.balance <> r.balance;

-- 3. Σ comptes clients d'une classe = solde du compte général de rattachement
-- 4. Σ stripes = solde agrégé
-- 5. Suspens non soldés au-delà du seuil d'ancienneté
-- 6. Écarts d'arrondi cumulés dans les bornes paramétrées
-- 7. Aucune écriture en période clôturée
-- 8. Contre-valeur de chaque position de change = contre-valeur historique + revalorisations
```

Un écart, même unitaire, bloque la bascule de journée et déclenche une alerte de
niveau critique.

Ce choix est délibéré. La tentation d'un seuil de tolérance est forte en exploitation ; elle
est toujours perdante : un écart accepté aujourd'hui devient un écart de plusieurs milliers
en fin d'exercice, dont l'origine est alors introuvable.
