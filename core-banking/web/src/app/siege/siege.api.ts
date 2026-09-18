import { HttpClient, HttpErrorResponse, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Socle } from '../api/socle';
import { RefusMetier } from '../guichet/modele/guichet.modele';
import {
  AlgorithmeCle, DemandeEtablissement, DemandeRegle, DomaineNumerotation, Etablissement,
  NatureSegment, PorteeCompteur, RegleNumerotation, RemiseAZero, Segment, StatutRegle,
} from './modele/etablissement.modele';
import { FiltreBalance, LigneBalance, PageBalance, RunTfj, TotauxBalance } from './modele/siege.modele';
import { EnAttenteSiege, Siege } from './siege.port';

function texte(valeur: unknown): string | null {
  return typeof valeur === 'string' && valeur.length > 0 ? valeur : null;
}

function nombre(valeur: unknown): number | null {
  return typeof valeur === 'number' ? valeur : null;
}

interface Enveloppe<T> {
  readonly data: T;
  readonly error: { type: string; title: string; status: number; detail?: string } | null;
  readonly page?: { size: number; number: number | null; hasNext: boolean; hasPrevious: boolean } | null;
  readonly meta: { requestId: string; timestamp: string };
}

@Injectable()
export class SiegeApi implements Siege {
  private readonly http = inject(HttpClient);
  private readonly socle = inject(Socle);

  async lancerTfj(legalEntityId: string, journee: string, mode: 'REAL' | 'DRY_RUN'): Promise<RunTfj> {
    return this.poster<RunTfj>(this.socle.url('/v1/entities/{legalEntityId}/eod/runs', { legalEntityId }),
                               { businessDate: journee, mode });
  }

  async lireTfj(legalEntityId: string, runId: string): Promise<RunTfj> {
    return this.lire<RunTfj>(
      this.socle.url('/v1/entities/{legalEntityId}/eod/runs/{runId}', { legalEntityId, runId }));
  }

  async reprendreTfj(legalEntityId: string, runId: string): Promise<RunTfj> {
    return this.poster<RunTfj>(
      this.socle.url('/v1/entities/{legalEntityId}/eod/runs/{runId}/resume', { legalEntityId, runId }), {});
  }

  async annulerTfj(legalEntityId: string, runId: string): Promise<RunTfj> {
    return this.poster<RunTfj>(
      this.socle.url('/v1/entities/{legalEntityId}/eod/runs/{runId}/cancel', { legalEntityId, runId }), {});
  }

  async balance(legalEntityId: string, filtre: FiltreBalance, page: number, taille: number): Promise<PageBalance> {
    const url = this.socle.url('/v1/entities/{legalEntityId}/ledger/trial-balance', { legalEntityId });
    const parametres = this.filtrer(filtre).set('page', page).set('size', taille);
    try {
      const enveloppe = await firstValueFrom(
        this.http.get<Enveloppe<LigneBalance[]>>(url, { params: parametres, headers: this.entetes() }),
      );
      return {
        lignes: enveloppe.data ?? [],
        numero: enveloppe.page?.number ?? page,
        taille: enveloppe.page?.size ?? taille,
        precedent: enveloppe.page?.hasPrevious ?? page > 0,
        suivant: enveloppe.page?.hasNext ?? false,
      };
    } catch (erreur) {
      throw this.refus(erreur);
    }
  }

  async totauxBalance(legalEntityId: string, filtre: FiltreBalance): Promise<readonly TotauxBalance[]> {
    const url = this.socle.url('/v1/entities/{legalEntityId}/ledger/trial-balance/totals', { legalEntityId });
    try {
      const enveloppe = await firstValueFrom(
        this.http.get<Enveloppe<TotauxBalance[]>>(url, { params: this.filtrer(filtre), headers: this.entetes() }),
      );
      return enveloppe.data ?? [];
    } catch (erreur) {
      throw this.refus(erreur);
    }
  }

  // ------------------------------------------------------------ établissement

