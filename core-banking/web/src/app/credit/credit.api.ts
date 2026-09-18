import { HttpClient, HttpErrorResponse, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Socle } from '../api/socle';
import { RefusMetier } from '../guichet/modele/guichet.modele';
import { Credit, EnAttente } from './credit.port';
import {
  Analyse, Condition, Contrat, Creance, Decision, Demande, DemandeAnalyse, DemandeCondition,
  DemandeContrat, DemandeDeCredit, DemandeDecision, DemandeReglement, DossierCredit, Echeance,
  Evenement, Imputation, Montant, Reglement, StatutDemande,
} from './modele/credit.modele';

interface Enveloppe<T> {
  readonly data: T;
  readonly error: { type: string; title: string; status: number; detail?: string } | null;
  readonly page?: { size: number; number: number | null; hasNext: boolean; hasPrevious: boolean } | null;
  readonly meta: { requestId: string; timestamp: string };
}

/**
 * Le crédit sur les routes réelles du socle.
 *
 * Tous les champs du contrat sont optionnels — c'est ainsi qu'`openapi-typescript`
 * rend un schéma sans obligatoires. On les ramène à des valeurs sûres plutôt que
 * de propager des `undefined` dans les écrans : « undefined » affiché à un
 * chargé de crédit est un défaut que le client voit.
 */
@Injectable()
export class CreditApi implements Credit {
  private readonly http = inject(HttpClient);
  private readonly socle = inject(Socle);

  async demandes(legalEntityId: string, statut: StatutDemande | null, page: number,
                 taille: number) {
    const url = this.socle.url('/v1/entities/{legalEntityId}/loan-applications', { legalEntityId });
    let parametres = new HttpParams().set('page', page).set('size', taille);
    if (statut) parametres = parametres.set('status', statut);
    try {
      const enveloppe = await firstValueFrom(
        this.http.get<Enveloppe<readonly unknown[]>>(url, { params: parametres }));
      return {
        demandes: (enveloppe.data ?? []).map(versDemande),
        page,
        precedent: enveloppe.page?.hasPrevious ?? page > 0,
        suivant: enveloppe.page?.hasNext ?? false,
      };
    } catch (erreur) {
      throw this.refus(erreur);
    }
  }

  async dossier(legalEntityId: string, applicationId: string): Promise<DossierCredit> {
    const brut = await this.obtenir<Record<string, unknown>>(this.socle.url(
      '/v1/entities/{legalEntityId}/loan-applications/{applicationId}',
      { legalEntityId, applicationId }));
    return {
      demande: versDemande(brut['application']),
      analyses: ((brut['assessments'] ?? []) as readonly unknown[]).map(versAnalyse),
      conditions: ((brut['conditions'] ?? []) as readonly unknown[]).map(versCondition),
      decision: brut['decision'] ? versDecision(brut['decision']) : null,
      evenements: ((brut['events'] ?? []) as readonly unknown[]).map(versEvenement),
    };
  }

  async deposer(demande: DemandeDeCredit, cle: string): Promise<{ id: string }> {
    const url = this.socle.url('/v1/entities/{legalEntityId}/loan-applications',
                               { legalEntityId: demande.legalEntityId });
    const cree = await this.poster<{ id?: string }>(url, {
      customerId: demande.customerId,
      productCode: demande.productCode,
      currency: demande.currency,
      requestedAmount: Number(demande.requestedAmount),
      requestedTermMonths: demande.requestedTermMonths,
      purpose: demande.purpose,
      ...(demande.reference ? { reference: demande.reference } : {}),
      ...(demande.requestedOn ? { requestedOn: demande.requestedOn } : {}),
    }, cle);
    return { id: texte(cree.id) ?? '' };
  }

  async analyser(legalEntityId: string, applicationId: string, analyse: DemandeAnalyse,
                 cle: string): Promise<void> {
    await this.poster(this.socle.url(
      '/v1/entities/{legalEntityId}/loan-applications/{applicationId}/assessment',
      { legalEntityId, applicationId }), {
      monthlyIncome: Number(analyse.monthlyIncome),
      monthlyCharges: Number(analyse.monthlyCharges),
      ratePercent: Number(analyse.ratePercent),
      ...(analyse.downPayment ? { downPayment: Number(analyse.downPayment) } : {}),
      ...(analyse.externalScore !== null ? { externalScore: analyse.externalScore } : {}),
      ...(analyse.scoreSource ? { scoreSource: analyse.scoreSource } : {}),
      ...(analyse.assessedOn ? { assessedOn: analyse.assessedOn } : {}),
    }, cle);
  }

