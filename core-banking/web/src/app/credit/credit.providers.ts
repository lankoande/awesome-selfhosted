import { EnvironmentProviders, makeEnvironmentProviders } from '@angular/core';
import { AppConfig } from '../core/config/runtime-config';
import { CreditApi } from './credit.api';
import { CreditFactice } from './credit.factice';
import { CREDIT } from './credit.port';

/** L'implémentation suit `sourceDonnees`, comme partout ailleurs. */
export function provideCredit(): EnvironmentProviders {
  return makeEnvironmentProviders([
    CreditApi,
    CreditFactice,
    {
      provide: CREDIT,
      useFactory: (config: AppConfig, api: CreditApi, factice: CreditFactice) =>
        (config.sourceDonnees() === 'api' ? api : factice),
      deps: [AppConfig, CreditApi, CreditFactice],
    },
  ]);
}
