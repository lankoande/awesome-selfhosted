import { Directive, booleanAttribute, input } from '@angular/core';

/**
 * Champ de saisie natif — `input`, `select`, `textarea`. La directive habille,
 * elle n'intercepte rien : la valeur reste celle de l'élément, donc les formulaires
 * Angular, l'autocomplétion du navigateur et le clavier fonctionnent tels quels.
 */
@Directive({
  selector: 'input[cbInput], select[cbInput], textarea[cbInput]',
  host: {
    class: 'cb-input',
    '[class.cb-input--mono]': 'mono()',
    '[class.cb-input--nombre]': 'nombre()',
    '[class.cb-input--invalide]': 'invalide()',
    '[attr.aria-invalid]': 'invalide() || null',
  },
})
export class CbInput {
  /** Références, numéros de compte, identifiants d'écriture. */
  readonly mono = input(false, { transform: booleanAttribute });
  /** Colonne de chiffres : chasse fixe, alignée à droite. */
  readonly nombre = input(false, { transform: booleanAttribute });
  readonly invalide = input(false, { transform: booleanAttribute });
}
