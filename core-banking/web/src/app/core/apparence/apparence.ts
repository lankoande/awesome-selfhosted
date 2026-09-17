import { Injectable, effect, inject, signal } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { AppConfig, Densite, Theme } from '../config/runtime-config';

const CLE_THEME = 'cb.theme';
const CLE_DENSITE = 'cb.densite';

/**
 * Thème et densité. Persistés par utilisateur et par poste : le guichetier qui
 * préfère le compact le retrouve demain matin, et l'agent qui fait les arrêtés
 * le soir garde son thème sombre.
 */
@Injectable({ providedIn: 'root' })
export class Apparence {
  private readonly document = inject(DOCUMENT);
  private readonly config = inject(AppConfig);

  readonly theme = signal<Theme>(this.lu(CLE_THEME, 'light') as Theme);
  readonly densite = signal<Densite>(this.lu(CLE_DENSITE, 'comfortable') as Densite);

  constructor() {
    effect(() => {
      const racine = this.document.documentElement;
      racine.dataset['theme'] = this.theme();
      racine.dataset['density'] = this.densite();
    });
  }

  /** Reprend les défauts du déploiement pour un poste qui n'a rien choisi. */
  alignerSurConfig(): void {
    const defauts = this.config.valeur().defauts;
    if (this.lu(CLE_THEME, '') === '') this.theme.set(defauts.theme);
    if (this.lu(CLE_DENSITE, '') === '') this.densite.set(defauts.densite);
  }

  basculerTheme(): void {
    this.poser(CLE_THEME, this.theme() === 'dark' ? 'light' : 'dark');
    this.theme.set(this.theme() === 'dark' ? 'light' : 'dark');
  }

  basculerDensite(): void {
    const suivante: Densite = this.densite() === 'compact' ? 'comfortable' : 'compact';
    this.poser(CLE_DENSITE, suivante);
    this.densite.set(suivante);
  }

  private lu(cle: string, defaut: string): string {
    try {
      return localStorage.getItem(cle) ?? defaut;
    } catch {
      return defaut;
    }
  }

  private poser(cle: string, valeur: string): void {
    try {
      localStorage.setItem(cle, valeur);
    } catch {
      /* navigation privée, stockage refusé : la préférence vaut pour la session */
    }
  }
}