  async etablissement(legalEntityId: string): Promise<Etablissement> {
    const brut = await this.lire<Record<string, unknown>>(
      this.socle.url('/v1/entities/{legalEntityId}/establishment', { legalEntityId }));
    return {
      id: texte(brut['id']) ?? legalEntityId,
      code: texte(brut['code']) ?? '',
      name: texte(brut['name']) ?? '',
      countryCode: texte(brut['countryCode']) ?? '',
      // Le socle rend la devise de tenue comme une référence complète ; l'écran
      // n'en montre que le code.
      functionalCurrency: this.deviseDe(brut['functionalCurrency']),
      businessDate: texte(brut['businessDate']) ?? '',
      status: texte(brut['status']) ?? '',
      bankCode: texte(brut['bankCode']),
      legalName: texte(brut['legalName']),
      approvalNumber: texte(brut['approvalNumber']),
      taxId: texte(brut['taxId']),
      registryNumber: texte(brut['registryNumber']),
      address: texte(brut['address']),
      phone: texte(brut['phone']),
      email: texte(brut['email']),
    };
  }

  private deviseDe(valeur: unknown): string {
    if (typeof valeur === 'string') {
      return valeur;
    }
    const objet = valeur as Record<string, unknown> | null;
    return texte(objet?.['code']) ?? '';
  }

  async majEtablissement(legalEntityId: string, demande: DemandeEtablissement,
                         cleIdempotence: string): Promise<EnAttenteSiege> {
    return this.soumettre(
      this.socle.url('/v1/entities/{legalEntityId}/establishment', { legalEntityId }),
      demande, cleIdempotence);
  }

  // ------------------------------------------------------------ numérotation

  async regles(legalEntityId: string): Promise<readonly RegleNumerotation[]> {
    const brutes = await this.lire<Record<string, unknown>[]>(
      this.socle.url('/v1/entities/{legalEntityId}/numbering-rules', { legalEntityId }));
    return (brutes ?? []).map((brute) => this.regle(brute));
  }

  async proposition(legalEntityId: string, domaine: DomaineNumerotation): Promise<DemandeRegle> {
    const brute = await this.lire<Record<string, unknown>>(
      this.socle.url('/v1/entities/{legalEntityId}/numbering-rules/proposals/{domain}',
                     { legalEntityId, domain: domaine }));
    return {
      domain: domaine,
      label: texte(brute['label']) ?? '',
      segments: this.segments(brute['segments']),
      sequenceScope: (texte(brute['scope']) as PorteeCompteur | null) ?? 'ENTITY',
      sequenceReset: (texte(brute['reset']) as RemiseAZero | null) ?? 'NEVER',
      sequenceStart: nombre(brute['sequenceStart']) ?? 1,
    };
  }

  async redigerRegle(legalEntityId: string, demande: DemandeRegle,
                     cleIdempotence: string): Promise<{ readonly id: string }> {
    const url = this.socle.url('/v1/entities/{legalEntityId}/numbering-rules', { legalEntityId });
    const corps = {
      domain: demande.domain,
      label: demande.label,
      sequenceScope: demande.sequenceScope,
      sequenceReset: demande.sequenceReset,
      sequenceStart: demande.sequenceStart,
      segments: demande.segments.map((segment) => ({
        kind: segment.kind,
        literalValue: segment.literalValue,
        length: segment.length,
        padChar: segment.padChar,
        datePattern: segment.datePattern,
        checkAlgorithm: segment.algorithm,
      })),
    };
    const entetes = new HttpHeaders({
      'Idempotency-Key': cleIdempotence,
      'X-Request-Id': crypto.randomUUID(),
    });
    try {
      const enveloppe = await firstValueFrom(this.http.post<Enveloppe<{ id?: string }>>(
        url, corps, { headers: entetes }));
      return { id: texte(enveloppe.data?.id) ?? '' };
    } catch (erreur) {
      throw this.refus(erreur);
    }
  }

