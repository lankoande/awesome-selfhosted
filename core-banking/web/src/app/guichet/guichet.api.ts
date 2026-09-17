import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AppConfig } from '../core/config/runtime-config';
import { ContexteCompte, DemandeEspeces, IssueVersement, Recu, RefusMetier, SoldeCompte } from './modele/guichet.modele';
import { Guichet } from './guichet.port';

/** Enveloppe de réponse du socle : `{ data, page, error, meta }`. */
interface Enveloppe<T> {
  readonly data: T;
  readonly error: { type: string; title: string; status: number; detail?: string } | null;
  readonly meta: { requestId: string; timestamp: string };
}

/**
 * Implémentation HTTP, alignée sur le contrat OpenAPI publié par le socle.
 *
 * Deux en-têtes portent tout le sérieux de l'affaire :
 *   `Idempotency-Key`, qui rend le rejeu sûr — le socle répond 200 avec le
 *   premier reçu plutôt que de comptabiliser deux fois ;
 *   `X-Request-Id`, qui relie la trace du poste à celle du serveur quand il
 *   faut expliquer une opération trois semaines plus tard.
 */
@Injectable()
export class GuichetApi implements Guichet {
  private readonly http = inject(HttpClient);
  private readonly config = inject(AppConfig);

  async soldes(legalEntityId: string, accountId: string): Promise<SoldeCompte> {
    return this.lire<SoldeCompte>(`${this.racine()}/entities/${legalEntityId}/accounts/${accountId}/balance`);
  }

  async contexte(legalEntityId: string, accountId: string): Promise<ContexteCompte> {
    // Le contrat ne relie pas encore un compte à son titulaire, et n'expose pas
    // la lecture des blocages. On ne devine pas : on dit ce qui manque, et le
    // bandeau client affiche l'absence plutôt qu'une valeur inventée.
    const solde = await this.soldes(legalEntityId, accountId);
    return {
      intitule: solde.code,
      partyId: null,
      reference: solde.code,
      nature: '—',
      produit: '—',
      ouvertLe: null,
      kyc: null,
      blocages: [],
      lacunes: [
        'Le titulaire du compte : le contrat n’expose pas le lien compte → tiers.',
        'Les blocages actifs : leur lecture n’est pas exposée, seule leur pose l’est.',
      ],
    };
  }

  async verser(demande: DemandeEspeces): Promise<IssueVersement> {
    return this.operation(demande, 'deposits');
  }

  async retirer(demande: DemandeEspeces): Promise<IssueVersement> {
    return this.operation(demande, 'withdrawals');
  }

  private async operation(demande: DemandeEspeces, route: 'deposits' | 'withdrawals'): Promise<IssueVersement> {
    const url = `${this.racine()}/entities/${demande.legalEntityId}/accounts/${demande.accountId}/${route}`;
    const corps = {
      amount: demande.amount,
      currency: demande.currency,
      channel: demande.channel,
      narrative: demande.narrative,
    };
    const entetes = new HttpHeaders({
      'Idempotency-Key': demande.cleIdempotence,
      'X-Request-Id': crypto.randomUUID(),
    });

    try {
      const reponse = await firstValueFrom(
        this.http.post<Enveloppe<Recu | { id: string }>>(url, corps, { headers: entetes, observe: 'response' }),
      );

      // 202 : l'opération est soumise au second regard, rien n'est comptabilisé.
      if (reponse.status === 202) {
        const attente = reponse.body?.data as { id: string };
        return { genre: 'en-attente', operationId: attente?.id ?? '', attenduDe: 'un second agent habilité' };
      }

      const recu = reponse.body?.data as Recu;
      // Un 200 sur une création, c'est le rejeu d'une clé déjà traitée. Le socle
      // le dit aussi dans `replayed` ; on tient les deux, parce qu'un écran qui
      // annonce « comptabilisé » deux fois fait recompter la caisse.
      return { genre: 'comptabilise', recu: reponse.status === 200 ? { ...recu, replayed: true } : recu };
    } catch (erreur) {
      throw this.refus(erreur);
    }
  }

  private racine(): string {
    return this.config.apiBaseUrl().replace(/\/$/, '');
  }

  private async lire<T>(url: string): Promise<T> {
    try {
      const enveloppe = await firstValueFrom(this.http.get<Enveloppe<T>>(url));
      return enveloppe.data;
    } catch (erreur) {
      throw this.refus(erreur);
    }
  }

  /** Traduit une erreur HTTP en refus lisible, sans jamais perdre le code du socle. */
  private refus(erreur: unknown): RefusMetier {
    if (erreur instanceof HttpErrorResponse) {
      const enveloppe = erreur.error as Enveloppe<null> | null;
      const detail = enveloppe?.error;
      if (detail) {
        return new RefusMetier(
          erreur.status,
          detail.type,
          detail.title,
          detail.detail,
          enveloppe?.meta?.requestId,
        );
      }
      if (erreur.status === 0) {
        return new RefusMetier(0, 'RESEAU_INDISPONIBLE', 'Le socle est injoignable.',
          "La saisie est intacte et la clé d’idempotence conservée : « Réessayer » rejoue la même opération sans risque de double comptabilisation.");
      }
      return new RefusMetier(erreur.status, 'REPONSE_INATTENDUE', `Réponse ${erreur.status} du socle.`, erreur.message);
    }
    return new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste de travail.', String(erreur));
  }
}
