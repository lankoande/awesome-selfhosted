/**
 * L'essai d'un schéma, tel que la démonstration le rend.
 *
 * <h2>Pourquoi un évaluateur existe ici, et nulle part ailleurs</h2>
 *
 * Le poste ne lit jamais une expression : il envoie ce qui est écrit et affiche ce que le socle
 * en fait. Ce fichier **ne fait pas partie du poste** — il tient le rôle du socle dans la
 * démonstration, exactement comme le reste de `siege.factice.ts` tient celui du journal et des
 * soldes. En mode `api`, rien de ce fichier n'est chargé.
 *
 * La distinction n'est pas une subtilité : c'est elle qui évite le seul défaut qui compte ici,
 * un écran qui montrerait une écriture que la production ne produirait pas. Le jour où le langage
 * du socle s'enrichira, cet évaluateur-ci sera en retard — et ce sera visible dans la seule
 * démonstration, jamais en agence.
 *
 * Le sous-ensemble couvre ce que les schémas du socle emploient : nombres, variables, `+ - * /`,
 * parenthèses, comparaisons, `and` / `or` / `not`, et les fonctions `round`, `abs`, `min`, `max`.
 */

import { Essai, LigneEssai, LigneSaisie, ValeurDerivee } from './schemas.modele';

type Valeur = number | boolean;

class Analyseur {
  private position = 0;

  constructor(private readonly source: string,
              private readonly variables: Readonly<Record<string, number>>) {}

  evaluer(): Valeur {
    const valeur = this.ou();
    this.blancs();
    if (this.position < this.source.length) {
      throw new Error(`Caractère inattendu à la position ${this.position}`);
    }
    return valeur;
  }

  private ou(): Valeur {
    let gauche = this.et();
    while (this.motCle('or')) {
      const droite = this.et();
      gauche = this.booleen(gauche) || this.booleen(droite);
    }
    return gauche;
  }

  private et(): Valeur {
    let gauche = this.comparaison();
    while (this.motCle('and')) {
      const droite = this.comparaison();
      gauche = this.booleen(gauche) && this.booleen(droite);
    }
    return gauche;
  }

  private comparaison(): Valeur {
    const gauche = this.somme();
    for (const operateur of ['>=', '<=', '==', '!=', '>', '<']) {
      if (this.symbole(operateur)) {
        const droite = this.nombre(this.somme());
        const a = this.nombre(gauche);
        switch (operateur) {
          case '>=': return a >= droite;
          case '<=': return a <= droite;
          case '==': return a === droite;
          case '!=': return a !== droite;
          case '>': return a > droite;
          default: return a < droite;
        }
      }
    }
    return gauche;
  }

  private somme(): Valeur {
    let gauche = this.produit();
    for (;;) {
      if (this.symbole('+')) {
        gauche = this.nombre(gauche) + this.nombre(this.produit());
      } else if (this.symbole('-')) {
        gauche = this.nombre(gauche) - this.nombre(this.produit());
      } else {
        return gauche;
      }
    }
  }

  private produit(): Valeur {
    let gauche = this.unaire();
    for (;;) {
      if (this.symbole('*')) {
        gauche = this.nombre(gauche) * this.nombre(this.unaire());
      } else if (this.symbole('/')) {
        const diviseur = this.nombre(this.unaire());
        if (diviseur === 0) {
          throw new Error('Division par zéro');
        }
        gauche = this.nombre(gauche) / diviseur;
      } else {
        return gauche;
      }
    }
  }

  private unaire(): Valeur {
    if (this.motCle('not')) {
      return !this.booleen(this.unaire());
    }
    if (this.symbole('-')) {
      return -this.nombre(this.unaire());
    }
    return this.terme();
  }

