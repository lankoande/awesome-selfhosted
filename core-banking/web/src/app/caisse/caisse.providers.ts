import { Provider, inject } from '@angular/core';
import { AppConfig } from '../core/config/runtime-config';
import { CaisseApi } from './caisse.api';
import { CaisseFactice } from './caisse.factice';
import { CAISSE, Caisse } from './caisse.port';

/**
 * Les fournisseurs de la caisse, portés par la coque du guichet — c'est là que l'arrêté de caisse se fait plutôt que par
 * `app.config.ts`.
 *
 * `app.config.ts` est chargé au démarrage : tout ce qu'il importe entre dans le
 * paquet initial — y compris la source de démonstration, que la production
 * n'ouvrira jamais. Accrochés à la coque, chargée paresseusement, ils
 * partent dans le morceau de l'espace.
 *
 * Le choix de la source se lit dans `config.json`, pas dans une variable de
 * compilation : une démonstration se bascule sur un socle réel en changeant une
 * ligne de déploiement, sans reconstruire.
 */
export const PROVIDERS_CAISSE: readonly Provider[] = [
  CaisseApi,
  CaisseFactice,
  {
    provide: CAISSE,
    useFactory: (): Caisse => {
      const config = inject(AppConfig);
      return config.sourceDonnees() === 'api' ? inject(CaisseApi) : inject(CaisseFactice);
    },
  },
];
