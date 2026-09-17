import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { CbButton } from '../ui';
import { AUTHENTIFICATION } from './auth.port';
import { Verrouillage } from './verrouillage';

/**
 * L'écran de verrouillage.
 *
 * Il couvre l'application sans la démonter : les composants dessous gardent
 * leur état, et la saisie en cours attend. C'est toute la différence entre
 * verrouiller et déconnecter, et c'est ce qui fait qu'un poste est réellement
 * verrouillé quand le guichetier s'absente.
 */
@Component({
  selector: 'cb-verrou',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbButton],
  host: { role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': 'cb-verrou-titre' },
  template: `
    <div class="panneau">
      <h2 id="cb-verrou-titre">Poste verrouillé</h2>
      <p class="qui">{{ nom() }}</p>
      <p class="explication">
        Votre saisie est intacte : elle vous attend derrière cet écran. Annoncez-vous pour reprendre.
      </p>
      <button type="button" cbButton="primaire" [disabled]="occupe()" (click)="deverrouiller()">
        Reprendre la main
      </button>
      @if (refus()) {
        <p class="refus">Le fournisseur d'identité n'a pas confirmé la session. Reconnectez-vous.</p>
      }
    </div>
  `,
  styles: `
    :host {
      position: fixed;
      inset: 0;
      z-index: 50;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: var(--cb-gutter);
      background: var(--cb-scrim);
      backdrop-filter: blur(3px);
    }
    .panneau {
      display: flex;
      flex-direction: column;
      gap: var(--cb-space-3);
      width: min(420px, 100%);
      padding: var(--cb-space-5);
      border: var(--cb-border) solid var(--cb-rule-strong);
      border-radius: var(--cb-radius);
      background: var(--cb-card);
      box-shadow: var(--cb-lift);
    }
    h2 { font-size: var(--cb-fs-lg); }
    .qui { font-family: var(--cb-font-mono); font-size: var(--cb-fs-sm); color: var(--cb-muted); }
    .explication { color: var(--cb-ink-2); line-height: var(--cb-lh-base); }
    .refus { color: var(--cb-rejected); font-size: var(--cb-fs-sm); }
  `,
})
export class Verrou {
  private readonly authentification = inject(AUTHENTIFICATION);
  private readonly verrouillage = inject(Verrouillage);

  protected readonly occupe = signal(false);
  protected readonly refus = signal(false);

  protected nom(): string {
    return this.authentification.porteur()?.nom ?? '';
  }

  protected async deverrouiller(): Promise<void> {
    this.occupe.set(true);
    this.refus.set(false);
    try {
      const repris = await this.authentification.deverrouiller();
      this.refus.set(!repris);
      if (repris) this.verrouillage.rearmer();
    } finally {
      this.occupe.set(false);
    }
  }
}
