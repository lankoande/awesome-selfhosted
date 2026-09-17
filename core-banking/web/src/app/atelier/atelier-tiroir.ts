import { DIALOG_DATA, DialogRef } from '@angular/cdk/dialog';
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { CbAmount, CbDrawerEntete, CbSection } from '../ui';

/** Contenu de démonstration du tiroir de contexte, pour l'atelier. */
@Component({
  selector: 'atelier-tiroir',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbAmount, CbDrawerEntete, CbSection],
  template: `
    <cb-drawer-entete titre="Contexte du compte" indication="épinglé" (fermer)="reference.close()" />
    <div class="corps">
      <cb-section titre="Compte">
        <dl>
          <dt>Numéro</dt><dd class="cb-mono">BF12 0010 2510 ···· 0417</dd>
          <dt>Intitulé</dt><dd>{{ donnees.intitule }}</dd>
          <dt>Devise</dt><dd class="cb-mono">XOF · 0 décimale</dd>
        </dl>
      </cb-section>
      <cb-section titre="Soldes">
        <dl>
          <dt>Comptable</dt><dd><cb-amount [valeur]="1240500" devise="XOF" /></dd>
          <dt>Disponible</dt><dd><cb-amount [valeur]="1190500" devise="XOF" ton="accent" /></dd>
        </dl>
      </cb-section>
    </div>
  `,
  styles: `
    :host { display: flex; flex-direction: column; width: 100%; overflow: hidden; }
    .corps { display: flex; flex-direction: column; gap: var(--cb-space-5); padding: var(--cb-space-4); overflow: auto; }
    dl { display: grid; grid-template-columns: 96px 1fr; gap: var(--cb-space-1) var(--cb-space-3); margin: 0; }
    dt { color: var(--cb-muted); font-size: var(--cb-fs-sm); }
    dd { margin: 0; }
  `,
})
export class AtelierTiroir {
  readonly reference = inject<DialogRef<void, AtelierTiroir>>(DialogRef);
  readonly donnees = inject<{ intitule: string }>(DIALOG_DATA);
}
