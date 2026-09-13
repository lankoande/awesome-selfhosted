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
| **Schémas comptables** (événement → écritures) | ⬜ | Chaque produit impose du code ; c'est le principal frein à l'ajout d'un produit |
| **Commissions et frais** (montant, périodicité, déclencheur) | ⬜ | Barème figé, non simulable avant activation |
| **Fiscalité** (TOB/TAF/TVA, retenues, exonérations) | ⬜ | Une loi de finances impose une livraison logicielle |
| **Plafonds et limites** (par produit, canal, client, période) | ⬜ | Contrôles absents ou codés en dur |
| **Calendrier et jours fériés** par entité | ⬜ | Dates de valeur et échéances fausses les jours non ouvrés |
| **Conventions de date de valeur** par type d'opération | ⬜ | Agios faux — point 11 de la checklist UEMOA, le plus coûteux |
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
