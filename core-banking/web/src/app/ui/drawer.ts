import { Dialog, DialogRef } from '@angular/cdk/dialog';
import { createGlobalPositionStrategy } from '@angular/cdk/overlay';
import { ComponentType } from '@angular/cdk/portal';
import { ChangeDetectionStrategy, Component, Injectable, Injector, inject, input, output } from '@angular/core';
import { CbButton } from './button';

/**
 * Tiroir de contexte. Il glisse à droite, la liste reste visible, l'opérateur ne
 * perd jamais où il est. C'est le remplacement de la modale de travail : la
 * modale est réservée à une seule chose, confirmer l'irréversible.
 *
 * Le piège de focus, la fermeture par Échap et la restitution du focus viennent
 * du CDK — écrire cela soi-même, c'est là qu'on se trompe.
 *
 * Aujourd'hui ce tiroir est modal : le `Dialog` du CDK piège le focus, donc la
 * liste reste lisible mais pas manipulable. Le jour où un écran demandera de
 * travailler dans la liste tiroir ouvert, ce sera un `Overlay` sans piège de
 * focus — pas un `Dialog` auquel on retire le voile en faisant semblant.
 */
@Injectable({ providedIn: 'root' })
export class CbDrawer {
  private readonly dialog = inject(Dialog);
  private readonly injector = inject(Injector);

  ouvrir<C, D = unknown, R = unknown>(
    composant: ComponentType<C>,
    options: { donnees?: D; largeur?: string; etiquette?: string } = {},
  ): DialogRef<R, C> {
    return this.dialog.open<R, D, C>(composant, {
      data: options.donnees,
      width: options.largeur ?? '420px',
      height: '100%',
      maxWidth: '100vw',
      panelClass: 'cb-drawer-panel',
      backdropClass: 'cb-scrim',
      positionStrategy: createGlobalPositionStrategy(this.injector).right('0').top('0'),
      autoFocus: 'first-tabbable',
      restoreFocus: true,
      ariaLabel: options.etiquette,
    });
  }
}

/** En-tête d'un tiroir : titre, indication, fermeture. */
@Component({
  selector: 'cb-drawer-entete',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbButton],
  template: `
    <div class="texte">
      <h2>{{ titre() }}</h2>
      @if (indication()) {
        <span class="indication">{{ indication() }}</span>
      }
    </div>
    <button type="button" cbButton="discret" icone aria-label="Fermer le tiroir" (click)="fermer.emit()">
      <svg viewBox="0 0 16 16" width="15" height="15" fill="none" stroke="currentcolor" stroke-width="1.5" aria-hidden="true">
        <path d="M3.6 3.6l8.8 8.8M12.4 3.6l-8.8 8.8"></path>
      </svg>
    </button>
  `,
  styles: `
    :host {
      display: flex;
      align-items: flex-start;
      gap: var(--cb-space-3);
      padding: var(--cb-space-3) var(--cb-space-4);
      border-bottom: var(--cb-border) solid var(--cb-rule);
    }
    .texte { display: flex; flex-direction: column; gap: 2px; flex: 1 1 auto; min-width: 0; }
    h2 { font-size: var(--cb-fs-md); }
    .indication { font-size: var(--cb-fs-sm); color: var(--cb-muted); }
  `,
})
export class CbDrawerEntete {
  readonly titre = input.required<string>();
  readonly indication = input<string>();
  readonly fermer = output<void>();
}
