import { Droit, Habilitations, Montant, Portee } from './auth.port';

/**
 * Le profil d'habilitations de la démonstration.
 *
 * <h2>Ce que c'est, et ce que ce n'est pas</h2>
 *
 * C'est une **copie de la politique du socle** (`SecurityConfig`) rendue pour un
 * profil : portée, second regard et plafonds de chaque opération. Ce n'en est
 * pas une seconde source : en mode `api`, rien de ce fichier n'est lu — les
 * droits viennent de `/v1/me/permissions`, et de là seulement.
 *
 * <h2>Pourquoi la démonstration ouvre toutes les portes</h2>
 *
 * Un profil réel fermerait la moitié de l'application, et une démonstration où
 * la moitié des écrans est invisible devient un appel au support. La
 * démonstration **accorde donc toutes les opérations**, mais annonce les
 * plafonds et les seconds regards du profil déclaré — ce sont eux qui portent
 * la granularité, et ils se voient partout.
 *
 * Pour montrer une porte fermée, `config.json` porte `demonstration.droitsRetires` :
 * la liste des opérations à retirer au profil de démonstration. Vide par défaut.
 *
 * <h2>Le profil</h2>
 *
 * Un chef d'agence qui tient aussi une caisse — `TELLER` et `BRANCH_MANAGER`,
 * ce que porte le porteur de démonstration. Le socle rend le plafond **le plus
 * favorable** des rôles de l'appelant : la caisse va donc à 25 000 000 XOF en
 * agence, et 5 000 000 seulement en opération déplacée, parce qu'on opère sur
 * le compte d'une autre agence sans avoir le dossier sous les yeux.
 */

type Ligne = readonly [
  operation: string,
  portee: Portee,
  secondRegard?: boolean,
  horsAgence?: boolean,
  plafonds?: Readonly<Record<string, string>>,
  plafondsHorsAgence?: Readonly<Record<string, string>>,
];

