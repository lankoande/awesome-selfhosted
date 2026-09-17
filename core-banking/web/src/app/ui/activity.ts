import { ChangeDetectionStrategy, Component, booleanAttribute, input } from '@angular/core';

/**
 * Travail en cours. Un filet de deux pixels, pas un disque qui tourne : le
 * mouvement dit « ça avance », il ne décore pas. Le libellé est annoncé une
 * fois, poliment, pour ne pas bavarder pendant une saisie.
 */
@Component({
  selector: 'cb-activity',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (enCours()) {
      <div class="piste" role="status" [attr.aria-label]="libelle()"><span class="curseur"></span></div>
      @if (libelle()) {
        <span class="libelle">{{ libelle() }}</span>
      }
    }
  `,
  styles: `
    :host { display: flex; align-items: center; gap: var(--cb-space-2); min-height: 2px; }
    .piste { position: relative; flex: 1 1 auto; height: 2px; overflow: hidden; background: var(--cb-rule); }
    .curseur {
      position: absolute;
      inset-block: 0;
      width: 32%;
      background: var(--cb-accent);
      animation: cb-glisse 1.1s var(--cb-ease) infinite;
    }
    .libelle { font-size: var(--cb-fs-sm); color: var(--cb-muted); white-space: nowrap; }
    @keyframes cb-glisse {
      from { transform: translateX(-110%); }
      to { transform: translateX(420%); }
    }
  `,
})
export class CbActivity {
  readonly enCours = input(false, { transform: booleanAttribute });
  readonly libelle = input<string>();
}
