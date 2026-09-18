import { HttpClient, HttpErrorResponse, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Socle } from '../api/socle';
import { Montant, RefusMetier } from '../guichet/modele/guichet.modele';
import {
  DemandeOrdre, DemandeRemise, OrdrePaiement, Prelevement, Remise, SensPrelevement, StatutOrdre,
  StatutPrelevement, StatutRemise,
} from './modele/paiements.modele';
import { Decision, Page, Paiements } from './paiements.port';

interface Enveloppe<T> {
  readonly data: T;
  readonly error: { type: string; title: string; status: number; detail?: string } | null;
  readonly page?: { size: number; number: number | null; hasNext: boolean;
                    hasPrevious: boolean } | null;
  readonly meta: { requestId: string; timestamp: string };
}

function texte(valeur: unknown): string | null {
  return typeof valeur === 'string' && valeur.length > 0 ? valeur : null;
}

/** Le socle rend un montant comme un couple ; l'absence d'un montant est une absence. */
function montant(valeur: unknown): Montant | null {
  const objet = valeur as Record<string, unknown> | null;
  const amount = texte(objet?.['amount']);
  const currency = texte(objet?.['currency']);
  return amount !== null && currency !== null ? { amount, currency } : null;
}

function montantRequis(valeur: unknown): Montant {
  return montant(valeur) ?? { amount: '0', currency: 'XOF' };
}

@Injectable()
export class PaiementsApi implements Paiements {
  private readonly http = inject(HttpClient);
  private readonly socle = inject(Socle);

  // ------------------------------------------------------------ virements émis

  async ordres(legalEntityId: string, statut: StatutOrdre | null, page: number,
               taille: number): Promise<Page<OrdrePaiement>> {
    return this.paginer(
      this.socle.url('/v1/entities/{legalEntityId}/payment-orders', { legalEntityId }),
      this.parametres({ status: statut }, page, taille), page, taille,
      (brut) => this.ordreDe(brut));
  }

  async ordre(legalEntityId: string, orderId: string): Promise<OrdrePaiement> {
    return this.ordreDe(await this.lire(
      this.socle.url('/v1/entities/{legalEntityId}/payment-orders/{orderId}',
                     { legalEntityId, orderId })));
  }

  async ordonner(legalEntityId: string, demande: DemandeOrdre,
                 cleIdempotence: string): Promise<OrdrePaiement> {
    const url = this.socle.url(
      '/v1/entities/{legalEntityId}/accounts/{accountId}/payment-orders',
      { legalEntityId, accountId: demande.accountId });
    return this.ordreDe(await this.poster(url, {
      amount: demande.amount,
      currency: demande.currency,
      beneficiaryName: demande.beneficiaryName,
      beneficiaryBank: demande.beneficiaryBank,
      beneficiaryAccount: demande.beneficiaryAccount,
      reference: demande.reference,
      channel: demande.channel,
    }, cleIdempotence));
  }

  /**
   * Chaque acte nomme son chemin en clair.
   *
   * Une table de chemins serait plus courte, mais `Socle.url` n'accepte que les
   * chemins du contrat : les nommer ici, c'est les faire vérifier à la
   * compilation. Un chemin construit à la volée ne se vérifierait nulle part.
   */
  async deciderOrdre(legalEntityId: string, orderId: string,
                     decision: Decision): Promise<OrdrePaiement> {
    const parametres = { legalEntityId, orderId };
    let url: string;
    switch (decision.acte) {
      case 'ENVOYER':
        url = this.socle.url('/v1/entities/{legalEntityId}/payment-orders/{orderId}/send',
                             parametres);
        break;
      case 'REGLER':
        url = this.socle.url('/v1/entities/{legalEntityId}/payment-orders/{orderId}/settlement',
                             parametres);
        break;
      case 'RETOURNER':
        url = this.socle.url('/v1/entities/{legalEntityId}/payment-orders/{orderId}/return',
                             parametres);
        break;
      case 'ANNULER':
        url = this.socle.url('/v1/entities/{legalEntityId}/payment-orders/{orderId}/cancellation',
                             parametres);
        break;
      default:
        throw new RefusMetier(0, 'ACTE_INCONNU', `Acte inconnu sur un ordre : ${decision.acte}`);
    }
    return this.ordreDe(await this.poster(url, this.corps(decision)));
  }

  // ------------------------------------------------------------ remises de chèques

  async remises(legalEntityId: string, statut: StatutRemise | null, page: number,
                taille: number): Promise<Page<Remise>> {
    return this.paginer(
      this.socle.url('/v1/entities/{legalEntityId}/cheque-deposits', { legalEntityId }),
      this.parametres({ status: statut }, page, taille), page, taille,
      (brut) => this.remiseDe(brut));
  }

