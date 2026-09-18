import { HttpClient, HttpErrorResponse, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Socle } from '../api/socle';
import { RefusMetier } from '../guichet/modele/guichet.modele';
import { EnAttente, Reglementaire } from './reglementaire.port';
import {
  AssietteTaxe, Declaration, DemandeDeclaration, DemandeRegleFiscale, DemandeTransmission,
  Destinataire, DossierEtat, Echeance, Etat, Frequence, LigneEtat, MethodeEtat, Montant,
  NatureSujet, RegleFiscale, StatutEtat,
} from './modele/reglementaire.modele';

interface Enveloppe<T> {
  readonly data: T;
  readonly error: { type: string; title: string; status: number; detail?: string } | null;
  readonly meta: { requestId: string; timestamp: string };
}

/**
 * Le réglementaire sur les routes réelles du socle.
 *
 * Tous les champs du contrat sont optionnels — c'est ainsi qu'`openapi-typescript`
 * rend un schéma sans obligatoires. On les ramène à des valeurs sûres plutôt que
 * de propager des `undefined` : « undefined » sur un état destiné au superviseur
 * est un défaut que l'inspection verra.
 */
@Injectable()
export class ReglementaireApi implements Reglementaire {
  private readonly http = inject(HttpClient);
  private readonly socle = inject(Socle);

  async echeances(legalEntityId: string): Promise<readonly Echeance[]> {
    const brut = await this.obtenir<readonly unknown[]>(this.socle.url(
      '/v1/entities/{legalEntityId}/regulatory/deadlines', { legalEntityId }));
    return (brut ?? []).map(versEcheance);
  }

  async declarations(legalEntityId: string): Promise<readonly Declaration[]> {
    const brut = await this.obtenir<readonly unknown[]>(this.socle.url(
      '/v1/entities/{legalEntityId}/regulatory/declarations', { legalEntityId }));
    return (brut ?? []).map(versDeclaration);
  }

  async declarer(legalEntityId: string, demande: DemandeDeclaration,
                 cle: string): Promise<EnAttente> {
    const vue = await this.poster<{ id?: string }>(this.socle.url(
      '/v1/entities/{legalEntityId}/regulatory/declarations', { legalEntityId }), {
      code: demande.code,
      label: demande.label,
      recipient: demande.recipient,
      method: demande.method,
      frequency: demande.frequency,
      deadlineDays: demande.deadlineDays,
      ...(demande.thresholdAmount ? { thresholdAmount: Number(demande.thresholdAmount) } : {}),
      validFrom: demande.validFrom,
      ...(demande.validTo ? { validTo: demande.validTo } : {}),
    }, cle);
    return { operationId: texte(vue.id) ?? '' };
  }

  async produire(legalEntityId: string, declarationId: string, periodEnd: string,
                 cle: string): Promise<Etat> {
    return versEtat(await this.poster<unknown>(this.socle.url(
      '/v1/entities/{legalEntityId}/regulatory/declarations/{declarationId}/filings',
      { legalEntityId, declarationId }), { periodEnd }, cle));
  }

  async etats(legalEntityId: string, statut: StatutEtat | null): Promise<readonly Etat[]> {
    const url = this.socle.url('/v1/entities/{legalEntityId}/regulatory/filings',
                               { legalEntityId });
    const parametres = statut ? new HttpParams().set('status', statut) : new HttpParams();
    try {
      const enveloppe = await firstValueFrom(
        this.http.get<Enveloppe<readonly unknown[]>>(url, { params: parametres }));
      return (enveloppe.data ?? []).map(versEtat);
    } catch (erreur) {
      throw this.refus(erreur);
    }
  }

  async etat(legalEntityId: string, filingId: string): Promise<DossierEtat> {
    const brut = await this.obtenir<Record<string, unknown>>(this.socle.url(
      '/v1/entities/{legalEntityId}/regulatory/filings/{filingId}',
      { legalEntityId, filingId }));
    return {
      etat: versEtat(brut['filing']),
      ecarts: ((brut['differences'] ?? []) as readonly unknown[])
        .map((e) => texte(e) ?? '').filter((e) => e !== ''),
    };
  }

  async transmettre(legalEntityId: string, filingId: string, demande: DemandeTransmission,
                    cle: string): Promise<EnAttente> {
    const vue = await this.poster<{ id?: string }>(this.socle.url(
      '/v1/entities/{legalEntityId}/regulatory/filings/{filingId}/transmission',
      { legalEntityId, filingId }), {
      reference: demande.reference,
      ...(demande.transmittedOn ? { transmittedOn: demande.transmittedOn } : {}),
    }, cle);
    return { operationId: texte(vue.id) ?? '' };
  }

  async annuler(legalEntityId: string, filingId: string, motif: string,
                cle: string): Promise<Etat> {
    return versEtat(await this.poster<unknown>(this.socle.url(
      '/v1/entities/{legalEntityId}/regulatory/filings/{filingId}/cancellation',
      { legalEntityId, filingId }), { reason: motif }, cle));
  }

  async reglesFiscales(legalEntityId: string): Promise<readonly RegleFiscale[]> {
    const brut = await this.obtenir<readonly unknown[]>(this.socle.url(
      '/v1/entities/{legalEntityId}/regulatory/tax-rules', { legalEntityId }));
    return (brut ?? []).map(versRegleFiscale);
  }

