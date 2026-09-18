import { Habilitations } from './auth.port';

/**
 * L'opération du socle que chaque écran appelle.
 *
 * Ce n'est **pas** une copie de la politique d'habilitation : c'est la liste de
 * ce que chaque écran demande. La politique — qui a le droit de quoi — reste
 * entière côté socle, et c'est lui qui refuse. Ici on se contente de ne pas
 * proposer une porte qu'on sait fermée.
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
  'guichet/versement': 'CASH_OPERATION',
  'guichet/retrait': 'CASH_OPERATION',
  'guichet/virement': 'TRANSFER',
  'guichet/releve': 'ACCOUNT_JOURNAL_READ',
  'guichet/caisse': 'TILL_CLOSE',
  'siege/exploitation': 'PERIOD_CLOSE',
  'siege/balance': 'LEDGER_READ',
};

/**
 * Tant que le socle n'expose pas les opérations autorisées, on ne cache rien :
 * l'interface montre tout et l'API refuse. Cacher au hasard serait pire que ne
 * rien cacher — l'opérateur croirait qu'un écran n'existe pas.
 */
export function autorise(habilitations: Habilitations, operation: string | undefined): boolean {
  if (!habilitations.connues || operation === undefined) return true;
  return habilitations.operations.has(operation);
}

/** Un espace s'affiche dès qu'un de ses écrans est autorisé. */
export function espaceAutorise(habilitations: Habilitations, prefixe: string): boolean {
  if (!habilitations.connues) return true;
  return Object.entries(OPERATION_PAR_ECRAN)
    .filter(([chemin]) => chemin.startsWith(prefixe))
    .some(([, operation]) => habilitations.operations.has(operation));
}
