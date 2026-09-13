# 10 — Roadmap de construction

Séquencement dicté par les dépendances techniques, pas par la visibilité fonctionnelle.
Le ledger d'abord, l'IHM en dernier.

---

## Vue d'ensemble

| Phase | Contenu | Durée | Équipe |
|---|---|---|---|
| P0 | Socle : ledger, référentiel, product factory | 5 mois | 4–5 |
| P1 | Dépôts : comptes, opérations, intérêts, arrêté | 5 mois | 6–7 |
| P2 | Crédits : octroi, échéanciers, classification, provisions | 5 mois | 6–7 |
| P3 | Paiements et canaux | 4 mois | 6–7 |
| P4 | Conformité et reporting réglementaire | 4 mois | 5–6 |
| P5 | Reprise de données et mise en production pilote | 4 mois | 8–10 |
| **Total** | **Première mise en production** | **~27 mois** | pic à 10 |

Ces durées supposent une équipe expérimentée en systèmes transactionnels et un référent
métier bancaire **dédié à plein temps**. C'est la condition la plus souvent absente, et la
première cause de dérive : sans arbitrage métier rapide, chaque règle de calcul devient un
point de blocage de plusieurs semaines.

---

## P0 — Socle (5 mois)

**Livrables**

- Ledger complet : écritures immuables, partie double, multi-devises, contre-passation,
  idempotence, striping, contrôle d'équilibre.
- Soldes : temps réel, snapshots quotidiens, rejeu, blocages, disponible.
- Référentiel : entités, agences, devises, cours, calendriers, périodes comptables,
  plans comptables et mappings.
- Product factory : types de produits, paramètres historisés, schémas comptables.
- Socle transverse : sécurité, RBAC/ABAC, maker-checker, audit, outbox.
- Comptabilité générale : balance, grand livre, journaux.

**Critères de sortie — non négociables**

- 100 % des invariants de [00](00-principes.md) couverts par des tests automatisés.
- 1 500 écritures/s soutenues, p99 < 150 ms, sur volumétrie cible.
- Rejeu intégral de 10 millions d'écritures sans un seul écart.
- Aucun interblocage sur 24 h de test de charge en virements croisés.
- Réconciliation quotidienne verte sur un jeu de 500 000 comptes.

**Ne pas passer en P1 si un seul critère n'est pas atteint.** Chaque défaut du socle est
multiplié par tous les modules construits dessus, et son coût de correction croît de façon
exponentielle avec l'avancement.

---

## P1 — Dépôts (5 mois)

- Comptes courants, épargne, dépôts à terme, comptes de garantie.
- Cycle de vie : ouverture, dormance, blocage, clôture.
- Moteur d'intérêts : toutes bases de calcul, toutes conventions de jours, **recalcul
  rétroactif**.
- Découverts autorisés et non autorisés, agios par la méthode des échelles.
- Commissions, frais périodiques, fiscalité associée.
- **Moteur d'arrêté complet** : EOD, EOM, EOY, reprise, annulation.
- Clients et KYC : niveau nécessaire à l'ouverture de compte.
- API REST des opérations de dépôt.

**Critères de sortie**

- Arrêté de 2 millions de comptes en moins de 90 minutes.
- Arrêté de référence exact au centime sur 90 jours simulés.
- Reprise d'arrêté testée à chaque étape ; annulation d'arrêté testée intégralement.
- Recalcul rétroactif validé sur les cas d'antidatage en cascade.

---

## P2 — Crédits (5 mois)

- Demande, instruction, comité, décision, contractualisation.
- Déblocage unique ou par tranches, conditions suspensives.
- Toutes méthodes d'amortissement, échéanciers versionnés.
- Remboursements, imputation paramétrable, remboursement anticipé, rééchelonnement.
- Garanties : prise, valorisation, rang, réalisation.
- Classification réglementaire, contagion, provisionnement, suspension des intérêts.
- Recouvrement : relances, contentieux, abandon de créance.

**Critères de sortie**

- Échéanciers identiques, au centime, à ceux du système existant sur un échantillon de
  1 000 dossiers réels couvrant tous les types de crédit.
- Classification et provisions conformes au profil réglementaire, validées par le
  contrôle interne.

---

## P3 — Paiements et canaux (4 mois)

- Virements internes, interbancaires, internationaux ; prélèvements ; chèques et effets.
- ISO 20022 : `pain`, `pacs`, `camt`.
- Mobile money et monétique : interfaces et réconciliation.
- Comptes de suspens et rapprochement automatique.
- Canaux : back-office agence, banque en ligne, mobile, API partenaires.
- Opérations de caisse : arrêté de caisse, gestion des valeurs.

