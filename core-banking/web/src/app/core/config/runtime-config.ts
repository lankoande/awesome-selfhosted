import { Injectable, computed, inject, signal } from '@angular/core';
import { DOCUMENT } from '@angular/common';

/**
 * Niveau 2 de la configurabilité : ce qui se règle par déploiement, sans
 * recompilation. Ni règle métier, ni contrôle de saisie — ceux-là sont du code.
 * Lu une fois au démarrage depuis `config.json`, servi à côté du bundle.
 */
export interface RuntimeConfig {
  /** Racine de l'API du socle, par exemple `https://api.banque.bf/v1`. */
  readonly apiBaseUrl: string;
  readonly locale: string;
  readonly banque: {
    readonly nom: string;
    readonly accent: string;
    readonly accentContraste: string;
    readonly accentSurvol: string;
    readonly logoUrl: string | null;
    /**
     * L'accent du thème sombre. Un accent lisible sur papier tiède ne l'est pas
     * toujours sur fond sombre : la banque le déclare, on ne le devine pas.
     * Absent, le thème sombre garde l'accent des tokens.
     */
    readonly sombre?: Accent | null;
  };
  readonly defauts: {
    readonly theme: Theme;
    readonly densite: Densite;
  };
  readonly affichage: {
    /** Taille des groupes de chiffres d'un numéro de compte affiché. */
    readonly groupeCompte: number;
    readonly deviseParDefaut: string;
  };
  readonly espaces: {
    readonly guichet: boolean;
    readonly siege: boolean;
  };
}

export interface Accent {
  readonly accent: string;
  readonly accentContraste: string;
  readonly accentSurvol: string;
}

export type Theme = 'light' | 'dark';
export type Densite = 'comfortable' | 'compact';

const ID_ACCENT = 'cb-accent';

/** Hexadécimal, ou une fonction de couleur CSS sans caractère capable de fermer la règle. */
const COULEUR = /^(#[0-9a-f]{3,8}|(rgb|rgba|hsl|hsla|lab|lch|oklab|oklch|color)\([0-9a-z%.,/ +-]*\))$/i;

/** Ce que l'application vaut sans `config.json` : elle démarre quand même. */
export const CONFIG_PAR_DEFAUT: RuntimeConfig = {
  apiBaseUrl: '/v1',
  locale: 'fr-FR',
  banque: {
    nom: 'Socle bancaire',
    accent: '#1f4e46',
    accentContraste: '#fbf9f4',
    accentSurvol: '#123a34',
    logoUrl: null,
    sombre: { accent: '#6fb3a4', accentContraste: '#10201d', accentSurvol: '#8cc7ba' },
  },
  defauts: { theme: 'light', densite: 'comfortable' },
  affichage: { groupeCompte: 4, deviseParDefaut: 'XOF' },
  espaces: { guichet: true, siege: true },
};

@Injectable({ providedIn: 'root' })
export class AppConfig {
  private readonly document = inject(DOCUMENT);
  private readonly etat = signal<RuntimeConfig>(CONFIG_PAR_DEFAUT);

  readonly valeur = this.etat.asReadonly();
  readonly apiBaseUrl = computed(() => this.etat().apiBaseUrl);
  readonly locale = computed(() => this.etat().locale);
  readonly banque = computed(() => this.etat().banque);

  /**
   * Charge `config.json`. Une configuration absente ou illisible n'empêche
   * jamais l'application de démarrer : le back-office d'une agence ne doit pas
   * tomber parce qu'un fichier de déploiement manque.
   */
  async charger(): Promise<void> {
    try {
      const reponse = await fetch('config.json', { cache: 'no-cache' });
      if (reponse.ok) {
        this.appliquer((await reponse.json()) as Partial<RuntimeConfig>);
        return;
      }
      console.warn('[config] config.json absent (%s), valeurs par défaut', reponse.status);
    } catch (erreur) {
      console.warn('[config] config.json illisible, valeurs par défaut', erreur);
    }
    this.peindreAccent(CONFIG_PAR_DEFAUT);
  }

  /** Fusion superficielle et typée : une clé absente garde sa valeur par défaut. */
  appliquer(partiel: Partial<RuntimeConfig>): void {
    const fusion: RuntimeConfig = {
      ...CONFIG_PAR_DEFAUT,
      ...partiel,
      banque: { ...CONFIG_PAR_DEFAUT.banque, ...(partiel.banque ?? {}) },
      defauts: { ...CONFIG_PAR_DEFAUT.defauts, ...(partiel.defauts ?? {}) },
      affichage: { ...CONFIG_PAR_DEFAUT.affichage, ...(partiel.affichage ?? {}) },
      espaces: { ...CONFIG_PAR_DEFAUT.espaces, ...(partiel.espaces ?? {}) },
    };
    this.etat.set(fusion);
    this.peindreAccent(fusion);
  }

  /**
   * L'accent de la banque écrase les tokens ; le reste de la palette ne bouge pas.
   *
   * On écrit une règle par thème plutôt qu'un style en ligne sur `<html>` : un
   * style en ligne l'emporte sur `[data-theme='dark']` et figerait l'accent
   * clair dans le thème sombre.
   */
  private peindreAccent(config: RuntimeConfig): void {
    const clair = this.regle(':root', config.banque);
    const sombre = config.banque.sombre ? this.regle(':root[data-theme=\'dark\']', config.banque.sombre) : '';

    const tete = this.document.head;
    let feuille = this.document.getElementById(ID_ACCENT) as HTMLStyleElement | null;
    if (!feuille) {
      feuille = this.document.createElement('style');
      feuille.id = ID_ACCENT;
      tete.appendChild(feuille);
    }
    feuille.textContent = [clair, sombre].filter(Boolean).join('\n');
  }

  private regle(selecteur: string, accent: Accent): string {
    const valeurs = [
      ['--cb-accent', accent.accent],
      ['--cb-accent-hover', accent.accentSurvol],
      ['--cb-accent-ink', accent.accentContraste],
    ] as const;
    const corps = valeurs
      .filter(([nom, valeur]) => this.couleurAcceptee(nom, valeur))
      .map(([nom, valeur]) => `${nom}:${valeur};`)
      .join('');
    return corps ? `${selecteur}{${corps}}` : '';
  }

  /**
   * `config.json` est du contenu de déploiement, pas du code : on ne recopie
   * dans une feuille de style que ce qui a la forme d'une couleur. Une valeur
   * refusée laisse le token par défaut en place plutôt que d'ouvrir une porte.
   */
  private couleurAcceptee(nom: string, valeur: string): boolean {
    if (typeof valeur === 'string' && COULEUR.test(valeur.trim())) return true;
    console.warn('[config] couleur refusée pour %s : %o', nom, valeur);
    return false;
  }
}
