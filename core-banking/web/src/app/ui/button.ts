import { Directive, ElementRef, booleanAttribute, inject, input, isDevMode } from '@angular/core';

export type VarianteBouton = 'primaire' | 'secondaire' | 'discret' | 'danger';

/**
 * Bouton. Posé sur un `<button>` ou un `<a href>` réel — jamais sur un `<div>` :
 * la tabulation et le lecteur d'écran ne rattrapent pas ce choix-là.
 *
 * Quatre variantes, et pas une de plus. Un écran n'a qu'une action primaire.
 */
@Directive({
  selector: 'button[cbButton], a[cbButton]',
  host: {
    class: 'cb-btn',
    '[class.cb-btn--primaire]': "variante() === 'primaire'",
    '[class.cb-btn--secondaire]': "variante() === 'secondaire'",
    '[class.cb-btn--discret]': "variante() === 'discret'",
    '[class.cb-btn--danger]': "variante() === 'danger'",
    '[class.cb-btn--icone]': 'icone()',
  },
})
export class CbButton {
  readonly variante = input<VarianteBouton, string | VarianteBouton>('secondaire', {
    alias: 'cbButton',
    transform: (valeur) => (valeur ? (valeur as VarianteBouton) : 'secondaire'),
  });

  /** Icône seule : réservé aux barres d'outils, et jamais sans `aria-label`. */
  readonly icone = input(false, { transform: booleanAttribute });

  constructor() {
    const hote = inject(ElementRef<HTMLElement>).nativeElement as HTMLElement;
    if (isDevMode()) {
      queueMicrotask(() => {
        if (this.icone() && !hote.getAttribute('aria-label') && !hote.getAttribute('aria-labelledby')) {
          console.warn('[cbButton] bouton à icône seule sans aria-label', hote);
        }
      });
    }
  }
}
