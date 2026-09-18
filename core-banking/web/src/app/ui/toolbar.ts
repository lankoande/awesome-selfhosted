import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * Barre d'outils d'un écran. Le seul endroit où un bouton à icône seule est
 * toléré. `<ng-content select="[fin]">` est poussé à droite.
 *
 * Elle **passe à la ligne** plutôt que de déborder. Un titre long et deux
 * actions ne tiennent pas sur 390 px : sans le retour à la ligne, la page
 * entière gagne une barre de défilement horizontale, et c'est le poste tout
 * entier qui se décale — pour un bouton.
 */
@Component({
  selector: 'cb-toolbar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ng-content />
    <span class="ressort"></span>
    <ng-content select="[fin]" />
  `,
  styles: `
    :host {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cb-space-2);
      min-height: var(--cb-tap-h);
    }
    .ressort { flex: 1 1 auto; }
  `,
})
export class CbToolbar {}
