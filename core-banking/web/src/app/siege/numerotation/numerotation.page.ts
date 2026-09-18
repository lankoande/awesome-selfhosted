import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Droits } from '../../auth/habilitations';
import { AppConfig } from '../../core/config/runtime-config';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import {
  CbActivity, CbButton, CbField, CbInput, CbNotice, CbSection, CbStateBadge, CbTable, CbToolbar,
  EtatOperation,
} from '../../ui';
import {
  AlgorithmeCle, apercu, DemandeRegle, DomaineNumerotation, FORMATS_DATE, LIBELLE_DOMAINE,
  LIBELLE_PORTEE_COMPTEUR, LIBELLE_REMISE, LIBELLE_SEGMENT, LIBELLE_STATUT_REGLE, NatureSegment,
  obstaclesAuGabarit, PorteeCompteur, RegleNumerotation, RemiseAZero, Segment, segmentNeuf,
} from '../modele/etablissement.modele';
import { SIEGE } from '../siege.port';

type Phase = 'chargement' | 'prete' | 'envoi' | 'refuse';

const DOMAINES: readonly DomaineNumerotation[] = [
  'ACCOUNT', 'PARTY', 'LOAN_APPLICATION', 'LOAN_CONTRACT', 'TERM_DEPOSIT', 'STANDING_ORDER',
];

const NATURES: readonly NatureSegment[] = [
  'LITERAL', 'BANK_CODE', 'BRANCH_CODE', 'DATE', 'SEQUENCE', 'CHECK_DIGITS',
];

/**
 * Le plan de numérotation.
 *
 * Comment la banque compose ses numéros de clients, de comptes, de dossiers.
 * En zone UEMOA, le numéro de compte est un RIB : code banque, code guichet,
 * numéro, clé de contrôle modulo 97.
 *
 * Deux partis pris d'écran :
 *
 *   **l'aperçu est permanent.** Un gabarit se lit mal ; le numéro qu'il produit
 *   se lit tout de suite. Un opérateur qui devrait calculer une clé modulo 97
 *   de tête ne relirait pas son gabarit — il l'activerait, et découvrirait au
 *   premier compte. L'aperçu est calculé ici, sur le gabarit en cours
 *   d'écriture, que le socle ne connaît pas encore ;
 *
 *   **le socle propose, la banque choisit.** Rien n'est semé à la création d'un
 *   établissement : tant qu'aucune règle n'est active, le socle refuse de
 *   composer et le dit. La proposition est un point de départ à relire, pas un
 *   défaut qui s'appliquerait tout seul.
 */
@Component({
  selector: 'cb-numerotation',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbActivity, CbButton, CbField, CbInput, CbNotice, CbSection, CbStateBadge, CbTable,
            CbToolbar],
  templateUrl: './numerotation.page.html',
  styleUrl: './numerotation.page.css',
})
export class NumerotationPage {
  private readonly siege = inject(SIEGE);
  private readonly config = inject(AppConfig);
  private readonly droits = inject(Droits);

  protected readonly DOMAINES = DOMAINES;
  protected readonly NATURES = NATURES;
  protected readonly FORMATS_DATE = FORMATS_DATE;
  protected readonly LIBELLE_DOMAINE = LIBELLE_DOMAINE;
  protected readonly LIBELLE_SEGMENT = LIBELLE_SEGMENT;
  protected readonly LIBELLE_PORTEE_COMPTEUR = LIBELLE_PORTEE_COMPTEUR;
  protected readonly LIBELLE_REMISE = LIBELLE_REMISE;
  protected readonly LIBELLE_STATUT_REGLE = LIBELLE_STATUT_REGLE;

  protected readonly phase = signal<Phase>('chargement');
  protected readonly regles = signal<readonly RegleNumerotation[]>([]);
  protected readonly refus = signal<RefusMetier | null>(null);
  protected readonly acquitte = signal<string | null>(null);
  protected readonly domaine = signal<DomaineNumerotation>('ACCOUNT');
  protected readonly brouillon = signal<DemandeRegle | null>(null);
  protected readonly codeBanque = signal<string | null>(null);
  protected readonly jour = signal<string>(new Date().toISOString().slice(0, 10));

