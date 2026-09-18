import { Provider, inject } from '@angular/core';
import { AppConfig } from '../core/config/runtime-config';
import { ClientsApi } from './clients.api';
import { ClientsFactice } from './clients.factice';
import { CLIENTS, Clients } from './clients.port';

/**
 * Les fournisseurs de l'espace client, portés par la coque des clients plutôt que par
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
export const PROVIDERS_CLIENTS: readonly Provider[] = [
  ClientsApi,
  ClientsFactice,
  {
    provide: CLIENTS,
    useFactory: (): Clients => {
      const config = inject(AppConfig);
      return config.sourceDonnees() === 'api' ? inject(ClientsApi) : inject(ClientsFactice);
    },
  },
];
