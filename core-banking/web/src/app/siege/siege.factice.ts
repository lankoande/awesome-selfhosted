import { Injectable } from '@angular/core';
import { Montant, RefusMetier } from '../guichet/modele/guichet.modele';
import {
  EtapeRun, FiltreBalance, LigneBalance, PageBalance, RunTfj, TotauxBalance,
} from './modele/siege.modele';
import { Siege } from './siege.port';

const DEVISE = 'XOF';

/**
 * L'ordre réel des étapes du traitement de fin de journée, celui que le socle
 * épingle dans son test de moteur. On ne l'invente pas : un exploitant qui
 * apprend l'écran doit reconnaître son traitement.
 */
const ETAPES = [
  'PRE_CHECKS', 'FX_RATES', 'HOLD_EXPIRY', 'DIRECT_DEBITS', 'STANDING_ORDERS', 'FEE_CHARGING',
  'LOAN_MOBILISATION', 'LOAN_SCHEDULE', 'LOAN_INTEREST_ACCRUAL', 'LOAN_LATE_CHARGES',
  'LOAN_CLASSIFICATION', 'LOAN_CLOSURE', 'TERM_DEPOSIT_ACCRUAL', 'TERM_DEPOSIT_MATURITY',
  'INTEREST_ACCRUAL', 'INTEREST_SETTLEMENT', 'FX_REVALUATION', 'DORMANCY', 'KYC_REVIEW',
  'DOCUMENT_EXPIRY', 'OFFER_EXPIRY', 'SUSPENSE_REVIEW', 'AML_MONITORING', 'REGULATORY_DEADLINES',
  'BALANCE_SNAPSHOT', 'RECONCILIATION', 'OPEN_NEXT_DAY',
] as const;

/** Les étapes dont l'échec arrête la chaîne. */
const BLOQUANTES = new Set<string>([
  'PRE_CHECKS', 'FX_RATES', 'BALANCE_SNAPSHOT', 'RECONCILIATION', 'OPEN_NEXT_DAY',
]);

const ANOMALIES: Readonly<Record<string, readonly string[]>> = {
  SUSPENSE_REVIEW: ['3 suspens de plus de 30 jours sans responsable désigné'],
  AML_MONITORING: ['Scenario SEUIL_ESPECES : 2 alertes ouvertes'],
  KYC_REVIEW: ['14 dossiers dont la revue KYC est échue'],
};

function montant(valeur: number): Montant {
  return { amount: String(Math.round(valeur)), currency: DEVISE };
}

/**
 * Source de démonstration du siège.
 *
 * Le premier passage échoue sur `PRE_CHECKS` — une caisse mouvementée n'est pas
 * arrêtée. C'est exactement ce qui relie l'arrêté de caisse du guichet à la
 * clôture de la journée, et c'est le refus le plus fréquent en agence. La
 * reprise passe.
 */
@Injectable()
export class SiegeFactice implements Siege {
  latenceMs = 300;

  private readonly runs = new Map<string, RunTfj>();

  /**
   * L'étape qui échouera au prochain passage, essai à blanc compris — c'est
   * tout l'intérêt de l'essai : il trouve le blocage avant que la journée soit
   * engagée. Consommée au premier passage, comme si la caisse avait été
   * arrêtée entre-temps. Les tests la mettent à `null` pour un passage propre.
   */
  echecPrevu: string | null = 'PRE_CHECKS';

  async lancerTfj(legalEntityId: string, journee: string, mode: 'REAL' | 'DRY_RUN'): Promise<RunTfj> {
    await this.latence();
    const echoue = this.echecPrevu;
    this.echecPrevu = null;
    const run = this.construire(legalEntityId, journee, mode, echoue);
    this.runs.set(run.id, run);
    return run;
  }

  async lireTfj(_entite: string, runId: string): Promise<RunTfj> {
    await this.latence();
    return this.require(runId);
  }

