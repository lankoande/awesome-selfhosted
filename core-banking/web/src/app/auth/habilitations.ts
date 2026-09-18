import { Injectable, inject } from '@angular/core';
import { AUTHENTIFICATION, Droit, Habilitations, Montant, Portee } from './auth.port';

/**
 * L'opération du socle que chaque écran appelle **pour s'afficher**.
 *
 * Ce n'est **pas** une copie de la politique d'habilitation : c'est la liste de
 * ce que chaque écran demande pour exister dans le menu. La politique — qui a
 * le droit de quoi — reste entière côté socle, et c'est lui qui refuse. Ici on
 * se contente de ne pas proposer une porte qu'on sait fermée.
 *
 * **Une entrée par écran ne suffit pas**, et c'est le sujet de `peut()` plus
 * bas : un même écran porte souvent des actes qui n'appellent pas le même
 * droit. Le détail d'un état réglementaire se lit avec `REGULATORY_READ`, s'y
 * produit avec `REGULATORY_REPORT_PRODUCE` et s'y transmet avec
 * `REGULATORY_REPORT_TRANSMIT` — trois droits, un écran. Ce qui est ici décide
 * de la navigation ; ce qui est dans les écrans décide des boutons.
 */
export const OPERATION_PAR_ECRAN: Readonly<Record<string, string>> = {
  'clients/recherche': 'PARTY_READ',
  'clients/nouveau': 'PARTY_CREATE',
  // Ni le dossier ni l'ouverture ne sont des entrées de barre : on y arrive
  // depuis un client. Ils figurent ici pour que l'espace reste visible d'un
  // opérateur qui n'aurait que l'ouverture de compte.
  'clients/dossier': 'PARTY_READ',
  'clients/compte': 'ACCOUNT_OPEN',
  'credit/demandes': 'LOAN_READ',
  'credit/portefeuille': 'LOAN_READ',
  'credit/nouvelle': 'LOAN_APPLICATION',
  // Le dossier et le contrat ne sont pas des entrées de barre : on y arrive
  // depuis une liste. Ils figurent ici pour que l'espace reste visible.
  'credit/dossier': 'LOAN_READ',
  'credit/contrat': 'LOAN_READ',
  'credit/perte': 'LOAN_READ',
  // La conformité se lit avec AML_READ, qui n'est donné ni au guichet ni à la
  // gestion de portefeuille : la surveillance ne se discute pas avec celui qui
  // reçoit le client. Le dossier d'alerte n'est pas une entrée de barre — on y
  // arrive depuis la file — mais il figure ici pour que l'espace reste visible.
  'conformite/alertes': 'AML_READ',
  'conformite/alerte': 'AML_READ',
  'conformite/declarations': 'AML_READ',
  'conformite/scenarios': 'AML_READ',
  // Le réglementaire se lit avec REGULATORY_READ, donné au comptable, au risque,
  // à l'audit et à l'exploitation. Produire et transmettre sont deux droits
  // distincts — les écrans s'en servent, pas la navigation.
  'reglementaire/echeances': 'REGULATORY_READ',
  'reglementaire/etats': 'REGULATORY_READ',
  'reglementaire/etat': 'REGULATORY_READ',
  'reglementaire/declarations': 'REGULATORY_READ',
  'reglementaire/fiscalite': 'REGULATORY_READ',
  // Les moyens de paiement. Chaque file se lit avec le droit de lecture de son
  // instrument ; les actes qui dénouent un engagement portent un droit distinct,
  // et les écrans s'en servent sans que la navigation le fasse.
  'paiements/virements': 'PAYMENT_READ',
  'paiements/remises': 'CHEQUE_READ',
  'paiements/prelevements': 'DIRECT_DEBIT_READ',
  'guichet/versement': 'CASH_OPERATION',
  'guichet/retrait': 'CASH_OPERATION',
  'guichet/virement': 'TRANSFER',
  'guichet/releve': 'ACCOUNT_JOURNAL_READ',
  'guichet/caisse': 'TILL_CLOSE',
  'siege/exploitation': 'PERIOD_CLOSE',
  'siege/balance': 'LEDGER_READ',
  // Le paramétrage du siège. L'identité de l'établissement se lit largement —
  // un guichetier la voit sur tout relevé qu'il imprime —, le plan de
  // numérotation non : le lire, c'est savoir deviner les numéros des autres.
  'siege/etablissement': 'ESTABLISHMENT_READ',
  'siege/numerotation': 'NUMBERING_READ',
};

