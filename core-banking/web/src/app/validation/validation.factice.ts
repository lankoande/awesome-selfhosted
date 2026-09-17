import { Injectable } from '@angular/core';
import { RefusMetier } from '../guichet/modele/guichet.modele';
import { Identite, OperationEnAttente, PageOperations, StatutSocle } from './modele/validation.modele';
import { Validation } from './validation.port';

/**
 * Source de démonstration de la file de validation. Elle rejoue les règles du
 * socle : on n'approuve pas sa propre demande, une opération expirée n'est plus
 * décidable, un rejet se motive, et **la requête est rejouée à l'approbation**
 * — donc l'exécution peut refuser ce que la saisie acceptait.
 *
 * Chaque ligne existe pour montrer une issue.
 */

const MOI: Identite = { id: 'u-kabore', username: 'a.kabore' };

/**
 * Les dates sont relatives à l'instant de lecture. Une démonstration dont les
 * échéances sont écrites en dur se périme : trois jours plus tard, tout est
 * expiré et il ne reste rien à montrer.
 */
const HEURE = 3_600_000;
function dans(heures: number): string {
  return new Date(Date.now() + heures * HEURE).toISOString();
}
function ilYA(heures: number): string {
  return dans(-heures);
}

type EntreeDemo = OperationEnAttente & { pourquoi: string; echoueALExecution?: string };

function entrees(): readonly EntreeDemo[] {
  return [
    {
      id: 'PND-000101',
      operation: 'ACCOUNT_LIMIT_MANAGE',
      handler: 'Account.limit',
      status: 'PENDING',
      makerId: 'u-ouedraogo',
      makerUsername: 'm.ouedraogo',
      madeAt: ilYA(6),
      expiresAt: dans(42),
      decidedBy: null,
      decidedAt: null,
      decisionReason: null,
      payload: {
        accountId: 'BF12 0010 2510 ···· 0417',
        client: 'SANKARA Aminata',
        plafondRetraitJournalier: '1 500 000 XOF',
        ancienPlafond: '500 000 XOF',
        motif: 'Commerçante, encaissements de marché hebdomadaires',
      },
      result: null,
      error: null,
      pourquoi: 'Cas nominal : approbation suivie d’une exécution réussie.',
    },
    {
      id: 'PND-000102',
      operation: 'LOAN_RESCHEDULE',
      handler: 'Loan.reschedule',
      status: 'PENDING',
      makerId: 'u-ouedraogo',
      makerUsername: 'm.ouedraogo',
      madeAt: ilYA(23),
      expiresAt: dans(25),
      decidedBy: null,
      decidedAt: null,
      decisionReason: null,
      payload: {
        contrat: 'PRT-2024-00871',
        client: 'ETS KABORE & Fils',
        capitalNonEchu: '4 200 000 XOF',
        nouvelleDuree: '36 mois',
        dateEffet: '2026-10-01',
        motif: 'Baisse saisonnière du chiffre d’affaires',
      },
      result: null,
      error: null,
      echoueALExecution:
        "Le contrat PRT-2024-00871 a reçu un remboursement anticipé le 17/09/2026 : le capital non échu "
        + "n’est plus celui de la demande (3 850 000 XOF).",
      pourquoi: 'La requête est rejouée à l’approbation, et l’état du jour a changé : l’exécution refuse.',
    },
    {
      id: 'PND-000103',
      operation: 'TAX_RULE_MANAGE',
      handler: 'Tax.rule',
      status: 'PENDING',
      makerId: MOI.id,
      makerUsername: MOI.username,
      madeAt: ilYA(5),
      expiresAt: dans(43),
      decidedBy: null,
      decidedAt: null,
      decisionReason: null,
      payload: {
        code: 'TAF-17',
        libelle: 'Taxe sur les activités financières',
        taux: '17 %',
        compteDeCollecte: '44210',
        validAPartirDu: '2026-10-01',
      },
      result: null,
      error: null,
      pourquoi: 'Soumise par vous : un second regard, c’est quelqu’un d’autre.',
    },
    {
      id: 'PND-000104',
      operation: 'PRODUCT_ACTIVATE',
      handler: 'Product.activate',
      status: 'PENDING',
      makerId: 'u-traore',
      makerUsername: 'f.traore',
      madeAt: ilYA(170),
      expiresAt: ilYA(122),
      decidedBy: null,
      decidedAt: null,
      decisionReason: null,
      payload: { produit: 'DAT-12M-2026', famille: 'TERM_DEPOSIT', taux: '4,25 %', dureeMois: 12 },
      result: null,
      error: null,
      pourquoi: 'Échéance dépassée : plus personne ne peut décider, le demandeur resoumet.',
    },
    {
      id: 'PND-000105',
      operation: 'BRANCH_MANAGE',
      handler: 'Branch.create',
      status: 'REJECTED',
      makerId: 'u-traore',
      makerUsername: 'f.traore',
      madeAt: ilYA(58),
      expiresAt: ilYA(10),
      decidedBy: 'a.kabore',
      decidedAt: ilYA(56),
      decisionReason: "Le compte de liaison de l’agence n’est pas ouvert : la demande est prématurée.",
      payload: { code: 'BOB1', libelle: 'Agence Bobo-Dioulasso 1', compteDeLiaison: '58110' },
      result: null,
      error: null,
      pourquoi: 'Déjà décidée : la file garde la trace et le motif.',
    },
    {
      id: 'PND-000106',
      operation: 'YEAR_CLOSE',
      handler: 'FiscalYear.close',
      status: 'EXECUTED',
      makerId: 'u-ouedraogo',
      makerUsername: 'm.ouedraogo',
      madeAt: ilYA(76),
      expiresAt: ilYA(28),
      decidedBy: 'a.kabore',
      decidedAt: ilYA(75),
      decisionReason: null,
      payload: { exercice: '2025', dateDeCloture: '2025-12-31' },
      result: { runId: 'TFA-2025-0001', statut: 'CLOSED' },
      error: null,
      pourquoi: 'Approuvée et exécutée : la file en garde le résultat.',
    },
  ];
}