  async poserCondition(legalEntityId: string, applicationId: string, condition: DemandeCondition,
                       cle: string): Promise<void> {
    await this.poster(this.socle.url(
      '/v1/entities/{legalEntityId}/loan-applications/{applicationId}/conditions',
      { legalEntityId, applicationId }), {
      kind: condition.kind,
      description: condition.description,
      ...(condition.dueOn ? { dueOn: condition.dueOn } : {}),
    }, cle);
  }

  async leverCondition(legalEntityId: string, conditionId: string, preuve: string,
                       cle: string): Promise<EnAttente> {
    const vue = await this.poster<{ id?: string }>(this.socle.url(
      '/v1/entities/{legalEntityId}/loan-conditions/{conditionId}/clearance',
      { legalEntityId, conditionId }), { evidence: preuve }, cle);
    return { operationId: texte(vue.id) ?? '' };
  }

  async decider(legalEntityId: string, applicationId: string, decision: DemandeDecision,
                cle: string): Promise<EnAttente> {
    const vue = await this.poster<{ id?: string }>(this.socle.url(
      '/v1/entities/{legalEntityId}/loan-applications/{applicationId}/decision',
      { legalEntityId, applicationId }), {
      outcome: decision.outcome,
      reason: decision.reason,
      ...(decision.grantedAmount ? { grantedAmount: Number(decision.grantedAmount) } : {}),
      ...(decision.grantedRatePercent
        ? { grantedRatePercent: Number(decision.grantedRatePercent) } : {}),
      ...(decision.grantedTermMonths !== null
        ? { grantedTermMonths: decision.grantedTermMonths } : {}),
      ...(decision.waiverReason ? { waiverReason: decision.waiverReason } : {}),
      ...(decision.decidedOn ? { decidedOn: decision.decidedOn } : {}),
    }, cle);
    return { operationId: texte(vue.id) ?? '' };
  }

  async contractualiser(legalEntityId: string, applicationId: string, contrat: DemandeContrat,
                        cle: string): Promise<{ contractId: string; reference: string }> {
    const fait = await this.poster<{ contractId?: string; reference?: string }>(this.socle.url(
      '/v1/entities/{legalEntityId}/loan-applications/{applicationId}/contract',
      { legalEntityId, applicationId }), {
      loanAccountId: contrat.loanAccountId,
      settlementAccountId: contrat.settlementAccountId,
      ...(contrat.contractReference ? { contractReference: contrat.contractReference } : {}),
      ...(contrat.disbursementDate ? { disbursementDate: contrat.disbursementDate } : {}),
    }, cle);
    return { contractId: texte(fait.contractId) ?? '', reference: texte(fait.reference) ?? '' };
  }

  async retirer(legalEntityId: string, applicationId: string, motif: string,
                cle: string): Promise<void> {
    await this.poster(this.socle.url(
      '/v1/entities/{legalEntityId}/loan-applications/{applicationId}/withdrawal',
      { legalEntityId, applicationId }), { reason: motif }, cle);
  }

  // ------------------------------------------------------------------ contrats

  async contrats(legalEntityId: string, page: number, taille: number) {
    const url = this.socle.url('/v1/entities/{legalEntityId}/loans', { legalEntityId });
    const parametres = new HttpParams().set('page', page).set('size', taille);
    try {
      const enveloppe = await firstValueFrom(
        this.http.get<Enveloppe<readonly unknown[]>>(url, { params: parametres }));
      return {
        contrats: (enveloppe.data ?? []).map(versContrat),
        page,
        precedent: enveloppe.page?.hasPrevious ?? page > 0,
        suivant: enveloppe.page?.hasNext ?? false,
      };
    } catch (erreur) {
      throw this.refus(erreur);
    }
  }

  async contrat(legalEntityId: string, contractId: string): Promise<Contrat> {
    return versContrat(await this.obtenir(this.socle.url(
      '/v1/entities/{legalEntityId}/loans/{contractId}', { legalEntityId, contractId })));
  }

  async debloquer(legalEntityId: string, contractId: string, cle: string): Promise<EnAttente> {
    const vue = await this.poster<{ id?: string }>(this.socle.url(
      '/v1/entities/{legalEntityId}/loans/{contractId}/disbursement',
      { legalEntityId, contractId }), {}, cle);
    return { operationId: texte(vue.id) ?? '' };
  }

