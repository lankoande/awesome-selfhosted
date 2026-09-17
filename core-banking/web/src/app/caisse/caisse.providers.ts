import { EnvironmentProviders, Provider, inject, makeEnvironmentProviders } from '@angular/core';
import { AppConfig } from '../core/config/runtime-config';
import { CaisseApi } from './caisse.api';
import { CaisseFactice } from './caisse.factice';
import { CAISSE, Caisse } from './caisse.port';

export function provideCaisse(): EnvironmentProviders {
  const providers: Provider[] = [
    CaisseApi,
    CaisseFactice,
    {
      provide: CAISSE,
      useFactory: (): Caisse => {
        const config = inject(AppConfig);
        return config.valeur().sourceDonnees === 'factice' ? inject(CaisseFactice) : inject(CaisseApi);
      },
    },
  ];
  return makeEnvironmentProviders(providers);
}
