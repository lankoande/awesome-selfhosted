# 12 — Inventaire du paramétrage

Recensement de tout ce qui varie — dans le temps, par pays, par produit ou par entité — avec
l'endroit où la valeur est stockée et son état d'implémentation.

Ce document existe pour une raison précise : un paramètre oublié ne se signale pas. Il reste figé
dans le code, produit des montants plausibles, et n'est découvert qu'au premier changement de
barème ou à la première ouverture de pays.

| Marque | Sens |
|---|---|
| ✅ | Implémenté et testé |
| 🔶 | Table présente, exploitation partielle |
| ⬜ | Conçu, pas encore implémenté |

---

## 1. Le mécanisme

```
product_version                    une version d'un produit, sur une période de validité
  ├── période          valid_from / valid_to (bornes incluses)
  ├── statut           DRAFT → ACTIVE, activation soumise à double validation
  ├── product_parameter        paramètres scalaires, clé/valeur typée à la lecture
  └── product_rate_tier        barème par tranches, contiguïté vérifiée à la saisie

account_product                    rattachement d'un compte à un produit, daté
product_audit                      journal immuable des modifications de paramétrage
```

Trois garanties portées par la base, pas seulement par l'applicatif :

| Garantie | Mise en œuvre | Test |
|---|---|---|
| Jamais deux versions actives simultanées | `EXCLUDE USING gist` sur la plage de validité | `overlapping_versions_are_rejected` |
| Le rédacteur n'active pas son propre paramétrage | `CHECK (approved_by <> created_by)` | `maker_cannot_be_checker` |
| Journal du paramétrage immuable | Déclencheur `BEFORE UPDATE/DELETE` | `product_audit_is_immutable` |

**La résolution se fait toujours à la date de la journée traitée**, jamais à celle du traitement.
Une période non couverte lève une erreur nommant le produit et la date — aucun repli sur la version
la plus proche. Se rabattre sur une autre version produit des montants faux que rien ne signale
ensuite.

---

## 2. Ce qui est paramétré aujourd'hui

### Schémas comptables — `accounting_schema` ✅

Traduction `événement métier → jeu d'écritures`, versionnée, datée, soumise à double validation.

| Table | Contenu |
|---|---|
| `accounting_schema` | Version datée, devise, statut, approbation à quatre yeux |
| `accounting_schema_derivation` | Variables calculées, dans l'ordre d'évaluation |
| `accounting_schema_line` | Compte (par rôle), sens, expression de montant, condition |

Les comptes sont désignés **par leur rôle** — `CONTRACT`, `GL:70611`, `RESOLVE:cash`,
`PARAM:fee_income` — jamais par identifiant technique. Le même schéma fonctionne donc dans deux
filiales aux plans comptables différents.

**Un schéma déséquilibré ne peut pas être enregistré** : la validation par tirage s'exécute avant
l'insertion. Elle vérifie l'équilibre *après arrondi à l'échelle de la devise*, ce qui attrape le
défaut réellement coûteux — trois composantes arrondies séparément qui ne se recomposent plus.

Ce que cela débloque directement : **commissions et taxes deviennent des lignes de schéma**. Une TVA
qui change de taux est une nouvelle version datée, pas une livraison.


### Plafonds, paiements sortants, chèques et prélèvements — bloc `operations`, `account_limit`, `cheque_*`, `debit_mandate`, `direct_debit` ✅

| Paramètre | Valeurs | Effet |
|---|---|---|
| `ops.transaction_max` | décimal | Débit maximal par opération du client (retrait, virement, paiement sortant) |
| `ops.daily_debit_max` | décimal | Somme des débits du jour, frais compris, hors écritures contre-passées |
| `ops.monthly_debit_max` | décimal | Même somme sur le mois civil |
| `term.rate` | décimal | Taux de référence d'un dépôt à terme, en pourcentage annuel : ce que l'agence propose. Le contrat le fige à la souscription |
| `term.max_rate` | décimal | Plafond de taux que la banque s'autorise à consentir ; absent, le taux de référence — le prix de la ressource ne se fixe pas en agence |
| `term.penalty_rate` | décimal | Taux servi à qui reprend ses fonds avant terme ; absent, zéro. Servir le taux convenu à qui ne tient pas la durée reviendrait à le servir à tout le monde |
| `term.day_count` | énum | Base de calcul des intérêts du dépôt à terme |
| `term.min_amount` | décimal | Capital minimum admis |
| `term.min_months`, `term.max_months` | entier | Bornes de durée, en mois pleins |
| `term.accrued_interest` | UUID (compte général) | Intérêts courus non échus des dépôts à terme, au passif : le sous-livre que le rapprochement de chaque nuit confronte aux contrats |
| `term.interest_expense` | UUID (compte général) | Charge d'intérêts : ce que le dépôt coûte, reconnu jour après jour |
| `term.withholding_rate`, `term.withholding_account` | décimal, UUID | Retenue à la source sur les intérêts servis ; le taux exige son compte |
| `term.renewal` | énum | `NONE` interdit la reconduction : un dépôt reconduit sans l'avoir demandé bloquerait l'argent du client une période de plus |
| `ops.payment_fee` | décimal | Frais forfaitaire d'un paiement sortant ; exige `ops.fee_income_account`. Un ordre permanent vers l'extérieur le paie : il dépose un ordre de paiement |
| `ops.payment_clearing_account` | UUID (compte général) | Compte de règlement sortant ; absent, le produit n'admet pas de paiement sortant |
| `ops.cheque_book_fee` | décimal | Frais de délivrance d'un chéquier, taxé au taux du produit ; absent, le chéquier est gratuit |
| `ops.cheque_collection_account` | UUID (compte général) | Compte de chèques à l'encaissement ; absent, le produit n'admet pas de remise de chèque |
| `ops.direct_debit_fee` | décimal | Frais d'un prélèvement, reçu ou émis, à la charge du client de la banque, taxé au taux du produit |
| `ops.direct_debit_collection_account` | UUID (compte général) | Compte de prélèvements à l'encaissement ; absent, le produit n'émet pas de prélèvement. Un prélèvement reçu d'un créancier d'ailleurs exige `ops.payment_clearing_account` |

