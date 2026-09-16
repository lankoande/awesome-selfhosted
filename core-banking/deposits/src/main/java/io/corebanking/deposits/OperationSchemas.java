package io.corebanking.deposits;

import io.corebanking.kernel.money.CurrencyRef;
import io.corebanking.schema.EventTemplate;
import io.corebanking.schema.TemplateLine;

/**
 * Schemas comptables des operations de guichet et de virement.
 *
 * <p>Le montant, le frais et la taxe sont arrondis chacun pour soi et le total debite est leur
 * somme : debiter un total arrondi puis en deduire une composante par difference desequilibrerait
 * l'ecriture des que deux composantes ont des decimales.
 */
public final class OperationSchemas {

    public static final String CASH_DEPOSIT = "CASH_DEPOSIT";
    public static final String CASH_WITHDRAWAL = "CASH_WITHDRAWAL";
    public static final String TRANSFER = "TRANSFER";
    public static final String PAYMENT_ORDER = "PAYMENT_ORDER";
    public static final String PAYMENT_SETTLEMENT = "PAYMENT_SETTLEMENT";
    public static final String PAYMENT_RETURN = "PAYMENT_RETURN";
    public static final String CHEQUE_BOOK_FEE = "CHEQUE_BOOK_FEE";
    public static final String CHEQUE_PAYMENT = "CHEQUE_PAYMENT";
    public static final String CHEQUE_DEPOSIT = "CHEQUE_DEPOSIT";
    public static final String CHEQUE_COLLECTION = "CHEQUE_COLLECTION";
    public static final String DIRECT_DEBIT = "DIRECT_DEBIT";
    public static final String DIRECT_DEBIT_SETTLEMENT = "DIRECT_DEBIT_SETTLEMENT";
    public static final String DIRECT_DEBIT_REFUND = "DIRECT_DEBIT_REFUND";
    public static final String DIRECT_DEBIT_ISSUE = "DIRECT_DEBIT_ISSUE";
    public static final String DIRECT_DEBIT_FEE = "DIRECT_DEBIT_FEE";
    public static final String DIRECT_DEBIT_COLLECTION = "DIRECT_DEBIT_COLLECTION";
    public static final String DIRECT_DEBIT_RETURN = "DIRECT_DEBIT_RETURN";

    public static final String ROLE_CASH = "cash";
    public static final String ROLE_DESTINATION = "destination";
    public static final String ROLE_CLEARING = "clearing";
    public static final String ROLE_COUNTERPARTY = "counterparty";
    public static final String ROLE_COLLECTION = "collection";
    public static final String ROLE_CREDITOR = "creditor";
    public static final String ROLE_FEE_INCOME = "fee_income";
    public static final String ROLE_TAX = "tax";

    private OperationSchemas() {}

    /** Versement : la caisse entre, le compte du client est credite. */
    public static EventTemplate cashDeposit(CurrencyRef currency) {
        return EventTemplate.of(CASH_DEPOSIT)
            .derive("amt", "round(amount, " + currency.scale() + ")")
            .line(TemplateLine.debit("PARAM:" + ROLE_CASH, "amt", "Versement especes"))
            .line(TemplateLine.credit("CONTRACT", "amt", "Versement especes"))
            .build();
    }

    /** Retrait : le client est debite du montant, du frais et de la taxe ; la caisse sort le montant. */
    public static EventTemplate cashWithdrawal(CurrencyRef currency) {
        int scale = currency.scale();
        return EventTemplate.of(CASH_WITHDRAWAL)
            .derive("amt", "round(amount, " + scale + ")")
            .derive("f", "round(fee, " + scale + ")")
            .derive("t", "round(tax, " + scale + ")")
            .derive("total", "amt + f + t")
            .line(TemplateLine.debit("CONTRACT", "total", "Retrait especes"))
            .line(TemplateLine.credit("PARAM:" + ROLE_CASH, "amt", "Retrait especes"))
            .line(TemplateLine.credit("PARAM:" + ROLE_FEE_INCOME, "f", "Frais de retrait")
                      .onlyIf("f > 0"))
            .line(TemplateLine.credit("PARAM:" + ROLE_TAX, "t", "Taxe sur frais").onlyIf("t > 0"))
            .build();
    }

    /**
     * Paiement sortant : le donneur d'ordre paie le montant, le frais et la taxe ; le montant va
     * au compte de reglement sortant, ou il attend le correspondant.
     */
    public static EventTemplate paymentOrder(CurrencyRef currency) {
        int scale = currency.scale();
        return EventTemplate.of(PAYMENT_ORDER)
            .derive("amt", "round(amount, " + scale + ")")
            .derive("f", "round(fee, " + scale + ")")
            .derive("t", "round(tax, " + scale + ")")
            .derive("total", "amt + f + t")
            .line(TemplateLine.debit("CONTRACT", "total", "Paiement emis"))
            .line(TemplateLine.credit("PARAM:" + ROLE_CLEARING, "amt", "Paiement a regler"))
            .line(TemplateLine.credit("PARAM:" + ROLE_FEE_INCOME, "f", "Frais de paiement")
                      .onlyIf("f > 0"))
            .line(TemplateLine.credit("PARAM:" + ROLE_TAX, "t", "Taxe sur frais").onlyIf("t > 0"))
            .build();
    }