  async remise(legalEntityId: string, depositId: string): Promise<Remise> {
    return this.remiseDe(await this.lire(
      this.socle.url('/v1/entities/{legalEntityId}/cheque-deposits/{depositId}',
                     { legalEntityId, depositId })));
  }

  async remettre(legalEntityId: string, demande: DemandeRemise,
                 cleIdempotence: string): Promise<Remise> {
    const url = this.socle.url(
      '/v1/entities/{legalEntityId}/accounts/{accountId}/cheque-deposits',
      { legalEntityId, accountId: demande.accountId });
    return this.remiseDe(await this.poster(url, {
      amount: demande.amount,
      currency: demande.currency,
      draweeBank: demande.draweeBank,
      chequeNumber: demande.chequeNumber,
      drawerName: demande.drawerName,
      channel: demande.channel,
    }, cleIdempotence));
  }

  async deciderRemise(legalEntityId: string, depositId: string,
                      decision: Decision): Promise<Remise> {
    const parametres = { legalEntityId, depositId };
    const url = decision.acte === 'REGLER'
      ? this.socle.url('/v1/entities/{legalEntityId}/cheque-deposits/{depositId}/settlement',
                       parametres)
      : this.socle.url('/v1/entities/{legalEntityId}/cheque-deposits/{depositId}/return',
                       parametres);
    return this.remiseDe(await this.poster(url, this.corps(decision)));
  }

  // ------------------------------------------------------------ prélèvements

  async prelevements(legalEntityId: string, sens: SensPrelevement | null,
                     statut: StatutPrelevement | null, page: number,
                     taille: number): Promise<Page<Prelevement>> {
    return this.paginer(
      this.socle.url('/v1/entities/{legalEntityId}/direct-debits', { legalEntityId }),
      this.parametres({ direction: sens, status: statut }, page, taille), page, taille,
      (brut) => this.prelevementDe(brut));
  }

  async prelevement(legalEntityId: string, directDebitId: string): Promise<Prelevement> {
    return this.prelevementDe(await this.lire(
      this.socle.url('/v1/entities/{legalEntityId}/direct-debits/{directDebitId}',
                     { legalEntityId, directDebitId })));
  }

  async deciderPrelevement(legalEntityId: string, directDebitId: string,
                           decision: Decision): Promise<Prelevement> {
    const parametres = { legalEntityId, directDebitId };
    let url: string;
    switch (decision.acte) {
      case 'REGLER':
        url = this.socle.url(
          '/v1/entities/{legalEntityId}/direct-debits/{directDebitId}/settlement', parametres);
        break;
      case 'ANNULER':
        url = this.socle.url(
          '/v1/entities/{legalEntityId}/direct-debits/{directDebitId}/cancellation', parametres);
        break;
      case 'REMBOURSER':
        url = this.socle.url(
          '/v1/entities/{legalEntityId}/direct-debits/{directDebitId}/refund', parametres);
        break;
      case 'RETOURNER':
        url = this.socle.url(
          '/v1/entities/{legalEntityId}/direct-debits/{directDebitId}/return', parametres);
        break;
      default:
        throw new RefusMetier(0, 'ACTE_INCONNU',
                              `Acte inconnu sur un prélèvement : ${decision.acte}`);
    }
    return this.prelevementDe(await this.poster(url, this.corps(decision)));
  }

  // ------------------------------------------------------------ lecture des formes

  private ordreDe(brut: Record<string, unknown>): OrdrePaiement {
    return {
      id: texte(brut['id']) ?? '',
      accountId: texte(brut['accountId']) ?? '',
      amount: montantRequis(brut['amount']),
      fee: montant(brut['fee']),
      tax: montant(brut['tax']),
      beneficiaryName: texte(brut['beneficiaryName']) ?? '',
      beneficiaryBank: texte(brut['beneficiaryBank']) ?? '',
      beneficiaryAccount: texte(brut['beneficiaryAccount']) ?? '',
      reference: texte(brut['reference']),
      channel: texte(brut['channel']),
      status: (texte(brut['status']) as StatutOrdre | null) ?? 'ORDERED',
      orderedOn: texte(brut['orderedOn']) ?? '',
      sentOn: texte(brut['sentOn']),
      settledOn: texte(brut['settledOn']),
      returnedOn: texte(brut['returnedOn']),
      returnReason: texte(brut['returnReason']),
      cancelledOn: texte(brut['cancelledOn']),
      cancelReason: texte(brut['cancelReason']),
    };
  }

  private remiseDe(brut: Record<string, unknown>): Remise {
    return {
      id: texte(brut['id']) ?? '',
      accountId: texte(brut['accountId']) ?? '',
      amount: montantRequis(brut['amount']),
      draweeBank: texte(brut['draweeBank']) ?? '',
      chequeNumber: texte(brut['chequeNumber']) ?? '',
      drawerName: texte(brut['drawerName']),
      channel: texte(brut['channel']),
      status: (texte(brut['status']) as StatutRemise | null) ?? 'DEPOSITED',
      depositedOn: texte(brut['depositedOn']) ?? '',
      valueDate: texte(brut['valueDate']),
      settledOn: texte(brut['settledOn']),
      returnedOn: texte(brut['returnedOn']),
      returnReason: texte(brut['returnReason']),
    };
  }

