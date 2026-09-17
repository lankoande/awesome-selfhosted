import { EnvironmentProviders, Provider, inject, makeEnvironmentProviders } from '@angular/core';
import { AppConfig } from '../core/config/runtime-config';
import { GuichetApi } from './guichet.api';
import { GuichetFactice } from './guichet.factice';
import { GUICHET, Guichet } from './guichet.port';

/**
 * Le choix de la source se lit dans `config.json`, pas dans une variable de
 * compilation : une démonstration se bascule sur un socle réel en changeant une
 * ligne de déploiement, sans reconstruire.
 */
export function provideGuichet(): EnvironmentProviders {
  const providers: Provider[] = [
    GuichetApi,
    GuichetFactice,
    {
      provide: GUICHET,
      useFactory: (): Guichet => {
        const config = inject(AppConfig);
        return config.valeur().sourceDonnees === 'factice' ? inject(GuichetFactice) : inject(GuichetApi);
      },
    },
  ];
  return makeEnvironmentProviders(providers);
}
