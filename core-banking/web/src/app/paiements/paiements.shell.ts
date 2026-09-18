import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Droits, OPERATION_PAR_ECRAN } from '../auth/habilitations';
import { PROVIDERS_CLIENTS } from '../clients/clients.providers';
import { PROVIDERS_PAIEMENTS } from './paiements.providers';

/**
 * L'espace des moyens de paiement et sa barre d'écrans.
 *
 * Trois files de travail, dans l'ordre où un service de compensation les
 * regarde : les **virements émis** partent le jour même et coûtent cher quand
 * ils traînent ; les **remises** immobilisent l'argent d'un client jusqu'au
 * règlement ; les **prélèvements** se dénouent au rythme des échéances.
 */
@Component({
  selector: 'cb-paiements',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  // Le référentiel client vient avec : un ordre part d'un compte, et un compte
  // se choisit par son numéro ou son titulaire, pas par son identifiant
  // technique. Les deux coques sont chargées paresseusement.
  providers: [...PROVIDERS_PAIEMENTS, ...PROVIDERS_CLIENTS],
  template: `
    <nav class="ecrans" aria-label="Écrans des moyens de paiement">
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
export class PaiementsShell {
  private readonly droits = inject(Droits);

  protected readonly visibles = computed(() =>
    this.ecrans.filter((ecran) =>
      this.droits.peut(OPERATION_PAR_ECRAN['paiements/' + ecran.chemin])),
  );

  private readonly ecrans = [
    { chemin: 'virements', libelle: 'Virements émis' },
    { chemin: 'remises', libelle: 'Remises de chèques' },
    { chemin: 'prelevements', libelle: 'Prélèvements' },
  ];
}
