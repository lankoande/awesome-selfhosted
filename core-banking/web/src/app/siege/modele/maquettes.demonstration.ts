/**
 * Les maquettes de la démonstration : un bilan en vigueur, un compte de résultat en vigueur,
 * et un bilan en brouillon qui laisse deux comptes sans rubrique.
 *
 * Le brouillon incomplet est délibéré : c'est le cas qu'on veut montrer. Une maquette qui
 * s'active à deux et laisse des comptes de côté produit un bilan faux, et on ne s'en aperçoit
 * qu'en le lisant — après l'avoir transmis. L'essai le dit avant.
 *
 * En mode `api`, rien de ce fichier n'est lu.
 */

import {
  CompteNonAffecte, Maquette, MaquetteComplete, RegleAffectation, Rubrique,
} from './maquettes.modele';

export const MAQUETTES_DEMONSTRATION: readonly Maquette[] = [
  { id: 'mq-bilan', kind: 'BALANCE_SHEET', code: 'BILAN-BCEAO', label: 'Bilan — plan BCEAO',
    validFrom: '2026-01-01', validTo: null, status: 'ACTIVE', createdBy: null,
    createdAt: '2025-12-18T09:00:00Z', approvedBy: null, approvedAt: '2025-12-19T11:30:00Z',
    withdrawnBy: null, withdrawnAt: null },
  { id: 'mq-resultat', kind: 'INCOME_STATEMENT', code: 'CR-BCEAO',
    label: 'Compte de résultat — plan BCEAO', validFrom: '2026-01-01', validTo: null,
    status: 'ACTIVE', createdBy: null, createdAt: '2025-12-18T09:20:00Z', approvedBy: null,
    approvedAt: '2025-12-19T11:35:00Z', withdrawnBy: null, withdrawnAt: null },
  { id: 'mq-brouillon', kind: 'BALANCE_SHEET', code: 'BILAN-2027',
    label: 'Bilan — refonte 2027', validFrom: '2027-01-01', validTo: null, status: 'DRAFT',
    createdBy: null, createdAt: '2026-09-12T14:05:00Z', approvedBy: null, approvedAt: null,
    withdrawnBy: null, withdrawnAt: null },
];

const RUBRIQUES_BILAN: readonly Rubrique[] = [
  { ordinal: 1, code: 'A1', label: 'Caisse et banques centrales', level: 1, kind: 'DETAIL',
    side: 'DEBIT', plus: [], minus: [] },
  { ordinal: 2, code: 'A2', label: 'Créances sur la clientèle', level: 1, kind: 'DETAIL',
    side: 'DEBIT', plus: [], minus: [] },
  { ordinal: 3, code: 'A3', label: 'Autres actifs', level: 1, kind: 'DETAIL', side: 'DEBIT',
    plus: [], minus: [] },
  { ordinal: 4, code: 'TA', label: 'Total actif', level: 0, kind: 'TOTAL', side: 'DEBIT',
    plus: ['A1', 'A2', 'A3'], minus: [] },
  { ordinal: 5, code: 'P1', label: 'Dépôts de la clientèle', level: 1, kind: 'DETAIL',
    side: 'CREDIT', plus: [], minus: [] },
  { ordinal: 6, code: 'P2', label: 'Autres passifs et fonds propres', level: 1, kind: 'DETAIL',
    side: 'CREDIT', plus: [], minus: [] },
  { ordinal: 7, code: 'PR', label: 'Résultat de l’exercice', level: 1, kind: 'PROFIT_OR_LOSS',
    side: 'CREDIT', plus: [], minus: [] },
  { ordinal: 8, code: 'TP', label: 'Total passif', level: 0, kind: 'TOTAL', side: 'CREDIT',
    plus: ['P1', 'P2', 'PR'], minus: [] },
];

