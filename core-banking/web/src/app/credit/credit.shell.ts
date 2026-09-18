import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AUTHENTIFICATION } from '../auth/auth.port';
import { OPERATION_PAR_ECRAN, autorise } from '../auth/habilitations';
import { PROVIDERS_CREDIT } from './credit.providers';

/**
 * L'espace crédit et sa barre d'écrans.
 *
 * Trois entrées : les demandes en cours d'instruction, le portefeuille des
 * contrats, et le dépôt d'une nouvelle demande. Le dossier et le contrat ne
 * sont pas des destinations — on y arrive depuis une liste, jamais dans le vide.
 */
@Component({
  selector: 'cb-credit',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  // Les fournisseurs de l'espace vivent ici : la coque est chargée
  // paresseusement, donc l'adaptateur et la source de démonstration ne pèsent
  // pas sur le paquet initial.
  providers: [...PROVIDERS_CREDIT],
  template: `
    <nav class="ecrans" aria-label="Écrans du crédit">
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
export class CreditShell {
  private readonly authentification = inject(AUTHENTIFICATION);

  /** Même règle que les autres espaces : on ne propose pas une porte fermée. */
  protected readonly visibles = computed(() =>
    this.ecrans.filter((ecran) =>
      autorise(this.authentification.habilitations(), OPERATION_PAR_ECRAN['credit/' + ecran.chemin]),
    ),
  );

  private readonly ecrans = [
    { chemin: 'demandes', libelle: 'Demandes' },
    { chemin: 'portefeuille', libelle: 'Portefeuille' },
    { chemin: 'nouvelle', libelle: 'Nouvelle demande' },
  ];
}
