import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, from, switchMap, throwError } from 'rxjs';
import { AppConfig } from '../core/config/runtime-config';
import { AUTHENTIFICATION } from './auth.port';

/**
 * Le jeton n'est posé que sur les appels à l'API du socle.
 *
 * Ce n'est pas une précaution de style : un jeton envoyé à une autre origine
 * est un jeton donné. Le fournisseur d'identité lui-même n'en reçoit pas —
 * ses échanges se signent autrement.
 */
function versLeSocle(requete: HttpRequest<unknown>, racine: string): boolean {
  return requete.url.startsWith(racine);
}

/**
 * Un 401 ne signifie pas « reconnecte-toi » : le plus souvent le jeton vient
 * d'expirer en pleine saisie. On rafraîchit une fois, on rejoue une fois, et si
 * ça ne passe toujours pas, on laisse remonter — l'écran saura le dire mieux
 * que l'intercepteur.
 */
export const jetonInterceptor: HttpInterceptorFn = (requete, suivant) => {
  const config = inject(AppConfig);
  const authentification = inject(AUTHENTIFICATION);
  const racine = config.apiBaseUrl().replace(/\/$/, '');

  if (!versLeSocle(requete, racine)) return suivant(requete);

  const porteuse = (jeton: string | null): HttpRequest<unknown> =>
    jeton ? requete.clone({ setHeaders: { Authorization: `Bearer ${jeton}` } }) : requete;

  return suivant(porteuse(authentification.jeton())).pipe(
    catchError((erreur: unknown) => {
      if (!(erreur instanceof HttpErrorResponse) || erreur.status !== 401) return throwError(() => erreur);
      return from(authentification.rafraichir()).pipe(
        switchMap((renouvele) =>
          renouvele ? suivant(porteuse(authentification.jeton())) : throwError(() => erreur),
        ),
      );
    }),
  );
};
