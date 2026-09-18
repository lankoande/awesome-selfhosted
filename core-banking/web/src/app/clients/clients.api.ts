import { HttpClient, HttpErrorResponse, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Socle } from '../api/socle';
import { RefusMetier } from '../guichet/modele/guichet.modele';
import { Clients } from './clients.port';
import {
  BeneficiaireEffectif, DemandeOuverture, DemandeTiers, Dossier, IssueOuverture,
  PageTiers, Piece, ProduitOuvrable, Tiers,
} from './modele/clients.modele';

interface Enveloppe<T> {
  readonly data: T;
  readonly error: { type: string; title: string; status: number; detail?: string } | null;
  readonly page?: { size: number; number: number | null; hasNext: boolean; hasPrevious: boolean } | null;
  readonly meta: { requestId: string; timestamp: string };
}

/**
 * Le référentiel client sur les routes réelles du socle.
 *
 * Les champs du contrat sont tous optionnels — c'est ainsi qu'`openapi-typescript`
 * rend un schéma qui ne déclare pas ses obligatoires. Le front les ramène donc à
 * des valeurs sûres au lieu de propager des `undefined` dans les écrans : un
 * gabarit qui affiche « undefined » à un guichetier est un défaut visible par le
 * client.
 */
@Injectable()
export class ClientsApi implements Clients {
  private readonly http = inject(HttpClient);
  private readonly socle = inject(Socle);

  async chercher(legalEntityId: string, q: string, page: number, taille: number): Promise<PageTiers> {
    const url = this.socle.url('/v1/entities/{legalEntityId}/parties', { legalEntityId });
    let parametres = new HttpParams().set('page', page).set('size', taille);
    if (q.trim()) parametres = parametres.set('q', q.trim());
    try {
      const enveloppe = await firstValueFrom(
        this.http.get<Enveloppe<readonly unknown[]>>(url, { params: parametres }));
      return {
        tiers: (enveloppe.data ?? []).map((t) => versTiers(t)),
        page,
        precedent: enveloppe.page?.hasPrevious ?? page > 0,
        suivant: enveloppe.page?.hasNext ?? false,
      };
    } catch (erreur) {
      throw this.refus(erreur);
    }
  }

  async lire(legalEntityId: string, partyId: string): Promise<Tiers> {
    return versTiers(await this.obtenir(
      this.socle.url('/v1/entities/{legalEntityId}/parties/{partyId}', { legalEntityId, partyId })));
  }

  async dossier(legalEntityId: string, partyId: string): Promise<Dossier> {
    const brut = await this.obtenir<Record<string, unknown>>(this.socle.url(
      '/v1/entities/{legalEntityId}/parties/{partyId}/file', { legalEntityId, partyId }));
    return {
      partyId: texte(brut['partyId']) ?? partyId,
      reference: texte(brut['reference']),
      kind: (texte(brut['kind']) ?? 'NATURAL_PERSON') as Dossier['kind'],
      level: (texte(brut['level']) ?? 'STANDARD') as Dossier['level'],
      complete: brut['complete'] === true,
      missing: (brut['missing'] ?? []) as Dossier['missing'],
      expired: (brut['expired'] ?? []) as Dossier['expired'],
      beneficialOwnersMissing: brut['beneficialOwnersMissing'] === true,
      unverifiedOwners: (brut['unverifiedOwners'] ?? []) as readonly string[],
      policyDeclared: brut['policyDeclared'] === true,
      summary: texte(brut['summary']) ?? '',
    };
  }

  async pieces(legalEntityId: string, partyId: string): Promise<readonly Piece[]> {
    const brutes = await this.obtenir<readonly Record<string, unknown>[]>(this.socle.url(
      '/v1/entities/{legalEntityId}/parties/{partyId}/documents', { legalEntityId, partyId }));
    return (brutes ?? []).map((d) => ({
      id: texte(d['id']) ?? '',
      kind: (texte(d['kind']) ?? 'OTHER') as Piece['kind'],
      reference: texte(d['reference']),
      issuer: texte(d['issuer']),
      issuedOn: texte(d['issuedOn']),
      expiresOn: texte(d['expiresOn']),
      collectedOn: texte(d['collectedOn']),
      supersededBy: texte(d['supersededBy']),
    }));
  }

  async beneficiaires(legalEntityId: string, partyId: string): Promise<readonly BeneficiaireEffectif[]> {
    const brutes = await this.obtenir<readonly Record<string, unknown>[]>(this.socle.url(
      '/v1/entities/{legalEntityId}/parties/{partyId}/beneficial-owners', { legalEntityId, partyId }));
    return (brutes ?? []).map((b) => ({
      id: texte(b['id']) ?? '',
      ownerName: texte(b['ownerName']) ?? '',
      ownershipPercent: b['ownershipPercent'] === undefined || b['ownershipPercent'] === null
        ? '' : String(b['ownershipPercent']),
      ownerReference: texte(b['ownerReference']),
      declaredOn: texte(b['declaredOn']),
      validTo: texte(b['validTo']),
    }));
  }

