import { ChangeDetectionStrategy, Component, computed, input, numberAttribute } from '@angular/core';
import { formaterMontant } from '../core/format/montant';

/** Le ton est décidé par l'appelant : un découvert autorisé n'est pas une alarme. */
export type TonMontant = 'neutre' | 'accent' | 'positif' | 'negatif' | 'discret';

/**
 * Affichage d'un montant. Chasse fixe, chiffres tabulaires, devise en graisse
 * légère, alignement sur le dernier chiffre. L'échelle vient de la devise —
 * XOF n'a pas de décimale, et l'afficher avec deux zéros serait un mensonge.
 */
@Component({
  selector: 'cb-amount',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[attr.data-ton]': 'ton()' },
  template: `
    <span class="valeur">{{ texte() }}</span>
    @if (devise()) {
      <span class="devise">{{ devise() }}</span>
    }
  `,
  styles: `
    :host {
      display: inline-flex;
      align-items: baseline;
      gap: var(--cb-space-1);
      font-family: var(--cb-font-mono);
      font-variant-numeric: tabular-nums lining-nums;
      white-space: nowrap;
    }
    .valeur { font-weight: 500; }
    .devise { font-size: 0.85em; font-weight: 400; color: var(--cb-muted); }

    :host([data-ton='accent']) .valeur   { color: var(--cb-accent); }
    :host([data-ton='positif']) .valeur  { color: var(--cb-posted); }
    :host([data-ton='negatif']) .valeur  { color: var(--cb-rejected); }
    :host([data-ton='discret']) .valeur  { color: var(--cb-muted); }
  `,
})
export class CbAmount {
  readonly valeur = input.required<number | string | null>();
  readonly echelle = input(0, { transform: numberAttribute });
  readonly devise = input<string>();
  readonly signe = input<'auto' | 'toujours' | 'jamais'>('auto');
  readonly ton = input<TonMontant>('neutre');

  readonly texte = computed(() => {
    const valeur = this.valeur();
    if (valeur === null || valeur === undefined || valeur === '') return '—';
    return formaterMontant(valeur, this.echelle(), { signe: this.signe() });
  });
}
