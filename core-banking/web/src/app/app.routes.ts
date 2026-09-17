import { Routes } from '@angular/router';

/**
 * Routes paresseuses par espace : le guichet n'embarque pas les écrans de
 * paramétrage, et le siège n'embarque pas le billetage.
 *
 * La barre du haut porte les espaces ; les écrans d'un espace sont ses routes
 * filles, sous la barre de l'espace.
 */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'guichet' },
  {
    path: 'guichet',
    loadComponent: () => import('./guichet/guichet.shell').then((m) => m.GuichetShell),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'versement' },
      {
        path: 'versement',
        title: "Guichet — versement d'espèces",
        loadComponent: () => import('./guichet/versement/versement.page').then((m) => m.Versement),
      },
      {
        path: 'retrait',
        title: "Guichet — retrait d'espèces",
        loadComponent: () => import('./guichet/retrait/retrait.page').then((m) => m.Retrait),
      },
      {
        path: 'virement',
        title: 'Guichet — virement interne',
        loadComponent: () => import('./guichet/virement/virement.page').then((m) => m.Virement),
      },
      {
        path: 'releve',
        title: 'Guichet — relevé de compte',
        loadComponent: () => import('./guichet/releve/releve.page').then((m) => m.Releve),
      },
      {
        path: 'caisse',
        title: 'Guichet — arrêté de caisse',
        loadComponent: () => import('./caisse/arrete/arrete-caisse.page').then((m) => m.ArreteDeCaisse),
      },
    ],
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
  { path: '**', redirectTo: 'guichet' },
];
