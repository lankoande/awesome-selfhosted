import { DIALOG_DATA, Dialog, DialogRef } from '@angular/cdk/dialog';
import { ChangeDetectionStrategy, Component, Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { CbButton } from './button';

export interface DemandeConfirmation {
  readonly titre: string;
  readonly message: string;
  /** Ce que l'action fait vraiment, écrit à l'infinitif sur le bouton. */
  readonly confirmer: string;
  readonly annuler?: string;
  readonly danger?: boolean;
  /** Conséquence de l'action, en une phrase, quand elle ne se devine pas. */
  readonly consequence?: string;
}

/**
 * La seule modale autorisée : confirmer l'irréversible. Elle ne sert jamais à
 * travailler — pour cela, il y a le tiroir de contexte. Et jamais de modale
 * imbriquée.
 */
@Component({
  selector: 'cb-confirm-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbButton],
  host: { role: 'dialog', 'aria-modal': 'true', '[attr.aria-labelledby]': "'cb-confirm-titre'" },
  template: `
    <h2 id="cb-confirm-titre">{{ demande.titre }}</h2>
    <p class="message">{{ demande.message }}</p>
    @if (demande.consequence) {
      <p class="consequence">{{ demande.consequence }}</p>
    }
    <div class="actions">
      <button type="button" cbButton="secondaire" (click)="reference.close(false)">
        {{ demande.annuler ?? 'Annuler' }}
      </button>
      <button
        type="button"
        [cbButton]="demande.danger ? 'danger' : 'primaire'"
        (click)="reference.close(true)"
      >
        {{ demande.confirmer }}
      </button>
    </div>
  `,
  styles: `
    :host { display: block; width: min(440px, 92vw); padding: var(--cb-space-5); }
    h2 { font-size: var(--cb-fs-lg); margin-bottom: var(--cb-space-3); }
    .message { color: var(--cb-ink); line-height: var(--cb-lh-base); }
    .consequence {
      margin-top: var(--cb-space-3);
      padding: var(--cb-space-2) var(--cb-space-3);
      border: var(--cb-border) solid var(--cb-pending-line);
      border-top-width: 3px;
      border-top-color: var(--cb-pending);
      border-radius: var(--cb-radius);
      background: var(--cb-pending-soft);
      color: var(--cb-pending);
      font-size: var(--cb-fs-sm);
      line-height: var(--cb-lh-base);
    }
    .actions { display: flex; justify-content: flex-end; gap: var(--cb-space-2); margin-top: var(--cb-space-5); }
  `,
})
export class CbConfirmDialog {
  readonly demande = inject<DemandeConfirmation>(DIALOG_DATA);
  readonly reference = inject<DialogRef<boolean, CbConfirmDialog>>(DialogRef);
}

@Injectable({ providedIn: 'root' })
export class CbConfirm {
  private readonly dialog = inject(Dialog);

  /** Résout à `true` si l'opérateur confirme ; Échap et clic extérieur valent non. */
  async demander(demande: DemandeConfirmation): Promise<boolean> {
    const reference = this.dialog.open<boolean, DemandeConfirmation, CbConfirmDialog>(CbConfirmDialog, {
      data: demande,
      panelClass: 'cb-dialog-panel',
      backdropClass: 'cb-scrim',
      autoFocus: 'first-tabbable',
      restoreFocus: true,
    });
    return (await firstValueFrom(reference.closed)) === true;
  }
}
