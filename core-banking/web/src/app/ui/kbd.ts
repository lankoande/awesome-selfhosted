import { ChangeDetectionStrategy, Component } from '@angular/core';

/** Raccourci clavier affiché. On optimise la répétition, pas la découverte. */
@Component({
  selector: 'cb-kbd',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<kbd><ng-content /></kbd>`,
  styles: `
    :host { display: inline-flex; }
    kbd {
      padding: 2px 6px;
      border: var(--cb-border) solid var(--cb-rule-strong);
      border-radius: var(--cb-radius);
      background: var(--cb-card);
      color: var(--cb-ink-2);
      font-family: var(--cb-font-mono);
      font-size: var(--cb-fs-xs);
      line-height: 1.4;
      white-space: nowrap;
    }
  `,
})
export class CbKbd {}
