import { HttpClient, HttpErrorResponse, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Socle } from '../api/socle';
import { RefusMetier } from '../guichet/modele/guichet.modele';
import { Conformite, EnAttente } from './conformite.port';
import {
  Alerte, Declaration, DemandeDeclaration, DemandeScenario, DemandeTransmission, MethodeScenario,
  Montant, OrigineAlerte, PieceAlerte, Scenario, StatutAlerte,
} from './modele/conformite.modele';

interface Enveloppe<T> {
  readonly data: T;
  readonly error: { type: string; title: string; status: number; detail?: string } | null;
  readonly meta: { requestId: string; timestamp: string };
}

/**
 * La conformité sur les routes réelles du socle.
 *
 * Tous les champs du contrat sont optionnels — c'est ainsi qu'`openapi-typescript`
 * rend un schéma sans obligatoires. On les ramène à des valeurs sûres plutôt que
 * de propager des `undefined` : « undefined » affiché sur une file d'alertes est
 * un défaut que l'inspection verra.
 */
@Injectable()
export class ConformiteApi implements Conformite {
  private readonly http = inject(HttpClient);
  private readonly socle = inject(Socle);

  async alertes(legalEntityId: string, statut: StatutAlerte | null): Promise<readonly Alerte[]> {
    const url = this.socle.url('/v1/entities/{legalEntityId}/compliance/alerts',
                               { legalEntityId });
    const parametres = statut ? new HttpParams().set('status', statut) : new HttpParams();
    try {
      const enveloppe = await firstValueFrom(
        this.http.get<Enveloppe<readonly unknown[]>>(url, { params: parametres }));
      return (enveloppe.data ?? []).map(versAlerte);
    } catch (erreur) {
      throw this.refus(erreur);
    }
  }

  async alerte(legalEntityId: string, alertId: string): Promise<Alerte> {
    return versAlerte(await this.obtenir<unknown>(this.socle.url(
      '/v1/entities/{legalEntityId}/compliance/alerts/{alertId}', { legalEntityId, alertId })));
  }

  async prendreEnCharge(legalEntityId: string, alertId: string, cle: string): Promise<Alerte> {
    return versAlerte(await this.poster<unknown>(this.socle.url(
      '/v1/entities/{legalEntityId}/compliance/alerts/{alertId}/assignment',
      { legalEntityId, alertId }), {}, cle));
  }

  async classer(legalEntityId: string, alertId: string, motif: string,
                cle: string): Promise<Alerte> {
    return versAlerte(await this.poster<unknown>(this.socle.url(
      '/v1/entities/{legalEntityId}/compliance/alerts/{alertId}/closure',
      { legalEntityId, alertId }), { reason: motif }, cle));
  }

  async declarations(legalEntityId: string): Promise<readonly Declaration[]> {
    const brut = await this.obtenir<readonly unknown[]>(this.socle.url(
      '/v1/entities/{legalEntityId}/compliance/reports', { legalEntityId }));
    return (brut ?? []).map(versDeclaration);
  }

  async rediger(legalEntityId: string, demande: DemandeDeclaration,
                cle: string): Promise<EnAttente> {
    const vue = await this.poster<{ id?: string }>(this.socle.url(
      '/v1/entities/{legalEntityId}/compliance/reports', { legalEntityId }), {
      partyId: demande.partyId,
      reference: demande.reference,
      narrative: demande.narrative,
      alertIds: demande.alertIds,
    }, cle);
    return { operationId: texte(vue.id) ?? '' };
  }

  async transmettre(legalEntityId: string, reportId: string, demande: DemandeTransmission,
                    cle: string): Promise<Declaration> {
    return versDeclaration(await this.poster<unknown>(this.socle.url(
      '/v1/entities/{legalEntityId}/compliance/reports/{reportId}/transmission',
      { legalEntityId, reportId }), {
      reference: demande.reference,
      ...(demande.transmittedOn ? { transmittedOn: demande.transmittedOn } : {}),
    }, cle));
  }

  async scenarios(legalEntityId: string): Promise<readonly Scenario[]> {
    const brut = await this.obtenir<readonly unknown[]>(this.socle.url(
      '/v1/entities/{legalEntityId}/compliance/scenarios', { legalEntityId }));
    return (brut ?? []).map(versScenario);
  }

