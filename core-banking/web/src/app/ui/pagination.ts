import { ChangeDetectionStrategy, Component, booleanAttribute, input, model, output } from '@angular/core';
import { CbButton } from './button';

/**
 * Pagination à curseur — celle que l'API sait faire. Pas de défilement infini
 * sur une liste comptable : un opérateur doit pouvoir dire « page 3 » à son chef.
 *
 * On n'annonce pas de total : l'API ne le compte pas, et inventer un nombre de
 * pages sur un journal de plusieurs millions de lignes serait cher et faux.
 */
@Component({
  selector: 'cb-pagination',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbButton],
  template: `
    <nav class="barre" aria-label="Pagination">
      <span class="position">{{ position() }}</span>
      <span class="ressort"></span>
      <label class="taille">
        <span class="cb-caps">Lignes</span>
        <select class="cb-input" (change)="taille.set(+$any($event.target).value)">
          @for (choix of tailles(); track choix) {
            <option [value]="choix" [selected]="choix === taille()">{{ choix }}</option>
          }
        </select>
      </label>
      <button type="button" cbButton="secondaire" [disabled]="!precedent()" (click)="reculer.emit()">Précédent</button>
      <button type="button" cbButton="secondaire" [disabled]="!suivant()" (click)="avancer.emit()">Suivant</button>
    </nav>
  `,
  styles: `
    :host { display: block; }
    .barre { display: flex; align-items: center; gap: var(--cb-space-2); padding-top: var(--cb-space-2); }
    .position { font-size: var(--cb-fs-sm); color: var(--cb-muted); }
    .ressort { flex: 1 1 auto; }
    .taille { display: flex; align-items: center; gap: var(--cb-space-2); }
    .taille select { width: auto; height: var(--cb-control-h); }
  `,
})
export class CbPagination {
  readonly position = input<string>('');
  readonly taille = model<number>(50);
  readonly tailles = input<readonly number[]>([25, 50, 100, 200]);
  readonly precedent = input(false, { transform: booleanAttribute });
  readonly suivant = input(false, { transform: booleanAttribute });

  readonly reculer = output<void>();
  readonly avancer = output<void>();
}
