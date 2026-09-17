import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AUTHENTIFICATION } from '../auth/auth.port';
import { OPERATION_PAR_ECRAN, autorise } from '../auth/habilitations';

/** L'espace siège et sa barre d'écrans, sur le modèle du guichet. */
@Component({
  selector: 'cb-siege',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <nav class="ecrans" aria-label="Écrans du siège">
      @for (ecran of visibles(); track ecran.chemin) {
        <a [routerLink]="ecran.chemin" routerLinkActive="actif">{{ ecran.libelle }}</a>
      }
    </nav>
    <router-outlet />
  `,
  styles: `
    :host { display: block; }
    .ecrans {
      display: flex;
      gap: var(--cb-space-1);
      padding: 0 var(--cb-gutter);
      background: var(--cb-panel);
      border-bottom: var(--cb-border) solid var(--cb-rule);
      overflow-x: auto;
      scrollbar-width: none;
    }
    .ecrans::-webkit-scrollbar { display: none; }
    .ecrans a {
      flex-shrink: 0;
      display: inline-flex;
      align-items: center;
      height: var(--cb-tap-h);
      padding: 0 var(--cb-space-3);
      border-bottom: 2px solid transparent;
      color: var(--cb-muted);
      font-size: var(--cb-fs-md);
      text-decoration: none;
    }
    .ecrans a:hover { color: var(--cb-ink); }
    .ecrans a.actif { color: var(--cb-ink); font-weight: 600; border-bottom-color: var(--cb-accent); }
  `,
})
export class SiegeShell {
  private readonly authentification = inject(AUTHENTIFICATION);

  /**
   * On ne propose pas une porte qu'on sait fermée. Tant que le socle n'expose
   * pas les opérations autorisées, on les montre toutes : cacher au hasard
   * ferait croire qu'un écran n'existe pas.
   */
  protected readonly visibles = computed(() =>
    this.ecrans.filter((ecran) =>
      autorise(this.authentification.habilitations(), OPERATION_PAR_ECRAN['siege/' + ecran.chemin]),
    ),
  );

  private readonly ecrans = [
    { chemin: 'exploitation', libelle: 'Fin de journée' },
    { chemin: 'balance', libelle: 'Balance générale' },
  ];
}
