/**
 * Contrôle de largeurs : à chaque largeur supportée, aucune page ne doit
 * provoquer de défilement horizontal, et aucune cible cliquable ne doit passer
 * sous la hauteur tactile.
 *
 * Ce contrôle demande un navigateur ; il n'est donc pas dans `npm test`, qui
 * doit rester utilisable sans. Il se lance ainsi :
 *
 *   npm run build
 *   npx http-server dist/web/browser -p 8181   (ou tout serveur statique)
 *   npm run check:largeurs -- http://127.0.0.1:8181/
 *
 * Playwright n'est pas une dépendance du projet : `npx playwright` suffit, et
 * le jour où le front aura une chaîne d'intégration, ce script y devient une
 * barrière au même titre que les budgets de taille.
 */
const BASE = process.argv[2] ?? 'http://127.0.0.1:8181/';
const CHEMINS = ['atelier', 'guichet/versement', 'validation'];
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
