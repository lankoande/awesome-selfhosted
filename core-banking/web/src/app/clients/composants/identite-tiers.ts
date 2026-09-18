import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { CbStateBadge } from '../../ui';
import {
  LIBELLE_KYC, LIBELLE_STATUT, LIBELLE_NATURE, LIBELLE_NIVEAU, LIBELLE_RISQUE, Tiers,
  etatDuKyc, etatDuTiers,
} from '../modele/clients.modele';

/**
 * Le bandeau d'identité d'un client.
 *
 * Il répond aux trois questions qu'on se pose avant toute chose : qui, dans
 * quel état, et jusqu'à quand la connaissance client vaut. Le reste — segment,
 * pays, date — est du contexte et se lit en second.
 */
@Component({
  selector: 'cb-identite-tiers',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbStateBadge],
  template: `
    <div class="qui">
      <h2>{{ tiers().displayName }}</h2>
      <p class="sous">
        <span class="cb-mono">{{ tiers().reference }}</span>
        · {{ nature() }}
        @if (tiers().segment) { · {{ tiers().segment }} }
        @if (tiers().countryCode) { · {{ tiers().countryCode }} }
      </p>
    </div>

    <div class="etats">
      <div class="etat">
        <span class="titre">Client</span>
        <cb-state-badge [etat]="etatTiers()" [mot]="libelleStatut()" />
        @if (tiers().statusReason) { <span class="motif">{{ tiers().statusReason }}</span> }
      </div>
      <div class="etat">
        <span class="titre">Connaissance client</span>
        <cb-state-badge [etat]="etatKyc()" [mot]="libelleKyc()" />
        <span class="detail">
          niveau {{ niveau().toLowerCase() }}
          @if (tiers().kycReviewDue) { · revue due le {{ jour(tiers().kycReviewDue!) }} }
        </span>
      </div>
      <div class="etat">
        <span class="titre">Risque</span>
        <span class="risque" [attr.data-risque]="tiers().riskRating">{{ risque() }}</span>
      </div>
    </div>
  `,
  styles: `
    :host {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-start;
      justify-content: space-between;
      gap: var(--cb-space-4);
      padding: var(--cb-space-4) var(--cb-gutter);
      border-bottom: var(--cb-border) solid var(--cb-rule);
      background: var(--cb-panel);
    }
    h2 { font-size: var(--cb-fs-lg); font-weight: 600; }
    .sous { margin-top: 2px; font-size: var(--cb-fs-sm); color: var(--cb-muted); }
    .etats { display: flex; flex-wrap: wrap; gap: var(--cb-space-5); }
    .etat { display: flex; flex-direction: column; gap: 4px; }
    .titre {
      font-size: var(--cb-fs-xs);
      letter-spacing: 0.08em;
      text-transform: uppercase;
      color: var(--cb-muted);
    }
    .detail, .motif { font-size: var(--cb-fs-xs); color: var(--cb-muted); }
    .motif { color: var(--cb-rejected); }
    /* Le risque n'est pas un état d'opération : il a sa propre échelle, et la
       confondre avec un badge d'état ferait lire « élevé » comme un refus. */
    .risque {
      font-size: var(--cb-fs-sm);
      font-weight: 600;
      color: var(--cb-ink-2);
    }
    .risque[data-risque='MEDIUM'] { color: var(--cb-pending); }
    .risque[data-risque='HIGH'] { color: var(--cb-rejected); }
    @media (max-width: 767px) {
      :host { padding: var(--cb-space-3) var(--cb-gutter); }
      .etats { gap: var(--cb-space-4); }
    }
  `,
})
export class IdentiteTiers {
  readonly tiers = input.required<Tiers>();

  protected readonly nature = computed(() => LIBELLE_NATURE[this.tiers().kind]);
  protected readonly niveau = computed(() => LIBELLE_NIVEAU[this.tiers().kycLevel]);
  protected readonly risque = computed(() => LIBELLE_RISQUE[this.tiers().riskRating]);
  protected readonly libelleKyc = computed(() => LIBELLE_KYC[this.tiers().kycStatus]);
  protected readonly libelleStatut = computed(() => LIBELLE_STATUT[this.tiers().status]);
  protected readonly etatTiers = computed(() => etatDuTiers(this.tiers().status));
  protected readonly etatKyc = computed(() => etatDuKyc(this.tiers().kycStatus));

  protected jour(iso: string): string {
    const [a, m, j] = iso.split('-');
    return `${j}/${m}/${a}`;
  }
}
