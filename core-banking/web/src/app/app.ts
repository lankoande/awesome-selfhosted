import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Apparence } from './core/apparence/apparence';
import { AppConfig } from './core/config/runtime-config';
import { AUTHENTIFICATION } from './auth/auth.port';
import { espaceAutorise } from './auth/habilitations';
import { Verrou } from './auth/verrou';
import { Verrouillage } from './auth/verrouillage';
import { CbButton, CbKbd } from './ui';

@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Verrou, CbButton, CbKbd],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly config = inject(AppConfig);
  protected readonly apparence = inject(Apparence);
  protected readonly authentification = inject(AUTHENTIFICATION);

  constructor() {
    // Le service de verrouillage s'arme dès le premier écran : un poste qui ne
    // se verrouille qu'après la première opération ne se verrouille pas.
    inject(Verrouillage);
  }

  protected readonly porteur = computed(() => this.authentification.porteur());
  protected readonly verrouille = computed(() => this.authentification.etat === 'verrouillee');
  /** Un espace ne s'affiche pas si aucun de ses écrans n'est autorisé. */
  protected espaceVisible(prefixe: string): boolean {
    return espaceAutorise(this.authentification.habilitations(), prefixe);
  }

  protected verrouiller(): void {
    this.authentification.verrouiller();
  }
}
