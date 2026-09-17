import { ChangeDetectionStrategy, Component, booleanAttribute, computed, input, model, signal } from '@angular/core';
import { CbInput } from './input';

const MASQUE = 'jj/mm/aaaa';

/**
 * Date en saisie masquée. Pas de calendrier : un guichetier tape `15/03/2026`
 * plus vite qu'il ne clique, et écrire un sélecteur de date accessible et
 * localisé coûte deux à trois semaines pour un gain nul au guichet.
 *
 * La valeur exposée est une date ISO (`2026-03-15`) — ce que l'API attend.
 */
@Component({
  selector: 'cb-date-input',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbInput],
  template: `
    <input
      cbInput
      mono
      type="text"
      inputmode="numeric"
      autocomplete="off"
      [id]="champId()"
      [placeholder]="masque"
      [value]="affiche()"
      [disabled]="desactive()"
      [invalide]="!!erreur()"
      [attr.aria-describedby]="champId() + '-fmt'"
      (input)="saisir($any($event.target).value)"
    />
    <span class="cb-visually-hidden" [id]="champId() + '-fmt'">Format jour, mois, année, par exemple 15/03/2026</span>
    @if (erreur()) {
      <span class="erreur" role="alert">{{ erreur() }}</span>
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: var(--cb-space-1); min-width: 0; }
    /* 10 caractères plus les marges intérieures : « 17/09/2026 » ne se tronque pas. */
    input { width: 100%; max-width: 16ch; }
    .erreur { font-size: var(--cb-fs-sm); color: var(--cb-rejected); }
  `,
})
export class CbDateInput {
  /** Date ISO `AAAA-MM-JJ`, ou `null` tant que la saisie est incomplète. */
  readonly valeur = model<string | null>(null);
  readonly champId = input.required<string>();
  readonly desactive = input(false, { transform: booleanAttribute });

  readonly masque = MASQUE;

  private readonly brouillon = signal<string | null>(null);
  private readonly incomplete = signal(false);

  readonly affiche = computed(() => {
    const brouillon = this.brouillon();
    if (brouillon !== null) return brouillon;
    const iso = this.valeur();
    if (!iso) return '';
    const [annee, mois, jour] = iso.split('-');
    return `${jour}/${mois}/${annee}`;
  });

  readonly erreur = computed(() => (this.incomplete() ? 'Date inexistante.' : null));

  saisir(texte: string): void {
    const chiffres = texte.replace(/\D/g, '').slice(0, 8);
    const morceaux: string[] = [];
    if (chiffres.length > 0) morceaux.push(chiffres.slice(0, 2));
    if (chiffres.length > 2) morceaux.push(chiffres.slice(2, 4));
    if (chiffres.length > 4) morceaux.push(chiffres.slice(4, 8));
    this.brouillon.set(morceaux.join('/'));

    if (chiffres.length < 8) {
      this.incomplete.set(false);
      this.valeur.set(null);
      return;
    }

    const jour = Number(chiffres.slice(0, 2));
    const mois = Number(chiffres.slice(2, 4));
    const annee = Number(chiffres.slice(4, 8));
    const iso = `${String(annee).padStart(4, '0')}-${String(mois).padStart(2, '0')}-${String(jour).padStart(2, '0')}`;

    if (!this.dateReelle(annee, mois, jour)) {
      this.incomplete.set(true);
      this.valeur.set(null);
      return;
    }
    this.incomplete.set(false);
    this.brouillon.set(null);
    this.valeur.set(iso);
  }

  /** Le 31 février n'existe pas, et `new Date` le convertit en 3 mars sans rien dire. */
  private dateReelle(annee: number, mois: number, jour: number): boolean {
    if (mois < 1 || mois > 12 || jour < 1 || annee < 1900) return false;
    const date = new Date(Date.UTC(annee, mois - 1, jour));
    return date.getUTCFullYear() === annee && date.getUTCMonth() === mois - 1 && date.getUTCDate() === jour;
  }
}
