import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

export type NatureNotice = 'refus' | 'avertissement' | 'information' | 'succes';

/**
 * Le refus est un moment de design. Le socle écrit déjà des refus faits pour
 * être lus : la raison, la règle, et quoi faire. Ils méritent mieux qu'un toast
 * rouge qui disparaît — donc une place fixe dans l'écran, un code technique
 * lisible en chasse fixe, et des actions à portée de main.
 *
 * Un refus est annoncé (`role="alert"`) ; une information ne coupe pas la parole.
 */
@Component({
  selector: 'cb-notice',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[attr.data-nature]': 'nature()',
    '[attr.role]': "nature() === 'refus' ? 'alert' : 'status'",
  },
  template: `
    <svg class="glyphe" viewBox="0 0 20 20" fill="none" stroke="currentcolor" stroke-width="1.5" aria-hidden="true">
      <circle cx="10" cy="10" r="7.6"></circle>
      @if (nature() === 'succes') {
        <path d="M6.4 10.2l2.6 2.6 4.6-5.2"></path>
      } @else if (nature() === 'information') {
        <path d="M10 9.2v4.6M10 6.4v0.1"></path>
      } @else {
        <path d="M10 6.2v4.6M10 13.4v0.1"></path>
      }
    </svg>
    <div class="corps">
      <div class="ligne-titre">
        <span class="titre">{{ titre() }}</span>
        @if (code()) {
          <span class="code cb-mono">{{ code() }}</span>
        }
      </div>
      <div class="texte"><ng-content /></div>
      <div class="actions"><ng-content select="[actions]" /></div>
    </div>
  `,
  styles: `
    :host {
      display: flex;
      gap: var(--cb-space-3);
      padding: var(--cb-space-3) var(--cb-space-4);
      border: var(--cb-border) solid var(--cb-rule-strong);
      border-top-width: 3px;
      border-radius: var(--cb-radius);
      background: var(--cb-panel);
    }
    .glyphe { flex: 0 0 auto; width: 20px; height: 20px; margin-top: 1px; }
    .corps { display: flex; flex-direction: column; gap: var(--cb-space-2); min-width: 0; }
    .ligne-titre { display: flex; align-items: baseline; gap: var(--cb-space-3); flex-wrap: wrap; }
    .titre { font-size: var(--cb-fs-md); font-weight: 600; }
    .code { font-size: var(--cb-fs-xs); }
    .texte { color: var(--cb-ink); line-height: var(--cb-lh-base); max-width: 72ch; }
    .texte:empty, .actions:empty { display: none; }
    .actions { display: flex; flex-wrap: wrap; gap: var(--cb-space-2); margin-top: var(--cb-space-1); }

    :host([data-nature='refus'])         { border-color: var(--cb-rejected-line); border-top-color: var(--cb-rejected); background: var(--cb-rejected-soft); }
    :host([data-nature='refus']) .glyphe,
    :host([data-nature='refus']) .titre,
    :host([data-nature='refus']) .code   { color: var(--cb-rejected); }

    :host([data-nature='avertissement']) { border-color: var(--cb-pending-line); border-top-color: var(--cb-pending); background: var(--cb-pending-soft); }
    :host([data-nature='avertissement']) .glyphe,
    :host([data-nature='avertissement']) .titre,
    :host([data-nature='avertissement']) .code { color: var(--cb-pending); }

    :host([data-nature='succes'])        { border-color: var(--cb-posted-line); border-top-color: var(--cb-posted); background: var(--cb-posted-soft); }
    :host([data-nature='succes']) .glyphe,
    :host([data-nature='succes']) .titre,
    :host([data-nature='succes']) .code  { color: var(--cb-posted); }

    :host([data-nature='information'])   { border-color: var(--cb-rule-strong); border-top-color: var(--cb-ink-2); background: var(--cb-panel); }
    :host([data-nature='information']) .glyphe,
    :host([data-nature='information']) .code { color: var(--cb-muted); }
  `,
})
export class CbNotice {
  readonly nature = input<NatureNotice>('information');
  readonly titre = input.required<string>();
  /** Code technique du socle, montré tel quel : il sert au support. */
  readonly code = input<string>();
  readonly estRefus = computed(() => this.nature() === 'refus');
}