  private terme(): Valeur {
    this.blancs();
    if (this.symbole('(')) {
      const valeur = this.ou();
      if (!this.symbole(')')) {
        throw new Error('Parenthèse fermante attendue');
      }
      return valeur;
    }
    const debut = this.position;
    while (this.position < this.source.length && /[0-9.]/.test(this.source[this.position]!)) {
      this.position += 1;
    }
    if (this.position > debut) {
      return Number(this.source.slice(debut, this.position));
    }
    while (this.position < this.source.length && /[A-Za-z0-9_]/.test(this.source[this.position]!)) {
      this.position += 1;
    }
    const nom = this.source.slice(debut, this.position);
    if (!nom) {
      throw new Error(`Terme attendu à la position ${debut}`);
    }
    if (nom === 'true' || nom === 'false') {
      return nom === 'true';
    }
    if (this.symbole('(')) {
      const arguments_: number[] = [this.nombre(this.ou())];
      while (this.symbole(',')) {
        arguments_.push(this.nombre(this.ou()));
      }
      if (!this.symbole(')')) {
        throw new Error('Parenthèse fermante attendue');
      }
      return this.fonction(nom, arguments_);
    }
    const valeur = this.variables[nom];
    if (valeur === undefined) {
      throw new Error(`Variable « ${nom} » non fournie`);
    }
    return valeur;
  }

  private fonction(nom: string, arguments_: readonly number[]): number {
    const [a, b] = [arguments_[0] ?? 0, arguments_[1] ?? 0];
    switch (nom) {
      // L'arrondi du socle est « au pair » : 0,5 va au chiffre pair le plus proche.
      case 'round': return arrondiAuPair(a, b);
      case 'abs': return Math.abs(a);
      case 'min': return Math.min(a, b);
      case 'max': return Math.max(a, b);
      default: throw new Error(`Fonction inconnue : ${nom}`);
    }
  }

  private nombre(valeur: Valeur): number {
    if (typeof valeur !== 'number') {
      throw new Error('Nombre attendu');
    }
    return valeur;
  }

  private booleen(valeur: Valeur): boolean {
    if (typeof valeur !== 'boolean') {
      throw new Error('Condition attendue');
    }
    return valeur;
  }

  private blancs(): void {
    while (this.position < this.source.length && /\s/.test(this.source[this.position]!)) {
      this.position += 1;
    }
  }

  private symbole(symbole: string): boolean {
    this.blancs();
    if (this.source.startsWith(symbole, this.position)) {
      this.position += symbole.length;
      return true;
    }
    return false;
  }

  private motCle(motCle: string): boolean {
    this.blancs();
    if (!this.source.startsWith(motCle, this.position)) {
      return false;
    }
    const apres = this.source[this.position + motCle.length];
    if (apres !== undefined && /[A-Za-z0-9_]/.test(apres)) {
      return false;
    }
    this.position += motCle.length;
    return true;
  }
}

/** L'arrondi du socle : HALF_EVEN, à l'échelle demandée. */
function arrondiAuPair(valeur: number, echelle: number): number {
  const facteur = 10 ** echelle;
  const porte = valeur * facteur;
  const bas = Math.floor(porte);
  const reste = porte - bas;
  let entier: number;
  if (reste > 0.5) {
    entier = bas + 1;
  } else if (reste < 0.5) {
    entier = bas;
  } else {
    entier = bas % 2 === 0 ? bas : bas + 1;
  }
  return entier / facteur;
}

function decimales(valeur: number): number {
  const texte = String(valeur);
  const point = texte.indexOf('.');
  return point < 0 ? 0 : texte.length - point - 1;
}

/** Les variables libres : celles qu'une expression cite et que le schéma ne calcule pas. */
function variablesLibres(expressions: readonly string[], calculees: readonly string[]): string[] {
  const citees: string[] = [];
  for (const expression of expressions) {
    for (const nom of expression.match(/[A-Za-z_][A-Za-z0-9_]*/g) ?? []) {
      if (['round', 'abs', 'min', 'max', 'and', 'or', 'not', 'true', 'false'].includes(nom)) {
        continue;
      }
      if (!calculees.includes(nom) && !citees.includes(nom)) {
        citees.push(nom);
      }
    }
  }
  return citees;
}

/**
 * L'essai, rendu comme le socle le rend — refus compris.
 *
 * Fidèle aux trois règles du moteur : montant nul non imputé, montant négatif refusé, montant non
 * comptabilisable refusé plutôt qu'arrondi. Un essai qui arrondirait là où la production refuse
 * montrerait une écriture que personne n'obtiendrait.
 */
