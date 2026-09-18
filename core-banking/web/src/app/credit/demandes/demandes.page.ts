import { ChangeDetectionStrategy, Component, effect, inject, input, signal, untracked } from '@angular/core';
import { Router } from '@angular/router';
import { AppConfig } from '../../core/config/runtime-config';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import {
  CbActivity, CbAmount, CbButton, CbNotice, CbPagination, CbStateBadge, CbTable, CbToolbar,
} from '../../ui';
import { CREDIT } from '../credit.port';
import {
  Demande, LIBELLE_STATUT_DEMANDE, StatutDemande, etatDeLaDemande,
} from '../modele/credit.modele';

/** Les filtres proposés, dans l'ordre où un chargé de crédit les parcourt. */
const FILTRES: readonly { valeur: StatutDemande | ''; libelle: string }[] = [
  { valeur: '', libelle: 'Toutes' },
  { valeur: 'SUBMITTED', libelle: 'Déposées' },
  { valeur: 'UNDER_REVIEW', libelle: 'En instruction' },
  { valeur: 'APPROVED', libelle: 'Accordées' },
  { valeur: 'CONTRACTED', libelle: 'Contractées' },
  { valeur: 'REJECTED', libelle: 'Refusées' },
];

/**
 * Les demandes de crédit en cours.
 *
 * **Le filtre par défaut est « en instruction ».** C'est ce qu'un chargé de
 * crédit vient faire : reprendre les dossiers qui attendent son travail.
 * Ouvrir sur « toutes » ferait défiler des dossiers clos qu'il ne peut plus
 * toucher, et il faudrait filtrer à chaque venue.
 */
@Component({
  selector: 'cb-demandes-credit',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbActivity, CbAmount, CbButton, CbNotice, CbPagination, CbStateBadge, CbTable, CbToolbar],
  templateUrl: './demandes.page.html',
  styleUrl: './demandes.page.css',
})
export class DemandesCredit {
  /** Le filtre passé en paramètre d'URL : un lien vers la file se partage. */
  readonly statutInitial = input<string>('', { alias: 'statut' });

  private readonly credit = inject(CREDIT);
  private readonly config = inject(AppConfig);
  private readonly router = inject(Router);

  protected readonly FILTRES = FILTRES;
  protected readonly LIBELLE_STATUT_DEMANDE = LIBELLE_STATUT_DEMANDE;
  protected readonly etatDeLaDemande = etatDeLaDemande;

  protected readonly statut = signal<StatutDemande | ''>('UNDER_REVIEW');
  protected readonly demandes = signal<readonly Demande[]>([]);
  protected readonly page = signal(0);
  protected readonly taille = signal(25);
  protected readonly precedent = signal(false);
  protected readonly suivant = signal(false);
  protected readonly chargement = signal(false);
  protected readonly refus = signal<RefusMetier | null>(null);
  /** Faux tant qu'aucune lecture n'a abouti : « aucune » ne se dit qu'après. */
  protected readonly lu = signal(false);

  constructor() {
    // L'entrée de route n'est pas posée à la construction ; `untracked` borne la
    // dépendance au seul paramètre d'URL.
    effect(() => {
      const initial = this.statutInitial();
      untracked(() => {
        if (initial) this.statut.set(initial as StatutDemande);
        void this.charger(0);
      });
    });
  }

  protected async charger(page: number): Promise<void> {
    this.chargement.set(true);
    this.refus.set(null);
    try {
      const resultat = await this.credit.demandes(
        this.config.legalEntityId(), this.statut() || null, page, this.taille());
      this.demandes.set(resultat.demandes);
      this.page.set(resultat.page);
      this.precedent.set(resultat.precedent);
      this.suivant.set(resultat.suivant);
      this.lu.set(true);
    } catch (erreur) {
      this.refus.set(erreur instanceof RefusMetier
        ? erreur
        : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste.', String(erreur)));
      this.demandes.set([]);
    } finally {
      this.chargement.set(false);
    }
  }

  protected filtrer(valeur: string): void {
    this.statut.set(valeur as StatutDemande | '');
    void this.charger(0);
  }

  protected ouvrir(demande: Demande): void {
    void this.router.navigate(['/credit/demandes', demande.id]);
  }

  protected nouvelle(): void {
    void this.router.navigate(['/credit/nouvelle']);
  }

  protected jour(iso: string | null): string {
    if (!iso) return '—';
    const [a, m, j] = iso.split('-');
    return `${j}/${m}/${a}`;
  }

  protected duree(mois: number | null): string {
    return mois === null ? '—' : `${mois} mois`;
  }
}
