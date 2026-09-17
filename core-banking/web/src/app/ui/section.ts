import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * Section d'écran : un sur-titre, un filet qui court jusqu'au bord, une
 * indication discrète à droite. Pas de carte flottante, pas d'ombre — le
 * découpage se lit par les filets, comme sur un état comptable.
 */
@Component({
  selector: 'cb-section',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="entete">
      <h3 class="titre">{{ titre() }}</h3>
      <span class="filet"></span>
      @if (indication()) {
        <span class="indication">{{ indication() }}</span>
      }
      <ng-content select="[entete]" />
    </div>
    <div class="corps"><ng-content /></div>
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: var(--cb-space-2); min-width: 0; }
    .entete { display: flex; align-items: baseline; gap: var(--cb-space-3); }
    .titre {
      font-size: var(--cb-fs-xs);
      font-weight: 600;
      letter-spacing: var(--cb-track-caps);
      text-transform: uppercase;
      color: var(--cb-ink-2);
    }
    .filet { flex: 1 1 auto; height: 1px; background: var(--cb-rule); }
    .indication { font-size: var(--cb-fs-xs); color: var(--cb-muted); }
    .corps { min-width: 0; }
  `,
})
export class CbSection {
  readonly titre = input.required<string>();
  readonly indication = input<string>();
}
