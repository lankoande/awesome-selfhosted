import { EnvironmentProviders, Provider, inject, makeEnvironmentProviders } from '@angular/core';
import { AppConfig } from '../core/config/runtime-config';
import { SiegeApi } from './siege.api';
import { SiegeFactice } from './siege.factice';
import { SIEGE, Siege } from './siege.port';

export function provideSiege(): EnvironmentProviders {
  const providers: Provider[] = [
    SiegeApi,
    SiegeFactice,
    {
      provide: SIEGE,
      useFactory: (): Siege => {
        const config = inject(AppConfig);
        return config.valeur().sourceDonnees === 'factice' ? inject(SiegeFactice) : inject(SiegeApi);
      },
    },
  ];
  return makeEnvironmentProviders(providers);
}
