/** Espace fine insécable : les groupes de chiffres ne se séparent jamais en fin de ligne. */
const FINE = ' ';

/**
 * Le montant est un objet de première classe : groupes de trois chiffres,
 * échelle imposée par la devise (XOF : 0 décimale), signe explicite quand il
 * compte. Le rouge n'est pas ici — un découvert autorisé n'est pas une alarme,
 * c'est l'appelant qui décide de la couleur.
 */
export function formaterMontant(
  valeur: number | string,
  echelle = 0,
  options: { signe?: 'auto' | 'toujours' | 'jamais' } = {},
): string {
  const nombre = typeof valeur === 'string' ? Number(valeur) : valeur;
  if (!Number.isFinite(nombre)) return '—';

  const signe = options.signe ?? 'auto';
  const negatif = nombre < 0;
  const absolu = Math.abs(nombre).toFixed(echelle);
  const [entier, decimales] = absolu.split('.');
  const groupe = entier.replace(/\B(?=(\d{3})+(?!\d))/g, FINE);
  const corps = decimales ? `${groupe},${decimales}` : groupe;

  if (negatif) return `-${FINE}${corps}`;
  if (signe === 'toujours') return `+${FINE}${corps}`;
  return corps;
}

/**
 * Un taux, tel qu'on l'écrit en français : virgule décimale, décimales
 * significatives conservées.
 *
 * Le socle rend `8.5` ou `29.1` — du JSON, donc un point. L'afficher tel quel
 * met un point décimal sous les yeux d'un agent qui lit des virgules toute la
 * journée ; à 9,25 % contre 9.25 %, la seconde forme fait hésiter une seconde,
 * et cette seconde se paie en relecture.
 */
export function formaterTaux(valeur: number | string | null): string {
  if (valeur === null || valeur === '') return '—';
  const nombre = typeof valeur === 'string' ? Number(valeur) : valeur;
  if (!Number.isFinite(nombre)) return '—';
  // On ne force pas de décimale : 9 % reste « 9 », 9,25 % reste « 9,25 ».
  return String(nombre).replace('.', ',');
}

/** Lit une saisie humaine : espaces, espaces fines, virgule décimale. */
export function lireMontant(saisie: string): number | null {
  const nettoye = saisie
    .replace(/[\s  ]/g, '')
    .replace(',', '.')
    .trim();
  if (nettoye === '' || nettoye === '-') return null;
  const nombre = Number(nettoye);
  return Number.isFinite(nombre) ? nombre : null;
}

/** Numéro de compte lisible : groupes de N chiffres, jamais tronqué en silence. */
export function formaterCompte(numero: string, groupe = 4): string {
  const brut = numero.replace(/\s/g, '');
  if (groupe <= 0) return brut;
  return brut.replace(new RegExp(`(.{${groupe}})`, 'g'), `$1${FINE}`).trim();
}

/** Les quatre derniers caractères, pour les listes denses. */
export function finDeCompte(numero: string): string {
  const brut = numero.replace(/\s/g, '');
  return brut.length <= 4 ? brut : `····${brut.slice(-4)}`;
}
