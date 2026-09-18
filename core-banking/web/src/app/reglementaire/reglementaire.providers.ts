import { Provider, inject } from '@angular/core';
import { AppConfig } from '../core/config/runtime-config';
import { ReglementaireApi } from './reglementaire.api';
import { ReglementaireFactice } from './reglementaire.factice';
import { REGLEMENTAIRE, Reglementaire } from './reglementaire.port';

/**
 * Les fournisseurs de l'espace réglementaire, portés par sa coque plutôt que
 * par `app.config.ts` — même raison que partout : `app.config.ts` est chargé au
 * démarrage, et la source de démonstration n'a rien à faire dans le paquet
 * initial.
 */
export const PROVIDERS_REGLEMENTAIRE: readonly Provider[] = [
  ReglementaireApi,
  ReglementaireFactice,
  {
    provide: REGLEMENTAIRE,
    useFactory: (): Reglementaire => {
      const config = inject(AppConfig);
      return config.sourceDonnees() === 'api' ? inject(ReglementaireApi)
                                              : inject(ReglementaireFactice);
    },
  },
];
