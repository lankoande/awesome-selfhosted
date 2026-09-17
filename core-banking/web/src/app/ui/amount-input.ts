import { ChangeDetectionStrategy, Component, booleanAttribute, computed, input, model, numberAttribute, signal } from '@angular/core';
import { formaterMontant, lireMontant } from '../core/format/montant';
import { CbInput } from './input';

/**
 * Saisie d'un montant. Le champ connaît l'échelle de sa devise : il refuse une
 * décimale de trop plutôt que de l'arrondir en silence — un arrondi discret,
 * c'est un écart de caisse le soir.
 *
 * On formate à la sortie du champ, jamais pendant la frappe : reformater sous
 * les doigts déplace le curseur et fait retaper.
 */
@Component({
  selector: 'cb-amount-input',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbInput],
  template: `
    <input
      cbInput
      nombre
      type="text"
      inputmode="decimal"
      autocomplete="off"
      [id]="champId()"
      [value]="affiche()"
      [disabled]="desactive()"
      [invalide]="!!erreur()"
      [attr.aria-describedby]="erreur() ? champId() + '-err' : null"
      (input)="saisir($any($event.target).value)"
      (blur)="quitter()"
    />
    @if (devise()) {
      <span class="devise">{{ devise() }}</span>
    }
    @if (erreur()) {
      <span class="cb-visually-hidden" [id]="champId() + '-err'" role="alert">{{ erreur() }}</span>
    }
  `,
  styles: `
    :host { display: flex; align-items: center; gap: var(--cb-space-2); min-width: 0; }
    input { flex: 1 1 auto; }
    .devise { font-size: var(--cb-fs-sm); color: var(--cb-muted); white-space: nowrap; }
  `,
})
export class CbAmountInput {
  readonly valeur = model<number | null>(null);
  readonly champId = input.required<string>();
  readonly echelle = input(0, { transform: numberAttribute });
  readonly devise = input<string>();
  readonly desactive = input(false, { transform: booleanAttribute });

  private readonly brouillon = signal<string | null>(null);
  private readonly invalide = signal(false);

  readonly affiche = computed(() => {
    const brouillon = this.brouillon();
    if (brouillon !== null) return brouillon;
    const valeur = this.valeur();
    return valeur === null ? '' : formaterMontant(valeur, this.echelle());
  });

  readonly erreur = computed(() =>
    this.invalide() ? `Montant invalide pour une devise à ${this.echelle()} décimale(s).` : null,
  );

  saisir(texte: string): void {
    this.brouillon.set(texte);
    const nombre = lireMontant(texte);
    if (nombre === null) {
      this.invalide.set(texte.trim() !== '');
      this.valeur.set(null);
      return;
    }
    this.invalide.set(!this.echelleRespectee(nombre));
    this.valeur.set(nombre);
  }

  /** À la sortie du champ, on réécrit proprement ce qui a été compris. */
  quitter(): void {
    this.brouillon.set(null);
  }

  private echelleRespectee(nombre: number): boolean {
    const facteur = 10 ** this.echelle();
    return Math.abs(nombre * facteur - Math.round(nombre * facteur)) < 1e-9;
  }
}
