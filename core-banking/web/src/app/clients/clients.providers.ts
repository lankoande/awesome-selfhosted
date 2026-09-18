import { EnvironmentProviders, makeEnvironmentProviders } from '@angular/core';
import { AppConfig } from '../core/config/runtime-config';
import { ClientsApi } from './clients.api';
import { ClientsFactice } from './clients.factice';
import { CLIENTS } from './clients.port';

/** L'implémentation suit `sourceDonnees`, comme partout ailleurs. */
export function provideClients(): EnvironmentProviders {
  return makeEnvironmentProviders([
    ClientsApi,
    ClientsFactice,
    {
      provide: CLIENTS,
      useFactory: (config: AppConfig, api: ClientsApi, factice: ClientsFactice) =>
        (config.sourceDonnees() === 'api' ? api : factice),
      deps: [AppConfig, ClientsApi, ClientsFactice],
    },
  ]);
}
