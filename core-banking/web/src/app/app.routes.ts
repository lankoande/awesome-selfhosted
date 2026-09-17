import { Routes } from '@angular/router';

/**
 * Routes paresseuses par espace : le guichet n'embarque pas les écrans de
 * paramétrage, et le siège n'embarque pas le billetage.
 */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'guichet/versement' },
  {
    path: 'guichet/versement',
    title: "Guichet — versement d'espèces",
    loadComponent: () => import('./guichet/versement/versement.page').then((m) => m.Versement),
  },
  {
    path: 'validation',
    title: 'File de validation',
    loadComponent: () => import('./validation/file/file-validation.page').then((m) => m.FileValidation),
  },
  {
    path: 'atelier',
    title: 'Atelier — socle visuel',
    loadComponent: () => import('./atelier/atelier').then((m) => m.Atelier),
  },
  { path: '**', redirectTo: 'guichet/versement' },
];
