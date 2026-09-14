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
| **Commissions et frais** (périodicité, déclencheur) | 🔶 | Le calcul et l'imputation sont paramétrés ; le déclenchement périodique reste à faire |
| **Plafonds et limites** (par produit, canal, client, période) | ⬜ | Contrôles absents ou codés en dur |

| **Circuits de double validation** (seuils par rôle et montant) | ⬜ | Maker-checker non généralisé hors paramétrage produit |

### Nécessaire à la couverture fonctionnelle visée

| Élément | État |
|---|---|
| Capitalisation des intérêts (périodicité, base minimum/moyenne) | ⬜ |
| Conditions de découvert rattachées au produit | 🔶 |
| Dormance (délai, régime de frais) | ⬜ |
| Classification des créances et provisionnement (buckets, taux, contagion) | ⬜ |
| Éligibilité et fraîcheur des garanties | ⬜ |
| Taux d'usure et composantes du TEG | ⬜ |
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