/** Aucune habilitation connue : on ne cache rien, l'API refusera. */
export const HABILITATIONS_INCONNUES: Habilitations = {
  connues: false,
  droits: new Map(),
};

/**
 * Tant que le socle n'expose pas les opérations autorisées, on ne cache rien :
 * l'interface montre tout et l'API refuse. Cacher au hasard serait pire que ne
 * rien cacher — l'opérateur croirait qu'un écran n'existe pas.
 */
export function autorise(habilitations: Habilitations, operation: string | undefined): boolean {
  if (!habilitations.connues || operation === undefined) return true;
  return habilitations.droits.has(operation);
}

/** Un espace s'affiche dès qu'un de ses écrans est autorisé. */
export function espaceAutorise(habilitations: Habilitations, prefixe: string): boolean {
  if (!habilitations.connues) return true;
  return Object.entries(OPERATION_PAR_ECRAN)
    .filter(([chemin]) => chemin.startsWith(prefixe))
    .some(([, operation]) => habilitations.droits.has(operation));
}

// ------------------------------------------------------- la granularité fine

/**
 * Le droit sur une opération, ou `null`.
 *
 * `null` recouvre deux cas que l'appelant doit distinguer : habilitations
 * inconnues (on ne sait pas, donc on ne cache pas) et opération absente (on
 * sait qu'elle est refusée). `autorise()` tranche le premier ; les fonctions
 * ci-dessous qui *annoncent* quelque chose se taisent dans les deux.
 */
export function droit(habilitations: Habilitations, operation: string): Droit | null {
  return habilitations.droits.get(operation) ?? null;
}

/**
 * L'acte part-il à la validation d'un second, d'après ce que le socle a dit ?
 *
 * **Faux quand on ne sait pas.** C'est la réponse stricte, celle qu'on pose
 * quand on veut savoir ; pour *annoncer* quelque chose à l'opérateur, voir
 * `annonceUnSecondRegard` — le défaut n'y va pas dans le même sens.
 */
export function exigeUnSecondRegard(habilitations: Habilitations, operation: string): boolean {
  return droit(habilitations, operation)?.secondRegard ?? false;
}

/**
 * Faut-il **dire** à l'opérateur que l'acte partira à la validation ?
 *
 * **Vrai tant qu'on ne sait pas**, et l'asymétrie est le sujet :
 *
 *   ne pas l'annoncer alors qu'il existe fait dire à un guichetier qu'un compte
 *   est ouvert quand il ne l'est pas — il l'annonce au client, et la banque
 *   découvre l'erreur quand le client revient ;
 *
 *   l'annoncer alors qu'il n'existe pas n'est qu'une attente déçue d'une
 *   seconde, que l'écran de résultat corrige aussitôt.
 *
 * L'écran choisit l'opération : seul un écran dont l'acte est effectivement à
 * deux appelle cette fonction, et le défaut ne déborde donc pas ailleurs.
 */
export function annonceUnSecondRegard(habilitations: Habilitations, operation: string): boolean {
  return !habilitations.connues || exigeUnSecondRegard(habilitations, operation);
}

/**
 * Le plafond de l'appelant sur cette opération, dans cette devise.
 *
 * `null` quand il n'y en a pas, ou qu'on ne le connaît pas. C'est le plus
 * favorable des rôles de l'appelant : le socle l'a déjà résolu, le poste ne
 * recalcule rien.
 *
 * `horsAgence` donne le plafond de l'opération déplacée, plus bas en général —
 * on opère sur le compte d'une autre agence avec moins de latitude, parce que
 * le dossier n'est pas sous les yeux.
 */
