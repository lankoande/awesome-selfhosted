import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, inject } from '@angular/core';
import { AppConfig } from '../core/config/runtime-config';
import { AUTHENTIFICATION } from './auth.port';

const EVENEMENTS = ['pointerdown', 'keydown', 'wheel', 'focusin'] as const;

/**
 * Verrouillage sur inactivité.
 *
 * **On verrouille, on ne déconnecte pas.** Un guichetier qui perd sa saisie
 * parce qu'il est allé chercher un dossier cesse de verrouiller son poste — et
 * un poste jamais verrouillé est un poste ouvert à qui passe derrière le
 * comptoir. La saisie survit, l'écran se couvre, et on redemande de s'annoncer.
 *
 * Le compteur n'est pas remis à zéro par le temps qui passe mais par ce que
 * l'opérateur fait : frappe, clic, molette, prise de focus.
 */
@Injectable({ providedIn: 'root' })
export class Verrouillage {
  private readonly document = inject(DOCUMENT);
  private readonly config = inject(AppConfig);
  private readonly authentification = inject(AUTHENTIFICATION);

  private minuterie: ReturnType<typeof setTimeout> | null = null;
  private readonly reveil = () => this.rearmer();

  constructor() {
    const fenetre = this.document.defaultView;
    if (fenetre) {
      for (const evenement of EVENEMENTS) {
        fenetre.addEventListener(evenement, this.reveil, { passive: true });
      }
      inject(DestroyRef).onDestroy(() => {
        for (const evenement of EVENEMENTS) fenetre.removeEventListener(evenement, this.reveil);
        this.eteindre();
      });
    }
    this.rearmer();
  }

  /** Repart de zéro. Appelé par toute activité de l'opérateur. */
  rearmer(): void {
    this.eteindre();
    const minutes = this.config.valeur().auth.verrouillageMinutes;
    if (minutes <= 0) return;
    this.minuterie = setTimeout(() => this.authentification.verrouiller(), minutes * 60_000);
  }

  private eteindre(): void {
    if (this.minuterie !== null) {
      clearTimeout(this.minuterie);
      this.minuterie = null;
    }
  }
}