@Injectable()
export class ValidationFactice implements Validation {
  private readonly depart = entrees();
  private readonly etat = new Map<string, OperationEnAttente>(
    this.depart.map((entree) => [entree.id, { ...entree }]),
  );

  latenceMs = 280;

  /** Ce que le socle ferait échouer à l'exécution, pour la démonstration. */
  private readonly echecs = new Map<string, string>(
    this.depart.filter((e) => e.echoueALExecution).map((e) => [e.id, e.echoueALExecution!]),
  );

  async identite(): Promise<Identite> {
    return MOI;
  }

  async file(_entite: string, page: number, taille: number): Promise<PageOperations> {
    await this.latence();
    const toutes = [...this.etat.values()].sort((a, b) => b.madeAt.localeCompare(a.madeAt));
    const debut = page * taille;
    return {
      elements: toutes.slice(debut, debut + taille),
      numero: page,
      taille,
      precedent: page > 0,
      suivant: debut + taille < toutes.length,
    };
  }

  async lire(_entite: string, id: string): Promise<OperationEnAttente> {
    await this.latence();
    return this.require(id);
  }

  async approuver(_entite: string, id: string): Promise<OperationEnAttente> {
    await this.latence();
    const operation = this.decidable(id);

    if (operation.makerId === MOI.id) {
      throw new RefusMetier(403, 'AUTO_APPROBATION_INTERDITE',
        'Vous avez soumis cette opération.',
        "Un second regard, c’est quelqu’un d’autre. La politique d’habilitation refuse qu’un "
        + "demandeur valide sa propre demande, quelles que soient ses habilitations.");
    }

    // La décision est prise, puis l'opération est exécutée. Si l'exécution
    // échoue, la décision reste et l'opération passe en ÉCHOUÉE.
    const echec = this.echecs.get(id);
    if (echec) {
      this.poser(id, { status: 'FAILED', decidedBy: MOI.username, decidedAt: new Date().toISOString(), error: echec });
      throw new RefusMetier(409, 'EXECUTION_REFUSEE', 'Approuvée, mais non exécutée.', echec);
    }

    return this.poser(id, {
      status: 'EXECUTED',
      decidedBy: MOI.username,
      decidedAt: new Date().toISOString(),
      result: { applique: true },
    });
  }

  async rejeter(_entite: string, id: string, motif: string): Promise<OperationEnAttente> {
    await this.latence();
    this.decidable(id);
    if (!motif.trim()) {
      throw new RefusMetier(400, 'MOTIF_REQUIS', 'Un rejet se motive.',
        'Le demandeur doit pouvoir corriger : sans motif, il resoumettra la même demande.');
    }
    return this.poser(id, {
      status: 'REJECTED',
      decidedBy: MOI.username,
      decidedAt: new Date().toISOString(),
      decisionReason: motif.trim(),
    });
  }

  private require(id: string): OperationEnAttente {
    const operation = this.etat.get(id);
    if (!operation) throw new RefusMetier(404, 'OPERATION_INTROUVABLE', 'Opération inconnue.');
    return operation;
  }

  private decidable(id: string): OperationEnAttente {
    const operation = this.require(id);
    if (new Date(operation.expiresAt) <= new Date()) {
      this.poser(id, { status: 'EXPIRED' });
      throw new RefusMetier(409, 'OPERATION_NON_DECIDABLE', 'Opération expirée.',
        `Le délai a couru le ${operation.expiresAt}. Le demandeur doit resoumettre.`);
    }
    if (operation.status !== 'PENDING') {
      throw new RefusMetier(409, 'OPERATION_NON_DECIDABLE', `Opération déjà ${this.mot(operation.status)}.`,
        'Une décision ne se reprend pas : elle laisse une trace, et une nouvelle demande la remplace.');
    }
    return operation;
  }

  private poser(id: string, modification: Partial<OperationEnAttente>): OperationEnAttente {
    const suivant = { ...this.require(id), ...modification };
    this.etat.set(id, suivant);
    return suivant;
  }

  private mot(statut: StatutSocle): string {
    return { PENDING: 'en attente', APPROVED: 'approuvée', REJECTED: 'rejetée', EXPIRED: 'expirée',
             EXECUTED: 'exécutée', FAILED: 'échouée' }[statut];
  }

  private latence(): Promise<void> {
    return this.latenceMs === 0 ? Promise.resolve() : new Promise((r) => setTimeout(r, this.latenceMs));
  }
}
