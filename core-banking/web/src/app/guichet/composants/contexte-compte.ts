import { DIALOG_DATA, DialogRef } from '@angular/cdk/dialog';
import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { CbAmount, CbDrawerEntete, CbSection } from '../../ui';
import { ContexteCompte, SoldeCompte } from '../modele/guichet.modele';

/** Le tiroir de contexte du compte : ce qu'on consulte sans quitter la saisie. */
@Component({
  selector: 'cb-contexte-compte',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbAmount, CbDrawerEntete, CbSection],
  template: `
    <cb-drawer-entete titre="Contexte du compte" [indication]="donnees.contexte.intitule" (fermer)="reference.close()" />
    <div class="corps">
      <cb-section titre="Compte">
        <dl>
          <dt>Numéro</dt><dd class="cb-mono">{{ donnees.contexte.reference }}</dd>
          <dt>Intitulé</dt><dd>{{ donnees.contexte.intitule }}</dd>
          <dt>Client</dt><dd class="cb-mono">{{ donnees.contexte.partyId ?? '—' }}</dd>
          <dt>Produit</dt><dd>{{ donnees.contexte.produit }}</dd>
          <dt>Ouvert le</dt><dd class="cb-mono">{{ donnees.contexte.ouvertLe ?? '—' }}</dd>
          <dt>Statut</dt><dd>{{ donnees.solde.status }}</dd>
          <dt>Devise</dt><dd class="cb-mono">{{ donnees.solde.currency }}</dd>
        </dl>
      </cb-section>

      <cb-section titre="Soldes" [indication]="'au ' + donnees.solde.asOf">
        <dl>
          <dt>Comptable</dt>
          <dd><cb-amount [valeur]="donnees.solde.current.amount" [devise]="donnees.solde.currency" /></dd>
          <dt>Disponible</dt>
          <dd><cb-amount [valeur]="donnees.solde.available.amount" [devise]="donnees.solde.currency" ton="accent" /></dd>
        </dl>
      </cb-section>

      <cb-section titre="Blocages" [indication]="donnees.contexte.blocages.length + ' actif(s)'">
        @for (blocage of donnees.contexte.blocages; track blocage.id) {
          <div class="blocage">
            <span class="motif">{{ blocage.motif }}</span>
            <cb-amount [valeur]="blocage.montant.amount" [devise]="blocage.montant.currency" />
            <span class="detail">{{ blocage.reference }} · posé le {{ blocage.poseLe }}</span>
            <span class="detail">Retenu sur le disponible, pas sur le solde.</span>
          </div>
        } @empty {
          <p class="vide">Aucun blocage.</p>
        }
      </cb-section>

      @if (donnees.contexte.lacunes.length) {
        <cb-section titre="Non exposé par le contrat">
          <ul class="lacunes">
            @for (lacune of donnees.contexte.lacunes; track lacune) {
              <li>{{ lacune }}</li>
            }
          </ul>
        </cb-section>
      }
    </div>
  `,
  styles: `
    :host { display: flex; flex-direction: column; width: 100%; overflow: hidden; }
    .corps { display: flex; flex-direction: column; gap: var(--cb-space-5); padding: var(--cb-space-4); overflow: auto; }
    dl { display: grid; grid-template-columns: 104px minmax(0, 1fr); gap: var(--cb-space-1) var(--cb-space-3); margin: 0; }
    dt { color: var(--cb-muted); font-size: var(--cb-fs-sm); }
    dd { margin: 0; overflow-wrap: anywhere; }
    .blocage {
      display: flex;
      flex-direction: column;
      gap: 3px;
      padding: var(--cb-space-2) var(--cb-space-3);
      border: var(--cb-border) solid var(--cb-pending-line);
      border-top-width: 3px;
      border-top-color: var(--cb-pending);
      border-radius: var(--cb-radius);
      background: var(--cb-pending-soft);
    }
    .motif { color: var(--cb-pending); font-weight: 600; font-size: var(--cb-fs-md); }
    .detail { font-size: var(--cb-fs-sm); color: var(--cb-muted); }
    .vide { color: var(--cb-muted); font-size: var(--cb-fs-sm); }
    .lacunes { margin: 0; padding-left: 18px; color: var(--cb-muted); font-size: var(--cb-fs-sm); line-height: var(--cb-lh-base); }
  `,
})
export class ContexteCompteTiroir {
  readonly reference = inject<DialogRef<void, ContexteCompteTiroir>>(DialogRef);
  readonly donnees = inject<{ contexte: ContexteCompte; solde: SoldeCompte }>(DIALOG_DATA);
}
