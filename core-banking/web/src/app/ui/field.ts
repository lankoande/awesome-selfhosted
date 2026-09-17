import { ChangeDetectionStrategy, Component, booleanAttribute, input } from '@angular/core';

/**
 * Étiquette, contrôle, aide, erreur. L'étiquette est un vrai `<label for>` :
 * cliquer dessus donne le focus au champ, et le lecteur d'écran l'annonce.
 *
 * L'erreur remplace l'aide — jamais les deux : à l'instant où ça refuse,
 * l'opérateur n'a pas besoin d'un rappel de format.
 */
@Component({
  selector: 'cb-field',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <label class="cb-caps" [attr.for]="pour()">
      {{ etiquette() }}@if (requis()) {<span class="requis" aria-hidden="true">*</span>}
    </label>
    <ng-content />
    @if (erreur()) {
      <p class="erreur" role="alert">{{ erreur() }}</p>
    } @else if (aide()) {
      <p class="aide">{{ aide() }}</p>
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: var(--cb-space-1); min-width: 0; }
    label { display: block; }
    .requis { margin-left: 2px; color: var(--cb-rejected); }
    .aide, .erreur { margin-top: var(--cb-space-1); font-size: var(--cb-fs-sm); line-height: var(--cb-lh-base); }
    .aide { color: var(--cb-muted); }
    .erreur { color: var(--cb-rejected); }
  `,
})
export class CbField {
  readonly etiquette = input.required<string>();
  /** `id` du contrôle projeté, pour lier l'étiquette. */
  readonly pour = input<string>();
  readonly aide = input<string>();
  readonly erreur = input<string>();
  readonly requis = input(false, { transform: booleanAttribute });
}
