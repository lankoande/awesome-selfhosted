# 11 — Profil réglementaire par défaut : UEMOA / BCEAO

Profil livré en configuration de référence. Les sept autres pays de l'Union partagent le
même cadre : une seule instance couvre les huit, avec une surcouche nationale pour la
fiscalité et le droit local.

> **Convention de lecture** — les éléments marqués ⚠ sont des **valeurs à caler sur les
> textes en vigueur** à la date du projet (PCB révisé, dispositif prudentiel, instructions
> BCEAO, codes généraux des impôts nationaux). La **structure** du profil est stable ; les
> **valeurs chiffrées** doivent être validées par le contrôle interne et la conformité avant
> mise en production. Ne pas les reprendre telles quelles.

---

## 1. Périmètre

| Élément | Valeur |
|---|---|
| Union monétaire | UEMOA / UMOA |
| États membres | Bénin, Burkina Faso, Côte d'Ivoire, Guinée-Bissau, Mali, Niger, Sénégal, Togo |
| Banque centrale | BCEAO |
| Superviseur | Commission Bancaire de l'UMOA |
| Devise | XOF (franc CFA — Afrique de l'Ouest) |
| Parité | **Fixe** : 1 EUR = 655,957 XOF |
| Référentiel comptable | Plan Comptable Bancaire (PCB) révisé, applicable depuis le 1ᵉʳ janvier 2018 |
| Cadre prudentiel | Dispositif prudentiel révisé (Bâle II / III), applicable depuis le 1ᵉʳ janvier 2018 |

**XOF et XAF ne sont pas la même devise.** Les deux francs CFA partagent la parité avec
l'euro mais relèvent de deux unions distinctes (UEMOA / CEMAC) et ne sont pas
interchangeables. Le modèle les traite comme deux devises sans lien, avec un cours croisé
explicite. Les confondre est une erreur classique des systèmes conçus hors zone.

---

## 2. Modèle à deux niveaux : régional + national

Refinement apporté au modèle de [03](03-referentiel-parametrage.md) pour ce cas : ce qui est
commun à l'Union et ce qui est propre à chaque État sont deux objets distincts.

```
RegulatoryProfile  « UEMOA_BCEAO »        ← commun aux 8 pays
  ├── référentiel comptable (PCB)
  ├── classification et provisionnement des créances
  ├── ratios prudentiels
  ├── taux d'usure
  ├── déclarations BCEAO et Commission Bancaire
  └── systèmes de paiement (STAR, SICA, GIM)
        │
        ├── CountryOverlay « CI »   ← propre à chaque État
        ├── CountryOverlay « SN »       fiscalité (TOB/TAF/TVA, retenues)
        ├── CountryOverlay « BF »       droits de timbre
        ├── CountryOverlay « ML »       jours fériés nationaux
        ├── CountryOverlay « NE »       identifiants fiscaux, RCCM
        ├── CountryOverlay « TG »       langue, spécificités de droit local
        ├── CountryOverlay « BJ »
        └── CountryOverlay « GW »       (lusophone : langue, formats)
```

Une `legal_entity` référence **un profil régional et une surcouche nationale**. Une filiale
sénégalaise et une filiale ivoirienne partagent le PCB, les règles de provisionnement et les
ratios ; elles diffèrent sur la fiscalité et le calendrier.

Sans cette séparation, l'ouverture du deuxième pays impose de dupliquer tout le profil — et
la moindre évolution BCEAO doit alors être répercutée huit fois.

---

## 3. Conséquences techniques de la devise XOF

C'est le point qui a le plus d'impact sur le cœur, et le plus souvent traité trop tard.

### Échelle nulle

Le XOF n'a **pas de subdivision** : `scale = 0`. Tous les montants comptabilisés sont des
entiers.

| Conséquence | Traitement |
|---|---|
| Tout arrondi porte sur une unité entière | Écart d'arrondi visible et significatif, à suivre |
| Les accruals quotidiens sont sub-unitaires | Accumulation en `NUMERIC(23,5)`, arrondi **au seul moment de la capitalisation** |
| La ventilation d'une commission et de sa taxe ne tombe pas juste | Règle de ventilation explicite : le reliquat est imputé sur la composante de plus fort montant |
| Le TFJ de référence se contrôle « à l'unité », pas « au centime » | Tolérance de test : zéro |

**Illustration du piège.** Un solde de 1 200 000 XOF à 3,5 % l'an, base ACT/365 :

```
Accrual quotidien réel     = 1 200 000 × 0,035 / 365 = 115,06849315 XOF

Arrondi chaque jour        : 115 × 365          = 41 975 XOF
Accumulé puis arrondi      : 41 999,99... → 42 000 XOF
                                              ─────────
                             Écart sur un an :     25 XOF par compte
```

Sur 500 000 comptes, l'écart atteint 12,5 millions XOF par an, entièrement dû à la méthode
d'arrondi. D'où la règle du §02 : **accumuler en précision étendue, arrondir une seule
fois**, à la capitalisation.

### Parité fixe

- Le cours officiel EUR/XOF est une **constante**, pas un flux quotidien : la table
  `exchange_rate` doit accepter un cours sans échéance (`valid_to` nul).
- Il n'y a pas de risque de change économique sur la position EUR, mais la **position reste
  suivie** : elle est exigée par le reporting et sert au contrôle.
- Les opérations clientèle en euro n'appliquent pas d'écart de cours mais une **commission
  de change** — c'est une commission, pas un spread. Elle doit apparaître comme telle dans
  le schéma comptable, sous peine de fausser le produit net bancaire.
- Les autres devises (USD, GBP, devises régionales) se cotent normalement, souvent par
  cours croisé via l'euro.

---

## 4. Plan comptable bancaire (PCB)

Structure de classes, de type plan bancaire francophone ⚠ *(à valider sur le PCB révisé)* :

| Classe | Contenu |
|---|---|
| 1 | Opérations de trésorerie et opérations interbancaires |
| 2 | Opérations avec la clientèle |
| 3 | Opérations sur titres et opérations diverses |
| 4 | Valeurs immobilisées |
| 5 | Capitaux permanents, provisions, fonds propres |
| 6 | Charges |
| 7 | Produits |
| 9 | Engagements hors bilan |

### Mise en œuvre dans le socle

Le PCB est chargé comme **plan comptable réglementaire**, cible du `gl_mapping`
([03 §2](03-referentiel-parametrage.md#2-plan-comptable-paramétrable)) :

```
Compte interne stable        gl_mapping (framework = PCB_UEMOA)      Compte PCB
────────────────────         ──────────────────────────────         ──────────
INT.DEPOSIT.SIGHT.RES    ──────────────────────────────────────►    2xxx ⚠
INT.INTEREST.EXPENSE     ──────────────────────────────────────►    6xxx ⚠
INT.LOAN.PROVISION       ──────────────────────────────────────►    59xx ⚠
```

Bénéfice concret : une renumérotation du PCB par la BCEAO devient un **import de mapping**.
Aucune écriture n'est reprise, aucun historique n'est touché.

> **Implémenté** sous la forme des maquettes d'états financiers
> ([02 §13](02-ledger.md#13-états-financiers)) : les rubriques du bilan, du compte de résultat et
> du hors bilan PCB sont des rubriques de maquette, et l'affectation des comptes internes se
> fait par règles ordonnées — nature de compte, préfixe de code, sens du solde. Une
> renumérotation est une nouvelle maquette, activée à deux à sa date de validité. Les modèles
> PCB eux-mêmes se chargent comme des maquettes ; ils ne sont pas livrés dans le code.

### Hors bilan

La classe 9 est traitée comme un **ledger à part entière**, pas comme une table annexe :
engagements de financement donnés et reçus, cautions et avals, engagements sur titres,
opérations de change à terme. Mêmes invariants — partie double, immuabilité, équilibre.

C'est une exigence forte de la zone : les engagements par signature (cautions de marché,
crédits documentaires) pèsent lourd dans les portefeuilles et sont directement contrôlés.
Les gérer hors du ledger conduit systématiquement à des écarts entre le suivi opérationnel
et les états réglementaires.

---

## 5. Classification et provisionnement des créances

Structure du profil. **Les seuils, taux et libellés exacts sont ⚠** et doivent être calés
sur l'instruction en vigueur.

```yaml
loan_classification:
  method: DAYS_PAST_DUE          # méthode = code ; seuils et taux = paramétrage
  default_definition_days: 90    # ⚠ définition du défaut
  contagion: PER_CUSTOMER        # déclassement de tous les encours du client
  collateral_deduction: true     # provision assise sur l'encours net de garanties

  buckets:                       # ⚠ intégralité des valeurs à confirmer
    - { code: SAIN,          from_days: 0,   to_days: 30,   provision_rate: 0 }
    - { code: SENSIBLE,      from_days: 31,  to_days: 89,   provision_rate: 0 }
    - { code: DOUTEUX,       from_days: 90,  to_days: 180,  provision_rate: 20 }
    - { code: COMPROMIS,     from_days: 181, to_days: 360,  provision_rate: 50 }
    - { code: PERTE,         from_days: 361, to_days: null, provision_rate: 100 }

  interest_suspension:
    trigger_bucket: DOUTEUX
    account: INT.INTEREST.RESERVED     # intérêts réservés, hors résultat
    retroactive: true                  # extourne des intérêts déjà comptabilisés

  collateral_eligibility:              # ⚠ quotités par type de garantie
    - { type: DEPOT_NANTI,     weight: 100 }
    - { type: GARANTIE_ETAT,   weight: 100 }
    - { type: HYPOTHEQUE,      weight: 50,  max_age_months: 36, revaluation_required: true }
    - { type: NANTISSEMENT,    weight: 50 }
    - { type: CAUTION_PERSO,   weight: 0 }
```

### Points de vigilance propres à la zone

**Contagion par client.** Le déclassement s'applique à l'ensemble des engagements du client,
pas au seul dossier en impayé. Un client avec trois crédits dont un en défaut voit les trois
déclassés. La contagion peut même s'étendre au groupe. C'est traité nativement par le modèle
`Party` → `Contract` de [04](04-modules-metier.md) ; un modèle où le crédit ignore le client
ne peut pas l'implémenter.

**Suspension des intérêts, avec rétroactivité.** Au passage en douteux, les intérêts cessent
d'alimenter le résultat **et** ceux déjà comptabilisés sur la période sont extournés vers un
compte d'intérêts réservés. L'omission de cette extourne surévalue le produit net bancaire
et constitue une non-conformité relevée en inspection.

**Éligibilité et fraîcheur des garanties.** Une hypothèque non réévaluée depuis trop
longtemps perd son éligibilité en déduction. Le suivi de la date de valorisation est donc un
attribut bloquant de la garantie, contrôlé au TFJ, pas une information documentaire.

---

## 6. Taux d'usure et TEG

```yaml
usury:
  enabled: true
  rates:                     # taux d'usure annuels
    BANK: 15.0               # banques et établissements financiers
    SFD:  24.0               # systèmes financiers décentralisés (microfinance)
  basis: TEG                 # comparaison sur le taux effectif global
  enforcement: BLOCKING      # un dossier au-dessus du plafond est refusé, pas alerté
```

### Exigences de mise en œuvre

Le **TEG intègre toutes les composantes du coût** : intérêts, commissions de mise en place,
frais de dossier, assurances obligatoires, et tout accessoire rendu obligatoire par l'octroi.
Le calculer sur le seul taux nominal donne une conformité apparente et une exposition
réelle.

- Calcul **au moment de l'octroi**, avec blocage si dépassement.
- **Recalcul à chaque avenant** : un rééchelonnement avec frais peut faire franchir le
  plafond à un dossier initialement conforme.
- TEG **archivé sur le contrat** avec son détail de calcul : c'est la pièce produite en
  contrôle et en contentieux.
- Le plafond applicable dépend du **type d'établissement**, donc de l'entité juridique : une
  banque et une filiale de microfinance dans le même groupe n'ont pas le même plafond.

---

## 7. Fiscalité — surcouche nationale

Le cadre comptable et prudentiel est régional ; **la fiscalité est nationale**. C'est la
principale source de divergence entre filiales.

```yaml
country_overlay:
  CI:
    transaction_tax:   { code: TVA_BANCAIRE, rate: null }   # ⚠
    interest_withholding: { code: IRC,       rate: null }   # ⚠
    stamp_duty:        { applicable: null }                 # ⚠
    tax_id_format:     "NCC"
    business_registry: "RCCM"
  SN:
    transaction_tax:   { code: TOB, rate: null }            # ⚠
    interest_withholding: { code: IRCM, rate: null }        # ⚠
  # BF, ML, NE, TG, BJ, GW : même structure
```

⚠ **Aucun taux n'est prérempli.** Les taux de TOB / TAF / TVA bancaire, les retenues à la
source sur intérêts créditeurs et les droits de timbre diffèrent par État, évoluent en loi
de finances annuelle, et comportent des exonérations par nature d'opération et par catégorie
de client. Ils sont à saisir par pays, avec période de validité, à partir du code général
des impôts en vigueur.

### Règles de conception

- La taxe est **une ligne du schéma comptable**, calculée au moment de la comptabilisation.
  Jamais reconstituée a posteriori : un redressement porte alors sur des milliers
  d'opérations.
- Les **exonérations** sont portées par le produit et par le segment client, pas codées en
  dur.
- Les taux sont **historisés par période de validité** : une loi de finances au 1ᵉʳ janvier
  ne doit pas modifier le calcul des opérations de décembre.
- Le **TFM produit les états déclaratifs** de chaque taxe, avec le détail des assiettes.

---

## 8. Systèmes de paiement

| Système | Rôle | Intégration |
|---|---|---|
| **STAR-UEMOA** | Règlement brut en temps réel, gros montants | Temps réel, dénouement en monnaie centrale |
| **SICA-UEMOA** | Compensation automatisée, petits montants et chèques | Échange de fichiers, cycles de compensation |
| **GIM-UEMOA** | Interopérabilité monétique régionale | ISO 8583, compensation monétique |
| Mobile money | Orange Money, MTN MoMo, Wave, Moov… | API opérateur, réconciliation quotidienne |

### Coordonnées bancaires

Structure RIB à 24 caractères, reprise dans l'IBAN :

```
code banque (5) + code guichet (5) + numéro de compte (12) + clé RIB (2) = RIB (24)
IBAN = code pays (2) + clé IBAN (2) + RIB (24)
```

⚠ La longueur d'IBAN est à vérifier **par pays** dans le registre officiel : elle n'est pas
uniforme dans l'Union. Les deux clés — clé RIB et clé IBAN — sont contrôlées à la saisie et
à la réception de tout message de paiement.

### Mobile money : à traiter comme un canal de premier rang

Dans cette zone, le mobile money n'est pas un canal secondaire : il porte une part majeure
des flux de détail. Il en découle trois exigences fermes :

1. **Comptes de liaison par opérateur**, avec rapprochement quotidien automatique au TFJ et
   suivi des suspens par ancienneté.
2. **Idempotence stricte** sur la référence opérateur : les API de mobile money rejouent
   fréquemment, et un double crédit est irrécupérable en pratique.
3. **Réversibilité encadrée** : un retour opérateur après dénouement se traite par
   contre-passation tracée, jamais par correction de solde.

Un traitement en « interface de complément » conduit invariablement à des écarts de
plusieurs dizaines de millions XOF non justifiés en fin d'exercice.

---

## 9. Déclarations et reporting

```yaml
reporting:
  - { code: SITUATION_COMPTABLE,     to: BCEAO,       frequency: MONTHLY,   deadline_days: null }  # ⚠
  - { code: CENTRALE_DES_RISQUES,    to: BCEAO,       frequency: MONTHLY,   threshold: null }      # ⚠
  - { code: INCIDENTS_DE_PAIEMENT,   to: BCEAO,       frequency: EVENT,     deadline_days: null }  # ⚠
  - { code: ETATS_PRUDENTIELS,       to: COMMISSION,  frequency: QUARTERLY, deadline_days: null }  # ⚠
  - { code: ETATS_FINANCIERS_ANNUELS,to: COMMISSION,  frequency: YEARLY,    deadline_days: null }  # ⚠
  - { code: DECLARATION_BIC,         to: BIC,         frequency: MONTHLY }                          # ⚠
```

| Déclaration | Contenu |
|---|---|
| Situation comptable périodique | Balance et annexes au format BCEAO |
| Centrale des Risques | Engagements par client au-delà d'un seuil ⚠ |
| Centrale des Incidents de Paiement | Chèques impayés, interdits bancaires |
| Bureau d'Information sur le Crédit | Historique de remboursement, avec consentement client |
| États prudentiels | Fonds propres, ratios, division des risques, liquidité |
| Réserves obligatoires | Assiette et constitution ⚠ |

### Exigences de conception

- **Consentement client obligatoire** pour la déclaration au BIC : c'est un attribut du
  dossier client, contrôlé avant transmission, et révocable.
- **Le fichier de la Centrale des Risques est agrégé par client**, tous engagements
  confondus, bilan et hors bilan — d'où la nécessité d'un identifiant client unique et
  dédoublonné ([04 §1](04-modules-metier.md#1-clients--kyc-party)).
- **Chaque état est archivé avec son jeu de données source et son paramétrage** : un état
  régénéré après contrôle doit être identique à l'original transmis.
- Les **délais de transmission** sont suivis comme des échéances bloquantes, avec alerte
  anticipée : le retard déclaratif est en lui-même un manquement.

> **Implémenté** — module `regulatory` (V54), détaillé en
> [07 §6](07-securite-conformite.md#prudentiel-et-réglementaire). Les déclarations de ce profil se
> chargent comme du paramétrage : code, destinataire, méthode, périodicité, délai, seuil. Quatre
> méthodes sont codées — situation comptable, centrale des risques **agrégée par client**
> (bilan et hors bilan, classe la plus dégradée), incidents de paiement, bureau du crédit sous
> consentement révocable. L'état produit est figé avec le paramétrage qui l'a calculé et se
> confronte à son recalcul ; sa transmission se décide à deux et porte la référence rendue. Les
> échéances dépassées sont constatées chaque nuit par l'arrêté. Restent à livrer : les **formats
> de fichier** attendus par chaque destinataire (la production rend les lignes, pas le fichier),
> les ratios prudentiels et les réserves obligatoires.

---

## 10. Ratios prudentiels

```yaml
prudential:
  framework: BASEL_II_III_UMOA
  ratios:                              # ⚠ toutes les valeurs sont à confirmer
    - { code: CET1,                     min: null }
    - { code: TIER1,                    min: null }
    - { code: SOLVABILITE_TOTALE,       min: null }
    - { code: COUSSIN_CONSERVATION,     min: null }
    - { code: LEVIER,                   min: null }
    - { code: DIVISION_RISQUES_UNITAIRE,max: null }   # exposition sur un même bénéficiaire
    - { code: LIQUIDITE,                min: null }
    - { code: COUVERTURE_EMPLOIS_MLT,   min: null }
    - { code: LIMITE_PARTICIPATIONS,    max: null }
    - { code: LIMITE_IMMOBILISATIONS,   max: null }
  frequency: QUARTERLY
```

Le socle **calcule et suit** ces ratios ; il ne les code pas en dur. Trois fonctions
attendues :

1. **Calcul périodique** à partir des encours du ledger et des pondérations de risque.
2. **Suivi en continu** avec alerte au franchissement d'un seuil d'attention, avant le
   franchissement réglementaire.
3. **Simulation** : impact d'un octroi important sur la division des risques, avant décision.
   C'est ce qui évite le dépassement constaté après coup, dont la régularisation est
   toujours coûteuse.

---

## 11. Fichier de configuration de référence

```yaml
# regulatory-profiles/uemoa-bceao.yaml
profile:
  code: UEMOA_BCEAO
  label: "Union Économique et Monétaire Ouest-Africaine — BCEAO"
  version: "2026.1"
  countries: [BJ, BF, CI, GW, ML, NE, SN, TG]

  currency:
    code: XOF
    scale: 0
    rounding: HALF_EVEN
    pegged_to: { currency: EUR, rate: 655.957, fixed: true }

  accounting:
    framework: PCB_UEMOA
    chart_source: "pcb-uemoa-revise.csv"        # import du plan réglementaire
    off_balance_sheet: LEDGER                    # classe 9 dans le ledger
    fiscal_year: { start_month: 1, periods: 12 }

  calendar:
    week_end: [SATURDAY, SUNDAY]
    holidays_source: "per-country"               # via country_overlay
    business_day_convention: MODIFIED_FOLLOWING

  tfj:
    cutoff_local_time: "18:00"
    max_duration_minutes: 90
    consecutive_runs_supported: 3                # rattrapage de retard
    dry_run_supported: true
    blocking_checks:
      - CAISSES_ARRETEES
      - SUSPENS_JUSTIFIES
      - BALANCE_EQUILIBREE
      - SOLDES_REJOUES_CONFORMES
      - COMPTES_LIAISON_MOBILE_MONEY_RAPPROCHES

  loan_classification:  { ... }   # cf. §5
  usury:                { ... }   # cf. §6
  payment_systems:      [STAR_UEMOA, SICA_UEMOA, GIM_UEMOA]
  reporting:            { ... }   # cf. §9
  prudential:           { ... }   # cf. §10

  # La fiscalité n'est PAS ici : elle relève de country_overlay (cf. §7)
```

---

## 12. Checklist de calage avant mise en production

À traiter avec la direction financière, le contrôle interne et la conformité. Chaque ligne
est un point de blocage tant qu'elle n'est pas validée et documentée.

| # | Point | Responsable |
|---|---|---|
| 1 | Import et validation du PCB révisé, mapping des comptes internes | Direction comptable |
| 2 | Seuils, buckets et taux de provisionnement | Risques + Conformité |
| 3 | Quotités d'éligibilité et règles de fraîcheur des garanties | Risques |
| 4 | Règle de contagion : périmètre client ou groupe | Risques |
| 5 | Composantes retenues dans le calcul du TEG | Conformité + Juridique |
| 6 | Taux de taxes et retenues, par pays, avec exonérations | Fiscalité, par filiale |
| 7 | Formats et calendriers des déclarations BCEAO | Contrôle de gestion |
| 8 | Seuil de déclaration à la Centrale des Risques | Contrôle de gestion |
| 9 | Ratios prudentiels : valeurs et périmètre de calcul | Direction financière |
| 10 | Jours fériés par pays, sur trois ans glissants | Exploitation |
| 11 | Conventions de date de valeur par type d'opération | Direction des opérations |
| 12 | Barème de commissions et conditions de banque | Direction commerciale |
| 13 | Règle d'imputation des règlements de crédit | Risques + Juridique |
| 14 | Circuits de double validation et plafonds par rôle | Contrôle interne |
| 15 | Conventions de réconciliation avec chaque opérateur mobile money | Direction des opérations |

Le point 11 est le plus souvent négligé et le plus coûteux : les conventions de date de
valeur déterminent directement les agios facturés au client. Une convention mal reprise
produit une réclamation de masse dès le premier mois d'exploitation.
