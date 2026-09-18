import { Injectable, inject } from '@angular/core';
import { AppConfig } from '../core/config/runtime-config';
import { Chemin, PREFIXE_SOCLE, chemin } from './routes';

/** Les variables d'un gabarit, lues dans le gabarit. */
type Variables<C extends string> =
  C extends `${string}{${infer V}}${infer Reste}` ? V | Variables<Reste> : never;
type Arguments<C extends string> =
  [Variables<C>] extends [never] ? [] : [Readonly<Record<Variables<C>, string>>];

/**
 * Où joindre le socle, et par quel chemin.
 *
 * Deux choses vivent à deux endroits différents, et c'est volontaire :
 *   — le **chemin** appartient au contrat (`/v1/entities/{id}/...`), il est
 *     généré depuis `openapi.json` et vérifié à la compilation ;
 *   — l'**origine** appartient au déploiement (`config.json`), parce que le
 *     socle peut être servi par la même origine que l'application ou par une
 *     autre. Les mélanger obligerait à redéployer pour changer de serveur.
 *
 * Cette classe remplace quatre copies de la même méthode `racine()`, une par
 * service d'accès : quatre endroits où corriger la même chose.
 */
@Injectable({ providedIn: 'root' })
export class Socle {
  private readonly config = inject(AppConfig);

  /** L'origine du socle, sans barre finale. Vide : la même que l'application. */
  origine(): string {
    return this.config.apiBaseUrl().replace(/\/$/, '');
  }

  /** L'adresse complète d'un appel, chemin vérifié contre le contrat. */
  url<C extends Chemin>(modele: C, ...valeurs: Arguments<C>): string {
    return this.origine() + chemin(modele, ...valeurs);
  }

  /**
   * Cette adresse est-elle celle du socle ?
   *
   * L'intercepteur s'en sert pour décider où porter le jeton. La règle est
   * stricte des deux côtés : origine déclarée, l'adresse doit la porter **et**
   * commencer par le préfixe du contrat ; origine vide, seule une adresse
   * relative commençant par ce préfixe compte. Un `startsWith('')` rendrait
   * vrai pour toute adresse — y compris celle d'un service tiers.
   */
  sien(url: string): boolean {
    return url.startsWith(`${this.origine()}${PREFIXE_SOCLE}/`);
  }
}
