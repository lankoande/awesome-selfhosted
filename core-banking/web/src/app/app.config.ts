import { ApplicationConfig, inject, provideAppInitializer, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { routes } from './app.routes';
import { AppConfig } from './core/config/runtime-config';
import { Apparence } from './core/apparence/apparence';
import { provideGuichet } from './guichet/guichet.providers';
import { provideValidation } from './validation/validation.providers';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(
      routes,
      withComponentInputBinding(),
      withInMemoryScrolling({ anchorScrolling: 'enabled', scrollPositionRestoration: 'enabled' }),
    ),
    provideHttpClient(withFetch()),
    provideGuichet(),
    provideValidation(),
    // La configuration de déploiement est lue avant le premier écran : l'accent
    // de la banque ne doit pas apparaître après coup.
    //
    // Les deux services sont résolus AVANT le premier `await` : passé celui-ci,
    // on n'est plus dans un contexte d'injection et `inject()` lève NG0203.
    provideAppInitializer(() => {
      const config = inject(AppConfig);
      const apparence = inject(Apparence);
      return config.charger().then(() => apparence.alignerSurConfig());
    }),
  ],
};