export function plafond(habilitations: Habilitations, operation: string, devise: string,
                        horsAgence = false): Montant | null {
  const trouve = droit(habilitations, operation);
  if (!trouve) return null;
  const table = horsAgence ? trouve.plafondsHorsAgence : trouve.plafonds;
  return table.get(devise) ?? null;
}

/** La portée de l'opération, ou `null` quand on ne la connaît pas. */
export function portee(habilitations: Habilitations, operation: string): Portee | null {
  return droit(habilitations, operation)?.portee ?? null;
}

/**
 * Le montant dépasse-t-il ce que le profil admet ?
 *
 * **Ce n'est pas une décision d'accès** : le socle refuse toujours, et il
 * connaît l'agence et l'objet, que le poste ignore. C'est un avertissement
 * avant la saisie — un guichetier qui apprend son plafond dans un refus après
 * avoir compté les billets a perdu deux minutes et la face devant le client.
 */
export function depasseLePlafond(habilitations: Habilitations, operation: string,
                                 montant: number | null, devise: string,
                                 horsAgence = false): boolean {
  if (montant === null || montant <= 0) return false;
  const limite = plafond(habilitations, operation, devise, horsAgence);
  return limite !== null && montant > Number(limite.amount);
}

export const LIBELLE_PORTEE: Readonly<Record<Portee, string>> = {
  OWN_BRANCH: 'votre agence',
  OWN_ENTITY: "l'ensemble de l'établissement",
  ANY_ENTITY: 'toutes les entités',
};

// ------------------------------------------------------- l'accès des écrans

/**
 * Ce qu'un écran demande aux habilitations, sans plomberie.
 *
 * Les écrans n'ont pas à connaître `AUTHENTIFICATION` ni à rappeler les
 * fonctions pures ci-dessus : ils demandent `peut()`, `aDeuxRegards()`,
 * `plafond()`. Les méthodes lisent un signal — les droits arrivent après le
 * premier rendu, et un bouton doit s'ouvrir quand ils arrivent.
 *
 * **Le service est optionnel.** Sans session injectée — un banc d'essai qui
 * monte un écran seul — il rend « on ne sait pas », donc tout est permis. C'est
 * la règle de l'application : on ne cache jamais au hasard.
 */
@Injectable({ providedIn: 'root' })
export class Droits {
  private readonly authentification = inject(AUTHENTIFICATION, { optional: true });

  /** Les habilitations courantes. Inconnues sans session. */
  etat(): Habilitations {
    return this.authentification?.habilitations() ?? HABILITATIONS_INCONNUES;
  }

  /** L'opération est-elle proposable ? Vrai tant qu'on ne sait pas. */
  peut(operation: string | undefined): boolean {
    return autorise(this.etat(), operation);
  }

  /** L'acte part-il à la validation d'un second ? Faux tant qu'on ne sait pas. */
  aDeuxRegards(operation: string): boolean {
    return exigeUnSecondRegard(this.etat(), operation);
  }

  /** Faut-il l'annoncer ? Vrai tant qu'on ne sait pas — voir la fonction. */
  annonceDeuxRegards(operation: string): boolean {
    return annonceUnSecondRegard(this.etat(), operation);
  }

  plafond(operation: string, devise: string, horsAgence = false): Montant | null {
    return plafond(this.etat(), operation, devise, horsAgence);
  }

  depasse(operation: string, montant: number | null, devise: string,
          horsAgence = false): boolean {
    return depasseLePlafond(this.etat(), operation, montant, devise, horsAgence);
  }

  portee(operation: string): Portee | null {
    return portee(this.etat(), operation);
  }

  /** « votre agence », « l'ensemble de l'établissement »… ou rien. */
  libellePortee(operation: string): string | null {
    const p = this.portee(operation);
    return p === null ? null : LIBELLE_PORTEE[p];
  }
}