Un compte porte son propre plafond (`account_limit` : nature, montant, validité, à deux, sans
chevauchement par nature) ; il remplace celui du produit, dans un sens comme dans l'autre. Les
chèques ne consomment pas ces plafonds.

| Table | Contenu |
|---|---|
| `cheque_book` | Plage de numéros délivrée à un compte, à deux ; deux chéquiers d'un compte ne se chevauchent pas (exclusion sur `int8range`) ; frais et écriture de frais |
| `cheque` | Un chèque par numéro : état (`UNUSED`, `PAID`, `STOPPED`, `REJECTED`), montant, porteur, date et écriture du paiement, clé de rejeu, opposition et son motif |
| `cheque_incident` | Chèque présenté sans provision : montant, date, motif, présentateur — survit au refus |
| `cheque_deposit` | Remise : chèque désigné (banque tirée, numéro, tireur), écriture et blocage, compte d'encaissement, règlement (nostro, écriture) ou impayé (motif, contre-passation) |
| `debit_mandate` | Mandat : compte débiteur, référence unique par créancier, créancier de la banque (compte) ou d'ailleurs (banque, compte) — l'un ou l'autre —, signature, validité, plafond par prélèvement, révocation datée et motivée, à deux |
| `fx_rate` | Cours de référence par entité, devise et jour : unités de la devise de tenue pour une unité cotée, source, à deux ; unique par jour et devise |
| `fx_position` | Position de change d'une devise : compte de position (créditeur, en devise), contre-valeur (débiteur, en devise de tenue), comptes de gain et de perte, marge tolérée en points de base, à deux |
| `channel_cutoff` | Heure limite d'un canal (ou de tous), dans le fuseau de l'entité, `closes_channel`, validité sans chevauchement par canal, à deux |
| `suspense_policy` | Par nature de suspens : ancienneté tolérée en jours ouvrés, responsable, validité sans chevauchement, à deux |
| `kyc_policy` / `kyc_policy_document` | Politique de diligence par nature de tiers et niveau : pièces exigées, bénéficiaires effectifs requis, seuil de détention, à deux ; une seule par couple, elle se remplace (V46, V47) |
| `lending_policy` | Politique d'octroi d'un produit : taux d'endettement maximal, montant et durée maximaux, apport minimal, garantie exigée, validité de l'offre en jours ; datée, sans chevauchement, à deux |
| `loan_write_off` / `loan_recovery` | Passage en perte d'un credit : ce qui sort de l'actif, ce qui l'absorbe (intérêts réservés, provision), la perte constatée et la reprise ; recouvrements ultérieurs, avec le compte par lequel ils arrivent |
| `direct_debit` | Prélèvement reçu ou émis : compte du client, mandat, montant, frais et taxe, échéance, tiers, état (`PENDING`, `COLLECTED`, `SETTLED`, `REJECTED`, `CANCELLED`, `RETURNED`, `REFUNDED`), exécution (date, traitement, écriture, blocage, compte de règlement ou d'encaissement, motif de rejet), règlement, clôture (date, écriture, motif) |

### Maquettes d'états financiers — `statement_layout` ✅

Bilan, compte de résultat et hors bilan sont des maquettes : des rubriques et des règles
d'affectation, versionnées par validité, rédigées puis activées à deux, une seule active par
nature d'état et par date ([02 §13](02-ledger.md#13-états-financiers)).

| Table | Contenu |
|---|---|
| `statement_layout` | Nature d'état, code, validité, statut, approbation à quatre yeux |
| `statement_line` | Rubrique : rang, code, libellé, niveau, nature (détail, total, résultat de l'exercice), sens, rubriques sommées et retranchées |
| `statement_rule` | Règle d'affectation, dans l'ordre : rubrique de détail visée, nature de compte, préfixe de code, sens du solde |

**Une maquette fausse n'entre pas en base** : totaux qui ne somment que des rubriques qui les
précèdent, règles vers des rubriques de détail existantes, au moins un critère par règle, un seul
résultat de l'exercice et seulement au bilan. Un compte qu'aucune règle ne reçoit n'est pas une
erreur de maquette mais une anomalie nommée de l'état produit.

### Calendrier et dates de valeur ✅

| Table | Contenu |
|---|---|
| `business_calendar` | Période **réellement saisie** : au-delà, le calendrier refuse de répondre |
| `calendar_weekend` | Jours de week-end — paramétrés, car ils ne tombent pas partout le samedi et le dimanche |
| `calendar_holiday` | Fériés, date par date |
| `value_date_rule` | Décalage par type d'opération, canal et **sens**, avec convention de report |

**La date de valeur se calcule, elle ne se fournit pas.** Tant que l'appelant la transmet, chaque
canal finit par appliquer sa propre lecture des conditions de banque, et l'écart ne se voit pas :
l'écriture est équilibrée, la comptabilité juste, seuls les agios sont faux.

**Le sens fait partie de la clé** — c'est le sujet. Les conditions décalent rarement le débit et le
crédit de la même façon : un retrait porte souvent une date de valeur antérieure, un versement une
date postérieure. Ces journées sont un produit pour la banque et un coût pour le client ; un modèle
qui n'en tiendrait pas compte ne saurait pas représenter les conditions réellement pratiquées.

Le prix est mesuré par un test : sur 10 M XOF à 6 %, **deux jours de valeur valent 3 288 XOF par
opération**. Sur cent mille versements par mois, l'écart dépasse trois cents millions par an.

Deux unités de décalage, et la distinction est financière : « deux jours ouvrés » depuis un
vendredi donne le mardi, « deux jours calendaires ajustés au suivant » donne le lundi.

Conventions de report : `UNADJUSTED`, `FOLLOWING`, `MODIFIED_FOLLOWING`, `PRECEDING`,
`MODIFIED_PRECEDING`. Sur une échéance de fin de mois tombant un dimanche, `FOLLOWING` la bascule
d'un exercice mensuel à l'autre, `MODIFIED_FOLLOWING` la garde dans son mois. Les deux sont
licites ; aucune n'est un défaut raisonnable.

### Intérêts — `product_parameter` ✅

| Paramètre | Valeurs | Effet |
|---|---|---|
| `interest.rate` | décimal | Taux annuel en pourcentage |
| `interest.day_count` | `ACT_360`, `ACT_365`, `ACT_ACT_ISDA`, `THIRTY_360_US`, `THIRTY_E_360` | Convention de décompte |
| `interest.side` | `CREDITOR`, `DEBTOR` | Côté de l'échelle rémunéré |
| `interest.tiering_mode` | `PROGRESSIVE`, `WHOLE_BALANCE` | Mode d'application du barème |
| `interest.debit_account` | UUID | Compte débité de l'écriture d'intérêts courus |
| `interest.credit_account` | UUID | Compte crédité |
| `interest.capitalisation` | `MONTHLY`, `QUARTERLY`, `SEMIANNUAL`, `ANNUAL` | Périodicité civile de règlement des courus au client — exigée : un produit qui ne règle jamais est un défaut |
| `interest.withholding` | code (`IRC`, `IRCM`…) | Retenue à la source appliquée aux intérêts créditeurs ; absent : exonéré |

Barème par tranches dans `product_rate_tier` : bornes et taux par tranche.

### Agios — `product_parameter` ✅

Le côté débiteur d'un compte courant, facultatif en bloc, exigeant dès qu'il est entamé.

| Paramètre | Valeurs | Effet |
|---|---|---|
| `overdraft.rate` | décimal | Taux annuel dans l'autorisation |
| `overdraft.excess_rate` | décimal | Taux de la part au-delà de l'autorisation, celui de l'autorisation à défaut |
| `overdraft.limit` | décimal | Autorisation par défaut du produit ; celle du compte (`overdraft_limit`, datée) l'emporte |
| `overdraft.day_count` | convention | Celle du côté principal à défaut |
| `overdraft.debit_account` / `.credit_account` | UUID | Agios courus à recevoir (actif), produit d'intérêts sur découverts |
| `overdraft.settlement` | périodicité | Arrêté des agios, débité au client taxe comprise |
| `overdraft.tax_rate` / `.tax_account` | décimal, UUID | Taxe sur les agios (TOB, TAF) et son compte de collecte |

### Opérations et dormance — `product_parameter` ✅

Frais d'opération d'un compte de dépôt et délai de dormance, sur les familles `CURRENT_ACCOUNT` et
`SAVINGS_ACCOUNT`.

| Paramètre | Valeurs | Effet |
|---|---|---|
| `ops.withdrawal_fee` | décimal | Forfait par retrait d'espèces, dans la même écriture que le retrait |
| `ops.transfer_fee` | décimal | Forfait par virement interne, à la charge de l'émetteur. Un ordre permanent interne le paie ; un balayage vire ce qui dépasse le plancher **net de ce frais et de sa taxe**, pour que le plancher promis reste sur le compte |
| `ops.fee_income_account` | UUID | Compte de produit des frais d'opération — exigé dès qu'un frais est paramétré |
| `ops.tax_rate` / `ops.tax_account` | décimal, UUID | Taxe sur les frais d'opération et son compte de collecte, l'un exigeant l'autre |
| `dormancy.months` | entier positif | Mois sans opération à l'initiative du client avant dormance ; absent : le produit ne connaît pas la dormance |

Les dates de valeur des opérations ne sont pas des paramètres produit : ce sont des conditions
de banque de l'entité (`value_date_rule`, par type d'opération, canal et sens), et leur absence
refuse l'opération.

### Retenues à la source — `interest_withholding` ✅

Par entité juridique, donc par pays : code, taux, compte de reversement, période de validité sans
chevauchement. Le taux est résolu **à la fin de période réglée**, jamais à la date du jour : une loi
de finances au 1ᵉʳ janvier ne change pas la capitalisation de décembre. Un produit qui désigne une
retenue sans taux en vigueur ne capitalise pas — il bloque, plutôt que de payer brut.

**Taux et convention de jours peuvent varier d'une journée à l'autre** — c'est le cas normal d'un
changement de barème, et chaque journée est rémunérée au taux en vigueur ce jour-là
(`each_day_uses_the_rate_in_force_that_day`).

**Le côté rémunéré et les comptes d'imputation ne le peuvent pas** au sein d'un même calcul :
l'écriture produite est unique et ne saurait viser deux couples de comptes. Le refus est explicite —
une imputation sur le mauvais compte de résultat ne se détecte qu'à l'arrêté.

### Commissions et frais — `product_parameter` ✅

Un produit déclare ses commissions dans `fee.codes`, séparées par des virgules, et décrit chacune
par des paramètres préfixés de son code.

| Paramètre | Valeurs | Effet |
|---|---|---|
| `fee.<code>.frequency` | `DAILY`, `MONTHLY`, `QUARTERLY`, `SEMIANNUAL`, `ANNUAL` | Périodicité |
| `fee.<code>.anchor` | date, *absent* | Origine des périodes ; absent, c'est la date d'ouverture du compte |
| `fee.<code>.timing` | `IN_ARREARS`, `IN_ADVANCE` | Terme échu ou terme à échoir |
| `fee.<code>.basis` | `FLAT`, `RATE_ON_CLOSING_BALANCE`, `RATE_ON_HIGHEST_DEBIT_BALANCE`, `TIERED_ON_CLOSING_BALANCE` | Assiette |
| `fee.<code>.amount` / `.rate` | décimal | Forfait, ou taux appliqué à l'assiette |
| `fee.<code>.floor` / `.cap` | décimal | Perception minimale et maximale, exigées imputables |
| `fee.<code>.proration` | `NONE`, `ACTUAL_DAYS` | Traitement d'une période incomplètement servie |
| `fee.<code>.tax_rate` | décimal | TOB, TAF, TVA — assise sur le net arrondi |
| `fee.<code>.income_account` / `.tax_account` | UUID | Comptes de produit et de taxe collectée |
| `fee.<code>.on_insufficient_funds` | `REJECT`, `FORCE`, `DEFER` | Conduite à tenir sans provision |
| `fee.<code>.arrear_max_age_days` | entier | Terme au-delà duquel une créance reportée est abandonnée |
| `fee.<code>.schema` | code | Schéma comptable, celui du standard à défaut |
| `overdraft.limit` | décimal | Découvert autorisé, ajouté au disponible lors du contrôle |

Barème par tranches dans `product_rate_tier`, discriminé par `purpose = 'FEE:<code>'` : un même
produit porte ainsi un barème d'intérêts et un barème de commission sans devoir être scindé.

**Deux ancrages sont légitimes**, et l'absence du paramètre en désigne un. Une date fixe du produit
facture tous les comptes aux mêmes échéances — commode pour rapprocher les états de gestion. La date
d'ouverture du compte lisse la charge du traitement sur le mois. Aucune n'est un défaut caché : ce
sont deux décisions de gestion.

**Les périodes se calculent depuis l'ancrage, jamais de proche en proche.** Une échéance au 31
janvier ramenée au 28 février reviendrait au 28 mars si l'on déduisait chaque échéance de la
précédente : le contrat aurait changé de jour d'échéance à cause d'une année non bissextile, sans
qu'aucune décision ne l'ait voulu (`pas_de_derive_de_fin_de_mois`).

### Exonérations — `account_fee_exemption` ✅

Fenêtre datée par compte et par commission, avec motif. Renoncer à une commission, c'est renoncer à
un produit : l'opération relève du même régime de double validation qu'un paramétrage tarifaire, et
la contrainte est portée par la base (`CHECK (approved_by <> granted_by)`).

Une période entièrement exonérée est **enregistrée avec son montant** et le dénouement `WAIVED` :
c'est ce qui rend le coût des gestes commerciaux mesurable. Une exonération partielle ne réduit la
commission que si celle-ci est proratisable ; sinon elle reste due en entier.

### Crédits — `product_parameter` ✅

Ce que le **produit** fixe ; le montant, la durée, le taux et la méthode d'amortissement varient
d'un dossier à l'autre et appartiennent au contrat.

| Paramètre | Valeurs | Effet |
|---|---|---|
| `loan.allocation_order` | liste de catégories | Ordre d'imputation d'un règlement, exigé exhaustif |
| `loan.direct_debit` | `true`, `false` | Prélèvement d'office à l'exigibilité |
| `loan.grace_days` | entier | Délai de grâce avant comptage des jours de retard |
| `loan.accrued_receivable` | UUID | Créances rattachées, débitées à l'exigibilité |
| `loan.accrued_interest` | UUID | Intérêts courus non échus : l'intérêt de l'échéance en cours, constaté jour après jour, repris par la créance à l'échéance |
| `loan.interest_income` | UUID | Produit d'intérêts |
| `loan.insurance_income` / `.fee_income` | UUID | Ventilation fine, celle des intérêts à défaut |
| `loan.tax_account` | UUID | Taxe collectée sur intérêts |

**L'ordre d'imputation est refusé s'il est incomplet.** Une catégorie omise rendrait la créance
correspondante impayable : les règlements passeraient à côté, elle vieillirait, déclencherait des
pénalités puis un déclassement, sans qu'aucune erreur ne soit jamais signalée. C'est la même
exigence d'exhaustivité que celle de la politique d'habilitation.

### Régime de retard — `product_parameter` ✅

| Paramètre | Valeurs | Effet |
|---|---|---|
| `loan.late_interest_rate` | décimal | Taux annuel de l'intérêt de retard |
| `loan.late_interest_basis` | `OVERDUE_PRINCIPAL`, `TOTAL_OVERDUE` | Assiette, hors créances de retard |
| `loan.late_day_count` | conventions de décompte | Base de calcul |
| `loan.penalty_mode` | `NONE`, `FLAT_PER_INSTALMENT`, `PERCENT_OF_OVERDUE` | Mode de la pénalité |
| `loan.penalty_amount` / `.penalty_rate` | décimal | Forfait ou taux |
| `loan.penalty_floor` / `.penalty_cap` | décimal | Encadrement de la pénalité |
| `loan.max_rate` | décimal | Plafond du cumul taux nominal + taux de retard |
| `loan.late_interest_income` / `.penalty_income` | UUID | Produits sur créances en souffrance |

**L'absence de régime est un choix licite** — tous les produits ne facturent pas le retard — et se
distingue d'un paramétrage incomplet, qui est refusé.

**`loan.max_rate` n'est pas le contrôle du taux d'usure.** Celui-ci porte sur le TEG et relève de
l'octroi, qui n'est pas implémenté. C'est un garde-fou contre un paramétrage aberrant, et il est
nommé comme tel.

### Coût du crédit et remboursement anticipé — `product_parameter` ✅

| Paramètre | Effet |
|---|---|
| `loan.teg_method` | `PROPORTIONAL` ou `ACTUARIAL` — la convention fait partie du chiffre |
| `loan.usury_rate` ⚠ | Plafond d'usure, porté sur le taux **effectif** |
| `loan.prepayment_indemnity_rate` | Indemnité contractuelle, en % du capital remboursé |
| `loan.prepayment_cap_percent` ⚠ | Plafond légal en % du capital remboursé |
| `loan.prepayment_cap_months` ⚠ | Plafond légal en mois d'intérêts |
| `loan.prepayment_indemnity_account` | Compte de produit, distinct des intérêts |

Les deux plafonds d'indemnité s'appliquent ensemble, et c'est **le plus bas** qui l'emporte.

### Déblocage par tranches — `loan_mobilisation` et `loan_tranche` ✅

Le plan de déblocage n'est **pas** un paramètre produit : il appartient au dossier, comme le
montant et la durée. Ce que le socle contrôle à l'ouverture, il le tire du plan et des conditions
du contrat, pas du paramétrage.

| Élément | Contenu |
|---|---|
| Tranches | rang, date prévue, montant engagé, condition en clair (« fondations achevées ») |
| Date limite | fin de la période de mobilisation, obligatoirement antérieure à la première échéance |
| Durée accordée | nombre d'échéances, différé, première échéance — logés sur la mobilisation tant qu'il n'existe pas d'échéancier |
| Frais de dossier | retenus sur la première tranche, et sur elle seule |

Les paramètres produit employés sont ceux du crédit ordinaire : `loan.interest_income`,
`loan.accrued_receivable`, `loan.tax_account`, `loan.fee_income` pour les frais retenus,
`loan.teg_method` et `loan.usury_rate` pour le contrôle du coût, `loan.direct_debit` pour le
prélèvement des intérêts intercalaires — qui sont des créances ordinaires.

**La condition de déblocage est conservée en clair et jamais interprétée.** Sa constatation est un
acte humain ; prétendre l'automatiser reviendrait à débloquer des fonds sur la foi d'une date.

**Le contrôle d'usure joue deux fois, et pas de la même façon.** À l'ouverture, sur le coût
prévisionnel — toutes tranches tirées comme prévu — et il **refuse** : aucun franc n'est sorti. À la
clôture, sur les dates réelles de versement, et il ne peut plus refuser : le dépassement est signalé
comme anomalie bloquante de l'arrêté, la ristourne de frais qui le corrige étant une décision ⚠.

**La commission d'engagement sur la fraction non tirée n'a pas de barème dédié** ⚠. Elle se
paramètre comme une commission ordinaire ; son assiette — l'engagement non mobilisé — n'est pas
encore une assiette reconnue par `FeeBasis`.

### Profil de risque — `risk_profile` et `risk_bucket` ✅

Grille datée, versionnée, sous double validation, résolue à la date de l'arrêté.

| Élément | Contenu |
|---|---|
| Classes | rang, code, bornes de retard, taux de provision, caractère sain ou en souffrance |
| Contagion | `NONE` ou `CUSTOMER` |
| Seuil de suspension | code de la classe à partir de laquelle les intérêts sont réservés |
| Période d'observation | `cure_days` : jours sans incident avant retour à meilleure fortune |

Rattachement au produit par `loan.risk_profile` ; comptes d'imputation par
`loan.provision_expense`, `loan.provision_allowance`, `loan.provision_release` et
`loan.reserved_interest`.

**Les valeurs numériques d'une grille UEMOA ne sont pas codées ici.** Les seuils de déclassement et
les taux de provisionnement relèvent de l'instruction en vigueur et doivent être saisis au
paramétrage ⚠ — les grilles qui figurent dans les tests sont illustratives et nommées comme telles.

**La grille est revalidée à chaque relecture**, et pas seulement à l'enregistrement : un correctif
manuel sur une ligne de barème s'appliquerait sinon à tout le portefeuille au prochain arrêté.

### Garanties — `collateral_policy`, `collateral`, `collateral_allocation` ✅

Le régime d'éligibilité est **paramétré par type de sûreté**, daté et sous double validation :
quotité retenue et ancienneté maximale de l'expertise. La sûreté, elle, porte la valeur de l'actif,
le montant garanti, son rang, et la date de son expertise ; son affectation à un crédit porte une
quote-part.

| Table | Ce qu'elle fixe |
|---|---|
| `collateral_policy` ⚠ | Quotité et fraîcheur exigée, par type de sûreté |
| `collateral` | Valeur de l'actif, montant garanti, rang, expertise, mainlevée |
| `collateral_allocation` | Quote-part affectée à chaque crédit, somme plafonnée à 100 % |

**La quotité ne se saisit pas sur le dossier.** La laisser saisir reviendrait à laisser un agent
décider du niveau de provision de son propre portefeuille. Les valeurs réglementaires ⚠ relèvent de
l'instruction en vigueur.

### Agences et comptes de liaison — `branch`, `branch_liaison` ✅

| Élément | Contenu |
|---|---|
| Siège | Un par entité, créé avec elle ; porte le miroir de toutes les liaisons |
| Agence, région | Code, nom, rattachement, statut ; comptes de liaison par devise, celui de la devise de tenue de compte obligatoire |
| Schéma de liaison | `legal_entity.interbranch_scheme` : `VIA_HEAD_OFFICE` seul implémenté ; bilatéral et via la région s'ajouteront par migration |
| Plafonds déplacés | Dans `SecurityConfig`, par rôle, jamais dans le jeton |

### Surveillance LCB-FT — `monitoring_scenario`, `party_activity_profile` ✅

| Élément | Contenu |
|---|---|
| Scénario | Code, libellé, méthode (`CASH_THRESHOLD`, `STRUCTURING`, `ATYPICAL_ACTIVITY`, `DORMANT_REACTIVATION`), validité datée, deux signatures |
| Seuil, fenêtre | `threshold_amount`, `window_days` — le montant au-delà duquel on alerte, et la période sur laquelle on cumule |
| Nombre minimal | `minimum_count` — pour le fractionnement : combien d'opérations sous le seuil font une suite (au moins deux) |
| Ratio | `ratio` — pour l'atypie : combien de fois le flux mensuel déclaré fait un écart, dans un sens comme dans l'autre |
| Population visée | `risk_rating` — un seuil de dossiers renforcés ne noie pas la banque entière ; absent, le scénario vise tout le monde |
| Profil déclaré | `party_activity_profile` : flux mensuels attendus au crédit et au débit, recueillis au guichet — la référence de l'atypie |

La méthode est du code, tout le reste est du paramétrage : la base vérifie par contrainte que
chaque méthode a les paramètres qu'elle exige, et le service les redit à la soumission — un
scénario incomplet ne surveille rien, et personne ne s'en apercevrait. Il les revérifie **à chaque
exécution**, parce que la table se laisse aussi écrire par une reprise de données : une fenêtre
hors du domaine (1 à 3 650 jours), un seuil ou un facteur négatif rendent ce scénario-là une
anomalie nommée, sans arrêter les autres. Le profil déclaré se saisit en devise de tenue de
compte, faute de quoi la comparaison n'aurait pas d'unité.

### Déclarations réglementaires — `regulatory_declaration` ✅

| Élément | Contenu |
|---|---|
| Déclaration | Code, libellé, destinataire (banque centrale, commission bancaire, bureau du crédit, administration fiscale), validité datée, deux signatures |
| Méthode | `ACCOUNTING_SITUATION`, `CREDIT_REGISTRY`, `PAYMENT_INCIDENTS`, `CREDIT_BUREAU` — du code ; tout le reste est du paramétrage |
| Périodicité, délai | `MONTHLY` / `QUARTERLY` / `YEARLY` et le délai de transmission en jours après la fin de période (1 à 365) : c'est lui qui fixe l'échéance |
| Seuil | `threshold_amount` — admis par la seule centrale des risques : une balance seuillée serait fausse, un incident se déclare quel que soit son montant |
| Consentement | `party_credit_bureau_consent` : donné ou révoqué, daté — sans lui, rien ne part au bureau du crédit |

L'état produit recopie **la méthode et le seuil** sous lesquels il a été calculé : relire la
déclaration six mois plus tard donnerait la règle d'aujourd'hui, et l'état ne serait plus
reproductible.

### Référentiel ✅

| Élément | Table | Contenu |
|---|---|---|
| Devise | `currency` | Échelle, mode d'arrondi |
| Entité juridique | `legal_entity` | Devise de tenue de compte, pays, fuseau, date comptable courante |
| Période comptable | `accounting_period` | Bornes, statut `OPEN`/`CLOSED` |
| Compte | `account` | Sens naturel, imputabilité, contrôle du disponible, nombre de stripes |
| Découvert autorisé | `overdraft_limit` | Montant, période de validité 🔶 *(table présente, non reliée au produit)* |

---

## 2 bis. Le contrat de paramétrage : les familles de produit

`product_type` désignait jusqu'ici une chaîne libre, stockée et lue par aucune logique. Une
**famille de produit** lui donne un contrat, déclaré dans
`product-catalog/src/main/resources/product/families.json` — ressource versionnée avec le code,
chargée et validée au démarrage, jamais éditée depuis une console d'administration.

| Élément du descripteur | Ce qu'il exprime |
|---|---|
| `required` | Paramètre sans lequel le produit n'est pas exploitable |
| `optional` | Paramètre admis, avec un comportement par défaut documenté côté code |
| `requireOneOf` | Alternative : `interest.rate` **ou** un barème `tier:INTEREST` |
| `conditions` | Ce qu'un autre paramètre rend obligatoire — y compris par sa **valeur par défaut** |
| `groups` | Bloc répété, indexé par une liste (`fee.codes`) : un jeu de paramètres par commission |

Chaque exigence porte un `because` : la conséquence de l'absence, restituée telle quelle à celui qui
paramètre.

**Quand le contrôle joue.** Le type est vérifié à la création du brouillon — découvrir une faute de
frappe après avoir renseigné trente paramètres coûte le double. La complétude est vérifiée à
l'**activation**, et avant la double validation : faire valider par un second regard un paramétrage
que la machine sait incomplet lui ferait porter une responsabilité sur une pièce incomplète. Un
brouillon a le droit d'être incomplet — c'est ce qui en fait un brouillon.

**Tout paramètre non déclaré est refusé.** Un produit d'épargne portant `loan.penalty_rate` donnait
à son auteur la certitude d'avoir paramétré une pénalité qui ne s'appliquerait jamais. C'est le seul
moyen de distinguer une valeur inutile d'une valeur mal nommée.

**Les comptes cités sont vérifiés en base.** Un paramètre déclaré sous `accounts` doit désigner un
compte qui existe, appartient à l'entité du produit, est tenu dans sa devise, est un compte général
imputable et actif. Chacun de ces défauts était refusé par le ledger à la première écriture, de
nuit, sur une étape bloquante ; il l'est maintenant à l'activation, devant celui qui paramètre. Les
mêmes contrôles jouent au rattachement d'un compte à un produit — devise et existence du produit —
et à la création d'un contrat de crédit — comptes clients de l'entité, dans la devise du crédit.

**Les familles déclarées** sont `CURRENT_ACCOUNT`, `SAVINGS_ACCOUNT` et `TERM_LOAN` — celles que le
code sait traiter. En ajouter une est une modification du fichier **et** du code qui lira ses
paramètres : le test d'accord de chaque module échoue tant que les deux ne coïncident pas, dans les
deux sens.

> **Un effet de bord à connaître.** Les familles de compte exigent les paramètres d'intérêts, y
> compris le compte courant non rémunéré, qui se paramètre alors à `interest.rate = 0`. Ce n'est pas
> une coquetterie du descripteur : l'étape d'accrual visite **tout** compte rattaché à un produit et
> elle est bloquante. La seule alternative — la faire sauter les produits sans taux — ferait payer
> zéro intérêt à un livret mal paramétré, en silence. Le refus bruyant est le bon comportement ; ce
> qui manquait était de le déplacer au déploiement.

---

## 3. Ce qui n'est pas encore paramétré

Liste exhaustive, par ordre de criticité. Chaque ligne est aujourd'hui soit absente, soit portée par
le code appelant.

### Bloquant pour une mise en production

| Élément | État | Conséquence de l'absence |
|---|---|---|
| **Plafonds et limites** (par produit, canal, client, période) | ⬜ | Contrôles absents ou codés en dur |
| **Familles de produit** (contrat de paramétrage par type) | ✅ | — |
| **Circuits de double validation** (seuils par rôle et montant) | ⬜ | Maker-checker non généralisé hors paramétrage produit |

### Nécessaire à la couverture fonctionnelle visée

| Élément | État |
|---|---|
| Capitalisation des intérêts — périodicité civile, retenue à la source par pays | ✅ |
| Base minimum mensuelle ou moyenne pour l'épargne classique | ⬜ |
| Conditions de découvert rattachées au produit — agios, dépassement, arrêté, taxe | ✅ |
| Commissions de découvert (mise en place, dépassement) | ⬜ |
| Dormance — délai | ✅ |
| Dormance — régime de frais, compte d'abandon | ⬜ |
| Frais d'opération (retrait, virement) avec taxe | ✅ |
| Plafonds d'opération par produit et par client | ⬜ |
| Profil réglementaire régional et surcouche nationale ([11](11-profil-uemoa-bceao.md)) | ⬜ |
| Déclarations réglementaires (destinataire, périodicité, délai, seuil) | ✅ |
| Ratios prudentiels | ⬜ |
| Mapping plan comptable interne → réglementaire | ⬜ |
| Scénarios de surveillance LCB-FT | ✅ |

---

## 4. Règles de conception du paramétrage

Elles s'appliquent à tout ce qui reste à implémenter.

1. **Tout paramètre est daté.** Sans période de validité, un arrêté rejoué ne redonne pas les
   montants d'origine — et le problème n'apparaît qu'au premier changement, donc trop tard.
2. **Résolution à la date traitée**, jamais à la date du traitement.
3. **Tout paramètre appartient à une famille**, qui dit s'il est exigé, admis, ou exigé sous
   condition. Un paramètre hors famille est refusé au déploiement : une valeur jamais lue est
   indiscernable d'une valeur mal nommée, et les deux donnent à leur auteur la certitude d'avoir
   paramétré quelque chose.
4. **Aucun repli silencieux.** Une période non couverte est une erreur nommée, pas un repli sur la
   version la plus proche.
5. **Validation à la saisie**, pas à l'exécution. Un barème lacunaire est refusé au déploiement du
   paramétrage, pas découvert au milieu d'un TFJ sur un compte quelconque.
6. **Double validation**, portée par la base. Un paramétrage produit des montants sur des comptes
   clients : il relève du même régime qu'une opération.
7. **Journal immuable** des modifications : qui, quand, quoi, valeur avant et après.
8. **La frontière code / paramétrage est assumée.** La *méthode* est du code — méthode
   d'amortissement, algorithme de provisionnement, convention de décompte. Les *valeurs* sont du
   paramétrage — taux, seuils, tranches, comptes. Un moteur de règles totalement libre devient un
   langage de programmation sans tests ni revue ; c'est le principal facteur d'ingouvernabilité des
   core banking anciens.
9. **Un paramètre qui ne pilote rien est pire qu'absent** : il laisse croire qu'il agit. Aucune clé
   déclarée sans effet — et c'est désormais vérifié : le test d'accord de chaque module échoue
   aussi bien sur un paramètre lu et non déclaré que sur un paramètre déclaré et lu par personne.
