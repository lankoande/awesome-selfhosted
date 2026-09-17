import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { CbAmount, CbButton, CbStateBadge } from '../../ui';
import { ContexteCompte, SoldeCompte } from '../modele/guichet.modele';

/**
 * Le bandeau client. Il existe pour une raison précise : un guichetier qui
 * refuse un retrait sans pouvoir dire pourquoi, c'est un incident client.
 *
 * Donc le solde comptable ET le disponible, côte à côte, avec l'écart expliqué
 * juste à côté — pas dans un onglet, pas derrière une infobulle.
 */
@Component({
  selector: 'cb-bandeau-client',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbAmount, CbButton, CbStateBadge],
  template: `
    <div class="identite">
      <div class="nom-ligne">
        <h1>{{ contexte()?.intitule ?? '—' }}</h1>
        @if (bloque()) {
          <cb-state-badge etat="bloque" />
        }
      </div>
      <p class="sous-titre">
        <span class="cb-mono">{{ contexte()?.reference ?? '—' }}</span>
        @if (contexte()?.nature) { · {{ contexte()!.nature }} }
        @if (contexte()?.produit) { · {{ contexte()!.produit }} }
      </p>
    </div>

    <div class="marques">
      @if (contexte()?.kyc; as kyc) {
        <span class="marque" [class.marque--alerte]="kyc.etat !== 'À jour'">
          KYC {{ kyc.etat }}@if (kyc.revuLe) { · revu le {{ jourMois(kyc.revuLe) }} }
        </span>
      }
      @for (blocage of contexte()?.blocages ?? []; track blocage.id) {
        <span class="marque marque--alerte">
          {{ blocage.motif }} · <cb-amount [valeur]="blocage.montant.amount" [devise]="blocage.montant.currency" />
        </span>
      }
      @for (lacune of contexte()?.lacunes ?? []; track lacune) {
        <span class="marque marque--lacune">{{ lacune }}</span>
      }
    </div>

    <div class="soldes">
      <div class="figure">
        <span class="cb-caps">Solde comptable</span>
        <cb-amount [valeur]="solde()?.current?.amount ?? null" [devise]="solde()?.currency" />
      </div>
      <div class="figure">
        <span class="cb-caps">Disponible</span>
        <cb-amount [valeur]="solde()?.available?.amount ?? null" [devise]="solde()?.currency" ton="accent" />
      </div>
      <button type="button" cbButton="secondaire" (click)="ouvrirContexte.emit()">Contexte</button>
    </div>
  `,
  styles: `
    :host {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cb-space-3) var(--cb-space-5);
      padding: var(--cb-space-3) var(--cb-gutter);
      background: var(--cb-panel);
      border-bottom: var(--cb-border) solid var(--cb-rule-strong);
    }
    .identite { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
    .nom-ligne { display: flex; align-items: center; gap: var(--cb-space-3); }
    h1 { font-size: var(--cb-fs-lg); }
    .sous-titre { font-size: var(--cb-fs-sm); color: var(--cb-muted); }
    .marques { display: flex; flex-wrap: wrap; gap: var(--cb-space-2); min-width: 0; }
    .marque {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      height: 26px;
      padding: 0 var(--cb-space-2);
      border: var(--cb-border) solid var(--cb-rule-strong);
      border-radius: var(--cb-radius);
      background: var(--cb-card);
      color: var(--cb-ink-2);
      font-size: var(--cb-fs-sm);
      white-space: nowrap;
    }
    .marque--alerte { border-color: var(--cb-pending-line); background: var(--cb-pending-soft); color: var(--cb-pending); }
    .marque--lacune { border-style: dashed; color: var(--cb-muted); white-space: normal; height: auto; padding: 4px 8px; }
    .soldes { display: flex; align-items: center; gap: var(--cb-space-5); margin-left: auto; }
    .figure { display: flex; flex-direction: column; align-items: flex-end; gap: 2px; }
    .figure cb-amount { font-size: var(--cb-fs-lg); }

    @media (max-width: 1099px) {
      .soldes { margin-left: 0; gap: var(--cb-space-4); }
    }
    @media (max-width: 767px) {
      :host { gap: var(--cb-space-2); }
      .soldes { width: 100%; justify-content: space-between; }
      .figure cb-amount { font-size: var(--cb-fs-md); }
    }
  `,
})
export class BandeauClient {
  readonly contexte = input<ContexteCompte | null>(null);
  readonly solde = input<SoldeCompte | null>(null);
  readonly ouvrirContexte = output<void>();

  readonly bloque = computed(() => {
    const statut = this.solde()?.status;
    return statut !== undefined && statut !== 'ACTIVE';
  });

  jourMois(iso: string): string {
    const [annee, mois, jour] = iso.split('-');
    return `${jour}/${mois}/${annee}`;
  }
}