export function essaiDeDemonstration(
    evenement: string, echelle: number, lignes: readonly LigneSaisie[],
    derivations: readonly (readonly [string, string])[],
    valeurs: Readonly<Record<string, string>>): Essai {

  const calculees = derivations.map(([nom]) => nom);
  const libres = variablesLibres(
    [...derivations.map(([, expression]) => expression),
     ...lignes.flatMap((ligne) => [ligne.amount, ligne.condition].filter((e): e is string => !!e))],
    calculees);

  const contexte: Record<string, number> = {};
  for (const nom of libres) {
    contexte[nom] = Number(valeurs[nom] ?? '0') || 0;
  }

  const derivees: ValeurDerivee[] = [];
  const vide: Essai = {
    eventType: evenement, variables: libres, derived: [], lines: [], debit: 0, credit: 0,
    imbalance: 0, rejection: null,
  };
  for (const [nom, expression] of derivations) {
    try {
      const valeur = new Analyseur(expression, contexte).evaluer();
      if (typeof valeur !== 'number') {
        throw new Error('Nombre attendu');
      }
      contexte[nom] = valeur;
      derivees.push({ name: nom, expression, value: valeur });
    } catch (erreur) {
      return { ...vide, rejection: { code: 'EVALUATION', detail: String(erreur) } };
    }
  }

  const rendues: LigneEssai[] = [];
  let debit = 0;
  let credit = 0;
  let imputees = 0;

  for (const ligne of lignes) {
    const base = {
      account: ligne.account, direction: ligne.direction, amountExpression: ligne.amount,
      label: ligne.label || null,
    };
    try {
      if (ligne.condition && new Analyseur(ligne.condition, contexte).evaluer() !== true) {
        rendues.push({ ...base, amount: null, posted: false, skipped: 'CONDITION' });
        continue;
      }
      const brut = new Analyseur(ligne.amount, contexte).evaluer();
      if (typeof brut !== 'number') {
        throw new Error('Nombre attendu');
      }
      if (brut === 0) {
        rendues.push({ ...base, amount: 0, posted: false, skipped: 'MONTANT_NUL' });
        continue;
      }
      if (brut < 0) {
        rendues.push({ ...base, amount: brut, posted: false, skipped: null });
        return {
          ...vide, derived: derivees, lines: rendues,
          rejection: {
            code: 'MONTANT_NEGATIF',
            detail: `Montant négatif ${brut} sur la ligne ${ligne.direction} ${ligne.account} : `
                    + 'le sens est porté par la direction, jamais par le signe.',
          },
        };
      }
      if (decimales(brut) > echelle) {
        rendues.push({ ...base, amount: brut, posted: false, skipped: null });
        return {
          ...vide, derived: derivees, lines: rendues,
          rejection: {
            code: 'MONTANT_NON_COMPTABILISABLE',
            detail: `Montant ${brut} non comptabilisable avec ${echelle} décimale(s) sur la ligne `
                    + `${ligne.direction} ${ligne.account}. Le moteur n'arrondit pas à votre `
                    + 'place : employez round(...) et imputez l’écart explicitement.',
          },
        };
      }
      rendues.push({ ...base, amount: brut, posted: true, skipped: null });
      imputees += 1;
      if (ligne.direction === 'DEBIT') {
        debit += brut;
      } else {
        credit += brut;
      }
    } catch (erreur) {
      rendues.push({ ...base, amount: null, posted: false, skipped: null });
      return {
        ...vide, derived: derivees, lines: rendues,
        rejection: { code: 'EVALUATION', detail: String(erreur) },
      };
    }
  }

  const ecart = debit - credit;
  let refus: Essai['rejection'] = null;
  if (imputees < 2) {
    refus = {
      code: 'LIGNE_UNIQUE',
      detail: `Les conditions et les montants nuls ont réduit l'écriture à ${imputees} ligne(s) : `
              + 'la partie double en exige deux.',
    };
  } else if (ecart !== 0) {
    refus = {
      code: 'DESEQUILIBRE',
      detail: `Déséquilibre de ${ecart} entre le débit et le crédit.`,
    };
  }
  return {
    eventType: evenement, variables: libres, derived: derivees, lines: rendues,
    debit, credit, imbalance: ecart, rejection: refus,
  };
}
