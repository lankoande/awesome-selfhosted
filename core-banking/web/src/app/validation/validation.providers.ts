import { EnvironmentProviders, Provider, inject, makeEnvironmentProviders } from '@angular/core';
import { AppConfig } from '../core/config/runtime-config';
import { ValidationApi } from './validation.api';
import { ValidationFactice } from './validation.factice';
import { VALIDATION, Validation } from './validation.port';

export function provideValidation(): EnvironmentProviders {
  const providers: Provider[] = [
    ValidationApi,
    ValidationFactice,
    {
      provide: VALIDATION,
      useFactory: (): Validation => {
        const config = inject(AppConfig);
        return config.valeur().sourceDonnees === 'factice' ? inject(ValidationFactice) : inject(ValidationApi);
      },
    },
  ];
  return makeEnvironmentProviders(providers);
}
