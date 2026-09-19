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
import {
  BAREME_INTERETS, CompteGeneral, EnteteVersion, FamilleProduit, StatutVersion, Tranche,
  VersionComplete, VersionProduit,
} from './modele/produits.modele';
import {
  Agence, ConditionsDeBanque, Convention, DemandeAgence, DemandeFerie, DemandeHeureLimite,
  DemandeRegleDateValeur, HeureLimite, NatureAgence, RegleDateValeur, SensOperation, UniteDecalage,
} from './modele/reseau.modele';
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
  // ------------------------------------------------------------ paramétrage produit

  async familles(legalEntityId: string): Promise<readonly FamilleProduit[]> {
    const brutes = await this.lire<Record<string, unknown>[]>(
      this.socle.url('/v1/entities/{legalEntityId}/products/families', { legalEntityId }));
    return (brutes ?? []).map((brute) => this.famille(brute));
  }

  async versions(legalEntityId: string, code: string | null,
                 statut: string | null): Promise<readonly VersionProduit[]> {
    let parametres = new HttpParams();
    if (code) {
      parametres = parametres.set('code', code);
    }
    if (statut) {
      parametres = parametres.set('status', statut);
    }
    const brutes = await this.lire<Record<string, unknown>[]>(
      this.socle.url('/v1/entities/{legalEntityId}/products/versions', { legalEntityId }),
      parametres);
    return (brutes ?? []).map((brute) => this.versionDe(brute));
  }

  async version(legalEntityId: string, versionId: string): Promise<VersionComplete> {
    const brute = await this.lire<Record<string, unknown>>(this.socle.url(
      '/v1/entities/{legalEntityId}/products/versions/{versionId}', { legalEntityId, versionId }));
    const baremes: Record<string, readonly Tranche[]> = {};
    const bruts = (brute?.['tiers'] ?? {}) as Record<string, unknown>;
    for (const [discriminant, tranches] of Object.entries(bruts)) {
      baremes[discriminant] = ((tranches ?? []) as Record<string, unknown>[]).map((t) => ({
        from: texte(t['from']) ?? '0',
        to: texte(t['to']),
        annualRatePercent: texte(t['annualRatePercent']) ?? '0',
      }));
    }
    return {
      header: this.versionDe((brute?.['header'] ?? {}) as Record<string, unknown>),
      parameters: (brute?.['parameters'] ?? {}) as Record<string, string>,
      tiers: baremes,
    };
  }

  async redigerVersion(legalEntityId: string, entete: EnteteVersion,
                       parametres: Readonly<Record<string, string>>,
                       baremes: Readonly<Record<string, readonly Tranche[]>>,
                       cleIdempotence: string): Promise<{ readonly id: string }> {
    const commissions: Record<string, readonly Tranche[]> = {};
    for (const [discriminant, tranches] of Object.entries(baremes)) {
      if (discriminant !== BAREME_INTERETS) {
        commissions[discriminant] = tranches;
      }
    }
    const corps = {
      code: entete.code,
      productType: entete.productType,
      label: entete.label,
      currency: entete.currency,
      validFrom: entete.validFrom,
      validTo: entete.validTo,
      parameters: parametres,
      tiers: baremes[BAREME_INTERETS] ?? [],
      feeTiers: commissions,
    };
    const entetes = new HttpHeaders({
      'Idempotency-Key': cleIdempotence,
      'X-Request-Id': crypto.randomUUID(),
    });
    try {
      const enveloppe = await firstValueFrom(this.http.post<Enveloppe<{ id?: string }>>(
        this.socle.url('/v1/entities/{legalEntityId}/products', { legalEntityId }), corps,
        { headers: entetes }));
      return { id: texte(enveloppe.data?.id) ?? '' };
    } catch (erreur) {
      throw this.refus(erreur);
    }
  }

  async activerVersion(legalEntityId: string, versionId: string,
                       cleIdempotence: string): Promise<EnAttenteSiege> {
    return this.soumettre(this.socle.url(
      '/v1/entities/{legalEntityId}/products/{versionId}/activation',
      { legalEntityId, versionId }), {}, cleIdempotence);
  }

  async fermerVersion(legalEntityId: string, versionId: string, validTo: string,
                      cleIdempotence: string): Promise<EnAttenteSiege> {
    return this.soumettre(this.socle.url(
      '/v1/entities/{legalEntityId}/products/versions/{versionId}/closure',
      { legalEntityId, versionId }), { validTo }, cleIdempotence);
  }

  async retirerVersion(legalEntityId: string, versionId: string): Promise<VersionProduit> {
    const rendu = await this.poster<Record<string, unknown>>(this.socle.url(
      '/v1/entities/{legalEntityId}/products/versions/{versionId}/withdrawal',
      { legalEntityId, versionId }), {});
    // Le socle rend l'acte, pas la version : l'écran relit la liste derrière.
    return this.versionDe({ ...rendu, status: 'WITHDRAWN' });
  }

  async comptesGeneraux(legalEntityId: string, texteCherche: string): Promise<readonly CompteGeneral[]> {
    const parametres = texteCherche.trim()
      ? new HttpParams().set('q', texteCherche.trim())
      : new HttpParams();
    const brutes = await this.lire<Record<string, unknown>[]>(
      this.socle.url('/v1/entities/{legalEntityId}/accounts/general', { legalEntityId }),
      parametres);
    return (brutes ?? []).map((brut) => ({
      id: texte(brut['id']) ?? '',
      code: texte(brut['code']) ?? '',
      kind: texte(brut['kind']) ?? '',
      normalBalance: texte(brut['normalBalance']) ?? '',
      currency: this.deviseDe(brut['currency']),
      nature: texte(brut['nature']) ?? '',
      status: texte(brut['status']) ?? '',
      postable: brut['postable'] !== false,
    }));
  }

  private famille(brute: Record<string, unknown>): FamilleProduit {
    const noms = (valeur: unknown): readonly string[] =>
      Array.isArray(valeur) ? valeur.map((v) => String(v)) : [];
    const conditions = (valeur: unknown) =>
      (Array.isArray(valeur) ? valeur : []).map((c) => {
        const brut = c as Record<string, unknown>;
        return {
          when: texte(brut['when']) ?? '',
          fallback: texte(brut['fallback']),
          in: noms(brut['in']),
          presence: brut['presence'] === true,
          require: noms(brut['require']),
          requireTier: texte(brut['requireTier']),
          because: texte(brut['because']) ?? '',
        };
      });
    return {
      code: texte(brute['code']) ?? '',
      label: texte(brute['label']) ?? '',
      required: noms(brute['required']),
      optional: noms(brute['optional']),
      requireOneOf: (Array.isArray(brute['requireOneOf']) ? brute['requireOneOf'] : [])
        .map((a) => {
          const brut = a as Record<string, unknown>;
          return { of: noms(brut['of']), because: texte(brut['because']) ?? '' };
        }),
      conditions: conditions(brute['conditions']),
      groups: (Array.isArray(brute['groups']) ? brute['groups'] : []).map((g) => {
        const brut = g as Record<string, unknown>;
        return {
          listParameter: texte(brut['listParameter']) ?? '',
          required: noms(brut['required']),
          optional: noms(brut['optional']),
          conditions: conditions(brut['conditions']),
          accounts: noms(brut['accounts']),
        };
      }),
      accounts: noms(brute['accounts']),
    };
  }

  private versionDe(brute: Record<string, unknown>): VersionProduit {
    return {
      id: texte(brute['id']) ?? '',
      code: texte(brute['code']) ?? '',
      productType: texte(brute['productType']) ?? '',
      label: texte(brute['label']) ?? '',
      currency: this.deviseDe(brute['currency']),
      validFrom: texte(brute['validFrom']) ?? '',
      validTo: texte(brute['validTo']),
      status: (texte(brute['status']) as StatutVersion | null) ?? 'DRAFT',
      createdBy: texte(brute['createdBy']),
      createdAt: texte(brute['createdAt']),
      approvedBy: texte(brute['approvedBy']),
      approvedAt: texte(brute['approvedAt']),
    };
  }

  // ------------------------------------------------------------ réseau et calendrier

  async agences(legalEntityId: string): Promise<readonly Agence[]> {
    const brutes = await this.lire<Record<string, unknown>[]>(
      this.socle.url('/v1/entities/{legalEntityId}/branches', { legalEntityId }));
    return (brutes ?? []).map((brute) => ({
      id: texte(brute['id']) ?? '',
      code: texte(brute['code']) ?? '',
      name: texte(brute['name']) ?? '',
      kind: (texte(brute['kind']) as NatureAgence | null) ?? 'BRANCH',
      parentId: texte(brute['parentId']),
      status: texte(brute['status']) ?? '',
      openedOn: texte(brute['openedOn']),
      closedOn: texte(brute['closedOn']),
    }));
  }

  async creerAgence(legalEntityId: string, demande: DemandeAgence,
                    cleIdempotence: string): Promise<EnAttenteSiege> {
    return this.soumettre(
      this.socle.url('/v1/entities/{legalEntityId}/branches', { legalEntityId }), {
        code: demande.code,
        name: demande.name,
        kind: demande.kind,
        parentId: demande.parentId,
        openedOn: demande.openedOn,
        liaisonAccounts: demande.liaisonAccounts,
      }, cleIdempotence);
  }

  async conditions(legalEntityId: string): Promise<ConditionsDeBanque> {
    const brut = await this.lire<Record<string, unknown>>(
      this.socle.url('/v1/entities/{legalEntityId}/calendar', { legalEntityId }));
    const liste = (valeur: unknown): Record<string, unknown>[] =>
      Array.isArray(valeur) ? (valeur as Record<string, unknown>[]) : [];
    return {
      calendarCode: texte(brut?.['calendarCode']),
      calendarLabel: texte(brut?.['calendarLabel']),
      coversFrom: texte(brut?.['coversFrom']),
      coversTo: texte(brut?.['coversTo']),
      weekend: Array.isArray(brut?.['weekend'])
        ? (brut['weekend'] as unknown[]).map((j) => Number(j))
        : [],
      holidays: liste(brut?.['holidays']).map((f) => ({
        date: texte(f['date']) ?? '',
        label: texte(f['label']) ?? '',
      })),
      rules: liste(brut?.['rules']).map<RegleDateValeur>((r) => ({
        id: texte(r['id']) ?? '',
        operationType: texte(r['operationType']) ?? '',
        channel: texte(r['channel']),
        direction: (texte(r['direction']) as SensOperation | null) ?? 'DEBIT',
        offset: Number(r['offset'] ?? 0),
        unit: (texte(r['unit']) as UniteDecalage | null) ?? 'CALENDAR_DAYS',
        convention: (texte(r['convention']) as Convention | null) ?? 'UNADJUSTED',
        validFrom: texte(r['validFrom']) ?? '',
        validTo: texte(r['validTo']),
      })),
      cutoffs: liste(brut?.['cutoffs']).map<HeureLimite>((h) => ({
        id: texte(h['id']) ?? '',
        channel: texte(h['channel']),
        // Le socle rend une heure locale : HH:mm:ss quand elle porte des secondes.
        cutoffTime: (texte(h['cutoffTime']) ?? '').slice(0, 5),
        closesChannel: h['closesChannel'] === true,
        validFrom: texte(h['validFrom']) ?? '',
        validTo: texte(h['validTo']),
      })),
    };
  }

  async ajouterFerie(legalEntityId: string, demande: DemandeFerie,
                     cleIdempotence: string): Promise<EnAttenteSiege> {
    return this.soumettre(
      this.socle.url('/v1/entities/{legalEntityId}/calendar/holidays', { legalEntityId }),
      { date: demande.date, label: demande.label }, cleIdempotence);
  }

  async ajouterRegle(legalEntityId: string, demande: DemandeRegleDateValeur,
                     cleIdempotence: string): Promise<EnAttenteSiege> {
    return this.soumettre(
      this.socle.url('/v1/entities/{legalEntityId}/calendar/value-date-rules', { legalEntityId }),
      {
        operationType: demande.operationType,
        channel: demande.channel,
        direction: demande.direction,
        offset: demande.offset,
        unit: demande.unit,
        convention: demande.convention,
        validFrom: demande.validFrom,
        validTo: demande.validTo,
      }, cleIdempotence);
  }

  async ajouterHeureLimite(legalEntityId: string, demande: DemandeHeureLimite,
                           cleIdempotence: string): Promise<EnAttenteSiege> {
    return this.soumettre(
      this.socle.url('/v1/entities/{legalEntityId}/calendar/cutoffs', { legalEntityId }), {
        channel: demande.channel,
        cutoffTime: demande.cutoffTime,
        closesChannel: demande.closesChannel,
        validFrom: demande.validFrom,
        validTo: demande.validTo,
      }, cleIdempotence);
  }

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

  private async lire<T>(url: string, parametres?: HttpParams): Promise<T> {
    try {
      return (await firstValueFrom(this.http.get<Enveloppe<T>>(
        url, { params: parametres, headers: this.entetes() }))).data;
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