  protected readonly travaille = computed(
    () => this.phase() === 'chargement' || this.phase() === 'envoi');

  protected readonly droitDeRediger = computed(() => this.droits.peut('NUMBERING_DRAFT'));
  protected readonly droitDActiver = computed(() => this.droits.peut('NUMBERING_ACTIVATE'));

  /** Les règles du domaine choisi, l'active d'abord. */
  protected readonly duDomaine = computed(() => {
    const rang: Readonly<Record<string, number>> = { ACTIVE: 0, DRAFT: 1, WITHDRAWN: 2 };
    return this.regles()
      .filter((regle) => regle.domain === this.domaine())
      .slice()
      .sort((a, b) => (rang[a.status] ?? 3) - (rang[b.status] ?? 3));
  });

  protected readonly active = computed(
    () => this.duDomaine().find((regle) => regle.status === 'ACTIVE') ?? null);

  /** Les domaines que rien ne numérote : le socle y refusera de composer. */
  protected readonly domainesSansRegle = computed(() => DOMAINES.filter(
    (domaine) => !this.regles().some((r) => r.domain === domaine && r.status === 'ACTIVE')));

  protected readonly obstacles = computed(() => {
    const brouillon = this.brouillon();
    return brouillon === null ? [] : obstaclesAuGabarit(brouillon);
  });

  protected readonly apercuBrouillon = computed(() => {
    const brouillon = this.brouillon();
    return brouillon === null ? '' : this.composer(brouillon);
  });

  constructor() {
    void this.charger();
  }

  /** L'aperçu d'une règle : le compteur est pris à un, la date à aujourd'hui. */
  protected composer(regle: RegleNumerotation | DemandeRegle): string {
    const demande: DemandeRegle = 'scope' in regle
      ? { domain: regle.domain, label: regle.label, segments: regle.segments,
          sequenceScope: regle.scope, sequenceReset: regle.reset,
          sequenceStart: regle.sequenceStart }
      : regle;
    return apercu(demande, {
      bankCode: this.codeBanque(),
      // Le code agence de l'aperçu est un exemple : la vraie valeur est celle
      // de l'agence qui ouvre, et le poste ne la connaît qu'au moment d'ouvrir.
      branchCode: '00001',
      jour: this.jour(),
      compteur: demande.sequenceStart,
    });
  }

