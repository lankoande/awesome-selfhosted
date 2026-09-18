import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Droits, OPERATION_PAR_ECRAN } from '../auth/habilitations';
import { PROVIDERS_CLIENTS } from './clients.providers';

/**
 * L'espace client et sa barre d'écrans.
 *
 * Deux entrées seulement, et c'est voulu : on cherche un client, ou on en crée
 * un. Le dossier et l'ouverture de compte ne sont pas des destinations — on y
 * arrive depuis un client, jamais dans le vide.
 */
@Component({
  selector: 'cb-clients',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  // Les fournisseurs de l'espace vivent ici : la coque est chargée
  // paresseusement, donc l'adaptateur et la source de démonstration ne pèsent
  // pas sur le paquet initial.
  providers: [...PROVIDERS_CLIENTS],
  template: `
    <nav class="ecrans" aria-label="Écrans du référentiel client">
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
export class ClientsShell {
  private readonly droits = inject(Droits);

  /** Même règle que les autres espaces : on ne propose pas une porte fermée. */
  protected readonly visibles = computed(() =>
    this.ecrans.filter((ecran) =>
      this.droits.peut(OPERATION_PAR_ECRAN['clients/' + ecran.chemin]),
    ),
  );

  private readonly ecrans = [
    { chemin: 'recherche', libelle: 'Rechercher un client' },
    { chemin: 'nouveau', libelle: 'Nouveau client' },
  ];
}
