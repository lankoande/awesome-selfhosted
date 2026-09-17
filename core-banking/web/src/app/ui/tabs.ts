import { ChangeDetectionStrategy, Component, ElementRef, inject, input, model } from '@angular/core';

export interface Onglet {
  readonly id: string;
  readonly libelle: string;
  /** Compteur discret — une file d'attente, un nombre d'anomalies. */
  readonly compte?: number;
}

/**
 * Onglets. Rôles ARIA complets et navigation aux flèches : c'est ce que les
 * lecteurs d'écran attendent d'un `tablist`, et ce qu'un back-office dense
 * demande au clavier. Un seul onglet est tabulable à la fois.
 */
@Component({
  selector: 'cb-tabs',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="piste" role="tablist" [attr.aria-label]="etiquette()" (keydown)="auClavier($event)">
      @for (onglet of onglets(); track onglet.id) {
        <button
          type="button"
          role="tab"
          class="onglet"
          [id]="'onglet-' + onglet.id"
          [class.actif]="onglet.id === actif()"
          [attr.aria-selected]="onglet.id === actif()"
          [attr.tabindex]="onglet.id === actif() ? 0 : -1"
          (click)="actif.set(onglet.id)"
        >
          {{ onglet.libelle }}
          @if (onglet.compte !== undefined) {
            <span class="compte">{{ onglet.compte }}</span>
          }
        </button>
      }
    </div>
  `,
  styles: `
    :host { display: block; }
    .piste { display: flex; gap: var(--cb-space-1); border-bottom: var(--cb-border) solid var(--cb-rule); }
    .onglet {
      display: inline-flex;
      align-items: center;
      gap: var(--cb-space-2);
      height: var(--cb-tap-h);
      padding: 0 var(--cb-space-3);
      margin-bottom: -1px;
      border: 0;
      border-bottom: 2px solid transparent;
      background: transparent;
      color: var(--cb-muted);
      font-family: inherit;
      font-size: var(--cb-fs-md);
      cursor: pointer;
      transition: color var(--cb-motion) var(--cb-ease), border-color var(--cb-motion) var(--cb-ease);
    }
    .onglet:hover { color: var(--cb-ink); }
    .onglet.actif { color: var(--cb-ink); font-weight: 600; border-bottom-color: var(--cb-accent); }
    .compte {
      min-width: 18px;
      padding: 0 5px;
      border-radius: var(--cb-radius);
      background: var(--cb-sunken);
      color: var(--cb-ink-2);
      font-family: var(--cb-font-mono);
      font-size: var(--cb-fs-xs);
      text-align: center;
    }
  `,
})
export class CbTabs {
  readonly onglets = input.required<readonly Onglet[]>();
  readonly actif = model.required<string>();
  readonly etiquette = input<string>('Onglets');

  private readonly hote = inject(ElementRef<HTMLElement>);

  auClavier(evenement: KeyboardEvent): void {
    const liste = this.onglets();
    const courant = liste.findIndex((onglet) => onglet.id === this.actif());
    if (courant < 0) return;

    let cible = courant;
    switch (evenement.key) {
      case 'ArrowRight': cible = (courant + 1) % liste.length; break;
      case 'ArrowLeft': cible = (courant - 1 + liste.length) % liste.length; break;
      case 'Home': cible = 0; break;
      case 'End': cible = liste.length - 1; break;
      default: return;
    }
    evenement.preventDefault();
    const choisi = liste[cible];
    if (!choisi) return;
    this.actif.set(choisi.id);
    const element = (this.hote.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
      `#onglet-${CSS.escape(choisi.id)}`,
    );
    element?.focus();
  }
}
