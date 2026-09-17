import { HttpClient, HttpErrorResponse, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AppConfig } from '../core/config/runtime-config';
import { RefusMetier } from '../guichet/modele/guichet.modele';
import { FiltreBalance, LigneBalance, PageBalance, RunTfj, TotauxBalance } from './modele/siege.modele';
import { Siege } from './siege.port';

interface Enveloppe<T> {
  readonly data: T;
  readonly error: { type: string; title: string; status: number; detail?: string } | null;
  readonly page?: { size: number; number: number | null; hasNext: boolean; hasPrevious: boolean } | null;
  readonly meta: { requestId: string; timestamp: string };
}

@Injectable()
export class SiegeApi implements Siege {
  private readonly http = inject(HttpClient);
  private readonly config = inject(AppConfig);

  async lancerTfj(legalEntityId: string, journee: string, mode: 'REAL' | 'DRY_RUN'): Promise<RunTfj> {
    return this.poster<RunTfj>(`${this.eod(legalEntityId)}/runs`, { businessDate: journee, mode });
  }

  async lireTfj(legalEntityId: string, runId: string): Promise<RunTfj> {
    return this.lire<RunTfj>(`${this.eod(legalEntityId)}/runs/${runId}`);
  }

  async reprendreTfj(legalEntityId: string, runId: string): Promise<RunTfj> {
    return this.poster<RunTfj>(`${this.eod(legalEntityId)}/runs/${runId}/resume`, {});
  }

  async annulerTfj(legalEntityId: string, runId: string): Promise<RunTfj> {
    return this.poster<RunTfj>(`${this.eod(legalEntityId)}/runs/${runId}/cancel`, {});
  }

  async balance(legalEntityId: string, filtre: FiltreBalance, page: number, taille: number): Promise<PageBalance> {
    const url = `${this.racine()}/entities/${legalEntityId}/ledger/trial-balance`;
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
    const url = `${this.racine()}/entities/${legalEntityId}/ledger/trial-balance/totals`;
    try {
      const enveloppe = await firstValueFrom(
        this.http.get<Enveloppe<TotauxBalance[]>>(url, { params: this.filtrer(filtre), headers: this.entetes() }),
      );
      return enveloppe.data ?? [];
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

  private eod(legalEntityId: string): string {
    return `${this.racine()}/entities/${legalEntityId}/eod`;
  }

  private racine(): string {
    return this.config.apiBaseUrl().replace(/\/$/, '');
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
