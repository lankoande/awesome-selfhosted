/**
 * Sert la compilation avec repli sur `index.html`.
 *
 * Le contrôle de largeurs ouvre des routes profondes (`guichet/versement`,
 * `siege/balance`) : un serveur statique ordinaire y répond 404, et le contrôle
 * mesurerait alors une page d'erreur au lieu de l'écran. C'est le repli d'une
 * application à page unique, et il tient en trente lignes — les embarquer évite
 * une dépendance de plus dans la chaîne d'intégration.
 *
 *   node scripts/servir.mjs dist/web/browser 8181
 */
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';

const RACINE = process.argv[2] ?? 'dist/web/browser';
const PORT = Number(process.argv[3] ?? 8181);

const TYPES = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css',
  '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff',
  '.ico': 'image/x-icon', '.svg': 'image/svg+xml', '.png': 'image/png',
};

createServer(async (requete, reponse) => {
  // `normalize` puis retrait des remontées : une requête ne sort pas de la racine.
  const chemin = normalize(decodeURIComponent(new URL(requete.url, 'http://x').pathname))
    .replace(/^(\.\.[/\\])+/, '');
  try {
    const contenu = await readFile(join(RACINE, chemin));
    reponse.writeHead(200, { 'content-type': TYPES[extname(chemin)] ?? 'application/octet-stream' });
    reponse.end(contenu);
  } catch {
    reponse.writeHead(200, { 'content-type': TYPES['.html'] });
    reponse.end(await readFile(join(RACINE, 'index.html')));
  }
}).listen(PORT, '127.0.0.1', () => console.log(`servi sur http://127.0.0.1:${PORT}/`));
