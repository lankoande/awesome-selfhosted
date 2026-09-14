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

Barème par tranches dans `product_rate_tier` : bornes et taux par tranche.

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

### Référentiel ✅

| Élément | Table | Contenu |
|---|---|---|
| Devise | `currency` | Échelle, mode d'arrondi |
| Entité juridique | `legal_entity` | Devise de tenue de compte, pays, fuseau, date comptable courante |
| Période comptable | `accounting_period` | Bornes, statut `OPEN`/`CLOSED` |
| Compte | `account` | Sens naturel, imputabilité, contrôle du disponible, nombre de stripes |
| Découvert autorisé | `overdraft_limit` | Montant, période de validité 🔶 *(table présente, non reliée au produit)* |

---

## 3. Ce qui n'est pas encore paramétré

Liste exhaustive, par ordre de criticité. Chaque ligne est aujourd'hui soit absente, soit portée par
le code appelant.

### Bloquant pour une mise en production

| Élément | État | Conséquence de l'absence |
|---|---|---|
| **Plafonds et limites** (par produit, canal, client, période) | ⬜ | Contrôles absents ou codés en dur |
| **Circuits de double validation** (seuils par rôle et montant) | ⬜ | Maker-checker non généralisé hors paramétrage produit |

### Nécessaire à la couverture fonctionnelle visée

| Élément | État |
|---|---|
| Capitalisation des intérêts (périodicité, base minimum/moyenne) | ⬜ |
| Conditions de découvert rattachées au produit — agios, échelles (seul `overdraft.limit` existe, employé au contrôle de provision) | 🔶 |
| Dormance (délai, régime de frais) | ⬜ |
| Profil réglementaire régional et surcouche nationale ([11](11-profil-uemoa-bceao.md)) | ⬜ |
| Ratios prudentiels | ⬜ |
| Mapping plan comptable interne → réglementaire | ⬜ |
| Scénarios de surveillance LCB-FT | ⬜ |

---

## 4. Règles de conception du paramétrage

Elles s'appliquent à tout ce qui reste à implémenter.

1. **Tout paramètre est daté.** Sans période de validité, un arrêté rejoué ne redonne pas les
   montants d'origine — et le problème n'apparaît qu'au premier changement, donc trop tard.
2. **Résolution à la date traitée**, jamais à la date du traitement.
3. **Aucun repli silencieux.** Une période non couverte est une erreur nommée, pas un repli sur la
   version la plus proche.
4. **Validation à la saisie**, pas à l'exécution. Un barème lacunaire est refusé au déploiement du
   paramétrage, pas découvert au milieu d'un TFJ sur un compte quelconque.
5. **Double validation**, portée par la base. Un paramétrage produit des montants sur des comptes
   clients : il relève du même régime qu'une opération.
6. **Journal immuable** des modifications : qui, quand, quoi, valeur avant et après.
7. **La frontière code / paramétrage est assumée.** La *méthode* est du code — méthode
   d'amortissement, algorithme de provisionnement, convention de décompte. Les *valeurs* sont du
   paramétrage — taux, seuils, tranches, comptes. Un moteur de règles totalement libre devient un
   langage de programmation sans tests ni revue ; c'est le principal facteur d'ingouvernabilité des
   core banking anciens.
8. **Un paramètre qui ne pilote rien est pire qu'absent** : il laisse croire qu'il agit. Aucune clé
   déclarée sans effet.
