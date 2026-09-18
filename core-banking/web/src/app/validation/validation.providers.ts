import { Provider, inject } from '@angular/core';
import { AppConfig } from '../core/config/runtime-config';
import { ValidationApi } from './validation.api';
import { ValidationFactice } from './validation.factice';
import { VALIDATION, Validation } from './validation.port';

/**
 * Les fournisseurs de la file de validation, portés par l'écran de la file — elle n'a pas de coque, c'est une route seule plutôt que par
 * `app.config.ts`.
 *
 * `app.config.ts` est chargé au démarrage : tout ce qu'il importe entre dans le
 * paquet initial — y compris la source de démonstration, que la production
 * n'ouvrira jamais. Accrochés à l'écran, chargée paresseusement, ils
 * partent dans le morceau de l'espace.
 *
 * Le choix de la source se lit dans `config.json`, pas dans une variable de
 * compilation : une démonstration se bascule sur un socle réel en changeant une
 * ligne de déploiement, sans reconstruire.
 */
export const PROVIDERS_VALIDATION: readonly Provider[] = [
  ValidationApi,
  ValidationFactice,
  {
    provide: VALIDATION,
    useFactory: (): Validation => {
      const config = inject(AppConfig);
      return config.sourceDonnees() === 'api' ? inject(ValidationApi) : inject(ValidationFactice);
    },
  },
];
