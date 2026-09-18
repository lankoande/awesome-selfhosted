import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Droits, OPERATION_PAR_ECRAN } from '../auth/habilitations';
import { PROVIDERS_CAISSE } from '../caisse/caisse.providers';
import { PROVIDERS_GUICHET } from './guichet.providers';

/**
 * L'espace guichet et sa barre d'écrans.
 *
 * La barre du haut porte les **espaces**, pas les écrans : à sept entrées, elle
 * tronquait déjà le dernier nom, et une entrée de menu tronquée fait
 * disparaître un écran. Les écrans d'un espace vivent dans l'espace.
 */
@Component({
  selector: 'cb-guichet',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  // La caisse vient avec : l'arrêté de caisse est un écran du guichet.
  // Les fournisseurs de l'espace vivent ici : la coque est chargée
  // paresseusement, donc l'adaptateur et la source de démonstration ne pèsent
  // pas sur le paquet initial.
  providers: [...PROVIDERS_GUICHET, ...PROVIDERS_CAISSE],
  template: `
    <nav class="ecrans" aria-label="Écrans du guichet">
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
export class GuichetShell {
  private readonly droits = inject(Droits);

  /**
   * On ne propose pas une porte qu'on sait fermée. Tant que le socle n'expose
   * pas les opérations autorisées, on les montre toutes : cacher au hasard
   * ferait croire qu'un écran n'existe pas.
   */
  protected readonly visibles = computed(() =>
    this.ecrans.filter((ecran) =>
      this.droits.peut(OPERATION_PAR_ECRAN['guichet/' + ecran.chemin]),
    ),
  );

  private readonly ecrans = [
    { chemin: 'versement', libelle: "Versement d'espèces" },
    { chemin: 'retrait', libelle: "Retrait d'espèces" },
    { chemin: 'virement', libelle: 'Virement interne' },
    { chemin: 'releve', libelle: 'Relevé de compte' },
    { chemin: 'caisse', libelle: 'Arrêté de caisse' },
  ];
}
