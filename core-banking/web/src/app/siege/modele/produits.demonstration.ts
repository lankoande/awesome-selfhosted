import { FamilleProduit } from './produits.modele';

/**
 * Le catalogue des familles, tel que le socle le sert.
 *
 * <h2>Pourquoi cette copie existe, et ce qu'elle n'est pas</h2>
 *
 * En mode branché, ces familles viennent de `GET /products/families` : le socle sert son propre
 * contrat, et l'écran s'y conforme. Ce fichier est la **source de démonstration**, celle qui
 * permet de monter l'écran sans socle — au même titre que les clients et les écritures factices.
 *
 * Elle est engendrée depuis `families.json` du socle plutôt que réécrite : une famille inventée ici
 * montrerait un paramétrage que la vraie banque refuserait. Elle vieillira, comme toute donnée de
 * démonstration, et c'est sans conséquence — rien de ce qui compte ne la lit.
 */
export const FAMILLES_DEMONSTRATION: readonly FamilleProduit[] = [
  {
    "code": "CURRENT_ACCOUNT",
    "label": "Compte courant",
    "required": [
      "interest.capitalisation",
      "interest.credit_account",
      "interest.day_count",
      "interest.debit_account",
      "interest.side"
    ],
    "optional": [
      "dormancy.months",
      "interest.tiering_mode",
      "interest.withholding",
      "ops.cheque_book_fee",
      "ops.cheque_collection_account",
      "ops.daily_debit_max",
      "ops.direct_debit_collection_account",
      "ops.direct_debit_fee",
      "ops.fee_income_account",
      "ops.monthly_debit_max",
      "ops.payment_clearing_account",
      "ops.payment_fee",
      "ops.tax_account",
      "ops.tax_rate",
      "ops.transaction_max",
      "ops.transfer_fee",
      "ops.withdrawal_fee",
      "overdraft.credit_account",
      "overdraft.day_count",
      "overdraft.debit_account",
      "overdraft.excess_rate",
      "overdraft.limit",
      "overdraft.rate",
      "overdraft.settlement",
      "overdraft.tax_account",
      "overdraft.tax_rate"
    ],
    "requireOneOf": [
      {
        "of": [
          "interest.rate",
          "tier:INTEREST"
        ],
        "because": "sans taux ni bareme, aucun interet ne serait jamais calcule et le compte paraitrait remunere"
      }
    ],
    "conditions": [
      {
        "when": "overdraft.rate",
        "fallback": null,
        "in": [],
        "presence": true,
        "require": [
          "overdraft.debit_account",
          "overdraft.credit_account",
          "overdraft.settlement"
        ],
        "requireTier": null,
        "because": "des agios sans comptes d'imputation ni arrete echoueraient a la premiere journee debitrice, ou courraient sans jamais etre preleves"
      },
      {
        "when": "overdraft.debit_account",
        "fallback": null,
        "in": [],
        "presence": true,
        "require": [
          "overdraft.rate"
        ],
        "requireTier": null,
        "because": "un compte d'agios courus sans taux designe des agios jamais calcules"
      },
      {
        "when": "overdraft.excess_rate",
        "fallback": null,
        "in": [],
        "presence": true,
        "require": [
          "overdraft.rate"
        ],
        "requireTier": null,
        "because": "un taux de depassement suppose un taux dans l'autorisation"
      },
      {
        "when": "overdraft.tax_rate",
        "fallback": null,
        "in": [],
        "presence": true,
        "require": [
          "overdraft.tax_account"
        ],
        "requireTier": null,
        "because": "une taxe sur agios sans compte de collecte resterait dans le produit de la banque, et la declaration serait fausse"
      },
      {
        "when": "ops.withdrawal_fee",
        "fallback": null,
        "in": [],
        "presence": true,
        "require": [
          "ops.fee_income_account"
        ],
        "requireTier": null,
        "because": "un frais de retrait sans compte de produit n'aurait nulle part ou aller"
      },
      {
        "when": "ops.transfer_fee",
        "fallback": null,
        "in": [],
        "presence": true,
        "require": [
          "ops.fee_income_account"
        ],
        "requireTier": null,
        "because": "un frais de virement sans compte de produit n'aurait nulle part ou aller"
      },
      {
        "when": "ops.tax_rate",
        "fallback": null,
        "in": [],
        "presence": true,
        "require": [
          "ops.tax_account"
        ],
        "requireTier": null,
        "because": "une taxe calculee et sans compte de collecte resterait dans le produit de la banque, et la declaration serait fausse"
      },
      {
        "when": "ops.payment_fee",
        "fallback": null,
        "in": [],
        "presence": true,
        "require": [
          "ops.fee_income_account"
        ],
        "requireTier": null,
        "because": "un frais de paiement sortant sans compte de produit n'aurait nulle part ou aller"
      },
      {
        "when": "ops.cheque_book_fee",
        "fallback": null,
        "in": [],
        "presence": true,
        "require": [
          "ops.fee_income_account"
        ],
        "requireTier": null,
        "because": "un frais de chequier sans compte de produit n'aurait nulle part ou aller"
      }
    ],
    "groups": [
      {
        "listParameter": "fee.codes",
        "required": [
          "fee.{code}.income_account"
        ],
        "optional": [
          "fee.{code}.anchor",
          "fee.{code}.arrear_max_age_days",
          "fee.{code}.basis",
          "fee.{code}.cap",
          "fee.{code}.floor",
          "fee.{code}.frequency",
          "fee.{code}.label",
          "fee.{code}.on_insufficient_funds",
          "fee.{code}.proration",
          "fee.{code}.schema",
          "fee.{code}.tax_account",
          "fee.{code}.tax_rate",
          "fee.{code}.tiering_mode",
          "fee.{code}.timing"
        ],
        "conditions": [
          {
            "when": "fee.{code}.basis",
            "fallback": "FLAT",
            "in": [
              "FLAT"
            ],
            "presence": false,
            "require": [
              "fee.{code}.amount"
            ],
            "requireTier": null,
            "because": "une commission forfaitaire sans montant se percevrait a zero, sans que rien ne le signale"
          },
          {
            "when": "fee.{code}.basis",
            "fallback": null,
            "in": [
              "RATE_ON_CLOSING_BALANCE",
              "RATE_ON_HIGHEST_DEBIT_BALANCE"
            ],
            "presence": false,
            "require": [
              "fee.{code}.rate"
            ],
            "requireTier": null,
            "because": "une commission au taux sans taux se percevrait a zero"
          },
          {
            "when": "fee.{code}.basis",
            "fallback": null,
            "in": [
              "TIERED_ON_CLOSING_BALANCE"
            ],
            "presence": false,
            "require": [],
            "requireTier": "FEE:{code}",
            "because": "le bareme par tranches de la commission n'existe pas, et la perception echouerait au premier arrete"
          },
          {
            "when": "fee.{code}.tax_rate",
            "fallback": null,
            "in": [],
            "presence": true,
            "require": [
              "fee.{code}.tax_account"
            ],
            "requireTier": null,
            "because": "une taxe calculee et sans compte de collecte resterait dans le produit de la banque, et la declaration serait fausse"
          }
        ],
        "accounts": [
          "fee.{code}.income_account",
          "fee.{code}.tax_account"
        ]
      }
    ],
    "accounts": [
      "interest.credit_account",
      "interest.debit_account",
      "ops.cheque_collection_account",
      "ops.direct_debit_collection_account",
      "ops.fee_income_account",
      "ops.payment_clearing_account",
      "ops.tax_account",
      "overdraft.credit_account",
      "overdraft.debit_account",
      "overdraft.tax_account"
    ]
  },
  {
    "code": "SAVINGS_ACCOUNT",
    "label": "Compte d'épargne",
    "required": [
      "interest.capitalisation",
      "interest.credit_account",
      "interest.day_count",
      "interest.debit_account",
      "interest.side"
    ],
    "optional": [
      "dormancy.months",
      "interest.tiering_mode",
      "interest.withholding",
      "ops.cheque_book_fee",
      "ops.cheque_collection_account",
      "ops.daily_debit_max",
      "ops.direct_debit_collection_account",
      "ops.direct_debit_fee",
      "ops.fee_income_account",
      "ops.monthly_debit_max",
      "ops.payment_clearing_account",
      "ops.payment_fee",
      "ops.tax_account",
      "ops.tax_rate",
      "ops.transaction_max",
      "ops.transfer_fee",
      "ops.withdrawal_fee"
    ],
    "requireOneOf": [
      {
        "of": [
          "interest.rate",
          "tier:INTEREST"
        ],
        "because": "sans taux ni bareme, aucun interet ne serait jamais calcule et le compte paraitrait remunere"
      }
    ],
    "conditions": [
      {
        "when": "ops.withdrawal_fee",
        "fallback": null,
        "in": [],
        "presence": true,
        "require": [
          "ops.fee_income_account"
        ],
        "requireTier": null,
        "because": "un frais de retrait sans compte de produit n'aurait nulle part ou aller"
      },
      {
        "when": "ops.transfer_fee",
        "fallback": null,
        "in": [],
        "presence": true,
        "require": [
          "ops.fee_income_account"
        ],
        "requireTier": null,
        "because": "un frais de virement sans compte de produit n'aurait nulle part ou aller"
      },
      {
        "when": "ops.tax_rate",
        "fallback": null,
        "in": [],
        "presence": true,
        "require": [
          "ops.tax_account"
        ],
        "requireTier": null,
        "because": "une taxe calculee et sans compte de collecte resterait dans le produit de la banque, et la declaration serait fausse"
      },
      {
        "when": "ops.payment_fee",
        "fallback": null,
        "in": [],
        "presence": true,
        "require": [
          "ops.fee_income_account"
        ],
        "requireTier": null,
        "because": "un frais de paiement sortant sans compte de produit n'aurait nulle part ou aller"
      },
      {
        "when": "ops.cheque_book_fee",
        "fallback": null,
        "in": [],
        "presence": true,
        "require": [
          "ops.fee_income_account"
        ],
        "requireTier": null,
        "because": "un frais de chequier sans compte de produit n'aurait nulle part ou aller"
      }
    ],
    "groups": [
      {
        "listParameter": "fee.codes",
        "required": [
          "fee.{code}.income_account"
        ],
        "optional": [
          "fee.{code}.anchor",
          "fee.{code}.arrear_max_age_days",
          "fee.{code}.basis",
          "fee.{code}.cap",
          "fee.{code}.floor",
          "fee.{code}.frequency",
          "fee.{code}.label",
          "fee.{code}.on_insufficient_funds",
          "fee.{code}.proration",
          "fee.{code}.schema",
          "fee.{code}.tax_account",
          "fee.{code}.tax_rate",
          "fee.{code}.tiering_mode",
          "fee.{code}.timing"
        ],
        "conditions": [
          {
            "when": "fee.{code}.basis",
            "fallback": "FLAT",
            "in": [
              "FLAT"
            ],
            "presence": false,
            "require": [
              "fee.{code}.amount"
            ],
            "requireTier": null,
            "because": "une commission forfaitaire sans montant se percevrait a zero, sans que rien ne le signale"
          },
          {
            "when": "fee.{code}.basis",
            "fallback": null,
            "in": [
              "RATE_ON_CLOSING_BALANCE",
              "RATE_ON_HIGHEST_DEBIT_BALANCE"
            ],
            "presence": false,
            "require": [
              "fee.{code}.rate"
            ],
            "requireTier": null,
            "because": "une commission au taux sans taux se percevrait a zero"
          },
          {
            "when": "fee.{code}.basis",
            "fallback": null,
            "in": [
              "TIERED_ON_CLOSING_BALANCE"
            ],
            "presence": false,
            "require": [],
            "requireTier": "FEE:{code}",
            "because": "le bareme par tranches de la commission n'existe pas, et la perception echouerait au premier arrete"
          },
          {
            "when": "fee.{code}.tax_rate",
            "fallback": null,
            "in": [],
            "presence": true,
            "require": [
              "fee.{code}.tax_account"
            ],
            "requireTier": null,
            "because": "une taxe calculee et sans compte de collecte resterait dans le produit de la banque, et la declaration serait fausse"
          }
        ],
        "accounts": [
          "fee.{code}.income_account",
          "fee.{code}.tax_account"
        ]
      }
    ],
    "accounts": [
      "interest.credit_account",
      "interest.debit_account",
      "ops.cheque_collection_account",
      "ops.direct_debit_collection_account",
      "ops.fee_income_account",
      "ops.payment_clearing_account",
      "ops.tax_account"
    ]
  },
  {
    "code": "TERM_DEPOSIT",
    "label": "Dépôt à terme",
    "required": [
      "term.accrued_interest",
      "term.day_count",
      "term.interest_expense",
      "term.max_months",
      "term.min_amount",
      "term.min_months",
      "term.rate"
    ],
    "optional": [
      "term.max_rate",
      "term.penalty_rate",
      "term.renewal",
      "term.withholding_account",
      "term.withholding_rate"
    ],
    "requireOneOf": [],
    "conditions": [
      {
        "when": "term.withholding_rate",
        "fallback": null,
        "in": [],
        "presence": true,
        "require": [
          "term.withholding_account"
        ],
        "requireTier": null,
        "because": "une retenue a la source sans compte d'imputation echouerait au versement des interets, de nuit, sur l'echeance d'un client"
      }
    ],
    "groups": [],
    "accounts": [
      "term.accrued_interest",
      "term.interest_expense",
      "term.withholding_account"
    ]
  },
  {
    "code": "TERM_LOAN",
    "label": "Crédit amortissable",
    "required": [
      "loan.accrued_interest",
      "loan.accrued_receivable",
      "loan.interest_income",
      "loan.tax_account"
    ],
    "optional": [
      "loan.allocation_order",
      "loan.direct_debit",
      "loan.fee_income",
      "loan.grace_days",
      "loan.insurance_income",
      "loan.late_day_count",
      "loan.late_interest_basis",
      "loan.max_rate",
      "loan.penalty_cap",
      "loan.penalty_floor",
      "loan.prepayment_cap_months",
      "loan.prepayment_cap_percent",
      "loan.provision_release",
      "loan.recovery_income",
      "loan.teg_method",
      "loan.usury_rate",
      "loan.write_off_loss",
      "loan.written_off_counterpart",
      "loan.written_off_off_balance"
    ],
    "requireOneOf": [],
    "conditions": [
      {
        "when": "loan.late_interest_rate",
        "fallback": null,
        "in": [],
        "presence": true,
        "require": [
          "loan.late_interest_income"
        ],
        "requireTier": null,
        "because": "les produits sur creances en souffrance forment une ligne a part des etats reglementaires ; sans compte dedie, l'imputation echoue de nuit"
      },
      {
        "when": "loan.penalty_mode",
        "fallback": "NONE",
        "in": [
          "FLAT_PER_INSTALMENT"
        ],
        "presence": false,
        "require": [
          "loan.penalty_amount"
        ],
        "requireTier": null,
        "because": "une penalite forfaitaire sans montant se percevrait a zero sur tout le portefeuille"
      },
      {
        "when": "loan.penalty_mode",
        "fallback": null,
        "in": [
          "PERCENT_OF_OVERDUE"
        ],
        "presence": false,
        "require": [
          "loan.penalty_rate"
        ],
        "requireTier": null,
        "because": "une penalite proportionnelle sans taux se percevrait a zero sur tout le portefeuille"
      },
      {
        "when": "loan.penalty_mode",
        "fallback": null,
        "in": [
          "FLAT_PER_INSTALMENT",
          "PERCENT_OF_OVERDUE"
        ],
        "presence": false,
        "require": [
          "loan.late_interest_income"
        ],
        "requireTier": null,
        "because": "la penalite s'impute sur un produit sur creances en souffrance, a defaut de compte dedie"
      },
      {
        "when": "loan.penalty_income",
        "fallback": null,
        "in": [],
        "presence": true,
        "require": [
          "loan.penalty_mode"
        ],
        "requireTier": null,
        "because": "un compte de penalites sans mode de penalite designe une penalite jamais percue"
      },
      {
        "when": "loan.risk_profile",
        "fallback": null,
        "in": [],
        "presence": true,
        "require": [
          "loan.provision_expense",
          "loan.provision_allowance",
          "loan.reserved_interest"
        ],
        "requireTier": null,
        "because": "classer un credit sans compte de dotation ni d'interets reserves arrete le TFJ a la premiere provision, et la suspension des interets ne pourrait pas etre comptabilisee"
      },
      {
        "when": "loan.prepayment_indemnity_rate",
        "fallback": null,
        "in": [],
        "presence": true,
        "require": [
          "loan.prepayment_indemnity_account"
        ],
        "requireTier": null,
        "because": "une indemnite de remboursement anticipe n'est pas un interet : la crediter sur le produit d'interets fausserait la marge et le rendement du portefeuille"
      },
      {
        "when": "loan.write_off_loss",
        "fallback": null,
        "in": [],
        "presence": true,
        "require": [
          "loan.recovery_income",
          "loan.written_off_off_balance",
          "loan.written_off_counterpart"
        ],
        "requireTier": null,
        "because": "un credit passe en perte reste du : sans compte de hors bilan pour le suivre ni compte de recuperation pour l'encaisser, la creance disparait des livres le jour ou elle sort de l'actif"
      }
    ],
    "groups": [],
    "accounts": [
      "loan.accrued_interest",
      "loan.accrued_receivable",
      "loan.fee_income",
      "loan.insurance_income",
      "loan.interest_income",
      "loan.late_interest_income",
      "loan.penalty_income",
      "loan.prepayment_indemnity_account",
      "loan.provision_allowance",
      "loan.provision_expense",
      "loan.provision_release",
      "loan.recovery_income",
      "loan.reserved_interest",
      "loan.tax_account",
      "loan.write_off_loss",
      "loan.written_off_counterpart",
      "loan.written_off_off_balance"
    ]
  }
];