  async regler(legalEntityId: string, contractId: string, reglement: DemandeReglement,
               cle: string): Promise<Reglement> {
    const brut = await this.poster<Record<string, unknown>>(this.socle.url(
      '/v1/entities/{legalEntityId}/loans/{contractId}/repayments',
      { legalEntityId, contractId }), {
      amount: reglement.amount,
      currency: reglement.currency,
      ...(reglement.valueDate ? { valueDate: reglement.valueDate } : {}),
    }, cle);
    return {
      paid: montant(brut['paid']),
      allocated: montant(brut['allocated']),
      unallocated: montant(brut['unallocated']),
      imputations: ((brut['allocations'] ?? []) as readonly unknown[]).map(versImputation),
    };
  }

  // ------------------------------------------------------------------ transport

  private async obtenir<T>(url: string): Promise<T> {
    try {
      const enveloppe = await firstValueFrom(this.http.get<Enveloppe<T>>(url));
      return enveloppe.data;
    } catch (erreur) {
      throw this.refus(erreur);
    }
  }

  private async poster<T>(url: string, corps: unknown, cle: string): Promise<T> {
    const entetes = new HttpHeaders({
      'Idempotency-Key': cle,
      'X-Request-Id': crypto.randomUUID(),
    });
    try {
      const enveloppe = await firstValueFrom(
        this.http.post<Enveloppe<T>>(url, corps, { headers: entetes }));
      return enveloppe.data;
    } catch (erreur) {
      throw this.refus(erreur);
    }
  }

  private refus(erreur: unknown): RefusMetier {
    if (erreur instanceof HttpErrorResponse) {
      const corps = erreur.error as { error?: { type?: string; title?: string; detail?: string } };
      const detail = corps?.error;
      return new RefusMetier(erreur.status, detail?.type ?? 'ERREUR',
                             detail?.title ?? 'Le socle a refusé.', detail?.detail);
    }
    return new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste.', String(erreur));
  }
}

// ------------------------------------------------------------------ lectures

function texte(valeur: unknown): string | null {
  return typeof valeur === 'string' && valeur !== '' ? valeur : null;
}

function nombre(valeur: unknown): number | null {
  return typeof valeur === 'number' ? valeur : null;
}

/** Un montant se garde en texte : le convertir en nombre l'arrondirait. */
function montant(valeur: unknown): Montant | null {
  if (valeur === null || typeof valeur !== 'object') return null;
  const m = valeur as Record<string, unknown>;
  const somme = m['amount'];
  if (somme === undefined || somme === null) return null;
  const devise = m['currency'];
  return {
    amount: String(somme),
    currency: typeof devise === 'string' ? devise
      : String((devise as Record<string, unknown> | null)?.['code'] ?? ''),
  };
}

/** Un pourcentage se garde en texte, pour la même raison qu'un montant. */
function pourcent(valeur: unknown): string | null {
  return valeur === null || valeur === undefined ? null : String(valeur);
}

function versDemande(brut: unknown): Demande {
  const d = (brut ?? {}) as Record<string, unknown>;
  return {
    id: texte(d['id']) ?? '',
    reference: texte(d['reference']) ?? '',
    customerId: texte(d['customerId']) ?? '',
    customerReference: texte(d['customerReference']),
    productCode: texte(d['productCode']) ?? '',
    purpose: texte(d['purpose']),
    requestedAmount: montant(d['requestedAmount']),
    requestedTermMonths: nombre(d['requestedTermMonths']),
    requestedOn: texte(d['requestedOn']),
    status: (texte(d['status']) ?? 'SUBMITTED') as Demande['status'],
    contractId: texte(d['contractId']),
    closedOn: texte(d['closedOn']),
    closingReason: texte(d['closingReason']),
  };
}

function versAnalyse(brut: unknown): Analyse {
  const a = (brut ?? {}) as Record<string, unknown>;
  return {
    id: texte(a['id']) ?? '',
    assessedOn: texte(a['assessedOn']),
    monthlyIncome: montant(a['monthlyIncome']),
    monthlyCharges: montant(a['monthlyCharges']),
    existingCommitments: montant(a['existingCommitments']),
    downPayment: montant(a['downPayment']),
    requestedInstalment: montant(a['requestedInstalment']),
    debtServiceRatioPercent: pourcent(a['debtServiceRatioPercent']),
    externalScore: nombre(a['externalScore']),
    scoreSource: texte(a['scoreSource']),
    breaches: ((a['breaches'] ?? []) as readonly unknown[])
      .map((b) => String(b)).filter((b) => b !== ''),
  };
}

