import {
  ChangeDetectionStrategy, Component, computed, effect, inject, input, signal,
} from '@angular/core';
import { Router } from '@angular/router';
import { Droits } from '../../auth/habilitations';
import { AppConfig } from '../../core/config/runtime-config';
import { formaterTaux } from '../../core/format/montant';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import {
  CbActivity, CbAmount, CbAmountInput, CbButton, CbDateInput, CbField, CbInput, CbInterdit,
  CbNotice, CbSection, CbStateBadge, CbTable,
} from '../../ui';
import { CREDIT } from '../credit.port';
import {
  Contrat, DossierPerte, LIBELLE_STATUT_CONTRAT, etatDuContrat, graviteDuRetard, resteARecouvrer,
} from '../modele/credit.modele';

type Volet = 'aucun' | 'passer' | 'recouvrer';

/**
 * Le passage en perte d'un crédit, et ce qui se recouvre après.
 *
 * **Un écran à part, et non un bouton de plus sur le contrat.** Passer en perte
 * est la sortie d'un actif des livres : cela se décide avec la provision sous
 * les yeux, pas au milieu d'un échéancier. Un bouton discret au bout d'une
 * barre d'actions ferait exactement l'inverse.
 *
 * Deux choses que l'écran doit faire comprendre, parce qu'elles sont
 * contre-intuitives et coûteuses à ignorer :
 *
 *   **la perte n'est pas l'exposition.** Elle est absorbée d'abord par les
 *   intérêts réservés — déjà sortis du résultat à la suspension, et les passer
 *   en perte une seconde fois constaterait une charge pour un produit jamais
 *   pris — puis par la provision, qui est faite pour cela. Le reliquat seul est
 *   une perte. L'écran montre la décomposition, pas un total ;
 *
 *   **la créance reste due.** Sortie de l'actif, elle se suit au hors bilan, et
 *   tout recouvrement s'y impute. Croire qu'un passage en perte éteint la dette
 *   est l'erreur qui fait cesser les relances.
 */
@Component({
  selector: 'cb-perte-credit',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CbActivity, CbAmount, CbAmountInput, CbButton, CbDateInput, CbField, CbInput, CbInterdit,
    CbNotice, CbSection, CbStateBadge, CbTable,
  ],
  templateUrl: './perte.page.html',
  styleUrl: './perte.page.css',
})
export class PerteCredit {
  readonly id = input.required<string>();

  private readonly credit = inject(CREDIT);
  private readonly config = inject(AppConfig);
  private readonly droits = inject(Droits);
  private readonly router = inject(Router);

  protected readonly LIBELLE_STATUT_CONTRAT = LIBELLE_STATUT_CONTRAT;
  protected readonly etatDuContrat = etatDuContrat;
  protected readonly graviteDuRetard = graviteDuRetard;

  protected readonly contrat = signal<Contrat | null>(null);
  protected readonly dossier = signal<DossierPerte | null>(null);
  protected readonly chargement = signal(false);
  protected readonly refus = signal<RefusMetier | null>(null);
  protected readonly envoi = signal(false);
  protected readonly volet = signal<Volet>('aucun');
  protected readonly enAttente = signal<string | null>(null);

  protected readonly motif = signal('');
  protected readonly dateDePerte = signal<string | null>(null);
  protected readonly montantRecouvre = signal<number | null>(null);
  protected readonly compteDEncaissement = signal('');
  protected readonly dateDeRecouvrement = signal<string | null>(null);

  private cle = crypto.randomUUID();

  protected readonly perte = computed(() => this.dossier()?.perte ?? null);

  /** L'exposition sortie de l'actif : capital plus créances, avant absorption. */
  protected readonly exposition = computed(() => {
    const p = this.perte();
    if (!p) return null;
    const somme = Number(p.principalWritten?.amount ?? '0')
      + Number(p.receivablesWritten?.amount ?? '0');
    return { amount: String(somme), currency: p.principalWritten?.currency ?? 'XOF' };
  });

  /** Ce qui reste à recouvrer au hors bilan : la créance vit toujours. */
  protected readonly reste = computed(() => {
    const p = this.perte();
    if (!p) return null;
    return {
      amount: resteARecouvrer(p),
      currency: p.principalWritten?.currency ?? 'XOF',
    };
  });

  /**
   * Deux actes sur cet écran, et **deux droits opposés**.
   *
   * Passer en perte sort un actif des livres : c'est une décision de crédit,
   * qui se prend à deux. Enregistrer un recouvrement constate de l'argent déjà
   * rentré : c'est du travail de recouvrement, qu'un seul fait. Le même écran,
   * deux profils — et souvent deux personnes.
   */
  protected readonly droitDePasser = computed(() => this.droits.peut('LOAN_WRITE_OFF'));
  protected readonly droitDeRecouvrer = computed(() => this.droits.peut('LOAN_RECOVERY'));

  protected readonly peutPasser = computed(() =>
    this.perte() === null && this.contrat()?.status === 'ACTIVE' && this.droitDePasser());

  protected readonly peutRecouvrer = computed(() => this.perte() !== null && this.droitDeRecouvrer());

  constructor() {
    effect(() => {
      const id = this.id();
      void this.charger(id);
    });
  }

  protected async charger(id = this.id()): Promise<void> {
    this.chargement.set(true);
    this.refus.set(null);
    const entite = this.config.legalEntityId();
    try {
      const [contrat, dossier] = await Promise.all([
        this.credit.contrat(entite, id),
        this.credit.perte(entite, id),
      ]);
      this.contrat.set(contrat);
      this.dossier.set(dossier);
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
    } finally {
      this.chargement.set(false);
    }
  }

  protected ouvrirVolet(volet: Volet): void {
    this.volet.set(volet);
    this.refus.set(null);
    this.enAttente.set(null);
    this.cle = crypto.randomUUID();
  }

  protected fermerVolet(): void {
    this.volet.set('aucun');
  }

  protected async passerEnPerte(): Promise<void> {
    await this.agir(async () => {
      const attente = await this.credit.passerEnPerte(this.config.legalEntityId(), this.id(), {
        reason: this.motif().trim(),
        writtenOffOn: this.dateDePerte(),
      }, this.cle);
      this.enAttente.set(attente.operationId);
    });
  }

  protected async enregistrerRecouvrement(): Promise<void> {
    await this.agir(async () => {
      await this.credit.enregistrerRecouvrement(this.config.legalEntityId(), this.id(), {
        amount: String(this.montantRecouvre()),
        channelAccountId: this.compteDEncaissement().trim(),
        recoveredOn: this.dateDeRecouvrement(),
      }, this.cle);
      this.montantRecouvre.set(null);
      this.fermerVolet();
    });
  }

  private async agir(acte: () => Promise<void>): Promise<void> {
    this.envoi.set(true);
    this.refus.set(null);
    try {
      await acte();
      await this.charger();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
    } finally {
      this.envoi.set(false);
    }
  }

  protected auContrat(): void {
    void this.router.navigate(['/credit/contrats', this.id()]);
  }

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
