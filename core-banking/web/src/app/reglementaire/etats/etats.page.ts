import {
  ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked,
} from '@angular/core';
import { Router } from '@angular/router';
import { AppConfig } from '../../core/config/runtime-config';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import {
  CbActivity, CbAmount, CbButton, CbNotice, CbStateBadge, CbTable, CbToolbar,
} from '../../ui';
import { REGLEMENTAIRE } from '../reglementaire.port';
import {
  Etat, LIBELLE_METHODE_ETAT, LIBELLE_STATUT_ETAT, StatutEtat, etatDeLEtat, libellePeriode,
} from '../modele/reglementaire.modele';

const FILTRES: readonly { valeur: StatutEtat | ''; libelle: string }[] = [
  { valeur: 'PRODUCED', libelle: 'Produits' },
  { valeur: 'TRANSMITTED', libelle: 'Transmis' },
  { valeur: '', libelle: 'Tous' },
  { valeur: 'CANCELLED', libelle: 'Annulés' },
];

/**
 * Les états produits.
 *
 * **Le filtre par défaut est « produits ».** Ce sont ceux qui attendent une
 * décision — transmettre ou reprendre. Les états transmis se relisent, mais on
 * ne vient pas les chercher tous les matins.
 *
 * **La colonne « anomalies » n'est pas décorative.** Un état qui en porte se
 * voit d'ici, sans l'ouvrir : il ne se transmettra pas tant qu'elles seront là,
 * et le découvrir la veille de l'échéance est le scénario que cette colonne
 * évite.
 */
@Component({
  selector: 'cb-etats',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbActivity, CbAmount, CbButton, CbNotice, CbStateBadge, CbTable, CbToolbar],
  templateUrl: './etats.page.html',
  styleUrl: './etats.page.css',
})
export class Etats {
  /** Le filtre passé en paramètre d'URL : un lien vers la liste se partage. */
  readonly statutInitial = input<string>('', { alias: 'statut' });

  private readonly reglementaire = inject(REGLEMENTAIRE);
  private readonly config = inject(AppConfig);
  private readonly router = inject(Router);

  protected readonly FILTRES = FILTRES;
  protected readonly LIBELLE_STATUT_ETAT = LIBELLE_STATUT_ETAT;
  protected readonly LIBELLE_METHODE_ETAT = LIBELLE_METHODE_ETAT;
  protected readonly etatDeLEtat = etatDeLEtat;
  protected readonly libellePeriode = libellePeriode;

  protected readonly statut = signal<StatutEtat | ''>('PRODUCED');
  protected readonly etats = signal<readonly Etat[]>([]);
  protected readonly chargement = signal(false);
  protected readonly refus = signal<RefusMetier | null>(null);
  protected readonly lu = signal(false);

  /** Ce qui ne pourra pas partir en l'état : le chiffre qui doit sauter aux yeux. */
  protected readonly enAnomalie = computed(() =>
    this.etats().filter((e) => e.status === 'PRODUCED' && e.anomalies.length > 0).length);

  constructor() {
    effect(() => {
      const initial = this.statutInitial();
      untracked(() => {
        if (initial) this.statut.set(initial as StatutEtat);
        void this.charger();
      });
    });
  }

  protected async charger(): Promise<void> {
    this.chargement.set(true);
    this.refus.set(null);
    try {
      this.etats.set(await this.reglementaire.etats(
        this.config.legalEntityId(), this.statut() || null));
      this.lu.set(true);
    } catch (erreur) {
      this.refus.set(erreur instanceof RefusMetier
        ? erreur
        : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste.', String(erreur)));
      this.etats.set([]);
    } finally {
      this.chargement.set(false);
    }
  }

  protected filtrer(valeur: string): void {
    this.statut.set(valeur as StatutEtat | '');
    void this.charger();
  }

  protected ouvrir(etat: Etat): void {
    void this.router.navigate(['/reglementaire/etats', etat.id]);
  }

  protected jour(iso: string | null): string {
    if (!iso) return '—';
    const [a, m, j] = iso.split('-');
    return `${j}/${m}/${a}`;
  }
}
