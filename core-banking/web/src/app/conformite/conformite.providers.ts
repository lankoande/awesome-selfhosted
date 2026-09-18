import { Provider } from '@angular/core';
import { AppConfig } from '../core/config/runtime-config';
import { ConformiteApi } from './conformite.api';
import { ConformiteFactice } from './conformite.factice';
import { CONFORMITE } from './conformite.port';

/**
 * Les fournisseurs de l'espace conformité, portés par sa coque.
 *
 * **Ils ne sont pas déclarés dans `app.config.ts`**, contrairement aux autres
 * espaces, et c'est volontaire : `app.config.ts` est chargé au démarrage, donc
 * tout ce qu'il importe entre dans le paquet initial — y compris les données de
 * démonstration, que la production n'ouvrira jamais. En les accrochant à la
 * coque, qui est chargée paresseusement, ils partent dans le morceau de
 * l'espace. Le budget de taille du build l'a dit avant la production : l'ajout
 * de cet espace faisait franchir les 450 ko d'initial.
 *
 * L'implémentation suit `sourceDonnees`, comme partout ailleurs.
 */
export const PROVIDERS_CONFORMITE: readonly Provider[] = [
  ConformiteApi,
  ConformiteFactice,
  {
    provide: CONFORMITE,
    useFactory: (config: AppConfig, api: ConformiteApi, factice: ConformiteFactice) =>
      (config.sourceDonnees() === 'api' ? api : factice),
    deps: [AppConfig, ConformiteApi, ConformiteFactice],
  },
];
