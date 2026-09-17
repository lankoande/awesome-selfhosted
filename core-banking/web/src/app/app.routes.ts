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
    path: 'guichet/retrait',
    title: "Guichet — retrait d'espèces",
    loadComponent: () => import('./guichet/retrait/retrait.page').then((m) => m.Retrait),
  },
  {
    path: 'guichet/caisse',
    title: 'Guichet — arrêté de caisse',
    loadComponent: () => import('./caisse/arrete/arrete-caisse.page').then((m) => m.ArreteDeCaisse),
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
