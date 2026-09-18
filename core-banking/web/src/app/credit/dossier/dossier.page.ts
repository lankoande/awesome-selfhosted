import {
  ChangeDetectionStrategy, Component, computed, effect, inject, input, signal,
} from '@angular/core';
import { Router } from '@angular/router';
import { AppConfig } from '../../core/config/runtime-config';
import { formaterTaux } from '../../core/format/montant';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import {
  CbActivity, CbAmount, CbAmountInput, CbButton, CbDateInput, CbField, CbInput, CbNotice,
  CbSection, CbStateBadge, CbTable,
} from '../../ui';
import { CREDIT } from '../credit.port';
import {
  Analyse, Condition, DossierCredit, LIBELLE_CONDITION, LIBELLE_STATUT_DEMANDE, NatureCondition,
  analyseEnVigueur, engagementsASuivre, etatDeLaDemande, obstaclesALaContractualisation,
} from '../modele/credit.modele';

/**
 * Les volets s'ouvrent **en place**, pas dans un tiroir.
 *
 * Un tiroir du CDK piège le focus : décider tiroir ouvert masquerait les
 * dépassements de grille qu'on doit lire au moment de motiver. Ici la saisie
 * s'ajoute sous la section qu'elle concerne, et tout reste lisible.
 */
type Volet = 'aucun' | 'analyse' | 'condition' | 'condition-levee' | 'decision'
           | 'contrat' | 'retrait';

/**
 * Le dossier d'instruction d'une demande de crédit.
 *
 * L'écran répond dans l'ordre où les questions se posent :
 *
 *   **où en est-on, et que puis-je faire maintenant ?** L'état de la demande et
 *   ce qui bloque la suite passent avant tout le reste ;
 *
 *   **le client peut-il rembourser ?** L'analyse, avec le ratio *et* les
 *   dépassements de grille nommés — un ratio seul est un chiffre, un
 *   dépassement nommé est une décision à prendre ;
 *
 *   **qu'a-t-on exigé de lui ?** Les conditions, **suspensives séparées des
 *   résolutoires**. Les confondre débloque un crédit sans la garantie qui le
 *   couvrait : c'est la faute la plus coûteuse de l'instruction ;
 *
 *   **qu'a-t-on décidé, et quand ?** La décision, puis l'historique complet.
 *
 * Deux actes passent par un second regard et l'écran le dit avant : **décider**
 * et **lever une condition**. Celui qui monte un dossier ne l'accorde pas seul.
 */
@Component({
  selector: 'cb-dossier-credit',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CbActivity, CbAmount, CbAmountInput, CbButton, CbDateInput, CbField, CbInput, CbNotice,
    CbSection, CbStateBadge, CbTable,
  ],
  templateUrl: './dossier.page.html',
  styleUrl: './dossier.page.css',
})
export class DossierCreditPage {
  readonly id = input.required<string>();

  private readonly credit = inject(CREDIT);
  private readonly config = inject(AppConfig);
  private readonly router = inject(Router);

  protected readonly LIBELLE_STATUT_DEMANDE = LIBELLE_STATUT_DEMANDE;
  protected readonly LIBELLE_CONDITION = LIBELLE_CONDITION;
  protected readonly etatDeLaDemande = etatDeLaDemande;

  protected readonly dossier = signal<DossierCredit | null>(null);
  protected readonly chargement = signal(false);
  protected readonly refus = signal<RefusMetier | null>(null);
  protected readonly envoi = signal(false);
  protected readonly volet = signal<Volet>('aucun');
  protected readonly enAttente = signal<string | null>(null);
  protected readonly contracte = signal<{ contractId: string; reference: string } | null>(null);

  // --- saisies du volet ouvert
  protected readonly revenu = signal<number | null>(null);
  protected readonly charges = signal<number | null>(null);
  protected readonly apport = signal<number | null>(null);
  protected readonly taux = signal('');
  protected readonly score = signal('');
  protected readonly natureCondition = signal<NatureCondition>('PRECEDENT');
  protected readonly descriptionCondition = signal('');
  protected readonly echeanceCondition = signal<string | null>(null);
  protected readonly issue = signal<'APPROVED' | 'REJECTED'>('APPROVED');
  protected readonly montantAccorde = signal<number | null>(null);
  protected readonly tauxAccorde = signal('');
  protected readonly dureeAccordee = signal('');
  protected readonly motif = signal('');
  protected readonly derogation = signal('');
  protected readonly comptePret = signal('');
  protected readonly compteReglement = signal('');
  protected readonly referenceContrat = signal('');
  protected readonly preuve = signal('');
  protected readonly conditionALever = signal<Condition | null>(null);

  private cle = crypto.randomUUID();

  /** Ce que chaque volet exige avant d'être envoyable. Le socle refusera le reste. */
  protected readonly analyseComplete = computed(() =>
    (this.revenu() ?? 0) > 0 && this.taux().trim() !== '');
  protected readonly conditionComplete = computed(() =>
    this.descriptionCondition().trim().length > 2);
  protected readonly decisionComplete = computed(() => this.motif().trim().length > 2);
  protected readonly contratComplet = computed(() =>
    this.comptePret().trim() !== '' && this.compteReglement().trim() !== '');

  protected readonly analyse = computed<Analyse | null>(() => {
    const d = this.dossier();
    return d ? analyseEnVigueur(d) : null;
  });

  protected readonly suspensives = computed(() =>
    (this.dossier()?.conditions ?? []).filter((c) => c.kind === 'PRECEDENT'));