function versCondition(brut: unknown): Condition {
  const c = (brut ?? {}) as Record<string, unknown>;
  return {
    id: texte(c['id']) ?? '',
    kind: (texte(c['kind']) ?? 'PRECEDENT') as Condition['kind'],
    description: texte(c['description']) ?? '',
    dueOn: texte(c['dueOn']),
    clearedOn: texte(c['clearedOn']),
    evidence: texte(c['evidence']),
  };
}

function versDecision(brut: unknown): Decision {
  const d = (brut ?? {}) as Record<string, unknown>;
  return {
    outcome: (texte(d['outcome']) ?? 'REJECTED') as Decision['outcome'],
    decidedOn: texte(d['decidedOn']),
    grantedAmount: montant(d['grantedAmount']),
    grantedRatePercent: pourcent(d['grantedRatePercent']),
    grantedTermMonths: nombre(d['grantedTermMonths']),
    validUntil: texte(d['validUntil']),
    reason: texte(d['reason']),
    waiverReason: texte(d['waiverReason']),
  };
}

function versEvenement(brut: unknown): Evenement {
  const e = (brut ?? {}) as Record<string, unknown>;
  return {
    kind: texte(e['kind']) ?? '',
    occurredOn: texte(e['occurredOn']),
    detail: texte(e['detail']),
  };
}

function versEcheance(brut: unknown): Echeance {
  const e = (brut ?? {}) as Record<string, unknown>;
  return {
    number: nombre(e['number']) ?? 0,
    dueDate: texte(e['dueDate']),
    principal: montant(e['principal']),
    interest: montant(e['interest']),
    insurance: montant(e['insurance']),
    tax: montant(e['tax']),
    fee: montant(e['fee']),
    total: montant(e['total']),
  };
}

function versCreance(brut: unknown): Creance {
  const c = (brut ?? {}) as Record<string, unknown>;
  return {
    id: texte(c['id']) ?? '',
    category: (texte(c['category']) ?? 'PRINCIPAL') as Creance['category'],
    dueDate: texte(c['dueDate']),
    instalmentNumber: nombre(c['instalmentNumber']),
    outstanding: montant(c['outstanding']),
  };
}

function versImputation(brut: unknown): Imputation {
  const i = (brut ?? {}) as Record<string, unknown>;
  const creance = versCreance(i['receivable']);
  return {
    category: creance.category,
    instalmentNumber: creance.instalmentNumber,
    amount: montant(i['amount']),
    remaining: montant(i['remaining']),
  };
}

/**
 * `LoanContract` (la liste) et `LoanUseCases.LoanView` (la lecture) n'ont pas
 * la même forme : la liste ne porte ni échéancier ni créances, et son taux vit
 * dans `terms`. Une seule lecture les ramène au même objet — sans quoi l'écran
 * de liste et l'écran de détail divergeraient sur le nom d'un champ.
 */
function versContrat(brut: unknown): Contrat {
  const c = (brut ?? {}) as Record<string, unknown>;
  const terms = (c['terms'] ?? {}) as Record<string, unknown>;
  const devise = c['currency'];
  return {
    id: texte(c['id']) ?? '',
    reference: texte(c['reference']) ?? '',
    productCode: texte(c['productCode']) ?? '',
    principal: montant(c['principal']),
    currency: typeof devise === 'string' ? devise
      : String((devise as Record<string, unknown> | null)?.['code'] ?? ''),
    status: (texte(c['status']) ?? 'DRAFT') as Contrat['status'],
    disbursedOn: texte(c['disbursedOn']) ?? texte(terms['disbursedOn']),
    daysPastDue: nombre(c['daysPastDue']) ?? 0,
    asOf: texte(c['asOf']),
    echeancier: ((c['schedule'] ?? []) as readonly unknown[]).map(versEcheance),
    creances: ((c['receivables'] ?? []) as readonly unknown[]).map(versCreance),
    tauxAnnuel: pourcent(terms['annualRatePercent']),
    nombreEcheances: nombre(terms['instalmentCount']),
    methode: texte(terms['method']),
  };
}
