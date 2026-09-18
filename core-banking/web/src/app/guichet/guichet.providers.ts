import { Provider, inject } from '@angular/core';
import { AppConfig } from '../core/config/runtime-config';
import { GuichetApi } from './guichet.api';
import { GuichetFactice } from './guichet.factice';
import { GUICHET, Guichet } from './guichet.port';

/**
 * Les fournisseurs de l'espace guichet, portés par la coque du guichet plutôt que par
 * `app.config.ts`.
 *
 * `app.config.ts` est chargé au démarrage : tout ce qu'il importe entre dans le
 * paquet initial — y compris la source de démonstration, que la production
 * n'ouvrira jamais. Accrochés à la coque, chargée paresseusement, ils
 * partent dans le morceau de l'espace.
 *
 * Le choix de la source se lit dans `config.json`, pas dans une variable de
 * compilation : une démonstration se bascule sur un socle réel en changeant une
 * ligne de déploiement, sans reconstruire.
 */
export const PROVIDERS_GUICHET: readonly Provider[] = [
  GuichetApi,
  GuichetFactice,
  {
    provide: GUICHET,
    useFactory: (): Guichet => {
      const config = inject(AppConfig);
      return config.sourceDonnees() === 'api' ? inject(GuichetApi) : inject(GuichetFactice);
    },
  },
];
