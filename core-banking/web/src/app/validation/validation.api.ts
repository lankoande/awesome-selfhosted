import { HttpClient, HttpErrorResponse, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AppConfig } from '../core/config/runtime-config';
import { RefusMetier } from '../guichet/modele/guichet.modele';
import { Identite, OperationEnAttente, PageOperations } from './modele/validation.modele';
import { Validation } from './validation.port';

interface Enveloppe<T> {
  readonly data: T;
  readonly error: { type: string; title: string; status: number; detail?: string } | null;
  readonly page: { size: number; number: number | null; hasNext: boolean; hasPrevious: boolean } | null;
  readonly meta: { requestId: string; timestamp: string };
}

@Injectable()
export class ValidationApi implements Validation {
  private readonly http = inject(HttpClient);
  private readonly config = inject(AppConfig);

  async file(legalEntityId: string, page: number, taille: number): Promise<PageOperations> {
    const url = `${this.racine()}/entities/${legalEntityId}/pending-operations`;
    const parametres = new HttpParams().set('page', page).set('size', taille);
    try {
      const enveloppe = await firstValueFrom(
        this.http.get<Enveloppe<OperationEnAttente[]>>(url, { params: parametres, headers: this.entetes() }),
      );
      return {
        elements: enveloppe.data ?? [],
        numero: enveloppe.page?.number ?? page,
        taille: enveloppe.page?.size ?? taille,
        precedent: enveloppe.page?.hasPrevious ?? page > 0,
        suivant: enveloppe.page?.hasNext ?? false,
      };
    } catch (erreur) {
      throw this.refus(erreur);
    }
  }

  async lire(legalEntityId: string, id: string): Promise<OperationEnAttente> {
    return this.appel<OperationEnAttente>('get', `${this.racine()}/entities/${legalEntityId}/pending-operations/${id}`);
  }

  async approuver(legalEntityId: string, id: string): Promise<OperationEnAttente> {
    return this.appel<OperationEnAttente>(
      'post', `${this.racine()}/entities/${legalEntityId}/pending-operations/${id}/approve`, {});
  }

  async rejeter(legalEntityId: string, id: string, motif: string): Promise<OperationEnAttente> {
    return this.appel<OperationEnAttente>(
      'post', `${this.racine()}/entities/${legalEntityId}/pending-operations/${id}/reject`, { reason: motif });
  }

  /**
   * Le contrat n'expose pas le porteur du jeton. Plutôt que de le déduire du
   * JWT — ce qui ferait du front une source d'identité — on rend `null` et on
   * laisse l'API refuser l'auto-approbation.
   */
  async identite(): Promise<Identite | null> {
    return null;
  }

  private racine(): string {
    return this.config.apiBaseUrl().replace(/\/$/, '');
  }

  private entetes(): HttpHeaders {
    return new HttpHeaders({ 'X-Request-Id': crypto.randomUUID() });
  }

  private async appel<T>(methode: 'get' | 'post', url: string, corps?: unknown): Promise<T> {
    try {
      const enveloppe =
        methode === 'get'
          ? await firstValueFrom(this.http.get<Enveloppe<T>>(url, { headers: this.entetes() }))
          : await firstValueFrom(this.http.post<Enveloppe<T>>(url, corps, { headers: this.entetes() }));
      return enveloppe.data;
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
          'La file n’a pas pu être lue. Aucune décision n’a été prise.');
      }
      return new RefusMetier(erreur.status, 'REPONSE_INATTENDUE', `Réponse ${erreur.status} du socle.`, erreur.message);
    }
    return new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste de travail.', String(erreur));
  }
}