  async declarerRegleFiscale(legalEntityId: string, demande: DemandeRegleFiscale,
                             cle: string): Promise<EnAttente> {
    const vue = await this.poster<{ id?: string }>(this.socle.url(
      '/v1/entities/{legalEntityId}/regulatory/tax-rules', { legalEntityId }), {
      code: demande.code,
      label: demande.label,
      basis: demande.basis,
      ratePercent: Number(demande.ratePercent),
      collectionAccountId: demande.collectionAccountId,
      validFrom: demande.validFrom,
      ...(demande.validTo ? { validTo: demande.validTo } : {}),
    }, cle);
    return { operationId: texte(vue.id) ?? '' };
  }

  // ------------------------------------------------------------------ outillage

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

// --------------------------------------------------------------- conversions

function texte(valeur: unknown): string | null {
  return typeof valeur === 'string' && valeur !== '' ? valeur : null;
}

function entier(valeur: unknown): number | null {
  return typeof valeur === 'number' ? valeur : null;
}

/** Un décimal du socle : `thresholdAmount` et `ratePercent` arrivent en nombre JSON. */
function decimal(valeur: unknown): string | null {
  if (typeof valeur === 'number') return String(valeur);
  return texte(valeur);
}

function versMontant(valeur: unknown): Montant | null {
  if (!valeur || typeof valeur !== 'object') return null;
  const brut = valeur as Record<string, unknown>;
  const montant = texte(brut['amount']) ?? decimal(brut['amount']);
  const devise = texte(brut['currency']);
  return montant !== null && devise !== null ? { amount: montant, currency: devise } : null;
}

function versEcheance(valeur: unknown): Echeance {
  const brut = (valeur ?? {}) as Record<string, unknown>;
  return {
    declarationCode: texte(brut['declarationCode']) ?? '',
    periodEnd: texte(brut['periodEnd']),
    dueOn: texte(brut['dueOn']),
    produced: brut['produced'] === true,
  };
}

function versDeclaration(valeur: unknown): Declaration {
  const brut = (valeur ?? {}) as Record<string, unknown>;
  return {
    id: texte(brut['id']) ?? '',
    code: texte(brut['code']) ?? '',
    label: texte(brut['label']) ?? '',
    recipient: (texte(brut['recipient']) ?? 'CENTRAL_BANK') as Destinataire,
    method: (texte(brut['method']) ?? 'ACCOUNTING_SITUATION') as MethodeEtat,
    frequency: (texte(brut['frequency']) ?? 'MONTHLY') as Frequence,
    deadlineDays: entier(brut['deadlineDays']),
    thresholdAmount: decimal(brut['thresholdAmount']),
    subjectCode: texte(brut['subjectCode']),
    validFrom: texte(brut['validFrom']),
    validTo: texte(brut['validTo']),
  };
}

function versEtat(valeur: unknown): Etat {
  const brut = (valeur ?? {}) as Record<string, unknown>;
  return {
    id: texte(brut['id']) ?? '',
    declarationId: texte(brut['declarationId']) ?? '',
    declarationCode: texte(brut['declarationCode']) ?? '',
    method: (texte(brut['method']) ?? 'ACCOUNTING_SITUATION') as MethodeEtat,
    subjectCode: texte(brut['subjectCode']),
    periodStart: texte(brut['periodStart']),
    periodEnd: texte(brut['periodEnd']),
    dueOn: texte(brut['dueOn']),
    producedOn: texte(brut['producedOn']),
    thresholdUsed: decimal(brut['thresholdUsed']),
    lineCount: entier(brut['lineCount']) ?? 0,
    totalAmount: versMontant(brut['totalAmount']),
    status: (texte(brut['status']) ?? 'PRODUCED') as StatutEtat,
    transmittedOn: texte(brut['transmittedOn']),
    transmissionReference: texte(brut['transmissionReference']),
    cancelledOn: texte(brut['cancelledOn']),
    cancellationReason: texte(brut['cancellationReason']),
    anomalies: ((brut['anomalies'] ?? []) as readonly unknown[])
      .map((a) => texte(a) ?? '').filter((a) => a !== ''),
    lignes: ((brut['lines'] ?? []) as readonly unknown[]).map(versLigne),
  };
}

function versLigne(valeur: unknown): LigneEtat {
  const brut = (valeur ?? {}) as Record<string, unknown>;
  return {
    subjectKind: (texte(brut['subjectKind']) ?? 'GL_ACCOUNT') as NatureSujet,
    subjectReference: texte(brut['subjectReference']),
    label: texte(brut['label']),
    amount: versMontant(brut['amount']),
    offBalance: versMontant(brut['offBalance']),
    classification: texte(brut['classification']),
    daysPastDue: entier(brut['daysPastDue']),
    occurrences: entier(brut['occurrences']),
    detail: texte(brut['detail']),
  };
}

function versRegleFiscale(valeur: unknown): RegleFiscale {
  const brut = (valeur ?? {}) as Record<string, unknown>;
  return {
    id: texte(brut['id']) ?? '',
    code: texte(brut['code']) ?? '',
    label: texte(brut['label']) ?? '',
    basis: (texte(brut['basis']) ?? 'TRANSACTION') as AssietteTaxe,
    ratePercent: decimal(brut['ratePercent']),
    collectionAccountId: texte(brut['collectionAccountId']),
    validFrom: texte(brut['validFrom']),
    validTo: texte(brut['validTo']),
  };
}