  protected readonly resolutoires = computed(() =>
    (this.dossier()?.conditions ?? []).filter((c) => c.kind === 'SUBSEQUENT'));

  protected readonly aSuivre = computed(() => {
    const d = this.dossier();
    return d ? engagementsASuivre(d) : [];
  });

  protected readonly obstacles = computed(() => {
    const d = this.dossier();
    return d ? obstaclesALaContractualisation(d) : [];
  });

  /** Ce qu'on peut faire maintenant : l'état de la demande commande tout. */
  protected readonly peutInstruire = computed(() => {
    const statut = this.dossier()?.demande.status;
    return statut === 'SUBMITTED' || statut === 'UNDER_REVIEW';
  });

  protected readonly peutDecider = computed(() => {
    const d = this.dossier();
    return this.peutInstruire() && (d?.analyses.length ?? 0) > 0;
  });

  protected readonly peutContractualiser = computed(() =>
    this.dossier()?.demande.status === 'APPROVED');

  constructor() {
    effect(() => {
      const id = this.id();
      void this.charger(id);
    });
  }

  protected async charger(id = this.id()): Promise<void> {
    this.chargement.set(true);
    this.refus.set(null);
    try {
      this.dossier.set(await this.credit.dossier(this.config.legalEntityId(), id));
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.dossier.set(null);
    } finally {
      this.chargement.set(false);
    }
  }

  protected ouvrirVolet(volet: Volet, condition: Condition | null = null): void {
    this.volet.set(volet);
    this.conditionALever.set(condition);
    this.refus.set(null);
    this.enAttente.set(null);
    // Une clé par acte : deux actes distincts ne se confondent jamais.
    this.cle = crypto.randomUUID();
  }

  protected fermerVolet(): void {
    this.volet.set('aucun');
    this.conditionALever.set(null);
  }

  protected async verserAnalyse(): Promise<void> {
    await this.agir(() => this.credit.analyser(this.config.legalEntityId(), this.id(), {
      monthlyIncome: String(this.revenu() ?? 0),
      monthlyCharges: String(this.charges() ?? 0),
      downPayment: this.apport() === null ? null : String(this.apport()),
      ratePercent: this.taux(),
      externalScore: this.score() ? Number(this.score()) : null,
      scoreSource: null,
      assessedOn: null,
    }, this.cle));
  }

  protected async poserCondition(): Promise<void> {
    await this.agir(() => this.credit.poserCondition(this.config.legalEntityId(), this.id(), {
      kind: this.natureCondition(),
      description: this.descriptionCondition().trim(),
      dueOn: this.echeanceCondition(),
    }, this.cle));
  }

  protected async lever(): Promise<void> {
    const condition = this.conditionALever();
    if (!condition) return;
    await this.agir(async () => {
      const attente = await this.credit.leverCondition(
        this.config.legalEntityId(), condition.id, this.preuve().trim(), this.cle);
      this.enAttente.set(attente.operationId);
    }, false);
  }

  protected async decider(): Promise<void> {
    await this.agir(async () => {
      const attente = await this.credit.decider(this.config.legalEntityId(), this.id(), {
        outcome: this.issue(),
        grantedAmount: this.montantAccorde() === null ? null : String(this.montantAccorde()),
        grantedRatePercent: this.tauxAccorde() || null,
        grantedTermMonths: this.dureeAccordee() ? Number(this.dureeAccordee()) : null,
        reason: this.motif().trim(),
        waiverReason: this.derogation().trim() || null,
        decidedOn: null,
      }, this.cle);
      this.enAttente.set(attente.operationId);
    }, false);
  }

  protected async contractualiser(): Promise<void> {
    await this.agir(async () => {
      this.contracte.set(await this.credit.contractualiser(
        this.config.legalEntityId(), this.id(), {
          loanAccountId: this.comptePret().trim(),
          settlementAccountId: this.compteReglement().trim(),
          contractReference: this.referenceContrat().trim() || null,
          disbursementDate: null,
        }, this.cle));
    }, false);
  }

  protected async retirer(): Promise<void> {
    await this.agir(() => this.credit.retirer(
      this.config.legalEntityId(), this.id(), this.motif().trim(), this.cle));
  }

  /**
   * Fait l'acte, puis relit le dossier. Relire n'est pas un luxe : la décision
   * et la levée partent en validation, et l'écran doit montrer l'état réel —
   * pas celui qu'il espérait.
   */
  private async agir(acte: () => Promise<unknown>, fermer = true): Promise<void> {
    this.envoi.set(true);
    this.refus.set(null);
    try {
      await acte();
      await this.charger();
      if (fermer) this.fermerVolet();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
    } finally {
      this.envoi.set(false);
    }
  }

  protected auContrat(): void {
    const fait = this.contracte();
    const id = fait?.contractId ?? this.dossier()?.demande.contractId;
    if (id) void this.router.navigate(['/credit/contrats', id]);
  }

  protected auxDemandes(): void {
    void this.router.navigate(['/credit/demandes']);
  }

  protected auClient(): void {
    const client = this.dossier()?.demande.customerId;
    if (client) void this.router.navigate(['/clients', client]);
  }

  /** Un taux se lit avec une virgule : le socle en rend un avec un point. */
  protected pourcent(valeur: string | null): string {
    return formaterTaux(valeur);
  }

  protected jour(iso: string | null): string {
    if (!iso) return '—';
    const [a, m, j] = iso.split('-');
    return `${j}/${m}/${a}`;
  }

  private enRefus(erreur: unknown): RefusMetier {
    return erreur instanceof RefusMetier
      ? erreur
      : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste.', String(erreur));
  }
}
