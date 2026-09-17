/**
 * Le jeu fermé de primitives. Ce n'est pas une documentation : c'est la liste
 * de référence du garde-fou. Un composant qui n'y figure pas n'existe pas, et
 * un composant qui y figure sans section dans l'atelier fait échouer les tests.
 *
 * Ajouter une primitive est une décision — on la prend en modifiant ce fichier,
 * pas en créant un fichier de plus dans un coin.
 */
export interface PrimitiveUI {
  readonly id: string;
  readonly nom: string;
  readonly role: string;
}

export const PRIMITIVES_UI: readonly PrimitiveUI[] = [
  { id: 'button', nom: 'Bouton', role: "Quatre variantes. Un écran n'a qu'une action primaire." },
  { id: 'input', nom: 'Saisie', role: 'Champ natif habillé : texte, liste, zone de texte.' },
  { id: 'field', nom: 'Champ', role: "Étiquette, aide, erreur. L'erreur remplace l'aide." },
  { id: 'amount', nom: 'Montant', role: "Chiffres tabulaires, échelle de la devise, ton choisi par l'appelant." },
  { id: 'amount-input', nom: 'Saisie de montant', role: "Refuse une décimale de trop plutôt que de l'arrondir." },
  { id: 'date-input', nom: 'Saisie de date', role: 'Masque jj/mm/aaaa, valeur ISO. Pas de calendrier.' },
  { id: 'state-badge', nom: "Badge d'état", role: 'Le vocabulaire fermé des six états.' },
  { id: 'notice', nom: 'Notice', role: 'Le refus est un moment de design : raison, règle, quoi faire.' },
  { id: 'section', nom: 'Section', role: 'Sur-titre et filet. Pas de carte flottante.' },
  { id: 'table', nom: 'Table', role: 'Table dense, colonnes de chiffres en chasse fixe.' },
  { id: 'toolbar', nom: "Barre d'outils", role: "Le seul endroit où une icône seule est tolérée." },
  { id: 'tabs', nom: 'Onglets', role: 'Rôles ARIA complets, navigation aux flèches.' },
  { id: 'pagination', nom: 'Pagination', role: "À curseur. Pas de défilement infini sur une liste comptable." },
  { id: 'kbd', nom: 'Raccourci', role: 'On optimise la répétition, pas la découverte.' },
  { id: 'drawer', nom: 'Tiroir de contexte', role: 'La liste reste visible. Remplace la modale de travail.' },
  { id: 'dialog', nom: 'Confirmation', role: "La seule modale : confirmer l'irréversible." },
  { id: 'activity', nom: 'Activité', role: 'Un filet de deux pixels. Le mouvement ne décore pas.' },
] as const;
