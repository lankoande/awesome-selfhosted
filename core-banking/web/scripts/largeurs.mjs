/**
 * Contrôle de largeurs : à chaque largeur supportée, aucune page ne doit
 * provoquer de défilement horizontal, et aucune cible cliquable ne doit passer
 * sous la hauteur tactile.
 *
 * Ce contrôle demande un navigateur ; il n'est donc pas dans `npm test`, qui
 * doit rester utilisable sans. Il se lance ainsi :
 *
 *   npm run build
 *   npm run servir &                 (repli SPA : les routes profondes existent)
 *   npm run check:largeurs -- http://127.0.0.1:8181/
 *
 * Playwright est une dépendance déclarée du projet : ce contrôle est une
 * barrière d'intégration au même titre que les budgets de taille, et une
 * barrière ne repose pas sur un paquet arrivé par transitivité — une mise à
 * jour sans rapport le retirerait, et le garde-fou tomberait en silence.
 */
const BASE = process.argv[2] ?? 'http://127.0.0.1:8181/';
// Les écrans qui prennent un identifiant sont visités sur un client de la
// démonstration : `p-sankara` (dossier complet) et `p-kabore-ets` (personne
// morale, dossier incomplet — c'est là que la liste d'obstacles s'allonge et
// que la mise en page se tend).
const CHEMINS = [
  'atelier',
  'guichet/versement', 'guichet/retrait', 'guichet/virement', 'guichet/releve', 'guichet/caisse',
  'clients/recherche', 'clients/nouveau',
  'clients/p-sankara', 'clients/p-kabore-ets', 'clients/p-sankara/compte',
  'credit/demandes', 'credit/portefeuille', 'credit/nouvelle',
  // Un dossier avec dépassement de grille et conditions suspensives, un contrat
  // en retard : c'est là que les tableaux et les avis se tendent le plus.
  'credit/demandes/d-ouedraogo', 'credit/demandes/d-traore',
  'credit/contrats/c-compaore', 'credit/contrats/c-nikiema',
  // Un contrat passé en perte : la cascade d'absorption est le bloc le plus
  // dense de l'application.
  'credit/contrats/c-sawadogo', 'credit/contrats/c-sawadogo/perte',
  'credit/contrats/c-compaore/perte',
  // La conformité : la file, un dossier d'alerte de surveillance (avec ses
  // pièces) et un de filtrage (sans aucune), la liste des déclarations et les
  // scénarios — le tableau le plus large de l'application.
  'conformite/alertes', 'conformite/alertes/al-especes', 'conformite/alertes/al-filtrage',
  'conformite/declarations', 'conformite/scenarios',
  // Le réglementaire : les échéances, la liste des états, un état transmis
  // (avec ses lignes) et un état en anomalie — c'est là que l'écran se charge
  // le plus —, le catalogue et la fiscalité.
  'reglementaire/echeances', 'reglementaire/etats',
  'reglementaire/etats/et-sit-aout', 'reglementaire/etats/et-sit-sept',
  'reglementaire/declarations', 'reglementaire/fiscalite',
  // Les moyens de paiement : les trois files, avec un objet ouvert sur chacune
  // — c'est le détail et ses actes qui chargent le plus l'écran. Puis les
  // chèques d'un compte, à vide et sur le compte instrumenté de la
  // démonstration : c'est là que les quatre onglets se remplissent.
  'paiements/virements', 'paiements/remises', 'paiements/prelevements',
  'paiements/compte', 'paiements/compte?compte=1001500021000000000018',
  // Le siège : l'exploitation, la balance, puis le paramétrage — l'établissement
  // et le plan de numérotation, dont l'éditeur de gabarit est l'écran le plus
  // chargé du poste.
  'siege/exploitation', 'siege/balance', 'siege/etablissement', 'siege/numerotation',
  // Le paramétrage produit : le catalogue, et la rédaction d'une version — le formulaire le plus
  // long du poste, puisque c'est la famille qui décide de sa longueur.
  'siege/produits', 'siege/agences', 'siege/calendrier',
  'validation',
];
const LARGEURS = [1920, 1440, 1366, 1100, 1024, 768, 390];
/**
 * 24 px : le minimum du critère « Target Size (Minimum) » (WCAG 2.2, 2.5.8).
 * Les actions d'un écran visent la hauteur tactile des tokens, bien au-dessus ;
 * ce plancher attrape les cibles qu'un réglage de densité aurait écrasées.
 */
const HAUTEUR_TACTILE_MIN = 24;

let chromium;
try {
  ({ chromium } = await import('playwright'));
} catch {
  console.error('Playwright est absent. `npx playwright install chromium` puis relancer.');
  process.exit(2);
}

const executablePath = process.env['CB_CHROMIUM'] || undefined;
const navigateur = await chromium.launch(executablePath ? { executablePath } : {});
const echecs = [];

for (const chemin of CHEMINS) {
  for (const largeur of LARGEURS) {
    const contexte = await navigateur.newContext({ viewport: { width: largeur, height: 900 } });
    const page = await contexte.newPage();
    page.on('pageerror', (e) => echecs.push(`${chemin} @${largeur} — erreur JS : ${e.message}`));
    await page.goto(new URL(chemin, BASE).href, { waitUntil: 'networkidle' });

    const mesure = await page.evaluate((min) => {
      const racine = document.scrollingElement ?? document.documentElement;
      const cibles = [...document.querySelectorAll('button, a[href], input, select')]
        .filter((e) => e.getBoundingClientRect().height > 0)
        .filter((e) => e.getBoundingClientRect().height < min)
        .map((e) => `${e.tagName.toLowerCase()}.${e.className || '?'}`.slice(0, 60));
      return { debord: racine.scrollWidth - racine.clientWidth, petites: [...new Set(cibles)] };
    }, HAUTEUR_TACTILE_MIN);

    if (mesure.debord > 1) echecs.push(`${chemin} @${largeur} — débordement horizontal de ${mesure.debord}px`);
    if (mesure.petites.length) echecs.push(`${chemin} @${largeur} — cibles sous ${HAUTEUR_TACTILE_MIN}px : ${mesure.petites.join(', ')}`);
    await contexte.close();
  }
}

await navigateur.close();
if (echecs.length) {
  console.error(`${echecs.length} problème(s) :\n - ` + echecs.join('\n - '));
  process.exit(1);
}
console.log(`Largeurs conformes : ${CHEMINS.length} page(s) × ${LARGEURS.join(', ')}.`);
