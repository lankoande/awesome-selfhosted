import { ApplicationConfig, inject, provideAppInitializer, provideBrowserGlobalErrorListeners } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { routes } from './app.routes';
import { AppConfig } from './core/config/runtime-config';
import { Apparence } from './core/apparence/apparence';
import { provideGuichet } from './guichet/guichet.providers';
import { provideValidation } from './validation/validation.providers';
import { provideCaisse } from './caisse/caisse.providers';
import { provideSiege } from './siege/siege.providers';
import { provideAuthentification } from './auth/auth.providers';
import { AUTHENTIFICATION } from './auth/auth.port';
import { jetonInterceptor } from './auth/jeton.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(
      routes,
      withComponentInputBinding(),
      withInMemoryScrolling({ anchorScrolling: 'enabled', scrollPositionRestoration: 'enabled' }),
    ),
    provideHttpClient(withFetch(), withInterceptors([jetonInterceptor])),
    provideAuthentification(),
    provideGuichet(),
    provideValidation(),
    provideCaisse(),
    provideSiege(),
    // La configuration de déploiement est lue avant le premier écran : l'accent
    // de la banque ne doit pas apparaître après coup.
    //
    // Les deux services sont résolus AVANT le premier `await` : passé celui-ci,
    // on n'est plus dans un contexte d'injection et `inject()` lève NG0203.
    provideAppInitializer(() => {
      const config = inject(AppConfig);
      const apparence = inject(Apparence);
      const authentification = inject(AUTHENTIFICATION);
      const document = inject(DOCUMENT);
      return config.charger().then(() => {
        apparence.alignerSurConfig();
        // Le retour d'autorisation a sa propre route : il ne faut surtout pas
        // relancer une autorisation par-dessus, on perdrait le code.
        if (document.location.pathname.endsWith('/auth/retour')) return;
        return authentification.reprendre().then(() => undefined);
      });
    }),
  ],
};