  async reprendreTfj(_entite: string, runId: string): Promise<RunTfj> {
    await this.latence();
    const run = this.require(runId);
    if (run.status !== 'FAILED') {
      throw new RefusMetier(409, 'REPRISE_IMPOSSIBLE',
        `Le passage est ${this.mot(run.status)}.`,
        "On ne reprend qu'un traitement en échec : il repart de l'étape qui a échoué, pas du début.");
    }
    const repris = this.construire(run.legalEntityId, run.businessDate, run.mode, null, run.id);
    this.runs.set(repris.id, repris);
    return repris;
  }

  async annulerTfj(_entite: string, runId: string): Promise<RunTfj> {
    await this.latence();
    const run = this.require(runId);
    if (run.status === 'CANCELLED') {
      throw new RefusMetier(409, 'DEJA_ANNULE', 'Ce passage est déjà annulé.');
    }
    const annule: RunTfj = { ...run, status: 'CANCELLED', finishedAt: new Date().toISOString() };
    this.runs.set(runId, annule);
    return annule;
  }

  async balance(_entite: string, filtre: FiltreBalance, page: number, taille: number): Promise<PageBalance> {
    await this.latence();
    const toutes = this.lignes().filter((l) => filtre.kind === null || l.kind === filtre.kind);
    const debut = page * taille;
    return {
      lignes: toutes.slice(debut, debut + taille),
      numero: page,
      taille,
      precedent: page > 0,
      suivant: debut + taille < toutes.length,
    };
  }

  async totauxBalance(_entite: string, filtre: FiltreBalance): Promise<readonly TotauxBalance[]> {
    await this.latence();
    const lignes = this.lignes().filter((l) => filtre.kind === null || l.kind === filtre.kind);
    const somme = (choix: (l: LigneBalance) => Montant) =>
      lignes.reduce((total, l) => total + Number(choix(l).amount), 0);

    const clotureDebit = somme((l) => l.closingDebit);
    const clotureCredit = somme((l) => l.closingCredit);
    return [{
      currency: DEVISE,
      accounts: lignes.length,
      // L'équilibre est constaté, pas décrété : on compare les deux colonnes.
      balanced: clotureDebit === clotureCredit,
      openingDebit: montant(somme((l) => l.openingDebit)),
      openingCredit: montant(somme((l) => l.openingCredit)),
      movementDebit: montant(somme((l) => l.movementDebit)),
      movementCredit: montant(somme((l) => l.movementCredit)),
      closingDebit: montant(clotureDebit),
      closingCredit: montant(clotureCredit),
    }];
  }

  // ----------------------------------------------------------------- détails

  private construire(legalEntityId: string, journee: string, mode: 'REAL' | 'DRY_RUN',
                     echecA: string | null, _repriseDe?: string): RunTfj {
    const steps: EtapeRun[] = [];
    let arrete = false;
    ETAPES.forEach((nom, index) => {
      const bloquante = BLOQUANTES.has(nom);
      if (arrete) {
        steps.push({ order: index + 1, name: nom, status: 'PENDING', read: 0, written: 0,
                     anomalies: [], blocking: bloquante, error: null });
        return;
      }
      if (nom === echecA) {
        arrete = true;
        steps.push({
          order: index + 1, name: nom, status: 'FAILED', read: 3, written: 0, anomalies: [],
          blocking: true,
          error: "La caisse OUA2-C02 a été mouvementée aujourd’hui et n’est pas arrêtée. "
            + "Une journée ne se clôt pas sur une caisse ouverte.",
        });
        return;
      }
      const lu = 40 + ((index * 137) % 900);
      steps.push({
        order: index + 1, name: nom, status: 'COMPLETED',
        read: lu, written: mode === 'DRY_RUN' ? 0 : Math.floor(lu / 3),
        anomalies: ANOMALIES[nom] ?? [], blocking: bloquante, error: null,
      });
    });

    return {
      id: crypto.randomUUID(),
      legalEntityId,
      businessDate: journee,
      mode,
      status: echecA ? 'FAILED' : 'COMPLETED',
      startedAt: new Date(Date.now() - 42_000).toISOString(),
      finishedAt: new Date().toISOString(),
      steps,
    };
  }

