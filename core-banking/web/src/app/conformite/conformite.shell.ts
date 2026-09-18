import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AUTHENTIFICATION } from '../auth/auth.port';
import { OPERATION_PAR_ECRAN, autorise } from '../auth/habilitations';
import { PROVIDERS_CONFORMITE } from './conformite.providers';

/**
 * L'espace conformité et sa barre d'écrans.
 *
 * Trois entrées : la file des alertes, les déclarations de soupçon, et les
 * scénarios de surveillance. Le dossier d'alerte n'est pas une destination —
 * on y arrive depuis la file, jamais dans le vide.
 *
 * **Cet espace ne communique avec aucun autre.** Rien n'y renvoie au dossier
 * client, et rien du dossier client n'y renvoie : informer la personne
 * surveillée est un délit, et une interface qui offrirait le chemin le rendrait
 * possible par inadvertance.
 */
@Component({
  selector: 'cb-conformite',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  // Les fournisseurs de l'espace vivent ici : la coque est chargée
  // paresseusement, donc l'adaptateur et la source de démonstration ne pèsent
  // pas sur le paquet initial.
  providers: [...PROVIDERS_CONFORMITE],
  template: `
    <nav class="ecrans" aria-label="Écrans de la conformité">
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
export class ConformiteShell {
  private readonly authentification = inject(AUTHENTIFICATION);

  /** Même règle que les autres espaces : on ne propose pas une porte fermée. */
  protected readonly visibles = computed(() =>
    this.ecrans.filter((ecran) =>
      autorise(this.authentification.habilitations(),
               OPERATION_PAR_ECRAN['conformite/' + ecran.chemin])),
  );

  private readonly ecrans = [
    { chemin: 'alertes', libelle: 'Alertes' },
    { chemin: 'declarations', libelle: 'Déclarations de soupçon' },
    { chemin: 'scenarios', libelle: 'Scénarios' },
  ];
}