  private prelevementDe(brut: Record<string, unknown>): Prelevement {
    return {
      id: texte(brut['id']) ?? '',
      direction: (texte(brut['direction']) as SensPrelevement | null) ?? 'RECEIVED',
      accountId: texte(brut['accountId']) ?? '',
      mandateId: texte(brut['mandateId']),
      amount: montantRequis(brut['amount']),
      fee: montant(brut['fee']),
      dueDate: texte(brut['dueDate']) ?? '',
      counterpartyName: texte(brut['counterpartyName']),
      counterpartyBank: texte(brut['counterpartyBank']),
      counterpartyAccount: texte(brut['counterpartyAccount']),
      mandateReference: texte(brut['mandateReference']),
      reference: texte(brut['reference']),
      channel: texte(brut['channel']),
      status: (texte(brut['status']) as StatutPrelevement | null) ?? 'PENDING',
      presentedOn: texte(brut['presentedOn']),
      executedOn: texte(brut['executedOn']),
      valueDate: texte(brut['valueDate']),
      rejectionReason: texte(brut['rejectionReason']),
      settledOn: texte(brut['settledOn']),
      closedOn: texte(brut['closedOn']),
      closeReason: texte(brut['closeReason']),
    };
  }

  // ------------------------------------------------------------ transport

  /** Ce que l'acte porte : le nostro du règlement, ou le motif qui reste au dossier. */
  private corps(decision: Decision): Record<string, unknown> {
    if (decision.acte === 'REGLER') {
      return { nostroAccountId: decision.nostroAccountId ?? null };
    }
    return { reason: decision.motif ?? null };
  }

  private parametres(filtres: Record<string, string | null>, page: number,
                     taille: number): HttpParams {
    let parametres = new HttpParams().set('page', String(page)).set('size', String(taille));
    for (const [cle, valeur] of Object.entries(filtres)) {
      if (valeur) {
        parametres = parametres.set(cle, valeur);
      }
    }
    return parametres;
  }

  private entetes(cleIdempotence?: string): HttpHeaders {
    const entetes: Record<string, string> = { 'X-Request-Id': crypto.randomUUID() };
    if (cleIdempotence) {
      entetes['Idempotency-Key'] = cleIdempotence;
    }
    return new HttpHeaders(entetes);
  }

  private async paginer<T>(url: string, parametres: HttpParams, page: number, taille: number,
                           lire: (brut: Record<string, unknown>) => T): Promise<Page<T>> {
    try {
      const enveloppe = await firstValueFrom(
        this.http.get<Enveloppe<Record<string, unknown>[]>>(
          url, { params: parametres, headers: this.entetes() }));
      return {
        lignes: (enveloppe.data ?? []).map(lire),
        numero: enveloppe.page?.number ?? page,
        taille: enveloppe.page?.size ?? taille,
        precedent: enveloppe.page?.hasPrevious ?? page > 0,
        suivant: enveloppe.page?.hasNext ?? false,
      };
    } catch (erreur) {
      throw this.refus(erreur);
    }
  }

  private async lire(url: string): Promise<Record<string, unknown>> {
    try {
      return (await firstValueFrom(this.http.get<Enveloppe<Record<string, unknown>>>(
        url, { headers: this.entetes() }))).data;
    } catch (erreur) {
      throw this.refus(erreur);
    }
  }

  private async poster(url: string, corps: unknown,
                       cleIdempotence?: string): Promise<Record<string, unknown>> {
    try {
      return (await firstValueFrom(this.http.post<Enveloppe<Record<string, unknown>>>(
        url, corps, { headers: this.entetes(cleIdempotence) }))).data;
    } catch (erreur) {
      throw this.refus(erreur);
    }
  }

  private refus(erreur: unknown): RefusMetier {
    if (erreur instanceof HttpErrorResponse) {
      const enveloppe = erreur.error as Enveloppe<null> | null;
      const detail = enveloppe?.error;
      if (detail) {
        return new RefusMetier(erreur.status, detail.type, detail.title, detail.detail,
                               enveloppe?.meta?.requestId);
      }
      if (erreur.status === 0) {
        return new RefusMetier(0, 'RESEAU_INDISPONIBLE', 'Le socle est injoignable.',
          "L'opération a très bien pu passer côté serveur : relire la file avant de refaire.");
      }
      return new RefusMetier(erreur.status, 'REPONSE_INATTENDUE',
                             `Réponse ${erreur.status} du socle.`, erreur.message);
    }
    return erreur instanceof RefusMetier
      ? erreur
      : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste de travail.', String(erreur));
  }
}
