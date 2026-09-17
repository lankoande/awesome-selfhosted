import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AppConfig } from '../core/config/runtime-config';
import { RefusMetier } from '../guichet/modele/guichet.modele';
import { ArreteCaisse, EtatCaisse } from './modele/caisse.modele';
import { Caisse } from './caisse.port';

interface Enveloppe<T> {
  readonly data: T;
  readonly error: { type: string; title: string; status: number; detail?: string } | null;
  readonly meta: { requestId: string; timestamp: string };
}

@Injectable()
export class CaisseApi implements Caisse {
  private readonly http = inject(HttpClient);
  private readonly config = inject(AppConfig);

  /**
   * Le contrat expose la création d'une caisse et son arrêté, pas sa lecture.
   * On ne devine pas un solde théorique côté poste — ce serait recalculer le
   * registre dans le navigateur. On rend l'état inconnu et on le dit.
   */
  async etat(_legalEntityId: string): Promise<EtatCaisse> {
    throw new RefusMetier(501, 'ETAT_CAISSE_NON_EXPOSE',
      "Le contrat n’expose pas l’état de la caisse.",
      "Il manque la caisse du porteur (`GET /v1/me/till`) et son solde théorique. Sans eux, "
      + "l’arrêté ne peut pas être présenté : un solde théorique recalculé côté poste serait "
      + "le registre réécrit dans le navigateur.");
  }

  async arreter(legalEntityId: string, tillId: string, compte: number, devise: string): Promise<ArreteCaisse> {
    const url = `${this.racine()}/entities/${legalEntityId}/tills/${tillId}/closure`;
    const entetes = new HttpHeaders({ 'X-Request-Id': crypto.randomUUID() });
    try {
      const enveloppe = await firstValueFrom(
        this.http.post<Enveloppe<ArreteCaisse>>(url, { counted: String(compte), currency: devise },
                                                { headers: entetes }),
      );
      return enveloppe.data;
    } catch (erreur) {
      throw this.refus(erreur);
    }
  }

  private racine(): string {
    return this.config.apiBaseUrl().replace(/\/$/, '');
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
          "L’arrêté n’a pas été enregistré. Le comptage est conservé à l’écran.");
      }
      return new RefusMetier(erreur.status, 'REPONSE_INATTENDUE', `Réponse ${erreur.status} du socle.`, erreur.message);
    }
    return erreur instanceof RefusMetier
      ? erreur
      : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste de travail.', String(erreur));
  }
}