  async creer(demande: DemandeTiers, cleIdempotence: string): Promise<Tiers> {
    const url = this.socle.url('/v1/entities/{legalEntityId}/parties',
                               { legalEntityId: demande.legalEntityId });
    const corps = {
      displayName: demande.displayName,
      kind: demande.kind,
      countryCode: demande.countryCode,
      birthOrRegistrationDate: demande.birthOrRegistrationDate,
      reference: demande.reference,
      segment: demande.segment,
    };
    const cree = await this.poster<{ id?: string }>(url, corps, cleIdempotence);
    // Le socle rend l'identifiant, pas le tiers : on le relit pour disposer de
    // son état réel — connaissance client comprise — plutôt que de le supposer.
    return this.lire(demande.legalEntityId, cree.id ?? '');
  }

  async ouvrirCompte(demande: DemandeOuverture, cleIdempotence: string): Promise<IssueOuverture> {
    const url = this.socle.url('/v1/entities/{legalEntityId}/accounts',
                               { legalEntityId: demande.legalEntityId });
    const corps = {
      holderPartyId: demande.holderPartyId,
      productCode: demande.productCode,
      currency: demande.currency,
      ...(demande.code ? { code: demande.code } : {}),
    };
    const entetes = new HttpHeaders({
      'Idempotency-Key': cleIdempotence,
      'X-Request-Id': crypto.randomUUID(),
    });
    try {
      const reponse = await firstValueFrom(this.http.post<Enveloppe<{ id?: string }>>(
        url, corps, { headers: entetes, observe: 'response' }));
      // Le socle enregistre une demande, il n'ouvre pas de compte : quel que
      // soit le 2xx, ce qu'il rend est l'identifiant de l'opération en attente.
      return { operationId: reponse.body?.data?.id ?? '' };
    } catch (erreur) {
      throw this.refus(erreur);
    }
  }

  async produits(legalEntityId: string): Promise<readonly ProduitOuvrable[]> {
    // Le socle ne rend que les versions **actives** dont la validité couvre le
    // jour : un brouillon ou un produit retiré proposé ici ferait saisir une
    // ouverture que le socle refuserait ensuite, après que le client a signé.
    const brutes = await this.obtenir<readonly Record<string, unknown>[]>(this.socle.url(
      '/v1/entities/{legalEntityId}/products', { legalEntityId }));
    return (brutes ?? []).map((p) => ({
      code: texte(p['code']) ?? '',
      libelle: texte(p['label']) ?? texte(p['code']) ?? '',
      devise: texte(p['currency']),
    })).filter((p) => p.code !== '');
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

  private async poster<T>(url: string, corps: unknown, cleIdempotence: string): Promise<T> {
    const entetes = new HttpHeaders({
      'Idempotency-Key': cleIdempotence,
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
      const enveloppe = erreur.error as Enveloppe<null> | null;
      const detail = enveloppe?.error;
      if (detail) {
        return new RefusMetier(erreur.status, detail.type, detail.title, detail.detail,
                               enveloppe?.meta?.requestId);
      }
      if (erreur.status === 0) {
        return new RefusMetier(0, 'RESEAU_INDISPONIBLE', 'Le socle est injoignable.',
                               "Rien n'a été enregistré.");
      }
      return new RefusMetier(erreur.status, 'REPONSE_INATTENDUE',
                             `Réponse ${erreur.status} du socle.`, erreur.message);
    }
    return erreur instanceof RefusMetier
      ? erreur
      : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste de travail.', String(erreur));
  }
}

const texte = (v: unknown): string | null => (typeof v === 'string' && v !== '' ? v : null);

/**
 * `Party` du contrat, champ pour champ.
 *
 * Les valeurs de repli sont **optimistes à dessein** — `ACTIVE`, `LOW` — et
 * c'est la règle du poste : il ne bloque que ce qui est certain. Un champ
 * absent n'est pas un client bloqué ; c'est un champ absent. Le socle, lui,
 * refuse sur son propre état. Seule la connaissance client se replie sur
 * `PENDING` : là, l'absence de preuve n'est pas une preuve de vérification.
 */
function versTiers(brut: unknown): Tiers {
  const t = (brut ?? {}) as Record<string, unknown>;
  return {
    id: texte(t['id']) ?? '',
    reference: texte(t['reference']) ?? '',
    displayName: texte(t['displayName']) ?? '',
    kind: (texte(t['kind']) ?? 'NATURAL_PERSON') as Tiers['kind'],
    countryCode: texte(t['countryCode']),
    birthOrRegistrationDate: texte(t['birthOrRegistrationDate']),
    segment: texte(t['segment']),
    status: (texte(t['status']) ?? 'ACTIVE') as Tiers['status'],
    statusReason: texte(t['statusReason']),
    kycStatus: (texte(t['kycStatus']) ?? 'PENDING') as Tiers['kycStatus'],
    kycLevel: (texte(t['kycLevel']) ?? 'STANDARD') as Tiers['kycLevel'],
    kycVerifiedOn: texte(t['kycVerifiedOn']),
    kycReviewDue: texte(t['kycReviewDue']),
    riskRating: (texte(t['riskRating']) ?? 'LOW') as Tiers['riskRating'],
  };
}
