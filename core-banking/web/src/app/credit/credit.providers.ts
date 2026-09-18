import { Provider, inject } from '@angular/core';
import { AppConfig } from '../core/config/runtime-config';
import { CreditApi } from './credit.api';
import { CreditFactice } from './credit.factice';
import { CREDIT, Credit } from './credit.port';

/**
 * Les fournisseurs de l'espace crédit, portés par la coque du crédit plutôt que par
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
export const PROVIDERS_CREDIT: readonly Provider[] = [
  CreditApi,
  CreditFactice,
  {
    provide: CREDIT,
    useFactory: (): Credit => {
      const config = inject(AppConfig);
      return config.sourceDonnees() === 'api' ? inject(CreditApi) : inject(CreditFactice);
    },
  },
];
