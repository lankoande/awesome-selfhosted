import { Provider, inject } from '@angular/core';
import { AppConfig } from '../core/config/runtime-config';
import { SiegeApi } from './siege.api';
import { SiegeFactice } from './siege.factice';
import { SIEGE, Siege } from './siege.port';

/**
 * Les fournisseurs de l'espace siège, portés par la coque du siège plutôt que par
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
export const PROVIDERS_SIEGE: readonly Provider[] = [
  SiegeApi,
  SiegeFactice,
  {
    provide: SIEGE,
    useFactory: (): Siege => {
      const config = inject(AppConfig);
      return config.sourceDonnees() === 'api' ? inject(SiegeApi) : inject(SiegeFactice);
    },
  },
];