  protected async charger(): Promise<void> {
    this.phase.set('chargement');
    this.refus.set(null);
    try {
      const entite = this.config.legalEntityId();
      const [regles, etablissement] = await Promise.all([
        this.siege.regles(entite),
        this.siege.etablissement(entite),
      ]);
      this.regles.set(regles);
      this.codeBanque.set(etablissement.bankCode);
      this.jour.set(etablissement.businessDate || this.jour());
      this.phase.set('prete');
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  protected choisirDomaine(domaine: DomaineNumerotation): void {
    this.domaine.set(domaine);
    this.brouillon.set(null);
    this.acquitte.set(null);
  }

  /** Partir de la proposition du socle : un point de départ, jamais un défaut appliqué. */
  protected async partirDeLaProposition(): Promise<void> {
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      this.brouillon.set(
        await this.siege.proposition(this.config.legalEntityId(), this.domaine()));
      this.phase.set('prete');
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  /** Repartir de la règle active : on corrige un chiffre sans tout réécrire. */
  protected partirDeLActive(): void {
    const active = this.active();
    if (active === null) {
      return;
    }
    this.brouillon.set({
      domain: active.domain,
      label: active.label,
      segments: active.segments,
      sequenceScope: active.scope,
      sequenceReset: active.reset,
      sequenceStart: active.sequenceStart,
    });
  }

  protected partirDeZero(): void {
    this.brouillon.set({
      domain: this.domaine(),
      label: LIBELLE_DOMAINE[this.domaine()],
      segments: [segmentNeuf('SEQUENCE')],
      sequenceScope: 'ENTITY',
      sequenceReset: 'NEVER',
      sequenceStart: 1,
    });
  }

  protected abandonner(): void {
    this.brouillon.set(null);
  }

  // ------------------------------------------------------ le gabarit en cours

  protected majLibelle(valeur: string): void {
    this.brouillon.update((b) => (b === null ? b : { ...b, label: valeur }));
  }

  protected majPortee(valeur: string): void {
    this.brouillon.update(
      (b) => (b === null ? b : { ...b, sequenceScope: valeur as PorteeCompteur }));
  }

  protected majRemise(valeur: string): void {
    this.brouillon.update(
      (b) => (b === null ? b : { ...b, sequenceReset: valeur as RemiseAZero }));
  }

  protected majDepart(valeur: string): void {
    const depart = Number(valeur);
    this.brouillon.update((b) => (b === null || !Number.isFinite(depart) || depart < 0
      ? b : { ...b, sequenceStart: Math.trunc(depart) }));
  }

  protected ajouter(nature: NatureSegment): void {
    this.brouillon.update((b) => (b === null
      ? b : { ...b, segments: [...b.segments, segmentNeuf(nature)] }));
  }

  protected retirer(index: number): void {
    this.brouillon.update((b) => (b === null
      ? b : { ...b, segments: b.segments.filter((_, i) => i !== index) }));
  }

  protected deplacer(index: number, pas: number): void {
    this.brouillon.update((b) => {
      if (b === null) {
        return b;
      }
      const cible = index + pas;
      if (cible < 0 || cible >= b.segments.length) {
        return b;
      }
      const segments = [...b.segments];
      [segments[index], segments[cible]] = [segments[cible], segments[index]];
      return { ...b, segments };
    });
  }

  protected majSegment(index: number, reglages: Partial<Segment>): void {
    this.brouillon.update((b) => (b === null ? b : {
      ...b,
      segments: b.segments.map((segment, i) => (i === index ? { ...segment, ...reglages }
                                                            : segment)),
    }));
  }

  protected majCadrage(index: number, valeur: string): void {
    const taille = Number(valeur);
    this.majSegment(index, { length: Number.isFinite(taille) && taille > 0
      ? Math.trunc(taille) : null });
  }

  protected majAlgorithme(index: number, valeur: string): void {
    const algorithme = valeur as AlgorithmeCle;
    this.majSegment(index, { algorithm: algorithme, length: algorithme === 'LUHN' ? 1 : 2 });
  }

  // ------------------------------------------------------ rédiger et activer

  protected async rediger(): Promise<void> {
    const brouillon = this.brouillon();
    if (brouillon === null || this.obstacles().length > 0) {
      return;
    }
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      await this.siege.redigerRegle(this.config.legalEntityId(), brouillon, crypto.randomUUID());
      this.brouillon.set(null);
      this.acquitte.set('La règle est rédigée. Elle ne numérote rien tant qu\'une autre personne '
                        + 'ne l\'a pas activée.');
      await this.charger();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  protected async activer(regle: RegleNumerotation): Promise<void> {
    this.phase.set('envoi');
    this.refus.set(null);
    try {
      await this.siege.activerRegle(this.config.legalEntityId(), regle.id, crypto.randomUUID());
      this.acquitte.set(`L'activation de « ${regle.label} » est soumise à un second regard.`);
      await this.charger();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.phase.set('refuse');
    }
  }

  /**
   * L'état de la règle, dit avec le vocabulaire fermé du badge.
   *
   * Le mot affiché est celui du domaine — « Active », « Retirée » —, la couleur
   * vient du vocabulaire commun : un brouillon est un brouillon, une règle qui
   * numérote est acquise, une règle retirée appartient au passé.
   */
  protected etatDe(regle: RegleNumerotation): EtatOperation {
    switch (regle.status) {
      case 'ACTIVE':
        return 'comptabilise';
      case 'DRAFT':
        return 'brouillon';
      default:
        return 'contre-passe';
    }
  }

  private enRefus(erreur: unknown): RefusMetier {
    return erreur instanceof RefusMetier
      ? erreur
      : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste.', String(erreur));
  }
}
