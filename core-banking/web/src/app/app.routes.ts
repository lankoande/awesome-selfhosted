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
    path: 'siege',
    loadComponent: () => import('./siege/siege.shell').then((m) => m.SiegeShell),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'exploitation' },
      {
        path: 'exploitation',
        title: 'Siège — fin de journée',
        loadComponent: () => import('./siege/exploitation/exploitation.page').then((m) => m.Exploitation),
      },
      {
        path: 'balance',
        title: 'Siège — balance générale',
        loadComponent: () => import('./siege/balance/balance.page').then((m) => m.Balance),
      },
    ],
  },
  {
    path: 'clients',
    loadComponent: () => import('./clients/clients.shell').then((m) => m.ClientsShell),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'recherche' },
      {
        path: 'recherche',
        title: 'Clients — recherche',
        loadComponent: () => import('./clients/recherche/recherche.page').then((m) => m.RechercheClient),
      },
      {
        path: 'nouveau',
        title: 'Clients — nouveau client',
        loadComponent: () => import('./clients/nouveau/nouveau.page').then((m) => m.NouveauClient),
      },
      // Le dossier et l'ouverture viennent après les chemins fixes : sinon
      // `:id` avalerait « recherche » et « nouveau ».
      {
        path: ':id/compte',
        title: "Clients — ouverture de compte",
        loadComponent: () => import('./clients/ouverture/ouverture.page').then((m) => m.OuvertureCompte),
      },
      {
        path: ':id',
        title: 'Clients — dossier',
        loadComponent: () => import('./clients/dossier/dossier.page').then((m) => m.DossierClient),
      },
    ],
  },
  {
    path: 'credit',
    loadComponent: () => import('./credit/credit.shell').then((m) => m.CreditShell),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'demandes' },
      {
        path: 'demandes',
        title: 'Crédit — demandes',
        loadComponent: () => import('./credit/demandes/demandes.page').then((m) => m.DemandesCredit),
      },
      {
        path: 'portefeuille',
        title: 'Crédit — portefeuille',
        loadComponent: () =>
          import('./credit/portefeuille/portefeuille.page').then((m) => m.PortefeuilleCredit),
      },
      {
        path: 'nouvelle',
        title: 'Crédit — nouvelle demande',
        loadComponent: () =>
          import('./credit/nouvelle/nouvelle.page').then((m) => m.NouvelleDemandeCredit),
      },
      // Les chemins fixes d'abord : sinon `:id` avalerait « nouvelle ».
      {
        path: 'demandes/:id',
        title: 'Crédit — dossier',
        loadComponent: () =>
          import('./credit/dossier/dossier.page').then((m) => m.DossierCreditPage),
      },
      {
        path: 'contrats/:id',
        title: 'Crédit — contrat',
        loadComponent: () => import('./credit/contrat/contrat.page').then((m) => m.ContratCredit),
      },
    ],
  },
  {
    path: 'validation',
    title: 'File de validation',
    loadComponent: () => import('./validation/file/file-validation.page').then((m) => m.FileValidation),
  },
  {
    path: 'auth/retour',
    title: 'Connexion',
    loadComponent: () => import('./auth/retour.page').then((m) => m.AuthRetour),
  },
  {
    path: 'atelier',
    title: 'Atelier — socle visuel',
    loadComponent: () => import('./atelier/atelier').then((m) => m.Atelier),
  },
  { path: '**', redirectTo: 'guichet' },
];