const REGLES_BILAN: readonly RegleAffectation[] = [
  { ordinal: 1, lineCode: 'A1', accountKind: 'INTERNAL', codePrefix: null, balanceSide: null },
  { ordinal: 2, lineCode: 'A2', accountKind: 'CUSTOMER', codePrefix: null, balanceSide: 'DEBIT' },
  { ordinal: 3, lineCode: 'P1', accountKind: 'CUSTOMER', codePrefix: null, balanceSide: 'CREDIT' },
  { ordinal: 4, lineCode: 'A3', accountKind: null, codePrefix: null, balanceSide: 'DEBIT' },
  { ordinal: 5, lineCode: 'P2', accountKind: null, codePrefix: null, balanceSide: 'CREDIT' },
];

const RUBRIQUES_RESULTAT: readonly Rubrique[] = [
  { ordinal: 1, code: 'C1', label: 'Charges d’exploitation bancaire', level: 1, kind: 'DETAIL',
    side: 'DEBIT', plus: [], minus: [] },
  { ordinal: 2, code: 'R1', label: 'Produits d’exploitation bancaire', level: 1, kind: 'DETAIL',
    side: 'CREDIT', plus: [], minus: [] },
  { ordinal: 3, code: 'RES', label: 'Résultat net', level: 0, kind: 'TOTAL', side: 'CREDIT',
    plus: ['R1'], minus: ['C1'] },
];

const REGLES_RESULTAT: readonly RegleAffectation[] = [
  { ordinal: 1, lineCode: 'C1', accountKind: null, codePrefix: null, balanceSide: 'DEBIT' },
  { ordinal: 2, lineCode: 'R1', accountKind: null, codePrefix: null, balanceSide: 'CREDIT' },
];

/** Le brouillon incomplet : deux rubriques, une seule règle. */
const RUBRIQUES_BROUILLON: readonly Rubrique[] = [
  { ordinal: 1, code: 'A1', label: 'Caisse et banques centrales', level: 1, kind: 'DETAIL',
    side: 'DEBIT', plus: [], minus: [] },
  { ordinal: 2, code: 'P1', label: 'Dépôts de la clientèle', level: 1, kind: 'DETAIL',
    side: 'CREDIT', plus: [], minus: [] },
];

const REGLES_BROUILLON: readonly RegleAffectation[] = [
  { ordinal: 1, lineCode: 'A1', accountKind: 'INTERNAL', codePrefix: null, balanceSide: null },
];

export const MAQUETTES_COMPLETES: Readonly<Record<string, MaquetteComplete>> = {
  'mq-bilan': {
    id: 'mq-bilan', kind: 'BALANCE_SHEET', code: 'BILAN-BCEAO', label: 'Bilan — plan BCEAO',
    validFrom: '2026-01-01', validTo: null, status: 'ACTIVE',
    lines: RUBRIQUES_BILAN, rules: REGLES_BILAN,
  },
  'mq-resultat': {
    id: 'mq-resultat', kind: 'INCOME_STATEMENT', code: 'CR-BCEAO',
    label: 'Compte de résultat — plan BCEAO', validFrom: '2026-01-01', validTo: null,
    status: 'ACTIVE', lines: RUBRIQUES_RESULTAT, rules: REGLES_RESULTAT,
  },
  'mq-brouillon': {
    id: 'mq-brouillon', kind: 'BALANCE_SHEET', code: 'BILAN-2027',
    label: 'Bilan — refonte 2027', validFrom: '2027-01-01', validTo: null, status: 'DRAFT',
    lines: RUBRIQUES_BROUILLON, rules: REGLES_BROUILLON,
  },
};

/** Les comptes que le brouillon laisse de côté : c'est ce que l'essai doit montrer. */
export const ORPHELINS_DEMONSTRATION: readonly CompteNonAffecte[] = [
  { code: '20110', accountKind: 'CUSTOMER', side: 'DEBIT',
    amount: { amount: '18400000', currency: 'XOF' } },
  { code: '20120', accountKind: 'CUSTOMER', side: 'CREDIT',
    amount: { amount: '96250000', currency: 'XOF' } },
  { code: '47100', accountKind: 'SUSPENSE', side: 'DEBIT',
    amount: { amount: '125000', currency: 'XOF' } },
];
