import { Routes } from '@angular/router';

/**
 * Routes paresseuses par espace : le guichet n'embarque pas les écrans de
 * paramétrage, et le siège n'embarque pas le billetage.
 */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'atelier' },
  {
    path: 'atelier',
    title: 'Atelier — socle visuel',
    loadComponent: () => import('./atelier/atelier').then((m) => m.Atelier),
  },
  { path: '**', redirectTo: 'atelier' },
];