const POLITIQUE: readonly Ligne[] = [
  ['ACCOUNT_BALANCE_READ', 'OWN_BRANCH', false, true],
  ['ACCOUNT_JOURNAL_READ', 'OWN_ENTITY'],
  ['LEDGER_READ', 'OWN_ENTITY'],
  ['PARTY_READ', 'OWN_BRANCH', false, true],
  ['CASH_OPERATION', 'OWN_BRANCH', false, true, { XOF: '25000000' }, { XOF: '5000000' }],
  ['TILL_MANAGE', 'OWN_BRANCH', true, false],
  ['TILL_CLOSE', 'OWN_BRANCH'],
  ['TRANSFER', 'OWN_ENTITY', false, false, { XOF: '100000000' }],
  ['PAYMENT_ORDER', 'OWN_ENTITY', false, false, { XOF: '100000000' }],
  ['PAYMENT_PROCESS', 'OWN_ENTITY'],
  ['PAYMENT_READ', 'OWN_ENTITY'],
  ['ACCOUNT_LIMIT_MANAGE', 'OWN_BRANCH', true, false],
  ['CHEQUE_BOOK_ISSUE', 'OWN_BRANCH', true, false],
  ['CHEQUE_PAY', 'OWN_ENTITY', false, false, { XOF: '25000000' }],
  ['CHEQUE_DEPOSIT', 'OWN_ENTITY'],
  ['CHEQUE_PROCESS', 'OWN_ENTITY'],
  ['CHEQUE_STOP', 'OWN_ENTITY'],
  ['CHEQUE_READ', 'OWN_ENTITY'],
  ['MANDATE_REGISTER', 'OWN_BRANCH', true, false],
  ['MANDATE_REVOKE', 'OWN_ENTITY'],
  ['STANDING_ORDER_REGISTER', 'OWN_BRANCH', true, false],
  ['STANDING_ORDER_CANCEL', 'OWN_BRANCH'],
  ['STANDING_ORDER_READ', 'OWN_ENTITY'],
  ['TERM_DEPOSIT_SUBSCRIBE', 'OWN_BRANCH', true, false],
  ['TERM_DEPOSIT_BREAK', 'OWN_BRANCH', true, false],
  ['TERM_DEPOSIT_READ', 'OWN_ENTITY'],
  ['DIRECT_DEBIT_PRESENT', 'OWN_ENTITY'],
  ['DIRECT_DEBIT_ISSUE', 'OWN_ENTITY', false, false, { XOF: '100000000' }],
  ['DIRECT_DEBIT_PROCESS', 'OWN_ENTITY'],
  ['DIRECT_DEBIT_READ', 'OWN_ENTITY'],
  ['PARTY_DOCUMENT', 'OWN_ENTITY'],
  ['PARTY_RELATIONSHIP', 'OWN_ENTITY', true, false],
  ['KYC_POLICY_MANAGE', 'OWN_ENTITY', true, false],
  ['AML_SCENARIO_MANAGE', 'OWN_ENTITY', true, false],
  ['AML_PROFILE_DECLARE', 'OWN_BRANCH'],
  ['AML_ALERT_REVIEW', 'OWN_ENTITY'],
  ['AML_REPORT', 'OWN_ENTITY', true, false],
  ['AML_READ', 'OWN_ENTITY'],
  ['REGULATORY_DECLARATION_MANAGE', 'OWN_ENTITY', true, false],
  ['REGULATORY_REPORT_PRODUCE', 'OWN_ENTITY'],
  ['REGULATORY_REPORT_TRANSMIT', 'OWN_ENTITY', true, false],
  ['CREDIT_BUREAU_CONSENT', 'OWN_BRANCH'],
  ['TAX_RULE_MANAGE', 'OWN_ENTITY', true, false],
  ['STATEMENT_PACK_MANAGE', 'OWN_ENTITY', true, false],
  ['CONSOLIDATION_MANAGE', 'OWN_ENTITY', true, false],
  ['REGULATORY_READ', 'OWN_ENTITY'],
  ['PARTY_FILE_READ', 'OWN_ENTITY'],
  ['FX_RATE_QUOTE', 'OWN_ENTITY', true, false],
  ['FX_POSITION_MANAGE', 'OWN_ENTITY', true, false],
  ['FX_READ', 'OWN_ENTITY'],
  ['SUSPENSE_MANAGE', 'OWN_ENTITY', true, false],
  ['SUSPENSE_READ', 'OWN_ENTITY'],
  ['ENTRY_REVERSAL', 'OWN_ENTITY', true, false],
  ['ACCOUNT_HOLD', 'OWN_ENTITY', true, false],
  ['ACCOUNT_BLOCK', 'OWN_ENTITY', true, false],
  ['JOURNAL_ENTRY_MANUAL', 'OWN_ENTITY', true, false],
  ['PARTY_CREATE', 'OWN_BRANCH'],
  ['KYC_VERIFY', 'OWN_BRANCH', true, false],
  ['ACCOUNT_OPEN', 'OWN_BRANCH', true, false],
  ['ACCOUNT_CLOSE', 'OWN_BRANCH', true, false],
  ['ACCOUNT_PRODUCT_ASSIGN', 'OWN_BRANCH', true, false],
  ['LOAN_READ', 'OWN_ENTITY'],
  ['LOAN_APPLICATION', 'OWN_BRANCH'],
  ['LOAN_APPLICATION_DECIDE', 'OWN_ENTITY', true, false, { XOF: '25000000' }],
  ['LOAN_CONDITION_CLEAR', 'OWN_ENTITY', true, false],
  ['LENDING_POLICY_MANAGE', 'OWN_ENTITY', true, false],
  ['LOAN_WRITE_OFF', 'OWN_ENTITY', true, false],
  ['LOAN_RECOVERY', 'OWN_ENTITY'],
  ['LOAN_RATE_REVISION', 'OWN_ENTITY', true, false],
  ['LOAN_CONTRACT_CREATE', 'OWN_BRANCH'],
  ['LOAN_DISBURSE', 'OWN_ENTITY', true, false, { XOF: '50000000' }],
  ['LOAN_RESCHEDULE', 'OWN_ENTITY', true, false],
  ['LOAN_PREPAY', 'OWN_BRANCH', true, false],
  ['LOAN_REPAYMENT', 'OWN_BRANCH', false, false, { XOF: '25000000' }],
  ['COLLATERAL_MANAGE', 'OWN_ENTITY', true, false],
  ['PRODUCT_DRAFT', 'OWN_ENTITY'],
  ['PRODUCT_ACTIVATE', 'OWN_ENTITY', true, false],
  ['PRODUCT_READ', 'OWN_ENTITY'],
  ['RISK_PARAMETER_DRAFT', 'OWN_ENTITY'],
  ['RISK_PARAMETER_ACTIVATE', 'OWN_ENTITY', true, false],
  ['ACCOUNTING_SCHEMA_DRAFT', 'OWN_ENTITY'],
  ['ACCOUNTING_SCHEMA_ACTIVATE', 'OWN_ENTITY', true, false],
  ['STATEMENT_LAYOUT_DRAFT', 'OWN_ENTITY'],
  ['STATEMENT_LAYOUT_ACTIVATE', 'OWN_ENTITY', true, false],
  ['CALENDAR_MANAGE', 'OWN_ENTITY', true, false],
  ['BRANCH_MANAGE', 'OWN_ENTITY', true, false],
  ['ESTABLISHMENT_READ', 'OWN_ENTITY'],
  ['ESTABLISHMENT_MANAGE', 'OWN_ENTITY', true, false],
  ['NUMBERING_READ', 'OWN_ENTITY'],
  ['NUMBERING_DRAFT', 'OWN_ENTITY'],
  ['NUMBERING_ACTIVATE', 'OWN_ENTITY', true, false],
  ['FEE_EXEMPTION_GRANT', 'OWN_ENTITY', true, false],
  ['TAX_PARAMETER_DECLARE', 'OWN_ENTITY', true, false],
  ['TFJ_RUN', 'OWN_ENTITY'],
  ['TFJ_CANCEL', 'OWN_ENTITY', true, false],
  ['PERIOD_CLOSE', 'OWN_ENTITY', true, false],
  ['PERIOD_REOPEN', 'OWN_ENTITY', true, false],
  ['FISCAL_YEAR_MANAGE', 'OWN_ENTITY', true, false],
  ['YEAR_CLOSE', 'OWN_ENTITY', true, false],
  ['YEAR_REOPEN', 'OWN_ENTITY', true, false],
  ['RESULT_APPROPRIATION', 'OWN_ENTITY', true, false],
  ['AUDIT_READ', 'ANY_ENTITY'],];

function montants(table: Readonly<Record<string, string>> | undefined): ReadonlyMap<string, Montant> {
  const plafonds = new Map<string, Montant>();
  for (const [devise, montant] of Object.entries(table ?? {})) {
    plafonds.set(devise, { amount: montant, currency: devise });
  }
  return plafonds;
}

/**
 * Les droits de la démonstration, moins ceux que le déploiement retire.
 *
 * Le retrait sert à montrer une porte fermée : la liste vient de `config.json`,
 * elle n'est pas figée ici.
 */
export function habilitationsDeDemonstration(retires: readonly string[] = []): Habilitations {
  const exclues = new Set(retires);
  const droits = new Map<string, Droit>();
  for (const [operation, portee, secondRegard, horsAgence, plafonds, distants] of POLITIQUE) {
    if (exclues.has(operation)) continue;
    droits.set(operation, {
      operation,
      portee,
      secondRegard: secondRegard === true,
      horsAgence: horsAgence === true,
      plafonds: montants(plafonds),
      plafondsHorsAgence: montants(distants),
    });
  }
  return { connues: true, droits };
}
