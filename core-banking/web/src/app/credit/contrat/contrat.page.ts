import {
  ChangeDetectionStrategy, Component, computed, effect, inject, input, signal,
} from '@angular/core';
import { Router } from '@angular/router';
import { AppConfig } from '../../core/config/runtime-config';
import { formaterTaux } from '../../core/format/montant';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import {
  CbActivity, CbAmount, CbAmountInput, CbButton, CbField, CbNotice, CbSection, CbStateBadge,
  CbTable, CbTabs, CbToolbar, Onglet,
} from '../../ui';
import { CREDIT } from '../credit.port';
import {
  Contrat, LIBELLE_CREANCE, LIBELLE_STATUT_CONTRAT, Reglement, etatDuContrat, graviteDuRetard,
} from '../modele/credit.modele';

type Volet = 'aucun' | 'deblocage' | 'reglement';

/**
 * Un contrat de crédit : ce qui est dû, ce qui vient, et ce qu'on peut faire.
 *
 * Deux choix tiennent l'écran :
 *
 *   **les créances exigibles passent avant l'échéancier.** L'échéancier dit ce
 *   qui était prévu ; les créances disent ce qui est dû aujourd'hui. Au guichet,
 *   c'est la seconde question qu'on pose, jamais la première ;
 *
 *   **l'imputation d'un règlement est rendue telle que le socle l'a faite.** Le
 *   front ne calcule rien : il montre à quoi l'argent est allé, dans l'ordre que
 *   la banque a paramétré — frais de recouvrement d'abord, capital en dernier.
 *   C'est ce qu'un guichetier doit pouvoir expliquer, ligne par ligne.
 */
@Component({
  selector: 'cb-contrat-credit',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CbActivity, CbAmount, CbAmountInput, CbButton, CbField, CbNotice, CbSection, CbStateBadge,
    CbTable, CbTabs, CbToolbar,
  ],
  templateUrl: './contrat.page.html',
  styleUrl: './contrat.page.css',
})
export class ContratCredit {
  readonly id = input.required<string>();

  private readonly credit = inject(CREDIT);
  private readonly config = inject(AppConfig);
  private readonly router = inject(Router);

  protected readonly LIBELLE_CREANCE = LIBELLE_CREANCE;
  protected readonly LIBELLE_STATUT_CONTRAT = LIBELLE_STATUT_CONTRAT;
  protected readonly etatDuContrat = etatDuContrat;
  protected readonly graviteDuRetard = graviteDuRetard;

  protected readonly contrat = signal<Contrat | null>(null);
  protected readonly chargement = signal(false);
  protected readonly refus = signal<RefusMetier | null>(null);
  protected readonly envoi = signal(false);
  protected readonly volet = signal<Volet>('aucun');
  protected readonly enAttente = signal<string | null>(null);
  protected readonly reglement = signal<Reglement | null>(null);

  protected readonly montant = signal<number | null>(null);

  protected readonly onglets: readonly Onglet[] = [
    { id: 'du', libelle: 'Ce qui est dû' },
    { id: 'echeancier', libelle: 'Échéancier' },
  ];
  protected readonly onglet = signal('du');

  private cle = crypto.randomUUID();

  /** Le total exigible : la somme de ce qui est échu, capital non échu exclu. */
  protected readonly exigible = computed(() => {
    const c = this.contrat();
    if (!c) return null;
    const lignes = c.creances.filter((cr) => cr.category !== 'FUTURE_PRINCIPAL');
    if (lignes.length === 0) return null;
    const somme = lignes.reduce((total, cr) => total + Number(cr.outstanding?.amount ?? '0'), 0);
    return { amount: String(somme), currency: c.currency };
  });

  protected readonly capitalRestant = computed(() => {
    const c = this.contrat();
    return c?.creances.find((cr) => cr.category === 'FUTURE_PRINCIPAL')?.outstanding ?? null;
  });

  protected readonly creancesExigibles = computed(() =>
    (this.contrat()?.creances ?? []).filter((cr) => cr.category !== 'FUTURE_PRINCIPAL'));

  protected readonly peutDebloquer = computed(() => this.contrat()?.status === 'DRAFT');
  protected readonly peutRegler = computed(() => this.contrat()?.status === 'ACTIVE');

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
      this.contrat.set(await this.credit.contrat(this.config.legalEntityId(), id));
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.contrat.set(null);
    } finally {
      this.chargement.set(false);
    }
  }

  protected ouvrirVolet(volet: Volet): void {
    this.volet.set(volet);
    this.refus.set(null);
    this.enAttente.set(null);
    this.reglement.set(null);
    this.cle = crypto.randomUUID();
  }

  protected fermerVolet(): void {
    this.volet.set('aucun');
  }

  protected async debloquer(): Promise<void> {
    this.envoi.set(true);
    this.refus.set(null);
    try {
      const attente = await this.credit.debloquer(
        this.config.legalEntityId(), this.id(), this.cle);
      this.enAttente.set(attente.operationId);
      await this.charger();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
    } finally {
      this.envoi.set(false);
    }
  }

  protected async regler(): Promise<void> {
    const contrat = this.contrat();
    if (!contrat || this.montant() === null) return;
    this.envoi.set(true);
    this.refus.set(null);
    try {
      this.reglement.set(await this.credit.regler(this.config.legalEntityId(), this.id(), {
        amount: String(this.montant()),
        currency: contrat.currency,
        valueDate: null,
      }, this.cle));
      await this.charger();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
    } finally {
      this.envoi.set(false);
    }
  }

  protected auPortefeuille(): void {
    void this.router.navigate(['/credit/portefeuille']);
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
