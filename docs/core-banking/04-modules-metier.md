# 04 — Modules métier

Chaque module publie des **événements métier**. Il ne produit jamais d'écriture lui-même :
la product factory traduit l'événement en écritures via le schéma comptable du produit.

---

## 1. Clients & KYC (`party`)

### Modèle

```
Party (abstrait)
  ├── NaturalPerson    état civil, pièces d'identité, situation familiale, profession
  └── LegalPerson      raison sociale, forme juridique, registre du commerce, NIF
        └── BeneficialOwner *   bénéficiaires effectifs, % de détention

Party ──* PartyRelationship ──* Party    (mandataire, représentant légal, groupe, conjoint)
Party ──* Contract                        (comptes, crédits, dépôts à terme)
Party ──1 KycFile                         (dossier de connaissance client)
Party ──* RiskAssessment                  (notation du risque, historisée)
```

### Décisions structurantes

**Le client est une entité distincte du compte.** Un client détient N contrats ; un contrat
peut avoir N titulaires. Modéliser le client comme un attribut du compte — erreur fréquente
des systèmes maison — rend impossibles la vue 360°, l'agrégation des risques et la
contagion réglementaire de déclassement.

**Identifiant unique par entité juridique, résolution de doublons obligatoire.** Un
dédoublonnage (nom, date de naissance, pièce d'identité, téléphone) s'exécute à la création
et périodiquement. Un client en double casse les plafonds réglementaires d'engagement et
les états de concentration des risques.

### KYC

| Élément | Contenu |
|---|---|
| Niveau de diligence | Simplifiée / standard / renforcée, dérivé du score de risque |
| Documents | Pièce d'identité, justificatif de domicile, de revenus, statuts — avec dates d'expiration |
| Revue périodique | Échéance par niveau de risque (12 / 24 / 36 mois), pilotée par le TFJ |
| Complétude | Un dossier incomplet restreint les opérations selon une matrice paramétrée |
| Bénéficiaires effectifs | Obligatoire pour les personnes morales, seuil paramétrable |

Un document expiré déclenche une alerte, puis une restriction progressive — jamais un blocage
brutal non annoncé.

### Filtrage (screening)

- **À la création et à chaque modification** : listes de sanctions, PPE, listes internes.
- **Périodiquement** : rescan complet du portefeuille à chaque mise à jour des listes.
- **Sur transaction** : filtrage du donneur d'ordre et du bénéficiaire pour les paiements
  internationaux, avec mise en attente en cas de correspondance.

Le moteur de correspondance approximative (phonétique, translittération, permutations) est
**externalisé** vers un éditeur spécialisé. Le socle expose une interface et gère le
workflow de levée de doute ; il ne réimplémente pas l'algorithme de matching, domaine où un
développement maison produit des taux de faux négatifs inacceptables.

---

## 2. Dépôts (`deposits`)

### Produits couverts

| Produit | Spécificités |
|---|---|
| Compte courant | Découvert autorisé, agios, commission de mouvement, échelles d'intérêts |
| Compte d'épargne | Intérêts créditeurs, base de calcul paramétrable, plafond de retraits |
| Dépôt à terme | Capital bloqué, échéance, pénalité de sortie anticipée, renouvellement |
| Compte sur livret | Fiscalité spécifique, plafond réglementaire |
| Compte de dépôt de garantie | Blocage total, affectation à un engagement |

### Cycle de vie

```
PROJET → OUVERT → ACTIF ⇄ DORMANT → EN CLÔTURE → CLÔTURÉ
            │                            │
            └──► BLOQUÉ (saisie, opposition, gel judiciaire)
```

- **Dormance** : absence de mouvement pendant N mois (paramétré). Déclenche une notification
  client, puis un régime de frais spécifique, puis un transfert vers un compte
  d'abandon si la réglementation locale l'impose.
- **Clôture** : impossible tant que le solde n'est pas nul, qu'un blocage est actif ou qu'un
  engagement reste attaché. Les intérêts courus sont arrêtés et capitalisés au prorata.
- **Blocage** : un blocage judiciaire prime sur toute opération, y compris les prélèvements
  automatiques du produit.

### Moteur d'intérêts

Le composant le plus sensible après le ledger.

```java
public interface InterestEngine {
    /** Calcule l'intérêt couru d'un contrat pour une date de valeur donnée. */
    InterestAccrual accrue(ContractId contract, LocalDate valueDate);

    /** Recalcule rétroactivement depuis une date (écriture antidatée). */
    RetroactiveResult recompute(ContractId contract, LocalDate from);
}
```

Bases de calcul supportées :

| Base | Description |
|---|---|
| `DAILY_BALANCE` | Solde de chaque jour en date de valeur |
| `MIN_MONTHLY_BALANCE` | Solde minimum de la période (épargne classique) |
| `AVG_DAILY_BALANCE` | Moyenne des soldes quotidiens |
| `TIERED` | Barème par tranches de solde, cumulatif ou non |
| `SCALE` | Méthode des échelles (nombres débiteurs / créditeurs) |

Conventions de décompte des jours : `ACT/360`, `ACT/365`, `ACT/ACT`, `30/360`.

**Le recalcul rétroactif est une exigence de conception, pas une fonctionnalité
optionnelle.** Une écriture antidatée modifie la série des soldes en date de valeur et donc
tous les accruals postérieurs. Le moteur contre-passe les accruals invalidés et les réémet.
Un moteur incapable de cela produit des agios faux dès la première opération antidatée —
cas qui survient dans les premières semaines d'exploitation.

### Commissions et frais périodiques

Le calcul et la perception sont implémentés ([`fee-domain`](../../core-banking/fee-domain),
[`fee-service`](../../core-banking/fee-service)), déclenchés par l'étape `FEE_CHARGING` du TFJ.

| Dimension | Couvert |
|---|---|
| Périodicité | Quotidienne, mensuelle, trimestrielle, semestrielle, annuelle ; terme échu ou à échoir |
| Assiette | Forfait, taux sur solde de clôture, taux sur plus fort découvert, barème par tranches |
| Bornes | Perception minimale et maximale, exigées imputables dans la devise |
| Proratisation | Aux jours réellement servis — ouverture, clôture, entrée et sortie d'exonération |
| Fiscalité | Taux paramétré (TOB, TAF, TVA), assis sur le net arrondi, compte de taxe distinct |
| Provision insuffisante | Abandon, forçage, ou report avec vieillissement et abandon au terme |
| Exonérations | Fenêtre datée par compte et par commission, sous double validation |

> **Ce qui reste à faire ici** : la restitution du prorata lors d'une clôture en cours de période
> facturée d'avance. La commission a été perçue ; son remboursement partiel est une opération
> distincte, qui relève du module de clôture de compte et non de la perception.

**La commission du plus fort découvert** se constate sur la série des soldes **en date de valeur**,
jour par jour sur la période, et le taux s'applique **directement** au pic : ce n'est pas un intérêt,
c'est un pourcentage d'un montant constaté. L'annualiser serait une erreur de nature, pas de réglage.

### Découvert

- **Autorisé** : contrat avec montant, durée, taux, commission de mise en place.
- **Non autorisé** : dépassement toléré ou rejeté selon paramétrage, taux majoré, commission
  de dépassement, plafonné par le taux d'usure quand le pays en impose un.
- Les agios se calculent par la méthode des échelles sur les nombres débiteurs.

---

## 3. Crédits (`lending`)

### Cycle

```
DEMANDE → INSTRUCTION → DÉCISION → CONTRACTUALISATION → DÉBLOCAGE
   → EN COURS → (RECOUVREMENT) → SOLDÉ | PASSÉ EN PERTE
```

### Modèle

```
LoanApplication      demande, pièces, scoring, décision, comité
  └── LoanContract   montant, taux, durée, différé, garanties, conditions suspensives
        ├── DisbursementSchedule   déblocages (unique ou par tranches)
        ├── RepaymentSchedule *    échéancier, versionné
        │     └── ScheduleLine     échéance : capital, intérêt, commission, assurance, taxe
        ├── Collateral *           garanties, valorisation, rang, réalisation
        └── Classification         bucket réglementaire, provision, historique
```

### Méthodes d'amortissement

| Méthode | Usage |
|---|---|
| Annuités constantes | Crédit habitat, consommation |
| Amortissement constant du capital | Crédit d'équipement |
| In fine | Crédit relais, trésorerie |
| Différé partiel (intérêts seuls) | Crédit d'investissement en phase de montée en charge |
| Différé total (capitalisation) | Crédit étudiant, projet agricole |
| Échéances irrégulières | Crédit agricole saisonnier, adossé à un plan de trésorerie |
| Révisable / indexé | Taux indexé sur un indice, avec cap/floor et périodicité de révision |

L'échéancier est **versionné** : un rééchelonnement, un remboursement anticipé partiel ou
une révision de taux produit une nouvelle version. L'ancienne est conservée. L'échéancier
contractuel initial reste consultable — exigence de traçabilité et de preuve en cas de
contentieux.

> **Implémenté** ([`loan-domain`](../../core-banking/loan-domain),
> [`loan-service`](../../core-banking/loan-service)) : annuités constantes, amortissement constant,
> in fine, différé d'amortissement ; assurance emprunteur sur capital initial ou restant dû ; frais
> par échéance et taxe sur intérêts. Exigibilité, prélèvement automatique et rééchelonnement sont
> pilotés par l'étape `LOAN_SCHEDULE` du TFJ.
>
> **La dernière échéance solde le capital restant dû**, quel qu'il soit. En devise sans
> subdivision, une annuité arrondie soixante fois laisse sinon un solde résiduel après la fin du
> crédit : invisible à la lecture de l'échéancier, réclamé au client des années plus tard. La somme
> des capitaux amortis est un invariant de construction — un échéancier qui ne le respecte pas ne
> peut pas être représenté, quelle que soit sa provenance.
>
> **Une version de remplacement ne porte que sur l'avenir.** Régénérer un plan complet depuis
> l'origine est l'erreur naturelle, et elle réclamerait une seconde fois des échéances déjà rendues
> exigibles. Le refus est explicite.
>
> **Le régime de retard est implémenté** : intérêt de retard couru chaque jour sur l'impayé,
> pénalité perçue une fois par échéance, franchise, plancher et plafond, et un garde-fou sur le
> cumul du taux nominal et du taux de retard.
>
> Trois propriétés y sont structurelles plutôt que paramétrées :
>
> - **aucune capitalisation** — les créances de retard sont exclues de leur propre assiette, parce
>   que l'anatocisme est encadré voire prohibé dans la plupart des droits de la zone ;
> - **l'assiette est reconstituée jour par jour** depuis l'historique daté des imputations, jamais
>   estimée sur l'état courant — un rattrapage doit facturer chaque journée sur l'impayé tel qu'il
>   était ce jour-là ;
> - **le cumul s'arrondit, la journée non**, comme pour les intérêts courus et pour la même raison.
>
> **Ce qui manque** : classification, provisionnement, suspension des intérêts. Le nombre de jours
> de retard est disponible — c'est l'entrée de tout ce qui suivra.

### Imputation d'un règlement

Ordre paramétrable par produit, valeur par défaut :

```
1. Frais de recouvrement
2. Pénalités de retard
3. Commissions et assurances échues
4. Intérêts de retard
5. Intérêts échus (les plus anciens d'abord)
6. Capital échu (le plus ancien d'abord)
7. Capital non échu (remboursement anticipé)
```

L'ordre a un impact financier direct et est parfois imposé par la réglementation locale :
il est donc dans le paramétrage, jamais codé en dur.

> **Implémenté.** L'ordre est **exigé exhaustif** : une catégorie omise rendrait la créance
> correspondante impayable — les règlements passeraient à côté, elle vieillirait, déclencherait des
> pénalités puis un déclassement, sans qu'aucune erreur ne soit jamais signalée. Au sein d'une
> catégorie, la créance la plus ancienne d'abord : c'est elle qui compte les jours de retard.
>
> **L'imputation partielle est admise**, contrairement à celle d'une commission. La différence
> n'est pas un détail de mise en œuvre : une échéance de crédit est une dette qui s'amortit, la
> couper ne scinde aucune assiette taxable déjà déclarée.
>
> **Conséquence à connaître de l'ordre standard** : il est *par nature avant l'âge*. Un client qui
> verse le montant exact d'une mensualité alors que deux sont exigibles solde les intérêts des
> deux échéances avant d'entamer le capital de la première — il ne solde donc aucune échéance et
> reste en retard de l'âge de la plus ancienne. C'est voulu, mais cela doit être explicable au
> guichet.

### Classification et provisionnement

Piloté par le profil réglementaire ([03](03-referentiel-parametrage.md#7-profil-réglementaire-par-pays)) :

> **Implémenté** ([`LoanClassificationService`](../../core-banking/loan-service)), les six points.
> La grille est un profil réglementaire daté, versionné et soumis à double validation ; ses seuils
> et ses taux sont du paramétrage, jamais du code.
>
> Quatre défauts de grille sont refusés au chargement — un trou entre deux classes, un
> chevauchement, un taux de provision qui décroît avec la dégradation, une classe saine après une
> classe douteuse. Tous seraient silencieux à l'exécution, et le premier n'échouerait que sur le
> crédit qui tombe dans le trou, un soir d'arrêté.
>
> Le point 6 — **la suspension des intérêts** — est traité en trois temps : sortie du résultat des
> intérêts déjà constatés au franchissement du seuil, naissance directe en intérêts réservés
> ensuite, et reprise de chaque composante sur le compte où elle avait été constatée.
>
> **Ce qui manque** : le retour à meilleure fortune avec son délai d'observation — un crédit
> régularisé voit sa provision reprise dès que son retard tombe à zéro, sans période d'observation.
> C'est une simplification, et elle est favorable à l'emprunteur. Le module de garanties
> (éligibilité réelle, rang, fraîcheur des expertises, opposabilité) n'existe pas non plus : une
> quotité d'éligibilité en tient lieu, et le dit.

1. Calcul du nombre de jours de retard du plus ancien impayé.
2. Détermination du bucket selon la méthode du profil.
3. **Contagion** : si le profil l'impose, tous les encours du client (voire du groupe)
   sont déclassés au bucket le plus défavorable.
4. Assiette de provision = encours − garanties éligibles pondérées.
5. Provision = assiette × taux du bucket ; comptabilisation de la dotation ou de la reprise.
6. **Suspension des intérêts** à partir du bucket déclencheur : les intérêts cessent d'être
   comptabilisés en produits et sont enregistrés en intérêts réservés, hors résultat.

L'étape 6 est régulièrement omise dans les développements maison. Son absence surévalue le
produit net bancaire et constitue une non-conformité directe.

---

## 4. Paiements (`payments`)

### Canaux

Virements internes, virements interbancaires (compensation locale, RTGS), virements
internationaux (SWIFT / ISO 20022), prélèvements, chèques, effets, mobile money, cartes.

### Machine à états

```
REÇU → VALIDÉ → AUTORISÉ → EXÉCUTÉ → COMPENSÉ → SOLDÉ
   │       │         │          │
   └───────┴─────────┴──────────┴──► REJETÉ / RETOURNÉ / RAPPELÉ
```

Chaque transition est horodatée, tracée, et produit son propre jeu d'écritures. Le passage
par un **compte de suspens** entre l'exécution et la compensation est obligatoire : les
fonds ne sont pas encore chez le correspondant, et le bilan doit le refléter.

### Points de conception

- **Idempotence de bout en bout** : la référence de bout en bout (`end-to-end id`) est la
  clé d'idempotence. Un fichier de compensation rejoué ne double aucune opération.
- **Cut-off** : au-delà de l'heure limite du canal, l'opération porte la date de valeur du
  jour ouvré suivant. Le calcul dépend du calendrier de l'entité.
- **Rappels et retours** : un retour interbancaire arrive après la compensation. Il se
  traite par contre-passation et non par suppression.
- **Réconciliation des suspens** : un compte de suspens non soldé à la fin du jour est une
  anomalie remontée à l'arrêté, avec ancienneté et responsable assigné.

---

## 5. Trésorerie & change (`treasury`)

- **Positions de change** par devise et par entité, alimentées par les écritures de change.
- **Revalorisation** à chaque arrêté au cours officiel de clôture ; écart porté en résultat
  de change.
- **Placements et emprunts interbancaires** : contrats, intérêts courus, échéances.
- **Nostro / Vostro** : comptes de correspondants, rapprochement automatique des relevés
  `camt.053`, gestion des suspens de rapprochement.
- **Position de liquidité** : projection des flux entrants et sortants sur horizon glissant.

---

## 6. Comptabilité générale (`accounting`)

Rappel : la comptabilité générale n'est **pas** une base alimentée par interface. C'est une
**vue agrégée du ledger**.

| État | Définition |
|---|---|
| Balance générale | Agrégation des soldes par compte, par entité, par devise et en contre-valeur |
| Grand livre | Détail des écritures d'un compte sur une période |
| Journal | Écritures chronologiques d'une période |
| Bilan / compte de résultat | Balance projetée sur le référentiel réglementaire via `gl_mapping` |
| Consolidation | Agrégation multi-entités avec conversion et élimination des opérations intragroupe |

La clôture annuelle produit : détermination du résultat, affectation, report à nouveau,
réouverture des comptes de bilan et remise à zéro des comptes de gestion — par écritures
générées selon le schéma de clôture de l'entité, pas par mise à jour de soldes.
