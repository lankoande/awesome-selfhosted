import type { paths } from './schema';

/**
 * Les chemins du socle, tenus par le contrat.
 *
 * <h2>Le défaut que ce module ferme</h2>
 *
 * Les URLs étaient écrites à la main dans chaque service d'accès :
 *
 *     `${racine}/entities/${legalEntityId}/accounts/${accountId}/balance`
 *
 * Une faute de frappe dans ce gabarit compile, passe les tests unitaires — qui
 * l'utilisent tel quel — et ne se voit qu'en 404 devant un client. Or le
 * contrat est déjà là : `schema.ts` est généré depuis `openapi.json` et porte
 * les 146 chemins comme clés de type. On ne s'en servait que pour les corps de
 * requête, pas pour les adresses.
 *
 * Désormais, `chemin()` n'accepte qu'une clé du contrat, et les noms de
 * variables sont extraits du gabarit lui-même : un chemin inexistant, une
 * variable mal nommée ou une variable oubliée sont trois erreurs de
 * compilation.
 *
 * <h2>Ce que ce module ne fait pas</h2>
 *
 * Il ne connaît pas l'origine du socle — elle est de déploiement, pas de
 * contrat, et vit dans `config.json`. Le service `Socle` les assemble.
 */

/** Un chemin du contrat. Toute autre chaîne est refusée à la compilation. */
export type Chemin = keyof paths & string;

/** Les variables d'un gabarit, lues dans le gabarit : `{a}/x/{b}` donne `a | b`. */
type Variables<C extends string> =
  C extends `${string}{${infer V}}${infer Reste}` ? V | Variables<Reste> : never;

/** Un chemin sans variable ne prend pas d'argument ; les autres les exigent tous. */
type Arguments<C extends string> =
  [Variables<C>] extends [never] ? [] : [Readonly<Record<Variables<C>, string>>];

/** Le préfixe de version, qui appartient au contrat et non au déploiement. */
export const PREFIXE_SOCLE = '/v1';

/**
 * Le chemin d'appel, variables substituées et encodées.
 *
 * L'encodage n'est pas une précaution de style : un identifiant de compte ou
 * un numéro de chèque qui porterait `/` ou `?` changerait la route appelée.
 */
export function chemin<C extends Chemin>(modele: C, ...valeurs: Arguments<C>): string {
  const params = (valeurs[0] ?? {}) as Readonly<Record<string, string>>;
  return modele.replace(/\{(\w+)\}/g, (_, nom: string) => {
    const valeur = params[nom];
    // Un identifiant vide produirait `/accounts//balance`, que le socle lit
    // comme une autre route — donc un refus incompréhensible à l'écran.
    if (valeur === undefined || valeur === '') {
      throw new Error(`Chemin ${modele} : la variable {${nom}} est absente ou vide.`);
    }
    return encodeURIComponent(valeur);
  });
}