  async declarerScenario(legalEntityId: string, demande: DemandeScenario,
                         cle: string): Promise<EnAttente> {
    const vue = await this.poster<{ id?: string }>(this.socle.url(
      '/v1/entities/{legalEntityId}/compliance/scenarios', { legalEntityId }), {
      code: demande.code,
      label: demande.label,
      method: demande.method,
      ...(demande.thresholdAmount ? { thresholdAmount: Number(demande.thresholdAmount) } : {}),
      ...(demande.windowDays !== null ? { windowDays: demande.windowDays } : {}),
      ...(demande.minimumCount !== null ? { minimumCount: demande.minimumCount } : {}),
      ...(demande.ratio ? { ratio: Number(demande.ratio) } : {}),
      ...(demande.riskRating ? { riskRating: demande.riskRating } : {}),
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

function nombre(valeur: unknown): number | null {
  return typeof valeur === 'number' ? valeur : null;
}

/** Un décimal du socle. `ratio` et `thresholdAmount` arrivent en nombre JSON. */
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

function versAlerte(valeur: unknown): Alerte {
  const brut = (valeur ?? {}) as Record<string, unknown>;
  return {
    id: texte(brut['id']) ?? '',
    partyId: texte(brut['partyId']) ?? '',
    scenarioCode: texte(brut['scenarioCode']),
    // Une origine inconnue serait un contrat en avance sur l'interface : on la
    // ramène à la surveillance, qui ne bloque rien — jamais au filtrage, qui
    // ferait croire à un blocage inexistant.
    origin: (texte(brut['origin']) ?? 'MONITORING') as OrigineAlerte,
    raisedOn: texte(brut['raisedOn']),
    detail: texte(brut['detail']),
    amount: versMontant(brut['amount']),
    status: (texte(brut['status']) ?? 'OPEN') as StatutAlerte,
    assignedTo: texte(brut['assignedTo']),
    closedOn: texte(brut['closedOn']),
    closureReason: texte(brut['closureReason']),
    closedBy: texte(brut['closedBy']),
    reportId: texte(brut['reportId']),
    pieces: ((brut['items'] ?? []) as readonly unknown[]).map(versPiece),
  };
}

function versPiece(valeur: unknown): PieceAlerte {
  const brut = (valeur ?? {}) as Record<string, unknown>;
  return {
    entryId: texte(brut['entryId']) ?? '',
    bookingDate: texte(brut['bookingDate']),
    accountId: texte(brut['accountId']) ?? '',
    direction: texte(brut['direction']) ?? '',
    amount: versMontant(brut['amount']),
  };
}

function versDeclaration(valeur: unknown): Declaration {
  const brut = (valeur ?? {}) as Record<string, unknown>;
  return {
    id: texte(brut['id']) ?? '',
    partyId: texte(brut['partyId']) ?? '',
    reference: texte(brut['reference']) ?? '',
    draftedOn: texte(brut['draftedOn']),
    narrative: texte(brut['narrative']) ?? '',
    transmittedOn: texte(brut['transmittedOn']),
    transmissionReference: texte(brut['transmissionReference']),
    alertIds: ((brut['alertIds'] ?? []) as readonly unknown[])
      .map((id) => texte(id) ?? '').filter((id) => id !== ''),
  };
}

function versScenario(valeur: unknown): Scenario {
  const brut = (valeur ?? {}) as Record<string, unknown>;
  return {
    id: texte(brut['id']) ?? '',
    code: texte(brut['code']) ?? '',
    label: texte(brut['label']) ?? '',
    method: (texte(brut['method']) ?? 'CASH_THRESHOLD') as MethodeScenario,
    thresholdAmount: decimal(brut['thresholdAmount']),
    windowDays: nombre(brut['windowDays']),
    minimumCount: nombre(brut['minimumCount']),
    ratio: decimal(brut['ratio']),
    riskRating: texte(brut['riskRating']),
    validFrom: texte(brut['validFrom']),
    validTo: texte(brut['validTo']),
  };
}
