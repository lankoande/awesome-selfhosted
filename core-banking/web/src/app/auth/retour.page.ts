import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { Router } from '@angular/router';
import { CbActivity, CbButton, CbNotice } from '../ui';
import { AUTHENTIFICATION } from './auth.port';

/**
 * Le retour du fournisseur d'identité.
 *
 * L'écran ne montre rien d'utile : il échange le code contre un jeton et s'en
 * va. Il existe surtout pour que l'échec ait une page — une redirection qui
 * échoue en silence laisse l'opérateur devant une application vide sans savoir
 * pourquoi.
 */
@Component({
  selector: 'cb-auth-retour',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CbActivity, CbButton, CbNotice],
  template: `
    <div class="ecran">
      @if (echec()) {
        <cb-notice nature="refus" titre="La connexion n'a pas abouti" code="AUTORISATION_REFUSEE">
          Le fournisseur d'identité n'a pas rendu d'autorisation valable. Cela arrive quand la page est
          restée ouverte trop longtemps, ou quand la redirection a été reprise depuis un autre onglet.
          <ng-container actions>
            <button type="button" cbButton="primaire" (click)="reessayer()">Se connecter</button>
          </ng-container>
        </cb-notice>
      } @else {
        <cb-activity enCours libelle="Ouverture de la session…" />
      }
    </div>
  `,
  styles: `
    .ecran { display: flex; flex-direction: column; gap: var(--cb-space-4); padding: var(--cb-space-6) var(--cb-gutter); max-width: 72ch; }
  `,
})
export class AuthRetour {
  private readonly authentification = inject(AUTHENTIFICATION);
  private readonly router = inject(Router);
  private readonly document = inject(DOCUMENT);

  protected readonly echec = signal(false);

  constructor() {
    void this.traiter();
  }

  private async traiter(): Promise<void> {
    const parametres = new URLSearchParams(this.document.location.search);
    try {
      const etat = await this.authentification.retour(parametres);
      if (etat === 'ouverte') {
        await this.router.navigateByUrl('/');
        return;
      }
    } catch {
      /* l'écran le dit ci-dessous */
    }
    this.echec.set(true);
  }

  protected async reessayer(): Promise<void> {
    await this.authentification.ouvrir();
  }
}
