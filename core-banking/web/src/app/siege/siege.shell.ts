import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Droits, OPERATION_PAR_ECRAN } from '../auth/habilitations';
import { PROVIDERS_SIEGE } from './siege.providers';

/** L'espace siège et sa barre d'écrans, sur le modèle du guichet. */
@Component({
  selector: 'cb-siege',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  // Les fournisseurs de l'espace vivent ici : la coque est chargée
  // paresseusement, donc l'adaptateur et la source de démonstration ne pèsent
  // pas sur le paquet initial.
  providers: [...PROVIDERS_SIEGE],
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
  private readonly droits = inject(Droits);

  /**
   * On ne propose pas une porte qu'on sait fermée. Tant que le socle n'expose
   * pas les opérations autorisées, on les montre toutes : cacher au hasard
   * ferait croire qu'un écran n'existe pas.
   */
  protected readonly visibles = computed(() =>
    this.ecrans.filter((ecran) =>
      this.droits.peut(OPERATION_PAR_ECRAN['siege/' + ecran.chemin]),
    ),
  );

  /**
   * L'exploitation d'abord, le paramétrage ensuite.
   *
   * L'ordre n'est pas alphabétique : la fin de journée se lance tous les jours,
   * l'identité de l'établissement se corrige une fois par an. Ce qui se touche
   * souvent vient devant.
   */
  private readonly ecrans = [
    { chemin: 'exploitation', libelle: 'Fin de journée' },
    { chemin: 'balance', libelle: 'Balance générale' },
    { chemin: 'etablissement', libelle: 'Établissement' },
    { chemin: 'produits', libelle: 'Produits' },
    { chemin: 'numerotation', libelle: 'Numérotation' },
  ];
}
