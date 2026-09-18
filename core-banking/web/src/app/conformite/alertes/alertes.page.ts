import {
  ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked,
} from '@angular/core';
import { Router } from '@angular/router';
import { AppConfig } from '../../core/config/runtime-config';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import {
  CbActivity, CbAmount, CbButton, CbNotice, CbStateBadge, CbTable, CbToolbar,
} from '../../ui';
import { CONFORMITE } from '../conformite.port';
import {
  Alerte, LIBELLE_ORIGINE, LIBELLE_STATUT_ALERTE, StatutAlerte, aTraiter, etatDeLAlerte,
} from '../modele/conformite.modele';

/** Les filtres proposés, dans l'ordre où un analyste les parcourt. */
const FILTRES: readonly { valeur: StatutAlerte | ''; libelle: string }[] = [
  { valeur: 'OPEN', libelle: 'Ouvertes' },
  { valeur: 'UNDER_REVIEW', libelle: 'En instruction' },
  { valeur: '', libelle: 'Toutes' },
  { valeur: 'CLOSED', libelle: 'Classées' },
  { valeur: 'REPORTED', libelle: 'Déclarées' },
];

/**
 * La file de travail de la conformité.
 *
 * **Le filtre par défaut est « ouvertes ».** C'est ce qu'un analyste vient
 * faire : prendre ce que personne n'a encore regardé. Ouvrir sur « toutes »
 * ferait défiler des alertes closes qu'il ne peut plus toucher.
 *
 * **Deux choses que cet écran refuse de faire**, et qui le distinguent de
 * toutes les autres files de l'application :
 *
 *   il ne nomme pas le client. Une file d'alertes n'est pas un annuaire ;
 *   l'identité se lit dans le dossier d'alerte, par quelqu'un qui l'instruit ;
 *
 *   il ne propose aucun geste sur le compte. Une alerte constate, elle
 *   n'empêche rien : bloquer un compte sur un compteur statistique priverait
 *   quelqu'un de son argent sur une présomption.
 */
@Component({
  selector: 'cb-alertes',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbActivity, CbAmount, CbButton, CbNotice, CbStateBadge, CbTable, CbToolbar],
  templateUrl: './alertes.page.html',
  styleUrl: './alertes.page.css',
})
export class Alertes {
  /** Le filtre passé en paramètre d'URL : un lien vers la file se partage. */
  readonly statutInitial = input<string>('', { alias: 'statut' });

  private readonly conformite = inject(CONFORMITE);
  private readonly config = inject(AppConfig);
  private readonly router = inject(Router);

  protected readonly FILTRES = FILTRES;
  protected readonly LIBELLE_STATUT_ALERTE = LIBELLE_STATUT_ALERTE;
  protected readonly LIBELLE_ORIGINE = LIBELLE_ORIGINE;
  protected readonly etatDeLAlerte = etatDeLAlerte;
  protected readonly aTraiter = aTraiter;

  protected readonly statut = signal<StatutAlerte | ''>('OPEN');
  protected readonly alertes = signal<readonly Alerte[]>([]);
  protected readonly chargement = signal(false);
  protected readonly refus = signal<RefusMetier | null>(null);
  protected readonly lu = signal(false);
  protected readonly enCours = signal<string | null>(null);

  /** Ce qui reste à traiter dans ce que l'écran montre : le chiffre du matin. */
  protected readonly restant = computed(() => this.alertes().filter(aTraiter).length);

  constructor() {
    effect(() => {
      const initial = this.statutInitial();
      untracked(() => {
        if (initial) this.statut.set(initial as StatutAlerte);
        void this.charger();
      });
    });
  }

  protected async charger(): Promise<void> {
    this.chargement.set(true);
    this.refus.set(null);
    try {
      this.alertes.set(await this.conformite.alertes(
        this.config.legalEntityId(), this.statut() || null));
      this.lu.set(true);
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
      this.alertes.set([]);
    } finally {
      this.chargement.set(false);
    }
  }

  protected filtrer(valeur: string): void {
    this.statut.set(valeur as StatutAlerte | '');
    void this.charger();
  }

  protected ouvrir(alerte: Alerte): void {
    void this.router.navigate(['/conformite/alertes', alerte.id]);
  }

  /**
   * Prise en charge depuis la file : le geste le plus fréquent du matin.
   *
   * Il n'ouvre pas le dossier. Un analyste prend d'abord ce qu'il traitera,
   * puis instruit ; l'obliger à ouvrir chaque alerte pour se l'attribuer ferait
   * quatre écrans pour une file de quatre lignes.
   */
  protected async prendre(alerte: Alerte, evenement: Event): Promise<void> {
    evenement.stopPropagation();
    this.enCours.set(alerte.id);
    this.refus.set(null);
    try {
      await this.conformite.prendreEnCharge(this.config.legalEntityId(), alerte.id,
                                            crypto.randomUUID());
      await this.charger();
    } catch (erreur) {
      this.refus.set(this.enRefus(erreur));
    } finally {
      this.enCours.set(null);
    }
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
