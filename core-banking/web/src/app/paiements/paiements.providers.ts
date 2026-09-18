import { Provider, inject } from '@angular/core';
import { AppConfig } from '../core/config/runtime-config';
import { PaiementsApi } from './paiements.api';
import { PaiementsFactice } from './paiements.factice';
import { PAIEMENTS, Paiements } from './paiements.port';

/**
 * Les fournisseurs de l'espace des moyens de paiement, portés par sa coque : la
 * coque est chargée paresseusement, donc la source de démonstration ne pèse pas
 * sur le paquet initial.
 */
export const PROVIDERS_PAIEMENTS: readonly Provider[] = [
  PaiementsApi,
  PaiementsFactice,
  {
    provide: PAIEMENTS,
    useFactory: (): Paiements => {
      const config = inject(AppConfig);
      return config.sourceDonnees() === 'api' ? inject(PaiementsApi) : inject(PaiementsFactice);
    },
  },
];
