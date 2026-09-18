import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * Une porte fermée, et la raison qui la ferme.
 *
 * <h2>Le défaut que cette primitive supprime</h2>
 *
 * Un bouton grisé sans explication envoie chercher la cause ailleurs — souvent
 * chez le voisin, parfois au support. Un bouton simplement absent fait pire :
 * l'opérateur croit que la fonction n'existe pas, et la banque découvre un an
 * plus tard qu'un geste prévu n'a jamais été fait.
 *
 * Là où l'écran **existe pour cet acte**, l'acte fermé se dit : il prend la
 * place du bouton, et porte sa raison en une phrase. Là où l'acte n'est qu'une
 * option parmi d'autres, il se cache — une liste d'impossibilités n'aide
 * personne.
 *
 * <h2>Ce que la phrase doit contenir</h2>
 *
 * Ce que le profil **fait**, pas seulement ce qu'il ne fait pas :
 * « votre profil ne transmet pas : il produit » oriente, « accès refusé »
 * laisse sur place.
 */
@Component({
  selector: 'cb-interdit',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg viewBox="0 0 16 16" width="13" height="13" fill="none" stroke="currentcolor"
         stroke-width="1.4" aria-hidden="true">
      <rect x="3.2" y="7" width="9.6" height="6.4" rx="1"></rect>
      <path d="M5.6 7V5a2.4 2.4 0 0 1 4.8 0v2"></path>
    </svg>
    <span><ng-content /></span>
  `,
  host: { role: 'note', '[attr.data-ton]': 'ton()' },
  styles: `
    :host {
      display: flex;
      align-items: flex-start;
      gap: var(--cb-space-2);
      color: var(--cb-muted);
      font-size: var(--cb-fs-sm);
      line-height: var(--cb-lh-base);
    }
    svg { flex: 0 0 auto; margin-top: 2px; }
    /* Un acte fermé par l'état du dossier — « déjà transmis » — n'est pas une
       anomalie ; un acte fermé par une anomalie, si. Le ton le dit sans crier. */
    :host([data-ton='attention']) { color: var(--cb-pending); }
  `,
})
export class CbInterdit {
  /** `neutre` par défaut ; `attention` quand c'est une anomalie qui ferme. */
  readonly ton = input<'neutre' | 'attention'>('neutre');
}