  async activerRegle(legalEntityId: string, ruleId: string,
                     cleIdempotence: string): Promise<EnAttenteSiege> {
    return this.soumettre(
      this.socle.url('/v1/entities/{legalEntityId}/numbering-rules/{ruleId}/activation',
                     { legalEntityId, ruleId }),
      {}, cleIdempotence);
  }

  private regle(brute: Record<string, unknown>): RegleNumerotation {
    return {
      id: texte(brute['id']) ?? '',
      domain: (texte(brute['domain']) as DomaineNumerotation | null) ?? 'PARTY',
      label: texte(brute['label']) ?? '',
      segments: this.segments(brute['segments']),
      scope: (texte(brute['scope']) as PorteeCompteur | null) ?? 'ENTITY',
      reset: (texte(brute['reset']) as RemiseAZero | null) ?? 'NEVER',
      sequenceStart: nombre(brute['sequenceStart']) ?? 1,
      status: (texte(brute['status']) as StatutRegle | null) ?? 'DRAFT',
    };
  }

  private segments(valeur: unknown): readonly Segment[] {
    return (Array.isArray(valeur) ? valeur : []).map((brut: Record<string, unknown>) => ({
      kind: (texte(brut['kind']) as NatureSegment | null) ?? 'LITERAL',
      literalValue: texte(brut['literalValue']),
      length: nombre(brut['length']),
      padChar: texte(brut['padChar']),
      datePattern: texte(brut['datePattern']),
      algorithm: texte(brut['algorithm']) as AlgorithmeCle | null,
    }));
  }

  /** Une action à deux : le socle répond 202 et rend l'opération en attente. */
  private async soumettre(url: string, corps: unknown,
                          cleIdempotence: string): Promise<EnAttenteSiege> {
    const entetes = new HttpHeaders({
      'Idempotency-Key': cleIdempotence,
      'X-Request-Id': crypto.randomUUID(),
    });
    try {
      const enveloppe = await firstValueFrom(this.http.post<Enveloppe<{ id?: string }>>(
        url, corps, { headers: entetes }));
      return { operationId: texte(enveloppe.data?.id) ?? '' };
    } catch (erreur) {
      throw this.refus(erreur);
    }
  }

  private filtrer(filtre: FiltreBalance): HttpParams {
    let parametres = new HttpParams();
    if (filtre.du) parametres = parametres.set('from', filtre.du);
    if (filtre.au) parametres = parametres.set('to', filtre.au);
    if (filtre.kind) parametres = parametres.set('kind', filtre.kind);
    return parametres;
  }

  private entetes(): HttpHeaders {
    return new HttpHeaders({ 'X-Request-Id': crypto.randomUUID() });
  }

  private async lire<T>(url: string): Promise<T> {
    try {
      return (await firstValueFrom(this.http.get<Enveloppe<T>>(url, { headers: this.entetes() }))).data;
    } catch (erreur) {
      throw this.refus(erreur);
    }
  }

  private async poster<T>(url: string, corps: unknown): Promise<T> {
    try {
      return (await firstValueFrom(this.http.post<Enveloppe<T>>(url, corps, { headers: this.entetes() }))).data;
    } catch (erreur) {
      throw this.refus(erreur);
    }
  }

  private refus(erreur: unknown): RefusMetier {
    if (erreur instanceof HttpErrorResponse) {
      const enveloppe = erreur.error as Enveloppe<null> | null;
      const detail = enveloppe?.error;
      if (detail) {
        return new RefusMetier(erreur.status, detail.type, detail.title, detail.detail, enveloppe?.meta?.requestId);
      }
      if (erreur.status === 0) {
        return new RefusMetier(0, 'RESEAU_INDISPONIBLE', 'Le socle est injoignable.',
          "Le traitement peut très bien tourner côté serveur : relire avant de relancer.");
      }
      return new RefusMetier(erreur.status, 'REPONSE_INATTENDUE', `Réponse ${erreur.status} du socle.`, erreur.message);
    }
    return erreur instanceof RefusMetier
      ? erreur
      : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste de travail.', String(erreur));
  }
}