    /** Frais de chequier : le client paie le frais et sa taxe, rien d'autre. */
    public static EventTemplate chequeBookFee(CurrencyRef currency) {
        int scale = currency.scale();
        return EventTemplate.of(CHEQUE_BOOK_FEE)
            .derive("f", "round(fee, " + scale + ")")
            .derive("t", "round(tax, " + scale + ")")
            .derive("total", "f + t")
            .line(TemplateLine.debit("CONTRACT", "total", "Frais de chequier"))
            .line(TemplateLine.credit("PARAM:" + ROLE_FEE_INCOME, "f", "Frais de chequier"))
            .line(TemplateLine.credit("PARAM:" + ROLE_TAX, "t", "Taxe sur frais").onlyIf("t > 0"))
            .build();
    }

    /** Paiement d'un cheque emis : le tireur est debite, la caisse ou le nostro sort le montant. */
    public static EventTemplate chequePayment(CurrencyRef currency) {
        return EventTemplate.of(CHEQUE_PAYMENT)
            .derive("amt", "round(amount, " + currency.scale() + ")")
            .line(TemplateLine.debit("CONTRACT", "amt", "Cheque paye"))
            .line(TemplateLine.credit("PARAM:" + ROLE_COUNTERPARTY, "amt", "Cheque paye"))
            .build();
    }

    /** Remise de cheque : le client est credite sauf bonne fin, la valeur attend l'encaissement. */
    public static EventTemplate chequeDeposit(CurrencyRef currency) {
        return EventTemplate.of(CHEQUE_DEPOSIT)
            .derive("amt", "round(amount, " + currency.scale() + ")")
            .line(TemplateLine.debit("PARAM:" + ROLE_COLLECTION, "amt", "Cheque a l'encaissement"))
            .line(TemplateLine.credit("CONTRACT", "amt", "Remise de cheque sauf bonne fin"))
            .build();
    }

    /**
     * Prelevement recu : le debiteur paie le montant, le frais et la taxe ; le montant va au
     * creancier — son compte s'il est de la banque, le compte de reglement s'il est d'ailleurs.
     */
    public static EventTemplate directDebit(CurrencyRef currency) {
        int scale = currency.scale();
        return EventTemplate.of(DIRECT_DEBIT)
            .derive("amt", "round(amount, " + scale + ")")
            .derive("f", "round(fee, " + scale + ")")
            .derive("t", "round(tax, " + scale + ")")
            .derive("total", "amt + f + t")
            .line(TemplateLine.debit("CONTRACT", "total", "Prelevement"))
            .line(TemplateLine.credit("PARAM:" + ROLE_CREDITOR, "amt", "Prelevement recu"))
            .line(TemplateLine.credit("PARAM:" + ROLE_FEE_INCOME, "f", "Frais de prelevement")
                      .onlyIf("f > 0"))
            .line(TemplateLine.credit("PARAM:" + ROLE_TAX, "t", "Taxe sur frais").onlyIf("t > 0"))
            .build();
    }

    /** Prelevement emis : le creancier est credite sauf bonne fin, la valeur attend l'encaissement. */
    public static EventTemplate directDebitIssue(CurrencyRef currency) {
        return EventTemplate.of(DIRECT_DEBIT_ISSUE)
            .derive("amt", "round(amount, " + currency.scale() + ")")
            .line(TemplateLine.debit("PARAM:" + ROLE_COLLECTION, "amt", "Prelevement a l'encaissement"))
            .line(TemplateLine.credit("CONTRACT", "amt", "Prelevement emis sauf bonne fin"))
            .build();
    }

    /** Frais d'un prelevement emis : le creancier paie le frais et sa taxe, dans leur ecriture. */
    public static EventTemplate directDebitFee(CurrencyRef currency) {
        int scale = currency.scale();
        return EventTemplate.of(DIRECT_DEBIT_FEE)
            .derive("f", "round(fee, " + scale + ")")
            .derive("t", "round(tax, " + scale + ")")
            .derive("total", "f + t")
            .line(TemplateLine.debit("CONTRACT", "total", "Frais de prelevement"))
            .line(TemplateLine.credit("PARAM:" + ROLE_FEE_INCOME, "f", "Frais de prelevement"))
            .line(TemplateLine.credit("PARAM:" + ROLE_TAX, "t", "Taxe sur frais").onlyIf("t > 0"))
            .build();
    }

    /** Virement interne : le donneur d'ordre paie le montant, le frais et la taxe. */
    public static EventTemplate transfer(CurrencyRef currency) {
        int scale = currency.scale();
        return EventTemplate.of(TRANSFER)
            .derive("amt", "round(amount, " + scale + ")")
            .derive("f", "round(fee, " + scale + ")")
            .derive("t", "round(tax, " + scale + ")")
            .derive("total", "amt + f + t")
            .line(TemplateLine.debit("CONTRACT", "total", "Virement emis"))
            .line(TemplateLine.credit("PARAM:" + ROLE_DESTINATION, "amt", "Virement recu"))
            .line(TemplateLine.credit("PARAM:" + ROLE_FEE_INCOME, "f", "Frais de virement")
                      .onlyIf("f > 0"))
            .line(TemplateLine.credit("PARAM:" + ROLE_TAX, "t", "Taxe sur frais").onlyIf("t > 0"))
            .build();
    }
}
