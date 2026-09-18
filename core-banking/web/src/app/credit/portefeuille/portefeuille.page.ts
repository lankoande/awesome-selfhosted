import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AppConfig } from '../../core/config/runtime-config';
import { formaterTaux } from '../../core/format/montant';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import {
  CbActivity, CbAmount, CbButton, CbNotice, CbPagination, CbStateBadge, CbTable, CbToolbar,
} from '../../ui';
import { CREDIT } from '../credit.port';
import {
  Contrat, LIBELLE_STATUT_CONTRAT, etatDuContrat, graviteDuRetard,
} from '../modele/credit.modele';

/**
 * Le portefeuille de crédits.
 *
 * **Les contrats en retard remontent en tête.** Un portefeuille trié par
 * référence oblige à le parcourir pour trouver ce qui ne va pas ; trié par
 * retard, il montre d'abord ce qui demande une relance. Le tri est fait ici,
 * sur la page rendue : il ne prétend pas ordonner tout le portefeuille, il
 * ordonne ce qu'on regarde.
 */
@Component({
  selector: 'cb-portefeuille-credit',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CbActivity, CbAmount, CbButton, CbNotice, CbPagination, CbStateBadge, CbTable, CbToolbar,
  ],
  templateUrl: './portefeuille.page.html',
  styleUrl: './portefeuille.page.css',
})
export class PortefeuilleCredit {
  private readonly credit = inject(CREDIT);
  private readonly config = inject(AppConfig);
  private readonly router = inject(Router);

  protected readonly LIBELLE_STATUT_CONTRAT = LIBELLE_STATUT_CONTRAT;
  protected readonly etatDuContrat = etatDuContrat;
  protected readonly graviteDuRetard = graviteDuRetard;

  protected readonly brut = signal<readonly Contrat[]>([]);
  protected readonly page = signal(0);
  protected readonly taille = signal(25);
  protected readonly precedent = signal(false);
  protected readonly suivant = signal(false);
  protected readonly chargement = signal(false);
  protected readonly refus = signal<RefusMetier | null>(null);
  protected readonly lu = signal(false);

  /** Le retard décroissant d'abord ; à retard égal, la référence, pour un ordre stable. */
  protected readonly contrats = computed(() =>
    [...this.brut()].sort((a, b) =>
      b.daysPastDue - a.daysPastDue || a.reference.localeCompare(b.reference)));

  protected readonly enRetard = computed(() =>
    this.contrats().filter((c) => c.daysPastDue > 0).length);

  constructor() {
    void this.charger(0);
  }

  protected async charger(page: number): Promise<void> {
    this.chargement.set(true);
    this.refus.set(null);
    try {
      const resultat = await this.credit.contrats(
        this.config.legalEntityId(), page, this.taille());
      this.brut.set(resultat.contrats);
      this.page.set(resultat.page);
      this.precedent.set(resultat.precedent);
      this.suivant.set(resultat.suivant);
      this.lu.set(true);
    } catch (erreur) {
      this.refus.set(erreur instanceof RefusMetier
        ? erreur
        : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste.', String(erreur)));
      this.brut.set([]);
    } finally {
      this.chargement.set(false);
    }
  }

  protected ouvrir(contrat: Contrat): void {
    void this.router.navigate(['/credit/contrats', contrat.id]);
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
}
