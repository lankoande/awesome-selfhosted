/**
 * Les coupures du franc CFA (BCEAO). Le 500 existe en billet et en pièce :
 * ce sont deux lignes de comptage distinctes, parce qu'un caissier les range
 * séparément et que l'arrêté de caisse les distingue.
 */
export interface Coupure {
  /** Identifiant de ligne : `b` pour billet, `p` pour pièce. */
  readonly id: string;
  readonly valeur: number;
  readonly genre: 'billet' | 'piece';
}

export const COUPURES_XOF: readonly Coupure[] = [
  { id: 'b10000', valeur: 10000, genre: 'billet' },
  { id: 'b5000', valeur: 5000, genre: 'billet' },
  { id: 'b2000', valeur: 2000, genre: 'billet' },
  { id: 'b1000', valeur: 1000, genre: 'billet' },
  { id: 'b500', valeur: 500, genre: 'billet' },
  { id: 'p500', valeur: 500, genre: 'piece' },
  { id: 'p250', valeur: 250, genre: 'piece' },
  { id: 'p200', valeur: 200, genre: 'piece' },
  { id: 'p100', valeur: 100, genre: 'piece' },
  { id: 'p50', valeur: 50, genre: 'piece' },
  { id: 'p25', valeur: 25, genre: 'piece' },
  { id: 'p10', valeur: 10, genre: 'piece' },
  { id: 'p5', valeur: 5, genre: 'piece' },
];

export type Comptage = Readonly<Record<string, number>>;

/**
 * Total du comptage. Arithmétique entière : un nombre de coupures multiplié
 * par une valeur entière ne perd rien, contrairement à une somme de flottants.
 */
export function totalComptage(comptage: Comptage, coupures: readonly Coupure[] = COUPURES_XOF): number {
  return coupures.reduce((somme, coupure) => somme + coupure.valeur * (comptage[coupure.id] ?? 0), 0);
}

/** Nombre de coupures comptées, toutes lignes confondues. */
export function nombreDeCoupures(comptage: Comptage): number {
  return Object.values(comptage).reduce((somme, nombre) => somme + (nombre || 0), 0);
}

/** Les coupures ne servent que pour une devise qui en a : ailleurs, pas de billetage. */
export function coupuresDe(devise: string): readonly Coupure[] {
  return devise === 'XOF' || devise === 'XAF' ? COUPURES_XOF : [];
}
