import { Routes } from '@angular/router';
import { PROVIDERS_VALIDATION } from './validation.providers';

/**
 * La file de validation et ses fournisseurs.
 *
 * **Pourquoi un fichier de routes pour un seul écran ?** Parce que la file n'a
 * pas de coque où accrocher ses fournisseurs, et que les poser sur le composant
 * lui-même les rendrait impossibles à remplacer : des fournisseurs de composant
 * l'emportent sur ceux du banc de test, et le double de test ne serait plus
 * jamais vu. Sur la route, ils vivent dans l'injecteur d'environnement de la
 * route — remplaçables, et toujours hors du paquet initial puisque ce fichier
 * est chargé paresseusement.
 */
export const ROUTES_VALIDATION: Routes = [
  {
    path: '',
    title: 'File de validation',
    providers: [...PROVIDERS_VALIDATION],
    loadComponent: () => import('./file/file-validation.page').then((m) => m.FileValidation),
  },
];