---

## P4 — Conformité et reporting (4 mois)

- LCB-FT : filtrage, scénarios de surveillance, alertes, déclarations.
- Reporting réglementaire piloté par le profil de l'entité.
- États financiers, liasse, consolidation multi-entités.
- Fiscalité et déclarations associées.
- Datamart et restitutions décisionnelles.

---

## P5 — Reprise et mise en production (4 mois)

La phase la plus risquée du programme.

### Reprise de données

1. **Cartographie et qualité** : inventaire, règles de transformation, mesure des anomalies
   du système source. Il y en a toujours, et davantage que prévu.
2. **Reprise des soldes** : écritures de reprise portant `source = MIGRATION`, sur un compte
   de contrepartie de reprise qui doit se solder exactement à zéro.
3. **Reprise des historiques** : mouvements nécessaires au calcul des intérêts et aux
   obligations légales de conservation.
4. **Reprise des contrats** : échéanciers de crédit recalculés **et comparés** aux
   échéanciers du système source, dossier par dossier.
5. **Réconciliation de bascule** : égalité stricte des balances avant et après, par compte,
   par devise et par entité.

### Répétitions générales

Minimum **trois répétitions complètes** en conditions réelles, chronométrées, avec
procédure de retour arrière testée. La dernière est exécutée par les équipes
d'exploitation sans l'assistance de l'équipe projet.

### Stratégie de bascule

| Option | Avantage | Inconvénient | Recommandation |
|---|---|---|---|
| Big bang | Simple, une seule reprise, pas de double saisie | Risque maximal, retour arrière difficile | Petite banque mono-pays |
| Par entité | Risque circonscrit, apprentissage progressif | Double exploitation temporaire | **Recommandé en multi-pays** |
| Par produit | Progressif | Comptes clients éclatés sur deux systèmes, réconciliation permanente | À éviter |

Le double fonctionnement en parallèle sur une période réduite (2 à 4 semaines) avec
comparaison quotidienne automatisée des soldes est la seule méthode qui détecte les écarts
avant qu'ils n'atteignent le client.

---

## Registre des risques

| # | Risque | Probabilité | Impact | Réponse |
|---|---|---|---|---|
| R1 | Moteur d'intérêts incapable de recalcul rétroactif | Élevée | Critique | Exigence de conception dès P0, testée en P1 |
| R2 | Arrêté non idempotent | Moyenne | Critique | Idempotence déterministe, reprise testée à chaque étape |
| R3 | Performance d'arrêté insuffisante | Élevée | Majeur | Test de charge sur volumétrie cible dès P0 |
| R4 | Qualité des données du système source | **Très élevée** | Majeur | Audit qualité en P0, pas en P5 |
| R5 | Absence de référent métier dédié | Élevée | Critique | Condition contractuelle de démarrage |
| R6 | Dérive fonctionnelle par ajouts continus | Élevée | Majeur | Périmètre gelé par phase, demandes en file d'attente |
| R7 | Paramétrage modifié directement en production | Moyenne | Critique | Promotion contrôlée, saisie directe techniquement impossible |
| R8 | Sous-estimation du back-office agence | Élevée | Majeur | Ateliers agence dès P1, pas en P3 |
| R9 | Non-conformité réglementaire découverte tardivement | Moyenne | Critique | Contrôle interne et conformité associés à chaque phase |
| R10 | Dépendance à un éditeur tiers critique | Moyenne | Majeur | Interface abstraite, second fournisseur qualifié |

R4 est systématiquement sous-estimé : l'audit de qualité des données doit être lancé dès
P0, en parallèle du socle. Découvrir en P5 que 8 % des dossiers clients sont incomplets ou
que des soldes historiques sont incohérents décale la mise en production de six mois.

---

## Points de décision

| Jalon | Décision | Critère |
|---|---|---|
| Fin P0 | Poursuivre ou renforcer le socle | Les 5 critères de sortie, sans exception |
| Fin P1 | Valider le modèle de calcul | Arrêté de référence exact au centime |
| Fin P2 | Valider la conformité crédit | Validation du contrôle interne |
| Mi-P5 | Confirmer la date de bascule | 3 répétitions réussies, retour arrière testé |
| Bascule | Passer en production | Réconciliation de reprise strictement équilibrée |

Chaque jalon est un point d'arrêt réel. Passer un jalon « sous réserve » sur un système
comptable produit une dette qui ne se rembourse pas : elle se paie en écarts sur des comptes
clients réels.
