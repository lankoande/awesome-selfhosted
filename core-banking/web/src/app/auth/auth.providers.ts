import { EnvironmentProviders, Provider, inject, makeEnvironmentProviders } from '@angular/core';
import { AppConfig } from '../core/config/runtime-config';
import { AuthFactice } from './auth.factice';
import { AuthKeycloak } from './auth.keycloak';
import { AUTHENTIFICATION, Authentification } from './auth.port';

export function provideAuthentification(): EnvironmentProviders {
  const providers: Provider[] = [
    AuthKeycloak,
    AuthFactice,
    {
      provide: AUTHENTIFICATION,
      useFactory: (): Authentification => {
        const config = inject(AppConfig);
        return config.valeur().sourceDonnees === 'factice' ? inject(AuthFactice) : inject(AuthKeycloak);
      },
    },
  ];
  return makeEnvironmentProviders(providers);
}