  private require(runId: string): RunTfj {
    const run = this.runs.get(runId);
    if (!run) throw new RefusMetier(404, 'PASSAGE_INTROUVABLE', 'Passage inconnu.');
    return run;
  }

  private mot(statut: RunTfj['status']): string {
    return { RUNNING: 'en cours', COMPLETED: 'terminé', FAILED: 'en échec', CANCELLED: 'annulé' }[statut];
  }

  /**
   * Une balance de démonstration qui **tombe juste**. Une balance d'essai qui
   * ne s'équilibre pas apprendrait l'écran à l'envers : l'alarme d'équilibre
   * doit se déclencher quand le registre ne se tient pas, pas en permanence.
   */
  private lignes(): readonly LigneBalance[] {
    const ligne = (
      code: string, kind: LigneBalance['kind'], nature: LigneBalance['nature'],
      normal: 'DEBIT' | 'CREDIT', ouvD: number, ouvC: number, mvtD: number, mvtC: number,
      libelle: string,
    ): LigneBalance => ({
      accountId: `cpt-${code}`, code, libelle, currency: DEVISE, kind, nature, normalBalance: normal,
      openingDebit: montant(ouvD), openingCredit: montant(ouvC),
      movementDebit: montant(mvtD), movementCredit: montant(mvtC),
      closingDebit: montant(ouvD + mvtD), closingCredit: montant(ouvC + mvtC),
    });

    return [
      ligne('10100', 'GL', 'BALANCE_SHEET', 'CREDIT', 0, 150000000, 0, 0, 'Capital et dotations'),
      ligne('25110', 'CUSTOMER', 'BALANCE_SHEET', 'CREDIT', 0, 207770000, 19687000, 24900000, 'Comptes de dépôts clientèle'),
      ligne('26110', 'CUSTOMER', 'BALANCE_SHEET', 'DEBIT', 184300000, 0, 11500000, 6200000, 'Crédits à la clientèle'),
      ligne('44210', 'GL', 'BALANCE_SHEET', 'CREDIT', 0, 1840000, 0, 187000, 'TAF collectée'),
      ligne('57110', 'INTERNAL', 'BALANCE_SHEET', 'DEBIT', 74200000, 0, 24900000, 18400000, 'Caisse des agences'),
      ligne('58110', 'INTERNAL', 'BALANCE_SHEET', 'DEBIT', 12000000, 0, 3400000, 3400000, 'Comptes de liaison'),
      ligne('37100', 'SUSPENSE', 'BALANCE_SHEET', 'DEBIT', 1250000, 0, 340000, 340000, 'Comptes de suspens'),
      ligne('47100', 'NOSTRO', 'BALANCE_SHEET', 'DEBIT', 92400000, 0, 6200000, 11800000, 'Correspondants bancaires'),
      ligne('35110', 'POSITION', 'BALANCE_SHEET', 'DEBIT', 4200000, 0, 620000, 620000, 'Position de change'),
      ligne('70611', 'GL', 'PROFIT_AND_LOSS', 'CREDIT', 0, 8420000, 0, 1100000, 'Commissions perçues'),
      ligne('70210', 'GL', 'PROFIT_AND_LOSS', 'CREDIT', 0, 21300000, 0, 2400000, "Produits d'intérêts"),
      ligne('60110', 'GL', 'PROFIT_AND_LOSS', 'DEBIT', 6780000, 0, 900000, 0, 'Charges générales'),
      ligne('63110', 'GL', 'PROFIT_AND_LOSS', 'DEBIT', 14200000, 0, 1800000, 0, 'Charges de personnel'),
      ligne('90110', 'GL', 'OFF_BALANCE_SHEET', 'DEBIT', 35000000, 0, 2000000, 0, 'Engagements donnés'),
      ligne('90910', 'GL', 'OFF_BALANCE_SHEET', 'CREDIT', 0, 35000000, 0, 2000000, 'Contrepartie hors bilan'),
    ];
  }

  private latence(): Promise<void> {
    return this.latenceMs === 0 ? Promise.resolve() : new Promise((r) => setTimeout(r, this.latenceMs));
  }
}
