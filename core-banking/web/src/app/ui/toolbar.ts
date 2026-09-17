import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * Barre d'outils d'un écran. Le seul endroit où un bouton à icône seule est
 * toléré. `<ng-content select="[fin]">` est poussé à droite.
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
      align-items: center;
      gap: var(--cb-space-2);
      min-height: var(--cb-tap-h);
    }
    .ressort { flex: 1 1 auto; }
  `,
})
export class CbToolbar {}
