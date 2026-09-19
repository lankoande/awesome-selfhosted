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
      {
        path: 'etablissement',
        title: 'Siège — établissement',
        loadComponent: () => import('./siege/etablissement/etablissement.page')
          .then((m) => m.EtablissementPage),
      },
      {
        path: 'produits',
        title: 'Siège — produits',
        loadComponent: () => import('./siege/produits/produits.page').then((m) => m.Produits),
      },
      {
        path: 'agences',
        title: 'Siège — agences',
        loadComponent: () => import('./siege/agences/agences.page').then((m) => m.Agences),
      },
      {
        path: 'calendrier',
        title: 'Siège — calendrier et conditions de banque',
        loadComponent: () => import('./siege/calendrier/calendrier.page')
          .then((m) => m.Calendrier),
      },
      {
        path: 'schemas',
        title: 'Siège — schémas comptables',
        loadComponent: () => import('./siege/schemas/schemas.page').then((m) => m.Schemas),
      },
      {
        path: 'numerotation',
        title: 'Siège — numérotation',
        loadComponent: () => import('./siege/numerotation/numerotation.page')
          .then((m) => m.NumerotationPage),
      },
    ],
  },
  {
    path: 'paiements',
    loadComponent: () => import('./paiements/paiements.shell').then((m) => m.PaiementsShell),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'virements' },
      {
        path: 'virements',
        title: 'Paiements — virements émis',
        loadComponent: () => import('./paiements/virements/virements.page')
          .then((m) => m.VirementsEmis),
      },
      {
        path: 'remises',
        title: 'Paiements — remises de chèques',
        loadComponent: () => import('./paiements/remises/remises.page')
          .then((m) => m.RemisesCheques),
      },
      {
        path: 'prelevements',
        title: 'Paiements — prélèvements',
        loadComponent: () => import('./paiements/prelevements/prelevements.page')
          .then((m) => m.Prelevements),
      },
      {
        path: 'compte',
        title: 'Paiements — chèques et mandats d\'un compte',
        loadComponent: () => import('./paiements/compte/compte.page')
          .then((m) => m.MoyensDePaiementDuCompte),
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
        path: 'contrats/:id/perte',
        title: 'Crédit — passage en perte',
        loadComponent: () => import('./credit/perte/perte.page').then((m) => m.PerteCredit),
      },
      {
        path: 'contrats/:id',
        title: 'Crédit — contrat',
        loadComponent: () => import('./credit/contrat/contrat.page').then((m) => m.ContratCredit),
      },
    ],
  },
  {
    path: 'conformite',
    loadComponent: () => import('./conformite/conformite.shell').then((m) => m.ConformiteShell),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'alertes' },
      {
        path: 'alertes',
        title: 'Conformité — alertes',
        loadComponent: () => import('./conformite/alertes/alertes.page').then((m) => m.Alertes),
      },
      {
        path: 'declarations',
        title: 'Conformité — déclarations de soupçon',
        loadComponent: () =>
          import('./conformite/declarations/declarations.page').then((m) => m.Declarations),
      },
      {
        path: 'scenarios',
        title: 'Conformité — scénarios de surveillance',
        loadComponent: () =>
          import('./conformite/scenarios/scenarios.page').then((m) => m.Scenarios),
      },
      // Le chemin fixe d'abord : sinon `:id` avalerait les autres écrans.
      {
        path: 'alertes/:id',
        title: "Conformité — dossier d'alerte",
        loadComponent: () => import('./conformite/alerte/alerte.page').then((m) => m.DossierAlerte),
      },
    ],
  },
  {
    path: 'reglementaire',
    loadComponent: () =>
      import('./reglementaire/reglementaire.shell').then((m) => m.ReglementaireShell),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'echeances' },
      {
        path: 'echeances',
        title: 'Réglementaire — échéances',
        loadComponent: () =>
          import('./reglementaire/echeances/echeances.page').then((m) => m.Echeances),
      },
      {
        path: 'etats',
        title: 'Réglementaire — états produits',
        loadComponent: () => import('./reglementaire/etats/etats.page').then((m) => m.Etats),
      },
      {
        path: 'declarations',
        title: 'Réglementaire — catalogue',
        loadComponent: () =>
          import('./reglementaire/declarations/declarations.page')
            .then((m) => m.CatalogueReglementaire),
      },
      {
        path: 'fiscalite',
        title: 'Réglementaire — fiscalité',
        loadComponent: () =>
          import('./reglementaire/fiscalite/fiscalite.page').then((m) => m.Fiscalite),
      },
      // Le chemin fixe d'abord : sinon `:id` avalerait les autres écrans.
      {
        path: 'etats/:id',
        title: "Réglementaire — détail d'un état",
        loadComponent: () => import('./reglementaire/etat/etat.page').then((m) => m.DetailEtat),
      },
    ],
  },
  {
    path: 'validation',
    loadChildren: () => import('./validation/validation.routes').then((m) => m.ROUTES_VALIDATION),
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
