import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, from, switchMap, throwError } from 'rxjs';
import { Socle } from '../api/socle';
import { AUTHENTIFICATION } from './auth.port';

/**
 * Le jeton n'est posé que sur les appels à l'API du socle.
 *
 * Ce n'est pas une précaution de style : un jeton envoyé à une autre origine
 * est un jeton donné. Le fournisseur d'identité lui-même n'en reçoit pas —
 * ses échanges se signent autrement.
 */


/**
 * Un 401 ne signifie pas « reconnecte-toi » : le plus souvent le jeton vient
 * d'expirer en pleine saisie. On rafraîchit une fois, on rejoue une fois, et si
 * ça ne passe toujours pas, on laisse remonter — l'écran saura le dire mieux
 * que l'intercepteur.
 */
export const jetonInterceptor: HttpInterceptorFn = (requete, suivant) => {
  const socle = inject(Socle);
  const authentification = inject(AUTHENTIFICATION);

  // La règle « cette adresse est-elle celle du socle ? » est tenue à un seul
  // endroit. La dupliquer ici serait la laisser diverger de celle qui construit
  // les URLs — et un jeton porté à la mauvaise origine ne se rattrape pas.
  if (!socle.sien(requete.url)) return suivant(requete);

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
