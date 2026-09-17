import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { AppConfig } from '../../core/config/runtime-config';
import { RefusMetier } from '../../guichet/modele/guichet.modele';
import {
  CbActivity, CbAmount, CbButton, CbDateInput, CbField, CbInput,
  CbNotice, CbPagination, CbSection, CbTable, CbToolbar,
} from '../../ui';
import {
  FiltreBalance, LIBELLE_KIND, LIBELLE_NATURE, LigneBalance, TotauxBalance,
} from '../modele/siege.modele';
import { SIEGE } from '../siege.port';

/**
 * Siège — balance générale.
 *
 * **L'équilibre est l'information de tête, pas une colonne de plus.** Une
 * balance qui ne s'équilibre pas veut dire que le registre ne se tient pas, et
 * rien de ce qu'on en tire — état financier, déclaration réglementaire — ne
 * vaut tant que ce n'est pas réglé. L'écran le dit en premier, avant les
 * chiffres.
 *
 * Les totaux sont rendus **par devise**. Une balance ne s'additionne pas entre
 * devises : le faire produirait un nombre qui ne veut rien dire.
 */
@Component({
  selector: 'cb-balance',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CbActivity, CbAmount, CbButton, CbDateInput, CbField, CbInput,
    CbNotice, CbPagination, CbSection, CbTable, CbToolbar,
  ],
  templateUrl: './balance.page.html',
  styleUrl: './balance.page.css',
})
export class Balance {
  private readonly siege = inject(SIEGE);
  private readonly config = inject(AppConfig);

  protected readonly du = signal<string | null>(null);
  protected readonly au = signal<string | null>(null);
  protected readonly kind = signal<LigneBalance['kind'] | null>(null);
  protected readonly page = signal(0);
  protected readonly taille = signal(50);
  protected readonly precedent = signal(false);
  protected readonly suivant = signal(false);

  protected readonly lignes = signal<readonly LigneBalance[]>([]);
  protected readonly totaux = signal<readonly TotauxBalance[]>([]);
  protected readonly chargement = signal(true);
  protected readonly refus = signal<RefusMetier | null>(null);

  protected readonly natures = Object.entries(LIBELLE_KIND) as [LigneBalance['kind'], string][];
  protected readonly libelleNature = LIBELLE_NATURE;

  /** Une seule devise déséquilibrée suffit à invalider la lecture. */
  protected readonly desequilibrees = computed(() => this.totaux().filter((t) => !t.balanced));
  protected readonly equilibree = computed(() => this.totaux().length > 0 && this.desequilibrees().length === 0);

  constructor() {
    void this.charger();
  }

  protected async charger(page = this.page()): Promise<void> {
    this.chargement.set(true);
    this.refus.set(null);
    const filtre: FiltreBalance = { du: this.du(), au: this.au(), kind: this.kind() };
    try {
      const [lignes, totaux] = await Promise.all([
        this.siege.balance(this.config.legalEntityId(), filtre, page, this.taille()),
        this.siege.totauxBalance(this.config.legalEntityId(), filtre),
      ]);
      this.lignes.set(lignes.lignes);
      this.page.set(lignes.numero);
      this.precedent.set(lignes.precedent);
      this.suivant.set(lignes.suivant);
      this.totaux.set(totaux);
    } catch (erreur) {
      this.lignes.set([]);
      this.totaux.set([]);
      this.refus.set(erreur instanceof RefusMetier
        ? erreur
        : new RefusMetier(0, 'ERREUR_POSTE', 'Erreur du poste de travail.', String(erreur)));
    } finally {
      this.chargement.set(false);
    }
  }

  protected async appliquer(): Promise<void> {
    this.page.set(0);
    await this.charger(0);
  }

  protected async changerPage(delta: number): Promise<void> {
    await this.charger(Math.max(0, this.page() + delta));
  }

  protected majKind(valeur: string): void {
    this.kind.set(valeur === '' ? null : (valeur as LigneBalance['kind']));
  }

  protected libelleKind(kind: LigneBalance['kind']): string {
    return LIBELLE_KIND[kind];
  }
}
